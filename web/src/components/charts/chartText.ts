import * as d3 from "d3";
import type { CurvePoint, TrainingLoadDay } from "../../api/types";
import { formatDuration, formatWatts, secsLabel } from "../../format";
import type { Series } from "./TimeSeriesChart";

// [파일 역할] 차트 접근성 문구(화면 읽기용 요약)를 만드는 함수와 상수. 컴포넌트 파일에는 컴포넌트만 두어야 Vite의
// fast refresh가 동작해 여기로 모았다. 파워 커브 표에 보여 줄 표준 구간(초)은 서버의 16구간(MeanMaximalPower.
// STANDARD_WINDOWS_SECS: 1·5·10·15·20·30초, 1·2·3·5·8·10·20·30·60·90분)과 같다.
export const STANDARD_SECS = [1, 5, 10, 15, 20, 30, 60, 120, 180, 300, 480, 600, 1200, 1800, 3600, 5400];

// 화면 읽기용 한 줄 요약: '파워 커브(이 라이딩): 점 120개, 최고 812 W(1s), 20분 263 W'. 점이 없으면 그 사실만.
export function curveSummary(label: string, points: CurvePoint[]): string {
  if (points.length === 0) return `파워 커브(${label}): 값 없음`;
  const best = points[d3.maxIndex(points, (p) => p.watts)];
  const twenty = points.find((p) => p.secs === 1200);
  return (
    `파워 커브(${label}): 점 ${points.length}개, 최고 ${formatWatts(best.watts)}(${secsLabel(best.secs)})` +
    (twenty ? `, 20분 ${formatWatts(twenty.watts)}` : "")
  );
}

// 화면 읽기용 한 줄 요약: '훈련 부하 2026-07-17~2026-10-15: 마지막 날 CTL 52.3, ATL 61.0, TSB -4.1, 기간 TSS 합 4120'.
export function loadSummary(days: TrainingLoadDay[]): string {
  if (days.length === 0) return "훈련 부하: 값 없음";
  const last = days[days.length - 1];
  const total = days.reduce((s, d) => s + d.tss, 0);
  return (
    `훈련 부하 ${days[0].date}~${last.date}: 마지막 날 CTL ${last.ctl.toFixed(1)}, ATL ${last.atl.toFixed(1)}, ` +
    `TSB ${last.tsb.toFixed(1)}, 기간 TSS 합 ${total.toFixed(0)}`
  );
}

// 화면 읽기용 한 줄 요약: '시계열 1:14:05: 파워 평균 201 W·최대 812 W, 심박 평균 142 bpm·최대 178 bpm'. 빠진
// 값(null)은 평균·최대에서 뺀다.
export function seriesSummary(secs: number[], series: Series[]): string {
  const span = secs.length > 1 ? secs[secs.length - 1] - secs[0] : 0;
  const parts = series.map((s) => {
    const values = s.values.filter((v): v is number => v != null);
    if (values.length === 0) return `${s.name} 값 없음`;
    const mean = values.reduce((a, b) => a + b, 0) / values.length;
    return `${s.name} 평균 ${Math.round(mean)} ${s.unit}·최대 ${Math.round(Math.max(...values))} ${s.unit}`;
  });
  return `시계열 ${formatDuration(span)}: ${parts.join(", ")}`;
}
