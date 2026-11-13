import { act, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import App from "./App";
import { fetchRound, fetchRounds } from "./api";
import type { RoundSummary } from "./types";

vi.mock("./api", () => ({ fetchRounds: vi.fn(), fetchRound: vi.fn() }));
vi.mock("./useSse", () => ({ useRounds: (initial: RoundSummary[]) => initial }));

const healthy: RoundSummary = {
  utteranceId: "turn-ok",
  deviceId: "car-a",
  source: "voice",
  startMs: 1_000,
  endMs: 1_500,
  localDecision: null,
  cloudDecision: "llm",
  finalDecision: "navigation/navigate",
  ttsCacheHit: true,
  playbackResult: "completed",
  audioPath: "turn-ok.wav",
};

const failed: RoundSummary = {
  ...healthy,
  utteranceId: "turn-failed",
  deviceId: "car-b",
  endMs: 3_000,
  cloudDecision: "cloud",
  finalDecision: "failed",
  ttsCacheHit: false,
  audioPath: null,
};

describe("telemetry dashboard", () => {
  beforeEach(() => {
    vi.mocked(fetchRounds).mockResolvedValue([healthy, failed]);
    vi.mocked(fetchRound).mockResolvedValue({
      summary: healthy,
      events: [{ stage: "utterance_start", tsMs: 1_000, level: "info", payload: { source: "voice" } }],
    });
  });

  it("loads, filters and opens a round detail", async () => {
    const user = userEvent.setup();
    render(<App />);

    expect(await screen.findByText("turn-ok")).toBeInTheDocument();
    expect(screen.getByText("turn-failed")).toBeInTheDocument();
    expect(within(screen.getByText("总轮次").parentElement!).getByText("2")).toBeInTheDocument();

    await user.selectOptions(screen.getByRole("combobox", { name: /设备/ }), "car-b");
    expect(screen.queryByText("turn-ok")).not.toBeInTheDocument();
    expect(screen.getByText("turn-failed")).toBeInTheDocument();

    await user.selectOptions(screen.getByRole("combobox", { name: /设备/ }), "");
    await user.click(await screen.findByText("turn-ok"));
    expect(await screen.findByText("音频回放")).toBeInTheDocument();
    expect(screen.getByText("话语开始")).toBeInTheDocument();
    expect(fetchRound).toHaveBeenCalledWith("turn-ok");
  });

  it("shows the device's terminal counts and discards an older window response", async () => {
    let completeOldRequest: (response: Response) => void = () => {};
    const oldRequest = new Promise<Response>((resolve) => {
      completeOldRequest = resolve;
    });
    const fetchStats = vi.spyOn(globalThis, "fetch").mockImplementation((url) => {
      const params = new URL(String(url), "http://localhost").searchParams;
      if (params.get("device") !== "car-b") return oldRequest;
      if (Number(params.get("to")) - Number(params.get("from")) === 60 * 60 * 1000) {
        return Promise.resolve(Response.json({ operations: [] }));
      }
      return Promise.resolve(Response.json({
        operations: [{
          operation: "start",
          total: 2,
          successRate: 0.5,
          outcomes: { succeeded: 1, accepted: 1, unknown: 0, unsupported: 0, missing_location: 0,
            failed: 0, timeout: 0, cancelled: 0, no_results: 0, rejected: 0 },
          p50Ms: 0,
          p95Ms: 200,
        }],
      }));
    });

    try {
      const user = userEvent.setup();
      render(<App />);
      await screen.findByText("turn-failed");
      await user.selectOptions(screen.getByRole("combobox", { name: /设备/ }), "car-b");

      const panel = screen.getByRole("region", { name: "导航质量" });
      const row = (await within(panel).findByText("开始导航")).closest("tr")!;
      expect(within(row).getByText("50.0%")).toBeInTheDocument();
      expect(within(row).getByText("0 ms")).toBeInTheDocument();
      expect(within(row).getByText("200 ms")).toBeInTheDocument();

      await user.selectOptions(within(panel).getByRole("combobox", { name: "时间范围" }), "1");
      expect(await within(panel).findByText("所选范围内暂无导航操作数据")).toBeInTheDocument();
      await act(async () => completeOldRequest(Response.json({
        operations: [{ operation: "plan", total: 1, successRate: 1,
          outcomes: {}, p50Ms: 50, p95Ms: 50 }],
      })));
      expect(within(panel).queryByText("路线规划")).not.toBeInTheDocument();
      expect(within(panel).getByText("所选范围内暂无导航操作数据")).toBeInTheDocument();
    } finally {
      fetchStats.mockRestore();
    }
  });

  it("shows an access error and can retry navigation statistics", async () => {
    const fetchStats = vi.spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(new Response(null, { status: 401 }))
      .mockResolvedValueOnce(Response.json({ operations: [] }));
    try {
      const user = userEvent.setup();
      render(<App />);
      const panel = screen.getByRole("region", { name: "导航质量" });
      expect(await within(panel).findByRole("alert")).toHaveTextContent("查询导航统计需要遥测管理权限");
      await user.click(within(panel).getByRole("button", { name: "刷新" }));
      expect(await within(panel).findByText("所选范围内暂无导航操作数据")).toBeInTheDocument();
    } finally {
      fetchStats.mockRestore();
    }
  });

  it("keeps the dashboard usable when history loading fails", async () => {
    vi.mocked(fetchRounds).mockRejectedValue(new Error("offline"));
    render(<App />);

    expect(await screen.findByText(/历史数据拉取失败/)).toBeInTheDocument();
    expect(screen.getByText(/暂无轮次/)).toBeInTheDocument();
  });
});
