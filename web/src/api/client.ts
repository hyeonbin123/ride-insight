// [파일 역할] 화면이 서버 REST API를 부르는 유일한 통로. 엔드포인트마다 함수 하나를 두고 응답을 types.ts의
// 타입으로 돌려준다. 화면 컴포넌트는 fetch를 직접 쓰지 않고 api.xxx()만 부른다(React Query의
// queryFn·mutationFn으로 넘긴다).
// [설계 이유] 경로·메서드·오류 처리를 한곳에 모아, 서버 API(docs/api.md)가 바뀌면 이 파일만 고치면 되게 한다.
// 모든 경로는 같은 출처의 /api로 시작해 개발 때는 Vite 프록시, 배포 때는 nginx가 서버로 넘긴다.

import type {
  Activity,
  ActivityList,
  ApiError,
  Athlete,
  CreateAthleteRequest,
  FtpEstimate,
  PowerCurve,
  Samples,
  TrainingLoadResponse,
} from "./types";

// 서버 오류 응답을 담는 예외. 서버는 오류를 { error: 코드, message: 설명 } 형태로 돌려주므로(docs/api.md,
// ApiExceptionHandler) status(HTTP 상태), code(error 값), message를 함께 보관한다. 화면은 instanceof로 구분해
// '422 unsupported_sport: ...'처럼 원인을 그대로 보여 준다. 본문이 JSON이 아니면 code는 http_error, 메시지는
// 상태 코드로 채운다.
export class ApiRequestError extends Error {
  readonly status: number;
  readonly code: string;

  constructor(status: number, body: ApiError | null) {
    super(body?.message ?? `request failed with status ${status}`);
    this.status = status;
    this.code = body?.error ?? "http_error";
  }
}

// 실패 응답의 본문을 JSON으로 읽어 ApiRequestError로 바꾼다. 프록시 오류 페이지(HTML)처럼 JSON이 아닌
// 본문이면 파싱 예외를 삼키고 본문 없이 만든다.
async function parseError(res: Response): Promise<ApiRequestError> {
  let body: ApiError | null = null;
  try {
    body = (await res.json()) as ApiError;
  } catch {
    body = null;
  }
  return new ApiRequestError(res.status, body);
}

// 공통 요청 함수. 2xx가 아니면 ApiRequestError를 던지고(React Query가 isError·error로 받는다), 204(본문 없음,
// 예: 삭제)는 undefined를 돌려주며, 나머지는 JSON을 T로 해석한다. 응답 구조를 실행 중에 검사하지는 않고 타입
// 단언(as T)만 한다.
async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(path, init);
  if (!res.ok) throw await parseError(res);
  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}

// JSON 본문 요청 옵션을 만든다(Content-Type 헤더 + 직렬화). 선수 생성과 FTP 등록에 쓴다.
function json(method: string, body: unknown): RequestInit {
  return { method, headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) };
}

// 엔드포인트 목록(서버 AthleteController·ActivityController와 1:1).
// - createAthlete / getAthlete: 선수 생성(POST /api/athletes), 조회(현재 FTP 포함)
// - setThreshold: FTP 등록(PUT .../threshold). 서버가 그 날짜 이후 활동의 IF·TSS를 다시 계산하고
//   개수(recomputedActivities)를 돌려준다
// - uploadActivity: FIT 업로드(multipart의 file 파트)
// - listActivities: 최신순 목록, before 커서 페이지네이션
// - getActivity / getSamples / getActivityPowerCurve / deleteActivity: 활동 상세, 시계열, 파워 커브, 삭제
// - getAthletePowerCurve / getFtpEstimate: 기간(weeks) 최고 파워 커브와 FTP 추정. asOf(기준일)를 주면
//   그날까지의 기간을 본다
// - getTrainingLoad: 날짜별 TSS·CTL·ATL·TSB
export const api = {
  createAthlete: (req: CreateAthleteRequest) => request<Athlete>("/api/athletes", json("POST", req)),
  getAthlete: (id: string) => request<Athlete>(`/api/athletes/${id}`),
  setThreshold: (id: string, effectiveFrom: string, ftpWatts: number) =>
    request<{ recomputedActivities: number }>(
      `/api/athletes/${id}/threshold`,
      json("PUT", { effectiveFrom, ftpWatts }),
    ),
  uploadActivity: (athleteId: string, file: File) => {
    // FormData를 body로 넘기면 브라우저가 multipart 경계가 들어간 Content-Type을 직접 붙인다. 그래서 여기서는
    // 헤더를 정하지 않는다(직접 정하면 경계 값이 빠져 서버가 해석하지 못한다). 서버는 'file' 파트 하나를 받고
    // 10 MB를 넘으면 413을 돌려준다.
    const form = new FormData();
    form.append("file", file, file.name);
    return request<Activity>(`/api/athletes/${athleteId}/activities`, { method: "POST", body: form });
  },
  listActivities: (athleteId: string, limit = 50, before?: string) => {
    // URLSearchParams로 쿼리 문자열을 만들어 값이 자동으로 인코딩되게 한다. before는 직전 응답의
    // nextBefore(시작 시각, + 기호가 들어갈 수 있는 ISO 문자열)라 인코딩이 필요하다.
    const params = new URLSearchParams({ limit: String(limit) });
    if (before) params.set("before", before);
    return request<ActivityList>(`/api/athletes/${athleteId}/activities?${params}`);
  },
  getActivity: (id: string) => request<Activity>(`/api/activities/${id}`),
  getSamples: (id: string, every = 1) => request<Samples>(`/api/activities/${id}/samples?every=${every}`),
  getActivityPowerCurve: (id: string) => request<PowerCurve>(`/api/activities/${id}/power-curve`),
  getAthletePowerCurve: (athleteId: string, weeks = 6, asOf?: string) => {
    const params = new URLSearchParams({ weeks: String(weeks) });
    if (asOf) params.set("asOf", asOf);
    return request<PowerCurve>(`/api/athletes/${athleteId}/power-curve?${params}`);
  },
  getFtpEstimate: (athleteId: string, weeks = 6, asOf?: string) => {
    const params = new URLSearchParams({ weeks: String(weeks) });
    if (asOf) params.set("asOf", asOf);
    return request<FtpEstimate>(`/api/athletes/${athleteId}/ftp-estimate?${params}`);
  },
  getTrainingLoad: (athleteId: string, from: string, to: string) =>
    // from·to는 yyyy-MM-dd 날짜라 인코딩할 문자가 없어 문자열로 바로 붙인다.
    request<TrainingLoadResponse>(`/api/athletes/${athleteId}/training-load?from=${from}&to=${to}`),
  deleteActivity: (id: string) => request<void>(`/api/activities/${id}`, { method: "DELETE" }),
};
