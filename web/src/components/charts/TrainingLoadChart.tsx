// [파일 역할] 대시보드의 훈련 부하 차트(PMC, Performance Management Chart). 날짜별 TSS 막대, CTL(42일 지수
// 가중 평균, 체력)·ATL(7일, 피로) 선을 왼쪽 축에, TSB(컨디션 = 어제 CTL − 어제 ATL) 선을 오른쪽 축에 그린다.
// 값 계산은 서버(GET /athletes/{id}/training-load)가 하고 여기서는 그리기만 한다.

import * as d3 from "d3";
import { useMemo, useState } from "react";
import { Box, Typography } from "@mui/material";
import type { TrainingLoadDay } from "../../api/types";
import { useMeasure } from "./useMeasure";

interface Props {
  days: TrainingLoadDay[];
  height?: number;
}

/**
 * 훈련 부하 차트(PMC): 날짜별 TSS 막대, CTL/ATL 선, 오른쪽 축의 TSB.
 * props: days(서버가 준 날짜별 TSS·CTL·ATL·TSB), height. 오른쪽 여백은 TSB 축 숫자 자리다.
 */
export function TrainingLoadChart({ days, height = 300 }: Props) {
  const [ref, width] = useMeasure<HTMLDivElement>();
  const [hover, setHover] = useState<number | null>(null);
  const margin = { top: 12, right: 48, bottom: 28, left: 44 };
  const w = Math.max(width, 320);
  const innerW = w - margin.left - margin.right;
  const innerH = height - margin.top - margin.bottom;

  // 날짜 문자열을 UTC 자정의 Date로 바꿔 둔다(아래 UTC 축과 맞추기 위해).
  const parsed = useMemo(() => days.map((d) => ({ ...d, t: new Date(d.date + "T00:00:00Z") })), [days]);
  // API의 날짜는 UTC 기준 날짜라 축도 UTC여야 한다(2026-10-10 Codex 검토).
  const x = useMemo(
    () => d3.scaleUtc().domain(d3.extent(parsed, (d) => d.t) as [Date, Date]).range([0, innerW]),
    [parsed, innerW],
  );
  // 왼쪽 축(TSS·CTL·ATL): 0부터 세 값 중 최댓값의 105%까지. 최소 상한을 10으로 둬, 훈련이 없어 모든 값이 0인
  // 기간에도 도메인이 [0, 0]이 되어 0 TSS 막대가 높이 있는 가짜 막대로 그려지는 일을 막는다(Codex 2차 검토
  // 지적 9, charts.test.tsx가 확인).
  const yLoad = useMemo(
    () =>
      d3
        .scaleLinear()
        .domain([0, Math.max(10, (d3.max(parsed, (d) => Math.max(d.tss, d.ctl, d.atl)) ?? 0) * 1.05)])
        .range([innerH, 0])
        .nice(),
    [parsed, innerH],
  );
  // 오른쪽 축(TSB): 양수(회복)와 음수(피로 누적)가 모두 나오므로 0을 가운데 두는 대칭 범위 [−m, m]로 잡는다.
  // m은 TSB 절댓값의 최댓값이고 최소 10이다.
  const yTsb = useMemo(() => {
    const ext = d3.extent(parsed, (d) => d.tsb) as [number, number];
    const m = Math.max(Math.abs(ext[0] ?? 0), Math.abs(ext[1] ?? 0), 10);
    return d3.scaleLinear().domain([-m, m]).range([innerH, 0]).nice();
  }, [parsed, innerH]);

  // 세 선의 경로 생성기(x는 날짜, y는 각자의 축). 막대 폭은 하루당 폭에서 1px을 뺀 값(최소 1px)이라 막대
  // 사이에 틈이 생긴다.
  const lineCtl = d3.line<(typeof parsed)[number]>().x((d) => x(d.t)).y((d) => yLoad(d.ctl));
  const lineAtl = d3.line<(typeof parsed)[number]>().x((d) => x(d.t)).y((d) => yLoad(d.atl));
  const lineTsb = d3.line<(typeof parsed)[number]>().x((d) => x(d.t)).y((d) => yTsb(d.tsb));
  const barW = Math.max(1, innerW / Math.max(parsed.length, 1) - 1);
  // 마우스 x와 가장 가까운 날을 찾아 세로선과 아래 글자에 쓴다.
  const hovered = hover == null || parsed.length === 0 ? null : parsed[d3.minIndex(parsed, (d) => Math.abs(x(d.t) - hover))];

  return (
    <Box ref={ref} sx={{ width: "100%" }}>
      <svg
        width={w}
        height={height}
        role="img"
        aria-label="training load chart"
        onMouseMove={(e) => {
          // 마우스 좌표를 그림 안쪽 영역 기준(왼쪽 여백 제외)으로 바꾼다.
          const rect = (e.currentTarget as SVGSVGElement).getBoundingClientRect();
          setHover(e.clientX - rect.left - margin.left);
        }}
        onMouseLeave={() => setHover(null)}
      >
        <g transform={`translate(${margin.left},${margin.top})`}>
          {/* 왼쪽 축 눈금·격자선, 오른쪽 TSB 축 눈금(보라), 아래 날짜 눈금(UTC 기준 월/일). */}
          {yLoad.ticks(5).map((t) => (
            <g key={t} transform={`translate(0,${yLoad(t)})`}>
              <line x2={innerW} stroke="#e0e0e0" />
              <text x={-6} dy="0.32em" textAnchor="end" fontSize={11} fill="#666">
                {t}
              </text>
            </g>
          ))}
          {yTsb.ticks(5).map((t) => (
            <text key={`tsb${t}`} x={innerW + 6} y={yTsb(t)} dy="0.32em" fontSize={11} fill="#7b1fa2">
              {t}
            </text>
          ))}
          {x.ticks(6).map((t) => (
            <text key={+t} x={x(t)} y={innerH + 18} textAnchor="middle" fontSize={11} fill="#666">
              {d3.utcFormat("%m/%d")(t)}
            </text>
          ))}
          {/* 그리는 순서가 곧 겹침 순서다: TSS 막대 → TSB 0 기준 점선 → TSB 선 → ATL 선 → CTL 선(가장 */}
          {/* 위). 막대는 날짜 위치를 가운데로 놓는다. */}
          {parsed.map((d) => (
            <rect key={d.date} x={x(d.t) - barW / 2} y={yLoad(d.tss)} width={barW} height={innerH - yLoad(d.tss)} fill="#90caf9" />
          ))}
          <line x1={0} x2={innerW} y1={yTsb(0)} y2={yTsb(0)} stroke="#ce93d8" strokeDasharray="3 3" />
          <path d={lineTsb(parsed) ?? ""} fill="none" stroke="#7b1fa2" strokeWidth={1.2} />
          <path d={lineAtl(parsed) ?? ""} fill="none" stroke="#ef6c00" strokeWidth={1.5} />
          <path d={lineCtl(parsed) ?? ""} fill="none" stroke="#1565c0" strokeWidth={2} />
          {hovered && <line x1={x(hovered.t)} x2={x(hovered.t)} y1={0} y2={innerH} stroke="#424242" />}
        </g>
      </svg>
      {/* 차트 아래 한 줄: 마우스가 있으면 그날의 TSS·CTL·ATL·TSB, 없으면 색 범례. */}
      <Typography variant="caption" sx={{ display: "block", minHeight: 20, color: "text.secondary" }}>
        {hovered
          ? `${hovered.date} · TSS ${hovered.tss.toFixed(0)} · CTL ${hovered.ctl.toFixed(1)} · ATL ${hovered.atl.toFixed(1)} · TSB ${hovered.tsb.toFixed(1)}`
          : "막대: 일일 TSS · 파랑: CTL(42일) · 주황: ATL(7일) · 보라(오른쪽 축): TSB"}
      </Typography>
    </Box>
  );
}
