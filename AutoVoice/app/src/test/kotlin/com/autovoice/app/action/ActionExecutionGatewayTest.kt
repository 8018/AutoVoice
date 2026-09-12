package com.autovoice.app.action

import java.util.concurrent.ConcurrentHashMap
import com.autovoice.app.action.ActionLedgerStore.Companion.STATE_EXECUTING
import com.autovoice.app.action.ActionLedgerStore.Companion.STATE_RESULT_UNKNOWN
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** D07b 客户端最终准入与原子执行抢占(JVM 用内存 fake 账本)。 */
class ActionExecutionGatewayTest {

    private class FakeLedger : ActionLedgerStore {
        private val records = ConcurrentHashMap<String, String>()
        private val claimed = ConcurrentHashMap.newKeySet<String>()

        @Synchronized
        override fun claim(actionId: String, summary: String): Boolean {
            if (!claimed.add(actionId)) return false
            records[actionId] = STATE_EXECUTING
            return true
        }

        override fun markTerminal(actionId: String, state: String) {
            records[actionId] = state
        }

        override fun stateOf(actionId: String): String? = records[actionId]

        override fun recoverUnknowns() {
            records.replaceAll { _, state ->
                if (state == STATE_EXECUTING) STATE_RESULT_UNKNOWN else state
            }
        }
    }

    @Test
    fun blankActionIdIsRejectedWithoutExecution() {
        val ledger = FakeLedger()
        val gateway = ActionExecutionGateway(ledger)
        val calls = AtomicInteger()
        assertFalse(gateway.execute("", "x") { calls.incrementAndGet(); true }, "无动作身份不得执行")
        assertEquals(0, calls.get())
    }

    @Test
    fun claimedActionExecutesExactlyOnce() {
        val ledger = FakeLedger()
        val gateway = ActionExecutionGateway(ledger)
        val calls = AtomicInteger()
        assertTrue(gateway.execute("a1", "导航") { calls.incrementAndGet(); true })
        assertTrue(gateway.execute("a1", "导航") { calls.incrementAndGet(); true }, "重复触达幂等")
        assertEquals(1, calls.get())
        assertEquals(ActionLedgerStore.STATE_SUCCESS, ledger.stateOf("a1"))
    }

    @Test
    fun failedAndUnknownOutcomesAreRecordedWithoutRetry() {
        val ledger = FakeLedger()
        val gateway = ActionExecutionGateway(ledger)
        assertFalse(gateway.execute("a2", "失败") { false })
        assertEquals(ActionLedgerStore.STATE_FAILED, ledger.stateOf("a2"))
        // 幂等:失败结果不重试
        assertFalse(gateway.execute("a2", "失败") { true })

        val unknown = ActionExecutionGateway(ledger)
        assertFalse(unknown.execute("a3", "未知") { null })
        assertEquals(ActionLedgerStore.STATE_RESULT_UNKNOWN, ledger.stateOf("a3"))
    }

    @Test
    fun expiredActionIsRejectedEvenWhenLedgerHasNoRecord() {
        // D14c:超过支持窗口的请求明确拒绝——不因账本查不到就当新动作执行
        val ledger = FakeLedger()
        val gateway = ActionExecutionGateway(ledger)
        val calls = AtomicInteger()
        val past = System.currentTimeMillis() - 1_000

        assertFalse(gateway.execute("a-expired", "旧动作", actionExpiresAtMs = past) {
            calls.incrementAndGet(); true
        })
        assertEquals(0, calls.get(), "过期动作不得执行")
        assertEquals(null, ledger.stateOf("a-expired"), "过期动作不得写入账本")
    }

    @Test
    fun actionWithinWindowStillExecutes() {
        val ledger = FakeLedger()
        val gateway = ActionExecutionGateway(ledger)
        val calls = AtomicInteger()
        val future = System.currentTimeMillis() + 60_000

        assertTrue(gateway.execute("a-fresh", "新动作", actionExpiresAtMs = future) {
            calls.incrementAndGet(); true
        })
        assertEquals(1, calls.get())
    }

    @Test
    fun crashRecoveryConvergesExecutingToUnknown() {
        val ledger = FakeLedger()
        val gateway = ActionExecutionGateway(ledger)
        gateway.execute("a4", "挂起") { null }
        ledger.claim("a5", "手动挂起")
        // a4 已被标 UNKNOWN;a5 仍 EXECUTING
        ledger.recoverUnknowns()
        assertEquals(ActionLedgerStore.STATE_RESULT_UNKNOWN, ledger.stateOf("a5"))
    }
}
