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

const TICKS = [1, 5, 15, 60, 300, 1200, 3600, 10800];

/** Mean-maximal power against duration on a log axis. */
export function PowerCurveChart({ points, comparison, labels = ["이 라이딩", "최근 최고"], height = 280 }: Props) {
  const [ref, width] = useMeasure<HTMLDivElement>();
  const [hover, setHover] = useState<number | null>(null);
  const margin = { top: 12, right: 16, bottom: 28, left: 48 };
  const w = Math.max(width, 320);
  const innerW = w - margin.left - margin.right;
  const innerH = height - margin.top - margin.bottom;

  const all = useMemo(() => [...points, ...(comparison ?? [])], [points, comparison]);
  const x = useMemo(
    () => d3.scaleLog().domain([1, Math.max(2, d3.max(all, (p) => p.secs) ?? 2)]).range([0, innerW]),
    [all, innerW],
  );
  const y = useMemo(
    () => d3.scaleLinear().domain([0, (d3.max(all, (p) => p.watts) ?? 1) * 1.05]).range([innerH, 0]).nice(),
    [all, innerH],
  );
  const line = d3.line<CurvePoint>().x((p) => x(p.secs)).y((p) => y(p.watts)).curve(d3.curveMonotoneX);
  const hovered = hover == null || points.length === 0 ? null : points[d3.minIndex(points, (p) => Math.abs(x(p.secs) - hover))];

  return (
    <Box ref={ref} sx={{ width: "100%" }}>
      <svg
        width={w}
        height={height}
        role="img"
        aria-label="power curve"
        onMouseMove={(e) => {
          const rect = (e.currentTarget as SVGSVGElement).getBoundingClientRect();
          setHover(e.clientX - rect.left - margin.left);
        }}
        onMouseLeave={() => setHover(null)}
      >
        <g transform={`translate(${margin.left},${margin.top})`}>
          {y.ticks(5).map((t) => (
            <g key={t} transform={`translate(0,${y(t)})`}>
              <line x2={innerW} stroke="#e0e0e0" />
              <text x={-6} dy="0.32em" textAnchor="end" fontSize={11} fill="#666">
                {t}
              </text>
            </g>
          ))}
          {TICKS.filter((t) => t <= x.domain()[1]).map((t) => (
            <text key={t} x={x(t)} y={innerH + 18} textAnchor="middle" fontSize={11} fill="#666">
              {secsLabel(t)}
            </text>
          ))}
          {comparison && comparison.length > 1 && (
            <path d={line(comparison) ?? ""} fill="none" stroke="#9e9e9e" strokeWidth={1.5} strokeDasharray="5 4" />
          )}
          {points.length > 1 && <path d={line(points) ?? ""} fill="none" stroke="#1565c0" strokeWidth={2} />}
          {hovered && <circle cx={x(hovered.secs)} cy={y(hovered.watts)} r={4} fill="#1565c0" />}
        </g>
      </svg>
      <Typography variant="caption" sx={{ display: "block", minHeight: 20, color: "text.secondary" }}>
        {hovered ? `${secsLabel(hovered.secs)}: ${formatWatts(hovered.watts)}` : `${labels[0]}${comparison ? ` · 점선: ${labels[1]}` : ""}`}
      </Typography>
    </Box>
  );
}
