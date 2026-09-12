package com.autovoice.app.action

/**
 * D07b 客户端最终准入 + 原子执行抢占:
 *
 * - 无动作身份(actionId 空)的动作拒绝执行(未签发/旧轮/落败);
 * - 已记账的 actionId 幂等返回既有结果,不重复执行;
 * - 首次触达以账本原子 claim 抢占执行权;
 * - 执行返回 null(结果未知)记 RESULT_UNKNOWN,**不自动重试**;
 * - 崩溃恢复由 [ActionLedgerStore.recoverUnknowns] 收敛残留 EXECUTING。
 */
class ActionExecutionGateway(private val ledger: ActionLedgerStore) {

    /**
     * @param action 实际执行;返回 true=成功,false=失败,null=结果未知
     * @return 本次执行是否成功(幂等命中时返回既有成功态)
     */
    fun execute(actionId: String, summary: String, action: () -> Boolean?): Boolean {
        if (actionId.isBlank()) {
            return false // 最终准入:无动作身份不执行
        }
        val existing = ledger.stateOf(actionId)
        if (existing != null) {
            return existing == ActionLedgerStore.STATE_SUCCESS // 幂等:不重复执行
        }
        if (!ledger.claim(actionId, summary)) {
            // 并发竞争:本线程未抢到,以既有终态为准
            return ledger.stateOf(actionId) == ActionLedgerStore.STATE_SUCCESS
        }
        val outcome = try {
            action()
        } catch (error: Throwable) {
            null // 异常无法确认下游结果 → 未知
        }
        ledger.markTerminal(
            actionId,
            when (outcome) {
                true -> ActionLedgerStore.STATE_SUCCESS
                false -> ActionLedgerStore.STATE_FAILED
                null -> ActionLedgerStore.STATE_RESULT_UNKNOWN
            },
        )
        return outcome == true
    }
}
