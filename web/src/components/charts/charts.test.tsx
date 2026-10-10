import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { PowerCurveChart } from "./PowerCurveChart";
import { TimeSeriesChart } from "./TimeSeriesChart";
import { TrainingLoadChart } from "./TrainingLoadChart";

describe("charts render without data problems", () => {
  it("draws a power curve path and dots for tiny curves", () => {
    const { container, rerender } = render(
      <PowerCurveChart points={[{ secs: 1, watts: 600 }, { secs: 60, watts: 400 }, { secs: 1200, watts: 300 }]} />,
    );
    expect(container.querySelectorAll("path").length).toBeGreaterThan(0);
    expect(container.querySelectorAll("circle").length).toBe(3); // three points -> dots too
    rerender(<PowerCurveChart points={[{ secs: 5, watts: 500 }]} />);
    expect(container.querySelectorAll("circle").length).toBe(1); // a single point is still visible
    rerender(<PowerCurveChart points={[]} />);
    expect(screen.getByRole("img", { name: "power curve" })).toBeInTheDocument();
  });

  it("training load chart keeps a sane y domain when every day is zero", () => {
    const days = Array.from({ length: 10 }, (_, i) => ({
      date: `2024-01-${String(i + 1).padStart(2, "0")}`,
      tss: 0,
      ctl: 0,
      atl: 0,
      tsb: 0,
    }));
    const { container } = render(<TrainingLoadChart days={days} />);
    const bars = Array.from(container.querySelectorAll("rect"));
    expect(bars.length).toBe(10);
    // Zero TSS must draw zero-height bars, not half-height ones (Codex review 2026-10-10).
    for (const b of bars) expect(Number(b.getAttribute("height"))).toBe(0);
  });

  it("time series skips null power samples in the path and shows the legend", () => {
    const secs = [0, 1, 2, 3, 4];
    const { container } = render(
      <TimeSeriesChart secs={secs} series={[{ name: "파워", color: "#000", values: [100, null, 120, 130, null], unit: "W" }]} />,
    );
    const d = container.querySelector("path")?.getAttribute("d") ?? "";
    // Two segments: [0] alone and [2..3]; d3 emits one "M" per segment.
    expect((d.match(/M/g) ?? []).length).toBe(2);
    expect(screen.getByText(/파워 \(W\)/)).toBeInTheDocument();
  });
});
