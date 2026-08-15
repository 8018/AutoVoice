import { afterEach, describe, expect, it, vi } from "vitest";
import { fetchRound, fetchRounds } from "./api";

afterEach(() => vi.unstubAllGlobals());

describe("telemetry api", () => {
  it("encodes the device filter and returns rounds", async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => [{ utteranceId: "u1" }] });
    vi.stubGlobal("fetch", fetchMock);

    await expect(fetchRounds("车 1")).resolves.toEqual([{ utteranceId: "u1" }]);
    expect(fetchMock).toHaveBeenCalledWith("/api/telemetry/rounds?device=%E8%BD%A6%201");
  });

  it("maps a missing round to null and surfaces other HTTP failures", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValueOnce({ ok: false, status: 404 }));
    await expect(fetchRound("missing/id")).resolves.toBeNull();

    vi.stubGlobal("fetch", vi.fn().mockResolvedValueOnce({ ok: false, status: 500 }));
    await expect(fetchRound("u1")).rejects.toThrow("http 500");
  });
});
