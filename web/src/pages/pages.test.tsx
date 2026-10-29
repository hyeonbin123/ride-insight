// [테스트 묶음] 화면 세 곳의 서버 상태 처리를 api 함수(또는 fetch)를 가짜로 바꿔 확인한다(Vitest + Testing Library).
// - 라이딩 화면: 업로드가 끝나면 그 선수의 FTP 추정 캐시도 무효화해야 한다. 처음 구현은 목록·훈련 부하·파워
//   커브만 무효화해, 대시보드의 FTP 추정 카드가 staleTime(30초) 동안 업로드 전 값을 보였다.
//   429 too_many_uploads·409 athlete_busy 응답은 한국어 설명과 Retry-After 초로 보여야 하고(처음에는 영어 원문만
//   나왔고 헤더를 읽지 않았다), 목록 첫 열은 키보드로 갈 수 있는 실제 링크여야 한다(처음에는 행 onClick만 있어
//   마우스로만 상세에 갈 수 있었다).
// - 라이딩 상세: '최근 6주 최고' 비교 커브의 기준일은 소유 선수 시간대의 현지 날짜여야 하고(처음에는 UTC 시각의 앞
//   10자를 써서 한국의 새벽·아침 라이딩이 전날 기준이 됐다), 선수 조회가 실패하거나 시간대가 없으면 UTC 날짜로 대신해야
//   한다(비교 곡선이 끝내 안 나오면 안 된다). 기록 간격은 소수 둘째 자리까지만 보이고, 삭제가 409 athlete_busy로 실패하면
//   한국어 설명과 Retry-After 초를 보여야 한다(처음에는 삭제 실패를 어디에도 보이지 않았다).
// - 대시보드: 파워 커브 요청이 실패하면 오류 상자만 보여야 한다. 처음 구현은 오류 상자와 '파워 라이딩 없음'
//   안내를 함께 보였다.

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { useEffect, type ReactElement } from "react";
import { MemoryRouter, Route, Routes, useLocation, useParams } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";
import { api, ApiRequestError, parseRetryAfter } from "../api/client";
import type { Activity, Athlete, FtpEstimate } from "../api/types";
import { useAthleteStore } from "../state/athleteStore";
import { ActivitiesPage } from "./ActivitiesPage";
import { ActivityPage } from "./ActivityPage";
import { DashboardPage } from "./DashboardPage";

// 테스트마다 새 쿼리 캐시를 쓰고 재시도는 끈다(실패 응답을 바로 화면에 보이게). initialEntries는 라우터의 시작
// 주소(기본 "/")로, 주소 파라미터(:id)를 쓰는 화면이나 화면 이동을 보는 테스트가 정한다.
function renderPage(ui: ReactElement, qc = new QueryClient({ defaultOptions: { queries: { retry: false } } }), initialEntries = ["/"]) {
  const view = render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={initialEntries}>{ui}</MemoryRouter>
    </QueryClientProvider>,
  );
  return { qc, ...view };
}

// 라우터 주소가 바뀔 때마다 pathname을 배열에 적는다. 화면 이동이 정확히 한 번인지(링크 클릭이 행 onClick까지
// 올라가 같은 주소를 두 번 쌓지 않는지) 세는 데 쓴다. 처음 그릴 때의 주소도 한 번 적힌다.
function LocationLog({ log }: { log: string[] }) {
  const location = useLocation();
  useEffect(() => {
    log.push(location.pathname);
  }, [location, log]);
  return null;
}

// 라이딩 상세 화면 자리에 두는 대역: 주소의 :id만 보여 준다(목록 → 상세 이동이 일어났는지 보는 용도).
function DetailStub() {
  const { id } = useParams();
  return <div>상세 {id}</div>;
}

// 포커스한 링크에서 Enter를 누른다. 브라우저는 Enter keydown의 기본 동작으로 링크를 클릭하지만 jsdom에는 그 동작이
// 없어, 어떤 처리기도 기본 동작을 막지 않았을 때(fireEvent가 true를 돌려줌)만 그 클릭을 대신 일으킨다. 그래서 이
// 도우미는 (1) 요소가 포커스를 받고 (2) Enter를 막는 처리기가 없고 (3) 클릭이 이동으로 이어지는지를 함께 본다.
function pressEnter(el: HTMLElement) {
  el.focus();
  expect(el).toHaveFocus();
  if (fireEvent.keyDown(el, { key: "Enter", code: "Enter" })) fireEvent.click(el);
}

