package com.autovoice.voicecore.session

/**
 * 会话状态机（spec §7.1）。
 *
 * ```
 * IDLE → LISTENING(录音+VAD) → UNDERSTANDING(竞速仲裁) → EXECUTING｜SPEAKING → IDLE
 *                               └──── 超时/全败 → IDLE（兜底话术归应用层）
 * ```
 *
 * 预留不实现：barge-in（SPEAKING 中打断重听）、多轮上下文。
 */
enum class SessionState {
    IDLE,
    LISTENING,
    UNDERSTANDING,
    EXECUTING,
    SPEAKING,
}
