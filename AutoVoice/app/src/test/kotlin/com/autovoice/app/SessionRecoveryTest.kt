package com.autovoice.app

import com.google.gson.Gson
import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** D15b 恢复语义解析:仅 reset 或候选失效时才清待选列表(正常恢复保留)。 */
class SessionRecoveryTest {

    private fun payload(json: String): JsonObject = Gson().fromJson(json, JsonObject::class.java)

    @Test
    fun `new session clears candidates`() {
        val recovery = SessionRecovery.parse(
            payload("""{"sessionId":"s1","sessionState":"new","navigationCandidatesValid":false}"""),
        )
        assertEquals(SessionRecovery.STATE_NEW, recovery.state)
        assertTrue(recovery.shouldClearCandidates)
    }

    @Test
    fun `resumed session with valid candidates keeps the list`() {
        val recovery = SessionRecovery.parse(
            payload("""{"sessionId":"s1","sessionState":"resumed","navigationCandidatesValid":true}"""),
        )
        assertEquals(SessionRecovery.STATE_RESUMED, recovery.state)
        assertFalse(recovery.shouldClearCandidates, "正常恢复且候选有效必须保留列表")
    }

    @Test
    fun `resumed session without valid candidates clears the list`() {
        val recovery = SessionRecovery.parse(
            payload("""{"sessionId":"s1","sessionState":"resumed","navigationCandidatesValid":false}"""),
        )
        assertTrue(recovery.shouldClearCandidates, "候选已失效应清理并提示重新搜索")
    }

    @Test
    fun `reset session clears candidates`() {
        val recovery = SessionRecovery.parse(
            payload("""{"sessionId":"s2","sessionState":"reset","navigationCandidatesValid":true}"""),
        )
        assertEquals(SessionRecovery.STATE_RESET, recovery.state)
        assertTrue(recovery.shouldClearCandidates, "会话重建必须清理(界面不能显示服务端不认识的列表)")
    }

    @Test
    fun `legacy server without fields is treated conservatively`() {
        val recovery = SessionRecovery.parse(payload("""{"sessionId":"s1"}"""))
        assertEquals(SessionRecovery.STATE_NEW, recovery.state)
        assertTrue(recovery.shouldClearCandidates, "缺字段按保守默认")
    }
}
