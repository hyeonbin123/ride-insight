import { describe, expect, it } from "vitest";
import { formatDuration, formatWatts, secsLabel, windowLabel } from "./format";

describe("format helpers", () => {
  it("formats durations with and without hours", () => {
    expect(formatDuration(59)).toBe("0:59");
    expect(formatDuration(3600)).toBe("1:00:00");
    expect(formatDuration(4700.05)).toBe("1:18:20");
    expect(formatDuration(null)).toBe("-");
  });

  it("formats watts and window labels", () => {
    expect(formatWatts(301.4)).toBe("301 W");
    expect(formatWatts(undefined)).toBe("-");
    expect(windowLabel("20m_critical_power")).toBe("20m");
    expect(secsLabel(5)).toBe("5s");
    expect(secsLabel(300)).toBe("5m");
    expect(secsLabel(3600)).toBe("1h");
    expect(secsLabel(5400)).toBe("1h30m");
  });
});
