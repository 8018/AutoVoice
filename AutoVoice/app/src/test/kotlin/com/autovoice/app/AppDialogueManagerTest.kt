package com.autovoice.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppDialogueManagerTest {
    @Test
    fun `chat domain changes once and rejected capture change does not publish mode`() {
        val applied = mutableListOf<Boolean>()
        var allow = false
        val manager = AppDialogueManager({ "interaction" }, {}, { true }) { enabled ->
            applied += enabled
            allow
        }
        assertFalse(manager.setChatMode(true))
        assertFalse(manager.isChatMode)
        allow = true
        assertTrue(manager.setChatMode(true))
        assertTrue(manager.isChatMode)
        assertFalse(manager.setChatMode(true))
        assertTrue(manager.setChatMode(false))
        assertFalse(manager.isChatMode)
        assertEquals(listOf(true, true, false), applied)
    }

    @Test
    fun `publishes and revokes the exact navigation context on task close`() {
        val contexts = mutableListOf<NavigationTaskContextRef>()
        val manager = AppDialogueManager({ "interaction" }, {}, { true })
        manager.bindNavigationContextSender(contexts::add)
        manager.navigationSession.offer(
            listOf(NavigationExecutor.NavigationCandidate("机场", 30.0, 104.0, candidateId = "a")),
            selectionId = "selection",
            originTurnId = "turn",
        )
        val revision = manager.listeningDirective()!!.revision
        assertTrue(manager.matchesTaskRevision(revision))
        assertEquals(1, contexts.size)
        manager.dismissSelection()
        assertEquals(2, contexts.size)
        assertEquals(contexts.first().copy(active = false), contexts.last())
        assertFalse(manager.matchesTaskRevision(revision))
    }
}
