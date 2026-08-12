import type { RoundSummary } from "./types";

/** 13 个阶段的中文标签（与 TelemetryStages 一一对应）。 */
export const STAGE_LABELS: Record<string, string> = {
  utterance_start: "话语开始",
  vad: "VAD 分段",
  local_asr: "本地识别",
  cloud_asr: "云端识别",
  llm: "大模型",
  offline_pool: "离线池",
  cloud_arbiter: "云端仲裁",
  device_arbiter: "端云仲裁",
  execute: "执行",
  tts_request: "TTS 请求",
  tts_cache: "TTS 缓存",
  tts_synth: "TTS 合成",
  tts_play: "播放",
};

export function stageLabel(stage: string): string {
  return STAGE_LABELS[stage] ?? stage;
}

/**
 * 失败判定（摘要级）：finalDecision 含 failed（both_failed / routing_failed /
 * worker_failed…均命中）。列表与统计无事件明细，只能按决策列判定；
 * 明细级失败还会叠加任事件 level=error（见 RoundDetail）。
 */
export function isFailedSummary(s: RoundSummary): boolean {
  return (s.finalDecision ?? "").includes("failed");
}
