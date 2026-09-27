package com.autovoice.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppDialogueManagerTest {
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
