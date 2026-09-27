package com.autovoice.voicecore.dialog

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class TaskListeningDirective(val revision: Long, val listenWindowMs: Long)

/** DM listening policy. Capture/VAD may run without admitting a turn and cannot stop this clock. */
class DialogueListeningController(
    private val scope: CoroutineScope,
    private val nowMs: () -> Long,
    private val current: () -> DialogueSnapshot,
    private val onExpired: (DialogueSnapshot, Long?) -> Unit,
    private val defaultWindowMs: Long = 10_000,
    private val maxInteractionMs: Long = 60_000,
) : AutoCloseable {
    private var interactionId: String? = null
    private var startedAt = 0L
    private var generation = 0L
    private var key: Pair<DialogueSnapshot, TaskListeningDirective?>? = null
    private var timer: Job? = null

    @Synchronized fun update(snapshot: DialogueSnapshot, directive: TaskListeningDirective?) {
        if (snapshot.interactionId != interactionId) {
            interactionId = snapshot.interactionId
            startedAt = nowMs()
        }
        val next = snapshot to directive
        if (next == key) return
        close()
        key = next
        if (snapshot.interactionId == null) return
        if (snapshot.state != DialogueState.AWAKE && snapshot.state != DialogueState.FOLLOW_UP_LISTENING) return
        val token = generation
        val remaining = (maxInteractionMs - (nowMs() - startedAt)).coerceAtLeast(0)
        val wait = minOf(directive?.listenWindowMs ?: defaultWindowMs, remaining)
        timer = scope.launch {
            delay(wait)
            val valid = synchronized(this@DialogueListeningController) {
                token == generation && current() == snapshot
            }
            if (valid) onExpired(snapshot, directive?.revision)
        }
    }

    @Synchronized override fun close() {
        generation++
        timer?.cancel()
        timer = null
        key = null
    }
}
