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
 * interaction state machine. State mutations and their effects share one FIFO boundary, including
 * timer callbacks and reentrant observers; effects never hold the internal state lock.
 */
class TaskDialogueCoordinator<T : Any>(
    private val newTaskId: () -> String = { UUID.randomUUID().toString() },
    private val onChanged: (DialogueTask<T>?, TaskEnd?) -> Unit = { _, _ -> },
) {
    private val lock = Any()
    private val effects = ArrayDeque<() -> Unit>()
    private var draining = false
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
    ): DialogueTask<T> = mutate {
        require(interactionId.isNotBlank() && domain.isNotBlank() && originTurnId.isNotBlank())
        val (replaced, task) = run {
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
        replaced?.let { end -> effects.add { onChanged(null, end) } }
        effects.add { onChanged(task, null) }
        task
    }

    /** First valid claimant wins; later voice/click results see EXECUTING and are rejected. */
    fun claim(
        identity: TaskIdentity,
        accepts: (T) -> Boolean = { true },
        update: (T) -> T = { it },
    ): DialogueTask<T>? = mutate {
        val next = run {
            val current = active?.takeIf {
                it.identity == identity && it.status == TaskStatus.WAITING_INPUT
            } ?: return@mutate null
            if (!accepts(current.context)) return@mutate null
            current.copy(
                status = TaskStatus.EXECUTING,
                expectation = null,
                context = update(current.context),
            ).also { active = it }
        }
        effects.add { onChanged(next, null) }
        next
    }

    fun finish(identity: TaskIdentity, reason: TaskEndReason): Boolean = mutate {
        val end = run {
            val current = active?.takeIf { it.identity == identity } ?: return@mutate false
            active = null
            TaskEnd(current.identity, reason)
        }
        effects.add { onChanged(null, end) }
        true
    }

    fun finishWaiting(identity: TaskIdentity, reason: TaskEndReason): Boolean = mutate {
        val end = run {
            val current = active?.takeIf {
                it.identity == identity && it.status == TaskStatus.WAITING_INPUT
            } ?: return@mutate false
            active = null
            TaskEnd(current.identity, reason)
        }
        effects.add { onChanged(null, end) }
        true
    }

    fun abortActive(reason: TaskEndReason = TaskEndReason.ABORTED): Boolean = mutate {
        val end = run {
            val current = active ?: return@mutate false
            active = null
            TaskEnd(current.identity, reason)
        }
        effects.add { onChanged(null, end) }
        true
    }

    fun waiting(domain: String? = null): DialogueTask<T>? = synchronized(lock) {
        active?.takeIf {
            it.status == TaskStatus.WAITING_INPUT && (domain == null || it.domain == domain)
        }
    }

    private fun <R> mutate(block: () -> R): R {
        var startDrain = false
        val result = synchronized(lock) {
            block().also {
                if (!draining && effects.isNotEmpty()) {
                    draining = true
                    startDrain = true
                }
            }
        }
        if (startDrain) {
            var failure: Throwable? = null
            while (true) {
                val effect = synchronized(lock) {
                    if (effects.isEmpty()) { draining = false; null } else effects.removeFirst()
                } ?: break
                // Finish draining even when one observer fails; later state projections must not
                // remain stranded. Surface the first failure to the initiating caller afterwards.
                try { effect() } catch (error: Throwable) { if (failure == null) failure = error }
            }
            failure?.let { throw it }
        }
        return result
    }
}
