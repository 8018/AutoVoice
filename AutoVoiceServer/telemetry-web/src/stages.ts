import type { RoundSummary } from "./types";

/**
 * 阶段中文标签（与两端 TelemetryStages 一一对应；B1-B4 事件细分后全集）。
 * label：面板显示名；order：**语义链顺序**（时间线排序主键）——端侧/服务端时钟
 * 偏差或旧事件未换算时，按语义链强制重排，避免 TTS 请求显示在仲裁之前。
 */
interface StageMeta {
  label: string;
  order: number;
}

export const STAGE_META: Record<string, StageMeta> = {
  utterance_start: { label: "话语开始", order: 10 },
  vad_start: { label: "VAD 开始", order: 20 },
  vad: { label: "VAD 分段", order: 30 },
  vad_end: { label: "VAD 结束", order: 40 },
  local_asr: { label: "本地识别", order: 50 },
  cloud_asr: { label: "云端识别", order: 60 },
  llm: { label: "大模型", order: 70 },
  offline_pool: { label: "离线池", order: 80 },
  cloud_arbiter_received: { label: "云端仲裁·收到候选", order: 90 },
  cloud_arbiter_won: { label: "云端仲裁·胜出", order: 95 },
  cloud_arbiter_lost: { label: "云端仲裁·失败", order: 95 },
  cloud_arbiter: { label: "云端仲裁", order: 100 },
  device_arbiter_received: { label: "端云仲裁·收到候选", order: 110 },
  device_arbiter_won: { label: "端云仲裁·胜出", order: 115 },
  device_arbiter_lost: { label: "端云仲裁·失败", order: 115 },
  device_arbiter: { label: "端云仲裁", order: 120 },
  execute: { label: "执行", order: 130 },
  tts_play_request: { label: "TTS 播报请求", order: 140 },
  tts_request: { label: "TTS 请求", order: 145 },
  tts_cache_check: { label: "TTS 缓存检查", order: 150 },
  tts_cache_hit: { label: "TTS 缓存命中", order: 155 },
  tts_cache_miss: { label: "TTS 缓存未命中", order: 155 },
  tts_synth_request: { label: "TTS 生成请求", order: 160 },
  tts_synth_ok: { label: "TTS 生成成功", order: 165 },
  tts_synth_failed: { label: "TTS 生成失败", order: 165 },
  tts_play_start: { label: "TTS 播放开始", order: 170 },
  tts_play_interrupted: { label: "TTS 播放中断", order: 175 },
  tts_play_end: { label: "TTS 播放结束", order: 175 },
  tts_play: { label: "TTS 播放", order: 175 },
};

/** 未知 stage 排最后（order 取极大值），同序内按时间戳。 */
export function stageMeta(stage: string): StageMeta {
  return STAGE_META[stage] ?? { label: stage, order: Number.MAX_SAFE_INTEGER };
}

export function stageLabel(stage: string): string {
  return STAGE_META[stage]?.label ?? stage;
}

export function stageOrder(stage: string): number {
  return STAGE_META[stage]?.order ?? Number.MAX_SAFE_INTEGER;
}

/**
 * 失败判定（摘要级）：finalDecision 含 failed（both_failed / routing_failed /
 * worker_failed…均命中）。列表与统计无事件明细，只能按决策列判定；
 * 明细级失败还会叠加任事件 level=error（见 RoundDetail）。
 */
export function isFailedSummary(s: RoundSummary): boolean {
  return (s.finalDecision ?? "").includes("failed");
}
