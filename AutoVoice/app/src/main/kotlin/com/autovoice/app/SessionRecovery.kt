package com.autovoice.app

import com.google.gson.JsonObject

/**
 * D15b 服务端恢复语义解析:
 *
 * - `new`:首次握手(客户端未携带 sessionId);
 * - `resumed`:既有会话已恢复——客户端应**保留**待选列表(若候选择仍有效);
 * - `reset`:服务端重建了会话(原会话过期/失效)——客户端应清理会话相关本地状态。
 *
 * `navigationCandidatesValid` 表示服务端侧该会话当前是否仍有有效候选列表。
 * 缺少字段(旧服务端)时按 `new + false` 处理:保守地认为无有效候选。
 */
data class SessionRecovery(
    val state: String,
    val navigationCandidatesValid: Boolean,
) {
    /** 是否需要清理客户端的待选列表(reset,或候选择已无效)。 */
    val shouldClearCandidates: Boolean
        get() = state == STATE_RESET || !navigationCandidatesValid

    companion object {
        const val STATE_NEW = "new"
        const val STATE_RESUMED = "resumed"
        const val STATE_RESET = "reset"

        /** 解析 ready 载荷;字段缺失按保守默认(新会话、无有效候选)。 */
        fun parse(payload: JsonObject): SessionRecovery {
            val state = payload.get("sessionState")
                ?.takeIf { it.isJsonPrimitive }?.asString ?: STATE_NEW
            val valid = payload.get("navigationCandidatesValid")
                ?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
            return SessionRecovery(state, valid)
        }
    }
}
