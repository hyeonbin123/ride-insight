// [테스트 묶음] format.ts 표시 함수의 단위 테스트(Vitest). 시간 형식(시간 유무, 반올림), 값 없음 처리('-'),
// 와트 반올림, MMP 키와 축 이름 변환을 확인한다. 표·카드·차트 축이 모두 이 함수를 쓰므로 형식이 바뀌면 화면
// 전체가 달라진다.

import { describe, expect, it } from "vitest";
import { dateIn, describeError, formatDate, formatDuration, formatWatts, ftpEstimateNote, isoDate, secsLabel, windowLabel } from "./format";

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

  // 선수 시간대 표시(2026-10-24): formatDate에 시간대를 주면 그 시간대의 현지 시각으로, 없으면 브라우저 시간대로 보여 준다.
  // 서버가 PMC 날짜·FTP 적용일·기준일을 선수 시간대로 세므로 목록·상세의 시작 시각도 같은 시간대로 보여야 PMC의 날짜와
  // 맞는다. 브라우저가 모르는 시간대 이름이면 브라우저 시간대로 대신하고, 해석할 수 없는 문자열은 그대로 돌려준다.
  // 형식은 브라우저 언어(toLocaleString)에 따르므로 같은 함수의 결과와 비교한다.
  it("formats dates in the athlete's time zone when one is given", () => {
    const iso = "2026-10-14T21:30:00Z";
    expect(formatDate(iso, "Asia/Seoul")).toBe(new Date(iso).toLocaleString(undefined, { timeZone: "Asia/Seoul" }));
    expect(formatDate(iso, "Asia/Seoul")).not.toBe(formatDate(iso, "UTC"));
    expect(formatDate(iso)).toBe(new Date(iso).toLocaleString());
    expect(formatDate(iso, "Mars/Olympus")).toBe(new Date(iso).toLocaleString());
    expect(formatDate("not a date", "Asia/Seoul")).toBe("not a date");
  });

  // 잠시 뒤 다시 보내면 되는 오류(2026-10-24): 429 too_many_uploads(서버 전체 동시 업로드 상한)와 409 athlete_busy(같은 선수의
  // 다른 쓰기 요청이 선수 행 잠금을 쥠)는 서버가 Retry-After(초)를 함께 보낸다. 설명 뒤에 그 초를 적고, 헤더가 없으면 '잠시
  // 뒤'라고 적는다. 다른 코드는 Retry-After가 없으면 전과 같은 문장이다. 고치기 전에는 두 코드의 한국어 설명이 없어 영어
  // 원문('too_many_uploads: ...')만 나왔고 Retry-After를 읽지 않았다.
  it("explains retryable errors in Korean with the Retry-After wait", () => {
    expect(describeError({ code: "too_many_uploads", message: "too many concurrent uploads", retryAfterSeconds: 5 })).toBe(
      "서버가 다른 업로드를 처리하는 중입니다. 5초 뒤 다시 시도하세요 (too_many_uploads: too many concurrent uploads)",
    );
    expect(describeError({ code: "too_many_uploads", message: "too many concurrent uploads", retryAfterSeconds: null })).toBe(
      "서버가 다른 업로드를 처리하는 중입니다. 잠시 뒤 다시 시도하세요 (too_many_uploads: too many concurrent uploads)",
    );
    expect(describeError({ code: "athlete_busy", message: "athlete is busy", retryAfterSeconds: 3 })).toBe(
      "이 선수의 다른 요청(업로드·FTP·시간대 변경·삭제)이 처리 중입니다. 3초 뒤 다시 시도하세요 (athlete_busy: athlete is busy)",
    );
    expect(describeError({ code: "athlete_busy", message: "athlete is busy" })).toBe(
      "이 선수의 다른 요청(업로드·FTP·시간대 변경·삭제)이 처리 중입니다. 잠시 뒤 다시 시도하세요 (athlete_busy: athlete is busy)",
    );
    expect(describeError({ code: "duplicate_activity", message: "exists", retryAfterSeconds: null })).toBe(
      "같은 시작 시각의 라이딩이 이미 있습니다 (duplicate_activity: exists)",
    );
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

  // 측정 조건(2026-10-24): 3단계 오차(개발 31 W·봉인 29 W·편향 −17 W)는 6주 창에 파워 라이딩이 8개 이상인 예측 단위로만 잰
  // 값이다. 서버가 withinMeasuredRange=false로 알리면(52주 창, 라이딩 8개 미만) 오차 숫자 없이 '재지 않았다'는 문장을
  // 만들고, 조건 안(6주·8개)이면 지금 문장 그대로다. 조건 밖 문장에는 31·29·17이 어디에도 들어가면 안 된다(다른 창의
  // 오차는 등록된 측정이 아니라 보여 주지 않는다). 고치기 전에는 weeks·라이딩 수와 관계없이 같은 오차 문장을 만들었다.
  it("drops the measured error outside the measured condition", () => {
    const outside = {
      ftpWatts: 250,
      note: "rule R1 ... this window was not measured",
      withinMeasuredRange: false,
      measuredCondition: { windowWeeks: 6, minRidesInWindow: 8 },
    };
    const longWindow = ftpEstimateNote({ ...outside, weeks: 52, ridesInWindow: 40 });
    expect(longWindow).toBe(
      "규칙 R1: 최근 52주 파워 라이딩 40개의 20분 최고 파워 × 0.95. 선수가 설정한 FTP와의 오차는 6주 창에 파워 라이딩이 8개 이상일 때만 " +
        "재었고, 이 조건(52주, 라이딩 40개)의 오차는 재지 않았습니다. 학습 모델은 미리 정한 기준(5 W 이상 개선)을 확정하지 못해 쓰지 않습니다.",
    );
    expect(longWindow).not.toMatch(/31|29|17/);
    const fewRides = ftpEstimateNote({ ...outside, weeks: 6, ridesInWindow: 5 });
    expect(fewRides).toContain("이 조건(6주, 라이딩 5개)의 오차는 재지 않았습니다.");
    expect(fewRides).not.toMatch(/31|29|17/);
    expect(
      ftpEstimateNote({
        ...outside,
        withinMeasuredRange: true,
        weeks: 6,
        ridesInWindow: 8,
        devMaeWatts: 31,
        testMaeWatts: 29,
        testBiasWatts: -17,
      }),
    ).toBe(
      "규칙 R1: 최근 6주 파워 라이딩 8개의 20분 최고 파워 × 0.95. 선수가 설정한 FTP와 비교하면 평균 31 W(개발 세트), " +
        "봉인 테스트에서 29 W 차이가 났고 평균 17 W 낮게 추정했습니다. 학습 모델은 미리 정한 기준(5 W 이상 개선)을 확정하지 못해 쓰지 않습니다.",
    );
  });
});