// 업로드 입력에 넣을 가짜 FIT 파일(내용은 보지 않으므로 바이트 세 개).
function fitFile(name: string) {
  return new File([new Uint8Array([1, 2, 3])], name);
}

const ESTIMATE: FtpEstimate = {
  method: "R1",
  ftpWatts: 250,
  rules: { R1_twenty_minute: 250 },
  windowsUsed: 16,
  ridesInWindow: 10,
  best20m: 263,
  weeks: 6,
  note: "20분 최대 × 0.95",
};

const ATHLETE: Athlete = { id: "a1", name: "A", createdAt: "2024-01-01T00:00:00Z", timeZone: "Asia/Seoul" };

const ACTIVITY: Activity = {
  id: "act-1",
  athleteId: "a1",
  startedAt: "2024-01-01T08:00:00Z",
  source: "fit",
  sampleCount: 60,
  device: {},
  hasPower: false,
  powerMissing: 60,
};

// 파워가 있는 라이딩. 시작 2026-10-14T21:30Z는 서울(ATHLETE.timeZone)로 10-15 06:30이고 UTC 날짜로는 10-14라, 두 날짜
// 기준이 갈리는 경계 사례다(서버 TimeZoneTest와 같은 시각). recIntSecs는 DB의 real(float4) 값이 이진 전송으로 올 때의
// 모양(1.26이 1.2599999904632568로 옴)이다.
const RIDE: Activity = {
  ...ACTIVITY,
  startedAt: "2026-10-14T21:30:00Z",
  sourceName: "morning.fit",
  recIntSecs: 1.2599999904632568,
  intervalShare: 0.95,
  durationSecs: 3661,
  hasPower: true,
  powerMissing: 0,
  metrics: {
    ftpUsed: 250,
    normalizedPower: 230,
    intensityFactor: 0.92,
    tss: 85,
    averagePower: 210,
    maxPower: 600,
    workKj: 760,
    mmp: { "20m_critical_power": 260 },
  },
};

afterEach(() => {
  vi.restoreAllMocks();
  useAthleteStore.setState({ athleteId: null });
});

