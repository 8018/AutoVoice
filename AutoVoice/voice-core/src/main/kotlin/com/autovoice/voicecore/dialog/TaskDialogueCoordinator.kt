package com.autovoice.voicecore.dialog

import java.util.UUID

data class TaskIdentity(
    val interactionId: String,
    val taskId: String,
    val revision: Long,
) {
    init {
        require(interactionId.isNotBlank() && taskId.isNotBlank() && revision > 0)
    }
}

data class InputExpectation(
    val kind: String,
    val listenWindowMs: Long,
) {
    init {
        require(kind.isNotBlank() && listenWindowMs > 0)
    }
}

enum class TaskStatus { WAITING_INPUT, EXECUTING }

data class DialogueTask<T : Any>(
    val identity: TaskIdentity,
    val domain: String,
    val status: TaskStatus,
    val expectation: InputExpectation?,
    val originTurnId: String,
    val context: T,
)

enum class TaskEndReason { COMPLETED, FAILED, CANCELLED, EXPIRED, REPLACED, ABORTED }

data class TaskEnd(val identity: TaskIdentity, val reason: TaskEndReason)

/**
 * Owns one active multi-turn task. All mutations compare task identity/revision, so stale timers,
 * UI actions and semantic results cannot change a replacement task.
 *
 * This component does not interpret text, arbitrate semantics, execute business actions or own the
 * interaction state machine. Callers serialize entry events through their existing application
 * event boundary.
 */
class TaskDialogueCoordinator<T : Any>(
    private val newTaskId: () -> String = { UUID.randomUUID().toString() },
    private val onChanged: (DialogueTask<T>?, TaskEnd?) -> Unit = { _, _ -> },
) {
    private val lock = Any()
    private var revision = 0L

    @Volatile
    var active: DialogueTask<T>? = null
        private set

    fun offer(
        interactionId: String,
        domain: String,
        originTurnId: String,
        expectation: InputExpectation,
        context: T,
    ): DialogueTask<T> {
        require(interactionId.isNotBlank() && domain.isNotBlank() && originTurnId.isNotBlank())
        val (replaced, task) = synchronized(lock) {
            val old = active?.let { TaskEnd(it.identity, TaskEndReason.REPLACED) }
            val next = DialogueTask(
                identity = TaskIdentity(interactionId, newTaskId(), ++revision),
                domain = domain,
                status = TaskStatus.WAITING_INPUT,
                expectation = expectation,
                originTurnId = originTurnId,
                context = context,
            )
            active = next
            old to next
        }
        // Effects are deliberately emitted outside the state lock and remain FIFO.
        replaced?.let { onChanged(null, it) }
        onChanged(task, null)
        return task
    }

    /** First valid claimant wins; later voice/click results see EXECUTING and are rejected. */
    fun claim(
        identity: TaskIdentity,
        accepts: (T) -> Boolean = { true },
        update: (T) -> T = { it },
    ): DialogueTask<T>? {
        val next = synchronized(lock) {
            val current = active?.takeIf {
                it.identity == identity && it.status == TaskStatus.WAITING_INPUT
            } ?: return null
            if (!accepts(current.context)) return null
            current.copy(
                status = TaskStatus.EXECUTING,
                expectation = null,
                context = update(current.context),
            ).also { active = it }
        }
        onChanged(next, null)
        return next
    }

    fun finish(identity: TaskIdentity, reason: TaskEndReason): Boolean {
        val end = synchronized(lock) {
            val current = active?.takeIf { it.identity == identity } ?: return false
            active = null
            TaskEnd(current.identity, reason)
        }
        onChanged(null, end)
        return true
    }

    fun finishWaiting(identity: TaskIdentity, reason: TaskEndReason): Boolean {
        val end = synchronized(lock) {
            val current = active?.takeIf {
                it.identity == identity && it.status == TaskStatus.WAITING_INPUT
            } ?: return false
            active = null
            TaskEnd(current.identity, reason)
        }
        onChanged(null, end)
        return true
    }

    fun abortActive(reason: TaskEndReason = TaskEndReason.ABORTED): Boolean {
        val end = synchronized(lock) {
            val current = active ?: return false
            active = null
            TaskEnd(current.identity, reason)
        }
        onChanged(null, end)
        return true
    }

    fun waiting(domain: String? = null): DialogueTask<T>? = synchronized(lock) {
        active?.takeIf {
            it.status == TaskStatus.WAITING_INPUT && (domain == null || it.domain == domain)
        }
    }
}
