package com.autovoice.voicecore.session

/**
 * 会话状态机（spec §7.1）。
 *
 * ```
 * IDLE → LISTENING(录音+VAD) → UNDERSTANDING(竞速仲裁) → EXECUTING｜SPEAKING → IDLE
 *                               └──── 超时/全败 → IDLE（兜底话术归应用层）
 * ```
 *
 * 这是兼容 UI 的旧编排状态；业务当前轮与延时聆听由 dialog.DialogueStateMachine 管理。
 */
enum class SessionState {
    IDLE,
    LISTENING,
    UNDERSTANDING,
    EXECUTING,
    SPEAKING,
}
