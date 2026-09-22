package com.autovoice.voicecore.dialog

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TaskDialogueCoordinatorTest {
    @Test fun `expired effect cannot overtake replacement projection`() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val projection = java.util.concurrent.atomic.AtomicReference<String?>()
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val coordinator = TaskDialogueCoordinator<String> { task, end ->
            if (end?.reason == TaskEndReason.EXPIRED) {
                entered.countDown()
                check(release.await(3, java.util.concurrent.TimeUnit.SECONDS))
            }
            projection.set(task?.context)
        }
        try {
            val old = coordinator.offer("i", "navigation", "old", expectation, "old")
            val expired = executor.submit<Boolean> { coordinator.finishWaiting(old.identity, TaskEndReason.EXPIRED) }
            assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS))
            coordinator.offer("i", "navigation", "new", expectation, "new")
            release.countDown()
            assertTrue(expired.get(3, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals("new", coordinator.active!!.context)
            assertEquals("new", projection.get())
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun `reentrant observer and failed observer do not strand queued projections`() {
        val observed = mutableListOf<String?>()
        lateinit var coordinator: TaskDialogueCoordinator<String>
        coordinator = TaskDialogueCoordinator { task, _ ->
            observed += task?.context
            if (task?.context == "old") {
                coordinator.offer("i", "navigation", "new", expectation, "new")
                error("observer failure")
            }
        }
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) {
            coordinator.offer("i", "navigation", "old", expectation, "old")
        }
        assertEquals(listOf("old", null, "new"), observed)
        assertEquals("new", coordinator.active!!.context)
        assertTrue(coordinator.abortActive())
        assertNull(observed.last())
    }

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
