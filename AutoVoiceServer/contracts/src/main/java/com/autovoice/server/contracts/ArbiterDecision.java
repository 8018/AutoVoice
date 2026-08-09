package com.autovoice.server.contracts;

/**
 * 云端仲裁结果（双候选竞速：离线命令 ∥ LLM）。
 *
 * @param reply       胜出回复（action：离线/LLM 车控；text：LLM 闲聊或 safety 兜底）
 * @param reason      收敛原因：{@code offline_won} / {@code llm_reply} / {@code safety_timeout}
 * @param offlineText 离线胜出时的识别原文，其余路径为 null（下行 asrText 用）
 */
public record ArbiterDecision(Reply reply, String reason, String offlineText) {
}
