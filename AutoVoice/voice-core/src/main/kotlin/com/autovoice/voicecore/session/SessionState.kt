package com.autovoice.voicecore.session

/**
 * 会话状态机（spec §7.1）。
 *
 * ```
 * IDLE → LISTENING(录音+VAD) → UNDERSTANDING(竞速仲裁) → EXECUTING｜SPEAKING → IDLE
 *                               └──── 超时/全败 → IDLE（兜底话术归应用层）
 * ```
 *
 * barge-in 时允许 UNDERSTANDING/SPEAKING 直接转 LISTENING；旧轮结果由
 * utteranceId 最新轮闸门拦截。多轮上下文仍未实现。
 */
enum class SessionState {
    IDLE,
    LISTENING,
    UNDERSTANDING,
    EXECUTING,
    SPEAKING,
}
