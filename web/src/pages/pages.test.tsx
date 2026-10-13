// [테스트 묶음] 화면 두 곳의 서버 상태 처리를 api 함수를 가짜로 바꿔 확인한다(Vitest + Testing Library).
// - 라이딩 화면: 업로드가 끝나면 그 선수의 FTP 추정 캐시도 무효화해야 한다. 처음 구현은 목록·훈련 부하·파워
//   커브만 무효화해, 대시보드의 FTP 추정 카드가 staleTime(30초) 동안 업로드 전 값을 보였다.
// - 대시보드: 파워 커브 요청이 실패하면 오류 상자만 보여야 한다. 처음 구현은 오류 상자와 '파워 라이딩 없음'
//   안내를 함께 보였다.

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { ReactElement } from "react";
import { MemoryRouter } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";
import { api, ApiRequestError } from "../api/client";
import type { Activity, FtpEstimate } from "../api/types";
import { useAthleteStore } from "../state/athleteStore";
import { ActivitiesPage } from "./ActivitiesPage";
import { DashboardPage } from "./DashboardPage";

// 테스트마다 새 쿼리 캐시를 쓰고 재시도는 끈다(실패 응답을 바로 화면에 보이게).
function renderPage(ui: ReactElement, qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })) {
  const view = render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>{ui}</MemoryRouter>
    </QueryClientProvider>,
  );
  return { qc, ...view };
}

const ESTIMATE: FtpEstimate = {
  method: "R1",
  ftpWatts: 250,
  rules: { R1_twenty_minute: 250 },
  windowsUsed: 16,
  ridesInWindow: 10,
  best20m: 263,
  weeks: 6,
  note: "20분 최대 × 0.95",
};

const ACTIVITY: Activity = {
  id: "act-1",
  athleteId: "a1",
  startedAt: "2024-01-01T08:00:00Z",
  source: "fit",
  sampleCount: 60,
  device: {},
  hasPower: false,
  powerMissing: 60,
};

afterEach(() => {
  vi.restoreAllMocks();
  useAthleteStore.setState({ athleteId: null });
});

describe("activities page", () => {
  // 업로드 성공 뒤 같은 선수의 FTP 추정 캐시는 '무효' 상태가 되고, 다른 선수의 캐시는 그대로여야 한다.
  it("invalidates the athlete's FTP estimate after an upload", async () => {
    useAthleteStore.setState({ athleteId: "a1" });
    vi.spyOn(api, "listActivities").mockResolvedValue({ items: [] });
    const upload = vi.spyOn(api, "uploadActivity").mockResolvedValue(ACTIVITY);
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const mine = ["ftp-estimate", "a1", 6, "2024-01-01"];
    const other = ["ftp-estimate", "a2", 6, "2024-01-01"];
    qc.setQueryData(mine, ESTIMATE);
    qc.setQueryData(other, ESTIMATE);
    const { container } = renderPage(<ActivitiesPage />, qc);

    const input = container.querySelector('input[type="file"]') as HTMLInputElement;
    fireEvent.change(input, { target: { files: [new File([new Uint8Array([1, 2, 3])], "ride.fit")] } });

    await waitFor(() => expect(upload).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(qc.getQueryState(mine)?.isInvalidated).toBe(true));
    expect(qc.getQueryState(other)?.isInvalidated).toBe(false);
  });
});

describe("dashboard page", () => {
  // 대시보드의 다른 요청은 정상으로 두고(훈련 부하는 계속 기다리는 상태) 파워 커브만 바꿔 가며 본다.
  function mockDashboard() {
    useAthleteStore.setState({ athleteId: "a1" });
    vi.spyOn(api, "listActivities").mockResolvedValue({ items: [] });
    vi.spyOn(api, "getTrainingLoad").mockReturnValue(new Promise(() => {}));
    vi.spyOn(api, "getFtpEstimate").mockResolvedValue(ESTIMATE);
  }

  it("shows only the error when the power curve request fails", async () => {
    mockDashboard();
    vi.spyOn(api, "getAthletePowerCurve").mockRejectedValue(
      new ApiRequestError(500, { error: "internal", message: "unexpected error" }),
    );
    renderPage(<DashboardPage />);
    expect(await screen.findByText(/unexpected error/)).toBeInTheDocument();
    expect(screen.queryByText("이 기간에 파워 데이터가 있는 라이딩이 없습니다.")).not.toBeInTheDocument();
  });

  it("explains an empty power curve", async () => {
    mockDashboard();
    vi.spyOn(api, "getAthletePowerCurve").mockResolvedValue({ points: [], standard: {} });
    renderPage(<DashboardPage />);
    expect(await screen.findByText("이 기간에 파워 데이터가 있는 라이딩이 없습니다.")).toBeInTheDocument();
  });
});
