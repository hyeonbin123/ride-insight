// [파일 역할] 라이딩 상세의 시계열 차트: 경과 시간(x)에 따른 파워(왼쪽 축)와 심박(오른쪽 축)을 선으로 그리고,
// FTP 기준선과 마우스 위치의 값 읽기를 보여 준다.
// [구현 방식] D3로 스케일과 선 경로 문자열만 만들고 SVG는 JSX로 그린다. 1시간 라이딩이면 점이 수천 개라
// 점마다 요소를 만들지 않고 시리즈마다 path 하나로 그린다.

import * as d3 from "d3";
import { useMemo, useState } from "react";
import { Box, Typography } from "@mui/material";
import { formatDuration } from "../../format";
import { useMeasure } from "./useMeasure";

// 그릴 시리즈 하나: 이름, 색, 값 배열(secs와 같은 길이, 빠진 샘플은 null), 단위, 오른쪽 축 여부.
export interface Series {
  name: string;
  color: string;
  values: (number | null)[];
  unit: string;
  /** 오른쪽 축에 그린다(예: 파워 옆의 심박). */
  right?: boolean;
}

interface Props {
  secs: number[];
  series: Series[];
  height?: number;
  /** 선택 사항인 왼쪽 축의 가로 기준선(예: FTP). */
  reference?: { value: number; label: string };
}

/**
 * 경과 시간에 따른 파워·심박·케이던스 그래프. 마우스를 올리면 그 시점 값을 보여 준다.
 * props: secs(x값, 경과 초), series(시리즈 목록), height, reference(왼쪽 축 기준선, 예: FTP). 좌우 여백
 * 48px은 양쪽 축 숫자 자리다.
 */
