import * as d3 from "d3";
import { useMemo, useState } from "react";
import { Box, Typography } from "@mui/material";
import { formatDuration } from "../../format";
import { useMeasure } from "./useMeasure";

export interface Series {
  name: string;
  color: string;
  values: (number | null)[];
  unit: string;
  /** Draw on the right axis (e.g. heart rate next to power). */
  right?: boolean;
}

interface Props {
  secs: number[];
  series: Series[];
  height?: number;
  /** Optional horizontal reference line on the left axis (e.g. FTP). */
  reference?: { value: number; label: string };
}

/** Power / heart rate / cadence over elapsed time with a hover readout. */
export function TimeSeriesChart({ secs, series, height = 260, reference }: Props) {
  const [ref, width] = useMeasure<HTMLDivElement>();
  const [hover, setHover] = useState<number | null>(null);
  const margin = { top: 12, right: 48, bottom: 28, left: 48 };
  const w = Math.max(width, 320);
  const innerW = w - margin.left - margin.right;
  const innerH = height - margin.top - margin.bottom;

  const scales = useMemo(() => {
    const x = d3.scaleLinear().domain([0, d3.max(secs) ?? 1]).range([0, innerW]);
    const left = series.filter((s) => !s.right);
    const right = series.filter((s) => s.right);
    const maxOf = (ss: Series[]) => d3.max(ss.flatMap((s) => s.values.filter((v): v is number => v != null))) ?? 1;
    const yL = d3.scaleLinear().domain([0, Math.max(maxOf(left), reference?.value ?? 0) * 1.05]).range([innerH, 0]).nice();
    const yR = d3.scaleLinear().domain([0, maxOf(right) * 1.05]).range([innerH, 0]).nice();
    return { x, yL, yR, hasRight: right.length > 0 };
  }, [secs, series, innerW, innerH, reference]);

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
  const hoverIndex = hover == null ? null : Math.min(secs.length - 1, Math.max(0, d3.bisectLeft(secs, scales.x.invert(hover))));

  return (
    <Box ref={ref} sx={{ width: "100%", position: "relative" }}>
      <svg
        width={w}
        height={height}
        role="img"
        aria-label="time series chart"
        onMouseMove={(e) => {
          const rect = (e.currentTarget as SVGSVGElement).getBoundingClientRect();
          setHover(e.clientX - rect.left - margin.left);
        }}
        onMouseLeave={() => setHover(null)}
      >
        <g transform={`translate(${margin.left},${margin.top})`}>
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
          {xTicks.map((t) => (
            <text key={t} x={scales.x(t)} y={innerH + 18} textAnchor="middle" fontSize={11} fill="#666">
              {formatDuration(t)}
            </text>
          ))}
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
          {hoverIndex != null && (
            <line x1={scales.x(secs[hoverIndex])} x2={scales.x(secs[hoverIndex])} y1={0} y2={innerH} stroke="#424242" strokeWidth={1} />
          )}
        </g>
      </svg>
      <Typography variant="caption" sx={{ display: "block", minHeight: 20, color: "text.secondary" }}>
        {hoverIndex != null
          ? [`${formatDuration(secs[hoverIndex])}`, ...series.map((s) => `${s.name} ${s.values[hoverIndex] ?? "-"} ${s.unit}`)].join(" · ")
          : series.map((s) => `${s.name} (${s.unit})`).join(" · ")}
      </Typography>
    </Box>
  );
}
