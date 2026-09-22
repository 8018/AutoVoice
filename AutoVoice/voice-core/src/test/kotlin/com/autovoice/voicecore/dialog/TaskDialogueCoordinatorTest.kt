package com.autovoice.voicecore.dialog

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TaskDialogueCoordinatorTest {
    private val expectation = InputExpectation("navigation_candidate", 30_000)

    @Test fun `replacement and stale events cannot change the active task`() {
        val ended = mutableListOf<TaskEnd>()
        val coordinator = TaskDialogueCoordinator<String>(newTaskId = { "task-${ended.size + 1}" }) { _, end ->
            end?.let(ended::add)
        }
        val first = coordinator.offer("i", "navigation", "turn-1", expectation, "old")
        val second = coordinator.offer("i", "navigation", "turn-2", expectation, "new")

        assertEquals(TaskEndReason.REPLACED, ended.single().reason)
        assertNull(coordinator.claim(first.identity))
        assertFalse(coordinator.finishWaiting(first.identity, TaskEndReason.EXPIRED))
        assertEquals(second.identity, coordinator.active!!.identity)
    }

    @Test fun `click and voice can claim a waiting task only once`() {
        val coordinator = TaskDialogueCoordinator<String>(newTaskId = { "task" })
        val task = coordinator.offer("i", "navigation", "turn", expectation, "candidate")

        assertEquals(TaskStatus.EXECUTING, coordinator.claim(task.identity)!!.status)
        assertNull(coordinator.claim(task.identity))
        assertTrue(coordinator.finish(task.identity, TaskEndReason.COMPLETED))
        assertNull(coordinator.active)
    }
}
