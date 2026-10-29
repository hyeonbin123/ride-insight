/** 페이지들이 함께 쓰는 작은 표시 형식 도우미. */

// [파일 역할] 화면 여러 곳(라이딩 목록·상세, 대시보드, 차트 축)이 같이 쓰는 표시용 문자열 변환 함수 모음.
// 값이 없거나(null/undefined) 유한하지 않으면 모두 '-'를 돌려줘, 지표가 없는 활동(파워 없음, FTP 없음)도
// 화면이 깨지지 않게 한다. 순수 함수라 format.test.ts에서 바로 검사한다.

// 초 → 'h:mm:ss'(1시간 이상) 또는 'm:ss'. 먼저 초 단위로 반올림한다(4700.05초 → 1:18:20). 라이딩 길이와
// 시계열 x축 눈금에 쓴다.
export function formatDuration(secs: number | undefined | null): string {
  if (secs == null || !Number.isFinite(secs)) return "-";
  const s = Math.round(secs);
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const r = s % 60;
  return h > 0 ? `${h}:${String(m).padStart(2, "0")}:${String(r).padStart(2, "0")}` : `${m}:${String(r).padStart(2, "0")}`;
}

// 와트 표시('301 W'). 기본은 소수점 없이 반올림한다.
export function formatWatts(w: number | undefined | null, digits = 0): string {
  return w == null || !Number.isFinite(w) ? "-" : `${w.toFixed(digits)} W`;
}

// 일반 숫자를 소수 digits자리로(IF는 2자리, TSS는 0자리, CTL 등은 1자리로 부른다).
export function formatNumber(v: number | undefined | null, digits = 1): string {
  return v == null || !Number.isFinite(v) ? "-" : v.toFixed(digits);
}

// Java가 정규화해 저장한 시간대 이름 → 브라우저 Intl이 받는 이름(2026-10-30). 서버는 선수 시간대를 `ZoneId.of(입력).id`로
// 저장해(server의 service/Errors.kt parseTimeZone) 'UTC+9'·'GMT-5'·'UT+9'는 'UTC+09:00'·'GMT-05:00'·'UT+09:00'으로, '+00:00'·
// 'Z'는 'Z'로, 'UT'는 'UT'로 돌려준다(JDK 21로 확인). Intl은 IANA 이름과 '+09:00' 같은 오프셋만 받아 이 이름들에 RangeError를
// 내고, 그러면 dateIn·formatDate가 UTC 날짜·브라우저 시간대로 대신해 서버와 날짜가 하루 어긋날 수 있었다(Node 24.16 확인).
// 그래서 오프셋 앞의 UTC·GMT·UT를 떼고('UTC+09:00' → '+09:00'. 두 표기 모두 +가 그리니치 동쪽이라 뜻이 같다) 'Z'·'UT'는
// 'UTC'로 바꾼다. 서버에서 ZoneId.normalized()로 바꾸는 길은 'UTC'까지 'Z'가 되어 Intl이 다시 실패하므로 쓰지 않았다.
// 다른 이름(IANA 지역 이름, 'UTC', 'GMT', '+09:00', 'Etc/GMT-9')은 그대로 넘긴다. 초까지 있는 오프셋('+09:00:30')은 Intl이
// 받지 않아 부르는 쪽이 전처럼 대체한다.
function intlTimeZone(timeZone: string): string {
  if (timeZone === "Z" || timeZone === "UT") return "UTC";
  return timeZone.replace(/^(?:UTC|GMT|UT)(?=[+-])/, "");
}

// 서버의 ISO 시각을 사람이 읽는 시각으로 바꾼다. 형식은 브라우저의 언어 설정(toLocaleString)을 따르고, 시간대는 timeZone을
// 주면 그 시간대(선수 시간대), 없으면 브라우저 시간대다. 서버가 2026-10-15부터 PMC 날짜·FTP 적용일·기준일을 선수 시간대로
// 세므로 목록·상세의 시작 시각도 같은 시간대로 보여야 PMC의 날짜와 맞는다(2026-10-24, 그 전에는 브라우저 시간대만 썼다).
// Java가 정규화한 오프셋 이름('UTC+09:00' 등)은 intlTimeZone으로 바꿔 넘긴다(2026-10-30). 그래도 브라우저가 모르는 이름이면
// RangeError가 나므로 브라우저 시간대로 대신한다. 해석할 수 없는 문자열은 그대로 보여 준다.
export function formatDate(iso: string, timeZone?: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  if (timeZone) {
    try {
      return d.toLocaleString(undefined, { timeZone: intlTimeZone(timeZone) });
    } catch {
      // 모르는 시간대 이름: 아래에서 브라우저 시간대로 표시한다
    }
  }
  return d.toLocaleString();
}

