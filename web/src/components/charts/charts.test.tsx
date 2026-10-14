// [테스트 묶음] D3 차트 세 개를 jsdom에 실제로 렌더링해 데이터 경계 상황에서 깨지지 않는지 확인한다(Vitest +
// Testing Library). Codex 2차 검토가 지적한 화면 문제들(점이 적은 커브가 빈 차트로 보임, 훈련 없는 기간의
// 가짜 막대)의 회귀 테스트이기도 하다.
// jsdom에는 레이아웃이 없어 폭이 0으로 재지므로 차트는 최소 폭 320px로 그려진다.

import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { PowerCurveChart } from "./PowerCurveChart";
import { TimeSeriesChart } from "./TimeSeriesChart";
import { TrainingLoadChart } from "./TrainingLoadChart";

describe("charts render without data problems", () => {
  // 파워 커브: 점 3개면 선(path)과 점(circle 3개)을 모두 그리고, 점 1개면 선 없이 점 하나라도 보이며, 빈
  // 배열이어도 오류 없이 빈 차트(img 역할의 SVG)를 그려야 한다.
  it("draws a power curve path and dots for tiny curves", () => {
    const { container, rerender } = render(
      <PowerCurveChart points={[{ secs: 1, watts: 600 }, { secs: 60, watts: 400 }, { secs: 1200, watts: 300 }]} />,
    );
    expect(container.querySelectorAll("path").length).toBeGreaterThan(0);
    expect(container.querySelectorAll("circle").length).toBe(3); // 점이 세 개면 점 표시도 그린다
    rerender(<PowerCurveChart points={[{ secs: 5, watts: 500 }]} />);
    expect(container.querySelectorAll("circle").length).toBe(1); // 점이 하나뿐이어도 보여야 한다
    rerender(<PowerCurveChart points={[]} />);
    expect(screen.getByRole("img", { name: "power curve" })).toBeInTheDocument();
  });

  // 훈련 부하: 열흘 모두 TSS·CTL·ATL·TSB가 0이면 막대 10개의 높이가 모두 0이어야 한다. y 도메인이 [0, 0]이
  // 되면 0 값이 축 가운데로 계산돼 반쯤 찬 가짜 막대가 그려지던 문제를 막는다.
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
    // TSS가 0이면 막대 높이도 0이어야 하고 반쯤 찬 막대가 되면 안 된다(2026-10-10 Codex 검토).
    for (const b of bars) expect(Number(b.getAttribute("height"))).toBe(0);
  });

  // 시계열: 값 [100, null, 120, 130, null]이면 null에서 선이 끊겨 경로가 두 조각(M 명령 2개)이어야 하고, 범례
  // '파워 (W)'가 보여야 한다. 빠진 샘플을 0으로 이어 그리지 않는지 확인한다.
  it("time series skips null power samples in the path and shows the legend", () => {
    const secs = [0, 1, 2, 3, 4];
    const { container } = render(
      <TimeSeriesChart secs={secs} series={[{ name: "파워", color: "#000", values: [100, null, 120, 130, null], unit: "W" }]} />,
    );
    const d = container.querySelector("path")?.getAttribute("d") ?? "";
    // 두 조각: 혼자 있는 [0]과 [2..3]. d3는 조각마다 "M"을 하나씩 낸다.
    expect((d.match(/M/g) ?? []).length).toBe(2);
    expect(screen.getByText(/파워 \(W\)/)).toBeInTheDocument();
  });
});