export function TimeSeriesChart({ secs, series, height = 260, reference }: Props) {
  const [ref, width] = useMeasure<HTMLDivElement>();
  const [hover, setHover] = useState<number | null>(null);
  const margin = { top: 12, right: 48, bottom: 28, left: 48 };
  const w = Math.max(width, 320);
  const innerW = w - margin.left - margin.right;
  const innerH = height - margin.top - margin.bottom;

  // 스케일 계산(데이터·폭이 바뀔 때만).
  // - x: 0 ~ 마지막 경과 초의 선형 축.
  // - 왼쪽 y: 왼쪽 시리즈 최댓값과 기준선(FTP) 중 큰 값의 105%까지. FTP가 라이딩 최고 파워보다 높아도
  //   기준선이 화면 밖으로 나가지 않는다.
  // - 오른쪽 y: 오른쪽 시리즈(심박) 최댓값의 105%까지. 축을 따로 두어 단위와 크기가 다른 파워(W)와
  //   심박(bpm)을 한 그림에서 비교한다.
  // null 값은 최댓값 계산에서 빼고, 값이 하나도 없으면 1을 써서 도메인이 [0, 0]이 되지 않게 한다.
  const scales = useMemo(() => {
    const x = d3.scaleLinear().domain([0, d3.max(secs) ?? 1]).range([0, innerW]);
    const left = series.filter((s) => !s.right);
    const right = series.filter((s) => s.right);
    const maxOf = (ss: Series[]) => d3.max(ss.flatMap((s) => s.values.filter((v): v is number => v != null))) ?? 1;
    const yL = d3.scaleLinear().domain([0, Math.max(maxOf(left), reference?.value ?? 0) * 1.05]).range([innerH, 0]).nice();
    const yR = d3.scaleLinear().domain([0, maxOf(right) * 1.05]).range([innerH, 0]).nice();
    return { x, yL, yR, hasRight: right.length > 0 };
  }, [secs, series, innerW, innerH, reference]);

  // 시리즈마다 SVG 경로 문자열을 만든다. 인덱스 0..n−1을 데이터로 넘기고 x는 secs[i], y는 values[i]로 찾는다.
  // defined()로 null인 샘플에서 선을 끊어, 센서가 빠진 구간을 0으로 떨어지는 선이 아니라 빈칸으로 보여
  // 준다(charts.test.tsx가 확인).
  const paths = useMemo(
    () =>
      series.map((s) => {
        const y = s.right ? scales.yR : scales.yL;
        const line = d3
          .line<number>()
          .defined((i) => s.values[i] != null)
          .x((i) => scales.x(secs[i]))
          .y((i) => y(s.values[i] as number));
        return line(d3.range(secs.length)) ?? "";
      }),
    [series, secs, scales],
  );

  const xTicks = scales.x.ticks(6);
  const yTicks = scales.yL.ticks(5);
  // 마우스 x(px)를 x 스케일의 invert로 경과 초로 바꾸고, 정렬된 secs에서 이진 탐색(bisectLeft)으로 그 시각
  // 이후 첫 샘플 인덱스를 찾는다. 수천 점에서도 마우스 이동마다 빠르게 찾는다. 범위를 벗어나면 양 끝 인덱스로
  // 자른다.
  const hoverIndex = hover == null ? null : Math.min(secs.length - 1, Math.max(0, d3.bisectLeft(secs, scales.x.invert(hover))));

  return (
    <Box ref={ref} sx={{ width: "100%", position: "relative" }}>
      <svg
        width={w}
        height={height}
        role="img"
        aria-label="time series chart"
        onMouseMove={(e) => {
          // 마우스 좌표를 그림 안쪽 영역 기준(왼쪽 여백 제외)으로 바꿔 hover에 저장한다.
          const rect = (e.currentTarget as SVGSVGElement).getBoundingClientRect();
          setHover(e.clientX - rect.left - margin.left);
        }}
        onMouseLeave={() => setHover(null)}
      >
        <g transform={`translate(${margin.left},${margin.top})`}>
          {/* 왼쪽 축 눈금과 가로 격자선. 오른쪽 축이 있으면 그 눈금은 흐린 색 숫자만 오른쪽에 적는다. */}
          {yTicks.map((t) => (
            <g key={t} transform={`translate(0,${scales.yL(t)})`}>
              <line x2={innerW} stroke="#e0e0e0" />
              <text x={-6} dy="0.32em" textAnchor="end" fontSize={11} fill="#666">
                {t}
              </text>
            </g>
          ))}
          {scales.hasRight &&
            scales.yR.ticks(5).map((t) => (
              <text key={`r${t}`} x={innerW + 6} y={scales.yR(t)} dy="0.32em" fontSize={11} fill="#999">
                {t}
              </text>
            ))}
          {/* x축 눈금은 경과 시간을 m:ss 또는 h:mm:ss로 적는다. */}
          {xTicks.map((t) => (
            <text key={t} x={scales.x(t)} y={innerH + 18} textAnchor="middle" fontSize={11} fill="#666">
              {formatDuration(t)}
            </text>
          ))}
          {/* FTP 기준선(점선)과 이름표. 파워가 이 선 위에 있는 구간이 FTP보다 강하게 탄 구간이다. */}
          {reference && (
            <g>
              <line x1={0} x2={innerW} y1={scales.yL(reference.value)} y2={scales.yL(reference.value)} stroke="#9e9e9e" strokeDasharray="4 4" />
              <text x={innerW - 4} y={scales.yL(reference.value) - 4} textAnchor="end" fontSize={11} fill="#757575">
                {reference.label}
              </text>
            </g>
          )}
          {series.map((s, i) => (
            <path key={s.name} d={paths[i]} fill="none" stroke={s.color} strokeWidth={1.2} opacity={0.9} />
          ))}
          {/* 마우스 위치의 세로선. */}
          {hoverIndex != null && (
            <line x1={scales.x(secs[hoverIndex])} x2={scales.x(secs[hoverIndex])} y1={0} y2={innerH} stroke="#424242" strokeWidth={1} />
          )}
        </g>
      </svg>
      {/* 차트 아래 한 줄: 마우스가 있으면 그 시각과 시리즈별 값(빠진 값은 '-'), 없으면 시리즈 이름과 단위 */}
      {/* 범례. */}
      <Typography variant="caption" sx={{ display: "block", minHeight: 20, color: "text.secondary" }}>
        {hoverIndex != null
          ? [`${formatDuration(secs[hoverIndex])}`, ...series.map((s) => `${s.name} ${s.values[hoverIndex] ?? "-"} ${s.unit}`)].join(" · ")
          : series.map((s) => `${s.name} (${s.unit})`).join(" · ")}
      </Typography>
    </Box>
  );
}