describe("activities page", () => {
  // 라이딩 화면의 공통 가짜 응답: 선수(시간대 Asia/Seoul)와 목록. 선수 조회는 메뉴바와 같은 캐시 키라 화면이 시작 시각을
  // 선수 시간대로 보이는 데 쓴다.
  function mockActivities(items: Activity[] = []) {
    useAthleteStore.setState({ athleteId: "a1" });
    vi.spyOn(api, "getAthlete").mockResolvedValue(ATHLETE);
    vi.spyOn(api, "listActivities").mockResolvedValue({ items });
  }

  // 목록(/activities)과 상세 대역(/activities/:id)을 라우터에 함께 두고 주소 변화를 적는다.
  function renderActivitiesWithRoutes() {
    const log: string[] = [];
    const view = renderPage(
      <>
        <LocationLog log={log} />
        <Routes>
          <Route path="/activities" element={<ActivitiesPage />} />
          <Route path="/activities/:id" element={<DetailStub />} />
        </Routes>
      </>,
      undefined,
      ["/activities"],
    );
    return { ...view, log };
  }

  // 파일 입력에 파일을 넣어 업로드를 시작한다.
  function uploadFiles(container: HTMLElement, ...names: string[]) {
    const input = container.querySelector('input[type="file"]') as HTMLInputElement;
    fireEvent.change(input, { target: { files: names.map(fitFile) } });
  }

  // 업로드 성공 뒤 같은 선수의 FTP 추정 캐시는 '무효' 상태가 되고, 다른 선수의 캐시는 그대로여야 한다.
  it("invalidates the athlete's FTP estimate after an upload", async () => {
    mockActivities();
    const upload = vi.spyOn(api, "uploadActivity").mockResolvedValue(ACTIVITY);
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const mine = ["ftp-estimate", "a1", 6, "2024-01-01"];
    const other = ["ftp-estimate", "a2", 6, "2024-01-01"];
    qc.setQueryData(mine, ESTIMATE);
    qc.setQueryData(other, ESTIMATE);
    const { container } = renderPage(<ActivitiesPage />, qc);

    uploadFiles(container, "ride.fit");

    await waitFor(() => expect(upload).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(qc.getQueryState(mine)?.isInvalidated).toBe(true));
    expect(qc.getQueryState(other)?.isInvalidated).toBe(false);
  });

  // 2026-10-24: 429 too_many_uploads(서버 전체 동시 업로드 상한)와 409 athlete_busy(같은 선수의 다른 쓰기 요청이 선수 행
  // 잠금을 쥠)는 서버가 Retry-After(초)를 함께 보낸다. 업로드 결과 줄에는 상태 코드, 한국어 설명, 그 초, 원문 코드가 있어야
  // 한다. 고치기 전에는 설명이 없어 '429 too_many_uploads: ...'처럼 영어 원문만 나왔다.
  it("explains 429 and 409 upload answers in Korean with the Retry-After wait", async () => {
    mockActivities();
    vi.spyOn(api, "uploadActivity")
      .mockRejectedValueOnce(new ApiRequestError(429, { error: "too_many_uploads", message: "too many concurrent uploads" }, 5))
      .mockRejectedValueOnce(new ApiRequestError(409, { error: "athlete_busy", message: "athlete a1 is busy" }, 5));
    const { container } = renderPage(<ActivitiesPage />);

    uploadFiles(container, "a.fit", "b.fit");

    expect(
      await screen.findByText("— 429 서버가 다른 업로드를 처리하는 중입니다. 5초 뒤 다시 시도하세요 (too_many_uploads: too many concurrent uploads)"),
    ).toBeInTheDocument();
    expect(
      screen.getByText("— 409 이 선수의 다른 요청(업로드·FTP·시간대 변경·삭제)이 처리 중입니다. 5초 뒤 다시 시도하세요 (athlete_busy: athlete a1 is busy)"),
    ).toBeInTheDocument();
  });

  // Retry-After는 응답 헤더에만 있어 api 함수를 가짜로 바꾸면 보이지 않는다. fetch만 가짜로 바꿔 client.ts가 헤더를 읽어
  // ApiRequestError.retryAfterSeconds로 넘기고 화면 문장까지 이어지는지 끝까지 본다. 고치기 전에는 헤더를 읽지 않았다.
  it("reads the Retry-After header of a 429 upload answer", async () => {
    mockActivities();
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(JSON.stringify({ error: "too_many_uploads", message: "too many concurrent uploads" }), {
        status: 429,
        headers: { "Content-Type": "application/json", "Retry-After": "7" },
      }),
    );
    const { container } = renderPage(<ActivitiesPage />);

    uploadFiles(container, "ride.fit");

    expect(await screen.findByText(/7초 뒤 다시 시도하세요 \(too_many_uploads: too many concurrent uploads\)/)).toBeInTheDocument();
  });

  // 접근성(2026-10-24): 목록 첫 열(시작 시각)은 실제 링크(a href)여서 Tab으로 포커스하고 Enter로 상세에 갈 수 있어야 한다.
  // 고치기 전에는 행 onClick만 있어(행 전체를 a로 두면 'tbody 안의 a' 경고가 나서 바꾼 것) 키보드·화면 읽기 프로그램으로는
  // 상세에 갈 수 없었다. 링크 글자는 선수 시간대로 보인 시작 시각이고, 이동은 정확히 한 번이어야 한다(링크 클릭이 행
  // onClick까지 올라가 같은 주소를 두 번 쌓으면 뒤로 가기가 두 번 필요해진다).
  it("opens the detail from the first-column link with the keyboard, once", async () => {
    mockActivities([RIDE]);
    const { log } = renderActivitiesWithRoutes();

    const link = await screen.findByRole("link");
    expect(link.closest("tbody")).not.toBeNull();
    expect(link).toHaveAttribute("href", "/activities/act-1");
    expect(link).toHaveTextContent(new Date(RIDE.startedAt).toLocaleString(undefined, { timeZone: "Asia/Seoul" }));

    pressEnter(link);

    expect(await screen.findByText("상세 act-1")).toBeInTheDocument();
    expect(log).toEqual(["/activities", "/activities/act-1"]);
  });

  // 마우스 사용자를 위한 행 onClick은 보조로 남는다: 링크 밖의 셀(여기서는 길이 1:01:01)을 눌러도 상세로 한 번 이동한다.
  it("still opens the detail when another cell of the row is clicked", async () => {
    mockActivities([RIDE]);
    const { log } = renderActivitiesWithRoutes();

    fireEvent.click(await screen.findByText("1:01:01"));

    expect(await screen.findByText("상세 act-1")).toBeInTheDocument();
    expect(log).toEqual(["/activities", "/activities/act-1"]);
  });
});

