// [테스트 묶음] format.ts 표시 함수의 단위 테스트(Vitest). 시간 형식(시간 유무, 반올림), 값 없음 처리('-'),
// 와트 반올림, MMP 키와 축 이름 변환을 확인한다. 표·카드·차트 축이 모두 이 함수를 쓰므로 형식이 바뀌면 화면
// 전체가 달라진다.

import { describe, expect, it } from "vitest";
import { dateIn, describeError, formatDuration, formatWatts, ftpEstimateNote, isoDate, secsLabel, windowLabel } from "./format";

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

  // 현지 날짜(2026-10-15): 2026-10-14T21:30Z는 서울로는 10-15, UTC로는 10-14. 시간대가 없거나 모르는 이름이면
  // UTC 날짜로 대신한다. 서버의 선수 시간대 기준(TimeZoneTest)과 같은 경계를 화면도 쓰는지 본다.
  it("counts dates in the athlete's time zone", () => {
    const ride = new Date("2026-10-14T21:30:00Z");
    expect(dateIn(ride, "Asia/Seoul")).toBe("2026-10-15");
    expect(dateIn(ride, "UTC")).toBe("2026-10-14");
    expect(dateIn(ride)).toBe(isoDate(ride));
    expect(dateIn(ride, "Mars/Olympus")).toBe("2026-10-14");
  });

  // 화면 언어(2026-10-15): FTP 추정 안내는 서버의 숫자로 한국어 문장을 만들고, 숫자가 없는 응답이면 서버 note를 쓴다.
  // 서버 오류는 코드별 한국어 설명을 앞에 붙인다.
  it("builds the FTP estimate note and error messages in Korean", () => {
    const base = { weeks: 6, ridesInWindow: 12, note: "rule R1 ..." };
    expect(ftpEstimateNote({ ...base, ftpWatts: 250, devMaeWatts: 31, testMaeWatts: 29, testBiasWatts: -17 })).toBe(
      "규칙 R1: 최근 6주 파워 라이딩 12개의 20분 최고 파워 × 0.95. 선수가 설정한 FTP와 비교하면 평균 31 W(개발 세트), " +
        "봉인 테스트에서 29 W 차이가 났고 평균 17 W 낮게 추정했습니다. 학습 모델은 미리 정한 기준(5 W 이상 개선)을 확정하지 못해 쓰지 않습니다.",
    );
    expect(ftpEstimateNote({ ...base })).toBe("최근 6주에 파워가 기록된 20분 이상 라이딩이 없어 추정할 수 없습니다.");
    expect(ftpEstimateNote({ ...base, ftpWatts: 250 })).toBe("rule R1 ...");
    expect(describeError({ code: "fit_limit_exceeded", message: "more than 500000 record messages in file" })).toBe(
      "파일이 처리 한도(기록 50만 개)를 넘습니다 (fit_limit_exceeded: more than 500000 record messages in file)",
    );
    expect(describeError({ code: "teapot", message: "short and stout" })).toBe("teapot: short and stout");
    expect(describeError(new Error("network down"))).toBe("network down");
  });
});
