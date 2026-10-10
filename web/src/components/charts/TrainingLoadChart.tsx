import * as d3 from "d3";
import { useMemo, useState } from "react";
import { Box, Typography } from "@mui/material";
import type { TrainingLoadDay } from "../../api/types";
import { useMeasure } from "./useMeasure";

interface Props {
  days: TrainingLoadDay[];
  height?: number;
}

/** Performance management chart: daily TSS bars, CTL/ATL lines, TSB on the right axis. */
export function TrainingLoadChart({ days, height = 300 }: Props) {
  const [ref, width] = useMeasure<HTMLDivElement>();
  const [hover, setHover] = useState<number | null>(null);
  const margin = { top: 12, right: 48, bottom: 28, left: 44 };
  const w = Math.max(width, 320);
  const innerW = w - margin.left - margin.right;
  const innerH = height - margin.top - margin.bottom;

  const parsed = useMemo(() => days.map((d) => ({ ...d, t: new Date(d.date + "T00:00:00Z") })), [days]);
  const x = useMemo(
    () => d3.scaleTime().domain(d3.extent(parsed, (d) => d.t) as [Date, Date]).range([0, innerW]),
    [parsed, innerW],
  );
  const yLoad = useMemo(
    () => d3.scaleLinear().domain([0, (d3.max(parsed, (d) => Math.max(d.tss, d.ctl, d.atl)) ?? 1) * 1.05]).range([innerH, 0]).nice(),
    [parsed, innerH],
  );
  const yTsb = useMemo(() => {
    const ext = d3.extent(parsed, (d) => d.tsb) as [number, number];
    const m = Math.max(Math.abs(ext[0] ?? 0), Math.abs(ext[1] ?? 0), 10);
    return d3.scaleLinear().domain([-m, m]).range([innerH, 0]).nice();
  }, [parsed, innerH]);

  const lineCtl = d3.line<(typeof parsed)[number]>().x((d) => x(d.t)).y((d) => yLoad(d.ctl));
  const lineAtl = d3.line<(typeof parsed)[number]>().x((d) => x(d.t)).y((d) => yLoad(d.atl));
  const lineTsb = d3.line<(typeof parsed)[number]>().x((d) => x(d.t)).y((d) => yTsb(d.tsb));
  const barW = Math.max(1, innerW / Math.max(parsed.length, 1) - 1);
  const hovered = hover == null || parsed.length === 0 ? null : parsed[d3.minIndex(parsed, (d) => Math.abs(x(d.t) - hover))];

  return (
    <Box ref={ref} sx={{ width: "100%" }}>
      <svg
        width={w}
        height={height}
        role="img"
        aria-label="training load chart"
        onMouseMove={(e) => {
          const rect = (e.currentTarget as SVGSVGElement).getBoundingClientRect();
          setHover(e.clientX - rect.left - margin.left);
        }}
        onMouseLeave={() => setHover(null)}
      >
        <g transform={`translate(${margin.left},${margin.top})`}>
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
              {d3.timeFormat("%m/%d")(t)}
            </text>
          ))}
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
      <Typography variant="caption" sx={{ display: "block", minHeight: 20, color: "text.secondary" }}>
        {hovered
          ? `${hovered.date} · TSS ${hovered.tss.toFixed(0)} · CTL ${hovered.ctl.toFixed(1)} · ATL ${hovered.atl.toFixed(1)} · TSB ${hovered.tsb.toFixed(1)}`
          : "막대: 일일 TSS · 파랑: CTL(42일) · 주황: ATL(7일) · 보라(오른쪽 축): TSB"}
      </Typography>
    </Box>
  );
}