describe("activity page", () => {
  // 상세 화면의 가짜 응답: 라이딩(RIDE), 소유 선수(기본은 Asia/Seoul인 ATHLETE를 돌려줌. owner로 실패나 다른 응답을 줄 수
  // 있다), 시계열, 이 라이딩의 커브. 비교 커브(getAthletePowerCurve) spy를 돌려줘 어떤 asOf로 불렸는지 본다.
  function mockActivityPage(owner: () => Promise<Athlete> = () => Promise.resolve(ATHLETE)) {
    vi.spyOn(api, "getActivity").mockResolvedValue(RIDE);
    vi.spyOn(api, "getAthlete").mockImplementation(owner);
    vi.spyOn(api, "getSamples").mockResolvedValue({ every: 1, secs: [0, 1, 2], power: [200, 210, 220] });
    vi.spyOn(api, "getActivityPowerCurve").mockResolvedValue({ points: [{ secs: 1, watts: 600 }], standard: {} });
    return vi.spyOn(api, "getAthletePowerCurve").mockResolvedValue({ points: [], standard: {} });
  }

  function renderActivity() {
    return renderPage(
      <Routes>
        <Route path="/activities/:id" element={<ActivityPage />} />
      </Routes>,
      undefined,
      ["/activities/act-1"],
    );
  }

  // 기준일(2026-10-24): 2026-10-14T21:30Z는 서울로 10-15 06:30이다. 비교 커브(최근 6주 최고)는 소유 선수의 시간대로 센 현지
  // 날짜 2026-10-15를 asOf로 요청해야 한다(서버는 asOf를 선수 시간대의 현지 날짜로 읽어 창을 그날 24시까지 잡는다). 고치기
  // 전에는 startedAt의 앞 10자(UTC 날짜 10-14)를 보내 창이 서울 10-15 0시에서 끝나, 이 라이딩 자체가 비교 곡선에서 빠졌다.
  // 선수 시간대를 받기 전에 UTC 날짜로 한 번 더 요청해서도 안 되고, 머리글의 시작 시각도 선수 시간대로 보여야 한다.
  it("asks for the comparison curve as of the ride's local date in the athlete's time zone", async () => {
    const best = mockActivityPage();
    renderActivity();

    await waitFor(() => expect(best).toHaveBeenCalledWith("a1", 6, "2026-10-15"));
    expect(best).not.toHaveBeenCalledWith("a1", 6, "2026-10-14");
    expect(screen.getByRole("heading", { level: 5 })).toHaveTextContent(
      new Date(RIDE.startedAt).toLocaleString(undefined, { timeZone: "Asia/Seoul" }),
    );
  });

  // Java 정규화 이름(2026-10-30): 'UTC+9'로 시간대를 정한 선수는 서버가 'UTC+09:00'으로 저장해 돌려준다. 브라우저 Intl은
  // 이 이름을 몰라, 고치기 전에는 기준일이 UTC 날짜(2026-10-14)로 대체됐다. 서버는 같은 라이딩을 +09:00의 현지 날짜
  // 10-15로 세므로(TimeZoneTest), 화면도 asOf=2026-10-15로 요청해야 이 라이딩이 비교 곡선에 들어간다.
  it("asks for the comparison curve on the athlete's local date when the zone is a Java offset name", async () => {
    const best = mockActivityPage(() => Promise.resolve({ ...ATHLETE, timeZone: "UTC+09:00" }));
    renderActivity();

    await waitFor(() => expect(best).toHaveBeenCalledWith("a1", 6, "2026-10-15"));
    expect(best).not.toHaveBeenCalledWith("a1", 6, "2026-10-14");
    expect(screen.getByRole("heading", { level: 5 })).toHaveTextContent(
      new Date(RIDE.startedAt).toLocaleString(undefined, { timeZone: "+09:00" }),
    );
  });

  // 기준일의 대체(2026-10-24): 주인 선수 조회가 실패하거나(서버 오류) 응답에 시간대가 없으면(timeZone 필드가 생기기 전의
  // 서버) 비교 커브를 비워 두지 않고 2026-10-24 전과 같은 UTC 날짜(2026-10-14)로 요청한다. '시간대가 올 때까지'만 기다리면
  // 시간대가 없는 응답에서는 비교 곡선이 끝내 나오지 않는다.
  it.each([
    ["the owner lookup fails", () => Promise.reject(new ApiRequestError(500, { error: "internal", message: "unexpected error" }))],
    ["the owner has no time zone", () => Promise.resolve({ ...ATHLETE, timeZone: undefined } as unknown as Athlete)],
  ])("falls back to the UTC date for the comparison curve when %s", async (_case, owner) => {
    const best = mockActivityPage(owner);
    renderActivity();

    await waitFor(() => expect(best).toHaveBeenCalledWith("a1", 6, "2026-10-14"));
  });

  // 삭제 실패(2026-10-24): DELETE도 같은 선수의 다른 쓰기 요청이 선수 행 잠금을 쥐고 있으면 409 athlete_busy + Retry-After다.
  // 상세 화면은 그 이유를 한국어 설명과 기다릴 초로 보여 주고 목록으로 넘어가지 않아야 한다. 고치기 전에는 삭제 실패를
  // 어디에도 보이지 않아, 버튼이 다시 눌리는 것 말고는 아무 일도 일어나지 않았다.
  it("explains a failed deletion in Korean with the Retry-After wait", async () => {
    mockActivityPage();
    vi.spyOn(window, "confirm").mockReturnValue(true);
    const remove = vi
      .spyOn(api, "deleteActivity")
      .mockRejectedValue(
        new ApiRequestError(409, { error: "athlete_busy", message: "another request is updating this athlete; retry after 5 seconds" }, 5),
      );
    renderActivity();

    fireEvent.click(await screen.findByRole("button", { name: "삭제" }));

    expect(
      await screen.findByText(
        "이 선수의 다른 요청(업로드·FTP·시간대 변경·삭제)이 처리 중입니다. 5초 뒤 다시 시도하세요 " +
          "(athlete_busy: another request is updating this athlete; retry after 5 seconds)",
      ),
    ).toBeInTheDocument();
    expect(remove).toHaveBeenCalledWith("act-1");
    expect(screen.getByRole("heading", { level: 5 })).toBeInTheDocument();
  });

  // 표시(2026-10-24): rec_int_secs는 DB의 real(float4) 열이라 1.26이 1.2599999904632568처럼 올 수 있다. 다른 지표처럼 반올림해
  // '간격 1.26s'로 보여야 한다. 고치기 전에는 숫자를 그대로 넣어 '간격 1.2599999904632568s'였다.
  it("rounds the recording interval to two decimals", async () => {
    mockActivityPage();
    renderActivity();

    expect(await screen.findByText(/간격 1\.26s/)).toBeInTheDocument();
    expect(screen.queryByText(/1\.2599999904632568/)).not.toBeInTheDocument();
  });
});