/** MMP 키(GoldenCheetah 이름)에서 '_critical_power'를 떼어 짧은 구간 이름만 남긴다. 예: "20m_critical_power" -> "20m". */
export function windowLabel(key: string): string {
  return key.replace("_critical_power", "");
}

/**
 * 초 → 파워 커브 x축(로그 축) 눈금과 마우스 위치 표시에 쓰는 짧은 시간 이름(1s, 30s, 5m, 1h). 60초 미만은
 * 초, 1시간 미만은 분(반올림), 그 이상은 시간과 남은 분(예: 5400초 → 1h30m).
 */
export function secsLabel(secs: number): string {
  if (secs < 60) return `${secs}s`;
  if (secs < 3600) return `${Math.round(secs / 60)}m`;
  const h = Math.floor(secs / 3600);
  const m = Math.round((secs % 3600) / 60);
  return m ? `${h}h${m}m` : `${h}h`;
}

// Date → 'yyyy-MM-dd'(UTC 날짜). 날짜 문자열끼리 더하고 빼는 계산(PMC 조회 범위)에만 쓴다. '오늘'이나 라이딩
// 날짜처럼 사람이 보는 날짜는 선수 시간대로 세는 dateIn을 쓴다.
export function isoDate(d: Date): string {
  return d.toISOString().slice(0, 10);
}

// Date → 시간대 timeZone의 현지 날짜 'yyyy-MM-dd'. 서버가 2026-10-15부터 PMC·FTP 적용일·기준일을 선수 시간대(기본
// Asia/Seoul)의 현지 날짜로 세므로, 화면의 '오늘'과 '마지막 라이딩 날짜'도 같은 시간대로 센다. 그 전에는 UTC
// 날짜라 한국 시각 오전 9시 전에는 전날이 됐다. en-CA 형식이 yyyy-mm-dd라 그대로 쓴다. Java가 정규화한 오프셋 이름은
// intlTimeZone으로 바꿔 넘긴다(2026-10-30, 그 전에는 'UTC+09:00' 선수의 날짜가 UTC 날짜로 대체됐다). 시간대가 없거나
// 그래도 브라우저가 모르는 이름이면 UTC 날짜로 대신한다.
export function dateIn(d: Date, timeZone?: string): string {
  if (!timeZone) return isoDate(d);
  try {
    return new Intl.DateTimeFormat("en-CA", {
      timeZone: intlTimeZone(timeZone),
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
    }).format(d);
  } catch {
    return isoDate(d);
  }
}

// FTP 추정 카드의 안내 문장(한국어). 서버의 note는 영어 문장이라 화면 언어와 맞지 않아, 2026-10-15부터 서버가
// 따로 주는 숫자(devMaeWatts 등)로 여기서 문장을 만든다. 숫자가 없는 응답(이전 서버)이면 note를 그대로 쓴다.
// 2026-10-24부터 서버가 withinMeasuredRange=false로 알리면(측정 조건인 6주 창·창 안 파워 라이딩 8개 이상이 아닌 12·52주
// 창이나 라이딩이 적은 창) 오차 숫자 없이 '재지 않았다'고 적는다. 이 검사를 숫자 없음 검사보다 먼저 해야 한다: 조건 밖
// 응답에는 숫자가 없어서, 먼저 보지 않으면 영어 note가 그대로 나간다. 조건 밖 문장에는 어떤 오차 수치도 넣지 않는다(다른
// 창의 오차는 등록된 측정이 아니다). 조건 수치(6주, 8개)는 서버의 measuredCondition을 그대로 쓴다.
export function ftpEstimateNote(e: {
  ftpWatts?: number;
  weeks: number;
  ridesInWindow: number;
  note: string;
  withinMeasuredRange?: boolean;
  measuredCondition?: { windowWeeks: number; minRidesInWindow: number };
  devMaeWatts?: number;
  testMaeWatts?: number;
  testBiasWatts?: number;
}): string {
  if (e.ftpWatts == null) return `최근 ${e.weeks}주에 파워가 기록된 20분 이상 라이딩이 없어 추정할 수 없습니다.`;
  const rule = `규칙 R1: 최근 ${e.weeks}주 파워 라이딩 ${e.ridesInWindow}개의 20분 최고 파워 × 0.95. `;
  const model = "학습 모델은 미리 정한 기준(5 W 이상 개선)을 확정하지 못해 쓰지 않습니다.";
  const c = e.measuredCondition;
  if (e.withinMeasuredRange === false && c) {
    return (
      rule +
      `선수가 설정한 FTP와의 오차는 ${c.windowWeeks}주 창에 파워 라이딩이 ${c.minRidesInWindow}개 이상일 때만 재었고, ` +
      `이 조건(${e.weeks}주, 라이딩 ${e.ridesInWindow}개)의 오차는 재지 않았습니다. ` +
      model
    );
  }
  if (e.devMaeWatts == null || e.testMaeWatts == null || e.testBiasWatts == null) return e.note;
  const direction = e.testBiasWatts < 0 ? "낮게" : "높게";
  return (
    rule +
    `선수가 설정한 FTP와 비교하면 평균 ${e.devMaeWatts} W(개발 세트), 봉인 테스트에서 ${e.testMaeWatts} W 차이가 났고 ` +
    `평균 ${Math.abs(e.testBiasWatts)} W ${direction} 추정했습니다. ` +
    model
  );
}

