package com.autovoice.app.action

/**
 * D07b 手机动作本地账本端口(独立 SQLite 业务库,设备实现见 SqliteActionLedgerStore)。
 * 状态机:EXECUTING → SUCCESS / FAILED / RESULT_UNKNOWN。
 * 结果未知不自动重试;崩溃恢复把 EXECUTING 收敛为 RESULT_UNKNOWN。
 */
interface ActionLedgerStore {

    /** 原子抢占:actionId 首次登记返回 true(EXECUTING);已存在返回 false(不重复执行)。 */
    fun claim(actionId: String, summary: String): Boolean

    fun markTerminal(actionId: String, state: String)

    fun stateOf(actionId: String): String?

    /** 崩溃未知态恢复:启动时把残留 EXECUTING 全部收敛为 RESULT_UNKNOWN。 */
    fun recoverUnknowns()

    companion object {
        const val STATE_EXECUTING = "EXECUTING"
        const val STATE_SUCCESS = "SUCCESS"
        const val STATE_FAILED = "FAILED"
        const val STATE_RESULT_UNKNOWN = "RESULT_UNKNOWN"
    }
}