describe("api client", () => {
  // Retry-After 헤더 → 초. 서버는 초(정수)만 보낸다(ApiExceptionHandler). 없거나 정수가 아닌 값(RFC 9110이 허용하는 HTTP 날짜
  // 형식 포함)은 null로 두어 화면이 '잠시 뒤'라고 적게 한다.
  it("parses Retry-After seconds and ignores other forms", () => {
    expect(parseRetryAfter("5")).toBe(5);
    expect(parseRetryAfter(" 12 ")).toBe(12);
    expect(parseRetryAfter(null)).toBeNull();
    expect(parseRetryAfter("")).toBeNull();
    expect(parseRetryAfter("-1")).toBeNull();
    expect(parseRetryAfter("Wed, 21 Oct 2026 07:28:00 GMT")).toBeNull();
    expect(new ApiRequestError(500, { error: "internal", message: "x" }).retryAfterSeconds).toBeNull();
  });
});

describe("dashboard page", () => {
  // 대시보드의 다른 요청은 정상으로 두고(훈련 부하는 계속 기다리는 상태) 파워 커브만 바꿔 가며 본다.
  function mockDashboard() {
    useAthleteStore.setState({ athleteId: "a1" });
    vi.spyOn(api, "getAthlete").mockResolvedValue(ATHLETE);
    vi.spyOn(api, "listActivities").mockResolvedValue({ items: [] });
    vi.spyOn(api, "getTrainingLoad").mockReturnValue(new Promise(() => {}));
    vi.spyOn(api, "getFtpEstimate").mockResolvedValue(ESTIMATE);
  }

  it("shows only the error when the power curve request fails", async () => {
    mockDashboard();
    vi.spyOn(api, "getAthletePowerCurve").mockRejectedValue(
      new ApiRequestError(500, { error: "internal", message: "unexpected error" }),
    );
    renderPage(<DashboardPage />);
    expect(await screen.findByText(/unexpected error/)).toBeInTheDocument();
    expect(screen.queryByText("이 기간에 파워 데이터가 있는 라이딩이 없습니다.")).not.toBeInTheDocument();
  });

  it("explains an empty power curve", async () => {
    mockDashboard();
    vi.spyOn(api, "getAthletePowerCurve").mockResolvedValue({ points: [], standard: {} });
    renderPage(<DashboardPage />);
    expect(await screen.findByText("이 기간에 파워 데이터가 있는 라이딩이 없습니다.")).toBeInTheDocument();
  });
});