// 서버 오류 코드(docs/api.md)의 한국어 설명. 화면에 '설명 (코드: 서버 메시지)'로 보여 준다. 처음에는 서버의 영어
// 메시지만 보여 화면 언어가 섞였다(2026-10-15 수정).
const ERROR_TEXT: Record<string, string> = {
  not_found: "찾을 수 없습니다",
  validation: "입력값이 올바르지 않습니다",
  bad_request: "요청이 올바르지 않습니다",
  invalid_fit: "FIT 파일을 읽을 수 없습니다",
  invalid_activity: "활동으로 쓸 수 없는 파일입니다(기록이 없거나 시각이 거꾸로 감)",
  unsupported_sport: "사이클링 활동만 올릴 수 있습니다",
  duplicate_activity: "같은 시작 시각의 라이딩이 이미 있습니다",
  file_too_large: "파일이 10 MB를 넘습니다",
  fit_limit_exceeded: "파일이 처리 한도(기록 50만 개)를 넘습니다",
  // 2026-10-24 추가: 둘 다 잠시 뒤 같은 요청을 다시 보내면 되는 응답이라 describeError가 뒤에 Retry-After 초를 덧붙인다.
  too_many_uploads: "서버가 다른 업로드를 처리하는 중입니다",
  athlete_busy: "이 선수의 다른 요청(업로드·FTP·시간대 변경·삭제)이 처리 중입니다",
  internal: "서버 오류가 났습니다",
};

// 잠시 뒤 같은 요청을 다시 보내면 되는 오류 코드. 서버는 이 둘에 Retry-After 헤더(초)를 붙인다(docs/api.md): 429
// too_many_uploads는 서버 전체의 동시 업로드 수가 차서, 409 athlete_busy는 같은 선수의 다른 쓰기 요청이 선수 행 잠금을 쥐고
// 있어서다. 헤더를 못 받았어도(이전 서버, 헤더를 지우는 프록시) 이 코드면 '잠시 뒤'라고 적는다.
const RETRYABLE = new Set(["too_many_uploads", "athlete_busy"]);

// 오류 객체 → 화면 문장. code·message가 있는 서버 오류(ApiRequestError)면 한국어 설명을 앞에 붙이고, retryAfterSeconds(Retry-After
// 헤더, 2026-10-24)가 있으면 'N초 뒤 다시 시도하세요'를, 없어도 다시 보내면 되는 코드면 '잠시 뒤 다시 시도하세요'를 덧붙인다.
// 모르는 코드는 '코드: 메시지', 그 밖의 예외(네트워크 오류 등)는 메시지만.
export function describeError(e: unknown): string {
  if (e && typeof e === "object" && "code" in e && "message" in e) {
    const { code, message, retryAfterSeconds } = e as { code: string; message: string; retryAfterSeconds?: number | null };
    let wait = "";
    if (retryAfterSeconds != null && retryAfterSeconds > 0) wait = `${retryAfterSeconds}초 뒤 다시 시도하세요`;
    else if (RETRYABLE.has(code)) wait = "잠시 뒤 다시 시도하세요";
    const text = [ERROR_TEXT[code], wait].filter(Boolean).join(". ");
    return text ? `${text} (${code}: ${message})` : `${code}: ${message}`;
  }
  return e instanceof Error ? e.message : String(e);
}
