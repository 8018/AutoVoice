import { describe, expect, it } from "vitest";
import { isFailedSummary, stageLabel, stageOrder } from "./stages";

describe("stage metadata", () => {
  it("orders known stages and keeps unknown stages visible", () => {
    expect(stageOrder("cloud_asr")).toBeLessThan(stageOrder("llm"));
    expect(stageLabel("custom_stage")).toBe("custom_stage");
  });

  it("marks failed final decisions", () => {
    expect(isFailedSummary({ finalDecision: "both_failed" } as never)).toBe(true);
    expect(isFailedSummary({ finalDecision: "cloud" } as never)).toBe(false);
  });
});
