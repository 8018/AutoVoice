import type { RoundSummary } from "./types";

/** 阶段中文标签（与两端 TelemetryStages 一一对应；B1-B4 事件细分后全集）。 */
export const STAGE_LABELS: Record<string, string> = {
  utterance_start: "话语开始",
  vad: "VAD 分段",
  vad_start: "VAD 开始",
  vad_end: "VAD 结束",
  local_asr: "本地识别",
  cloud_asr: "云端识别",
  llm: "大模型",
  offline_pool: "离线池",
  cloud_arbiter: "云端仲裁",
  cloud_arbiter_received: "云端仲裁·收到候选",
  cloud_arbiter_won: "云端仲裁·胜出",
  cloud_arbiter_lost: "云端仲裁·失败",
  device_arbiter: "端云仲裁",
  device_arbiter_received: "端云仲裁·收到候选",
  device_arbiter_won: "端云仲裁·胜出",
  device_arbiter_lost: "端云仲裁·失败",
  execute: "执行",
  tts_play_request: "TTS 播报请求",
  tts_cache_check: "TTS 缓存检查",
  tts_cache_hit: "TTS 缓存命中",
  tts_cache_miss: "TTS 缓存未命中",
  tts_synth_request: "TTS 生成请求",
  tts_synth_ok: "TTS 生成成功",
  tts_synth_failed: "TTS 生成失败",
  tts_play_start: "TTS 播放开始",
  tts_play_interrupted: "TTS 播放中断",
  tts_play_end: "TTS 播放结束",
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
