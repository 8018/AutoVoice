import { afterEach, describe, expect, it, vi } from "vitest";
import { discoverTools, listSkills, login } from "./api";
import type { SkillDraft } from "./types";

afterEach(() => vi.unstubAllGlobals());

describe("skill manager api", () => {
  it("sends login JSON and reports unauthorized", async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: false, status: 401, text: async () => "" });
    vi.stubGlobal("fetch", fetchMock);
    await expect(login("pw")).rejects.toThrow("unauthorized");
    expect(fetchMock).toHaveBeenCalledWith("/api/admin/login", expect.objectContaining({ method: "POST" }));
  });

  it("returns skills and omits an empty auth override during discovery", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce({ ok: true, status: 200, text: async () => "[]" })
      .mockResolvedValueOnce({ ok: true, status: 200, text: async () => "[]" });
    vi.stubGlobal("fetch", fetchMock);
    await expect(listSkills()).resolves.toEqual([]);

    const draft = {
      id: "maps", name: "Maps", description: "", mcpUrl: "https://mcp.example",
      authHeader: "Authorization", authValue: "", toolsJson: "[]", enabled: true,
    } satisfies SkillDraft;
    await discoverTools("maps", draft);
    const init = fetchMock.mock.calls[1][1] as RequestInit;
    expect(JSON.parse(String(init.body))).toEqual({
      mcpUrl: "https://mcp.example", authHeader: "Authorization",
    });
  });
});
