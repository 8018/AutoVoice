import { act, renderHook } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { RoundSummary } from "./types";
import { useRounds } from "./useSse";

const round = (utteranceId: string): RoundSummary => ({
  utteranceId,
  deviceId: "car-a",
  source: "voice",
  startMs: 1,
  endMs: 2,
  localDecision: null,
  cloudDecision: "llm",
  finalDecision: "ok",
  ttsCacheHit: null,
  playbackResult: null,
  audioPath: null,
});

class FakeEventSource {
  static latest: FakeEventSource;
  readonly close = vi.fn();
  private listeners = new Map<string, EventListener>();

  constructor(readonly url: string) {
    FakeEventSource.latest = this;
  }

  addEventListener(type: string, listener: EventListener) {
    this.listeners.set(type, listener);
  }

  emit(type: string, data: string) {
    this.listeners.get(type)?.(new MessageEvent(type, { data }));
  }
}

afterEach(() => vi.unstubAllGlobals());

describe("round SSE subscription", () => {
  it("prepends and deduplicates valid frames, resets history and closes", () => {
    vi.stubGlobal("EventSource", FakeEventSource);
    const initial = [round("old")];
    const { result, rerender, unmount } = renderHook(
      ({ rounds }) => useRounds(rounds),
      { initialProps: { rounds: initial } },
    );

    expect(FakeEventSource.latest.url).toBe("/api/telemetry/stream");
    act(() => FakeEventSource.latest.emit("round", JSON.stringify(round("new"))));
    expect(result.current.map((item) => item.utteranceId)).toEqual(["new", "old"]);

    act(() => FakeEventSource.latest.emit("round", JSON.stringify({ ...round("old"), finalDecision: "updated" })));
    expect(result.current.map((item) => item.utteranceId)).toEqual(["old", "new"]);
    expect(result.current[0].finalDecision).toBe("updated");

    act(() => FakeEventSource.latest.emit("round", "not-json"));
    expect(result.current).toHaveLength(2);

    rerender({ rounds: [round("replacement")] });
    expect(result.current.map((item) => item.utteranceId)).toEqual(["replacement"]);
    const source = FakeEventSource.latest;
    unmount();
    expect(source.close).toHaveBeenCalledOnce();
  });
});
