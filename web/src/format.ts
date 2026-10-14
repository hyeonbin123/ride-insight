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

// 서버의 ISO 시각을 브라우저의 지역 시간대·언어 형식으로 바꾼다. 해석할 수 없는 문자열은 그대로 보여 준다.
export function formatDate(iso: string): string {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString();
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

// Date → 'yyyy-MM-dd'. toISOString을 쓰므로 UTC 기준 날짜다(한국 시간 오전 9시 전에는 전날 날짜가 된다).
// 서버가 기준일을 받지 않았을 때 쓰는 기본값도 UTC 오늘(LocalDate.now(UTC))이라 기준이 같다. 대시보드
// 기준일과 FTP 적용 시작일 기본값에 쓴다.
export function isoDate(d: Date): string {
  return d.toISOString().slice(0, 10);
}
