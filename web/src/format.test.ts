// [테스트 묶음] format.ts 표시 함수의 단위 테스트(Vitest). 시간 형식(시간 유무, 반올림), 값 없음 처리('-'),
// 와트 반올림, MMP 키와 축 이름 변환을 확인한다. 표·카드·차트 축이 모두 이 함수를 쓰므로 형식이 바뀌면 화면
// 전체가 달라진다.

import { describe, expect, it } from "vitest";
import { formatDuration, formatWatts, secsLabel, windowLabel } from "./format";

describe("format helpers", () => {
  // 1시간 미만은 m:ss, 이상은 h:mm:ss, 소수 초는 반올림, null은 '-'.
  it("formats durations with and without hours", () => {
    expect(formatDuration(59)).toBe("0:59");
    expect(formatDuration(3600)).toBe("1:00:00");
    expect(formatDuration(4700.05)).toBe("1:18:20");
    expect(formatDuration(null)).toBe("-");
  });

  // 와트는 정수로 반올림해 단위를 붙이고 없으면 '-'. 구간 키 '20m_critical_power' → '20m', 초 →
  // 5s·5m·1h·1h30m.
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
