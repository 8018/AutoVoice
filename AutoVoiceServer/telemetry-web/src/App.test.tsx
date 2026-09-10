import { render, screen, within } from "@testing-library/react";
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

  it("keeps the dashboard usable when history loading fails", async () => {
    vi.mocked(fetchRounds).mockRejectedValue(new Error("offline"));
    render(<App />);

    expect(await screen.findByText(/历史数据拉取失败/)).toBeInTheDocument();
    expect(screen.getByText(/暂无轮次/)).toBeInTheDocument();
  });
});
