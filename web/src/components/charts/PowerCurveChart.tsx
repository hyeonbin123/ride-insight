// [파일 역할] 파워 커브(구간 길이별 최대 평균 파워, MMP) 차트. 라이딩 상세에서는 이 라이딩 곡선과 최근 6주
// 최고 곡선(점선)을 겹쳐 보여 주고, 대시보드에서는 기간 최고 곡선 하나를 그린다.
// [구현 방식] D3는 스케일·선 경로 계산만 맡고, 실제 SVG 요소는 React JSX로 그린다. D3가 DOM을 직접 고치지
// 않으므로 React의 렌더링과 충돌하지 않는다.

import * as d3 from "d3";
import { useMemo, useState } from "react";
import { Box, Typography } from "@mui/material";
import type { CurvePoint } from "../../api/types";
import { formatWatts, secsLabel } from "../../format";
import { useMeasure } from "./useMeasure";

interface Props {
  /** Primary curve (an activity or the athlete's recent best). */
  points: CurvePoint[];
  /** Optional comparison curve drawn behind (e.g. the 6-week best behind one ride). */
  comparison?: CurvePoint[];
  labels?: [string, string];
  height?: number;
}

// x축 눈금으로 쓸 대표 시간(초): 1초, 5초, 15초, 1분, 5분, 20분, 1시간, 3시간. 로그 축이라 D3 기본 눈금 대신
// 사람이 읽기 쉬운 값을 직접 정한다. 데이터의 최대 구간보다 긴 눈금은 그리지 않는다.
const TICKS = [1, 5, 15, 60, 300, 1200, 3600, 10800];

/** Mean-maximal power against duration on a log axis. */
// props: points(주 곡선), comparison(선택, 뒤에 점선으로 그릴 비교 곡선), labels(범례 문구 두 개),
// height(px). 폭은 useMeasure로 잰 부모 폭(최소 320px)이고, 여백(margin)을 뺀 안쪽 영역에 그린다. hover는
// 마우스의 안쪽 영역 기준 x 좌표(px)다.
export function PowerCurveChart({ points, comparison, labels = ["이 라이딩", "최근 최고"], height = 280 }: Props) {
  const [ref, width] = useMeasure<HTMLDivElement>();
  const [hover, setHover] = useState<number | null>(null);
  const margin = { top: 12, right: 16, bottom: 28, left: 48 };
  const w = Math.max(width, 320);
  const innerW = w - margin.left - margin.right;
  const innerH = height - margin.top - margin.bottom;

  // 두 곡선을 합친 점들로 축 범위를 정해 둘 다 화면 안에 들어오게 한다.
  // - x: 로그 스케일(1초 ~ 최대 구간). 1초와 1시간을 한 화면에서 고르게 보려면 로그 축이 필요하다(짧은 구간이
  //   선형 축에서는 왼쪽 끝에 몰린다). 최솟값 2는 점이 1초 하나뿐일 때 도메인이 [1, 1]이 되는 것을 막는다.
  // - y: 0부터 최대 파워의 105%까지, nice()로 눈금이 깔끔한 값에서 끝나게 한다.
  // useMemo로 데이터·폭이 바뀔 때만 다시 계산한다(마우스 이동으로 다시 그릴 때는 재사용).
  const all = useMemo(() => [...points, ...(comparison ?? [])], [points, comparison]);
  const x = useMemo(
    () => d3.scaleLog().domain([1, Math.max(2, d3.max(all, (p) => p.secs) ?? 2)]).range([0, innerW]),
    [all, innerW],
  );
  const y = useMemo(
    () => d3.scaleLinear().domain([0, (d3.max(all, (p) => p.watts) ?? 1) * 1.05]).range([innerH, 0]).nice(),
    [all, innerH],
  );
  // 곡선 경로 생성기. curveMonotoneX는 점 사이를 부드럽게 잇되 점과 점 사이에서 값이 위아래로 튀어나오지
  // 않게(단조 보간) 해서, 실제 측정점보다 높은 가짜 값을 그리지 않는다.
  const line = d3.line<CurvePoint>().x((p) => x(p.secs)).y((p) => y(p.watts)).curve(d3.curveMonotoneX);
  // 마우스 x와 화면상 가장 가까운 주 곡선의 점(d3.minIndex)을 찾아 아래 글자와 강조 점에 쓴다.
  const hovered = hover == null || points.length === 0 ? null : points[d3.minIndex(points, (p) => Math.abs(x(p.secs) - hover))];

  return (
    <Box ref={ref} sx={{ width: "100%" }}>
      <svg
        width={w}
        height={height}
        role="img"
        aria-label="power curve"
        onMouseMove={(e) => {
          // SVG 기준 마우스 좌표에서 왼쪽 여백을 빼 안쪽 영역 좌표로 바꾼다. 나가면 강조를 지운다.
          const rect = (e.currentTarget as SVGSVGElement).getBoundingClientRect();
          setHover(e.clientX - rect.left - margin.left);
        }}
        onMouseLeave={() => setHover(null)}
      >
        <g transform={`translate(${margin.left},${margin.top})`}>
          {/* y축: 눈금 5개 안팎마다 연한 가로 격자선과 와트 값. */}
          {y.ticks(5).map((t) => (
            <g key={t} transform={`translate(0,${y(t)})`}>
              <line x2={innerW} stroke="#e0e0e0" />
              <text x={-6} dy="0.32em" textAnchor="end" fontSize={11} fill="#666">
                {t}
              </text>
            </g>
          ))}
          {/* x축: 데이터 범위 안의 대표 시간 눈금만 짧은 이름(1s, 5m, 1h)으로. */}
          {TICKS.filter((t) => t <= x.domain()[1]).map((t) => (
            <text key={t} x={x(t)} y={innerH + 18} textAnchor="middle" fontSize={11} fill="#666">
              {secsLabel(t)}
            </text>
          ))}
          {/* 비교 곡선(회색 점선)을 먼저 그려 주 곡선(파랑 실선) 뒤에 놓는다. 선은 점이 2개 이상일 때만 */}
          {/* 그린다. */}
          {comparison && comparison.length > 1 && (
            <path d={line(comparison) ?? ""} fill="none" stroke="#9e9e9e" strokeWidth={1.5} strokeDasharray="5 4" />
          )}
          {points.length > 1 && <path d={line(points) ?? ""} fill="none" stroke="#1565c0" strokeWidth={2} />}
          {/* 점이 1~3개뿐인 곡선은 선만으로는 거의 안 보이거나(점 1개면 선이 없다) 빈 차트처럼 보여 */}
          {/* 점으로도 표시한다(Codex 2차 검토 지적 12). 그 아래는 마우스 위치의 강조 점. */}
          {points.length <= 3 && points.map((p) => <circle key={p.secs} cx={x(p.secs)} cy={y(p.watts)} r={3} fill="#1565c0" />)}
          {hovered && <circle cx={x(hovered.secs)} cy={y(hovered.watts)} r={4} fill="#1565c0" />}
        </g>
      </svg>
      {/* 차트 아래 한 줄: 마우스가 있으면 그 구간의 값, 없으면 범례(비교 곡선이 있으면 '점선: ...'). */}
      {/* minHeight로 줄 높이를 고정해 글자가 바뀔 때 화면이 흔들리지 않게 한다. */}
      <Typography variant="caption" sx={{ display: "block", minHeight: 20, color: "text.secondary" }}>
        {hovered ? `${secsLabel(hovered.secs)}: ${formatWatts(hovered.watts)}` : `${labels[0]}${comparison ? ` · 점선: ${labels[1]}` : ""}`}
      </Typography>
    </Box>
  );
}
