package com.autovoice.voicebusiness.dialog

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
    private val onInteractionExpired: (String) -> Unit = {},
) : AutoCloseable {
    private var interactionId: String? = null
    private var startedAt = 0L
    private var generation = 0L
    private var interactionGeneration = 0L
    private var key: Triple<DialogueSnapshot, TaskListeningDirective?, Boolean>? = null
    private var timer: Job? = null
    private var interactionTimer: Job? = null

    @Synchronized fun update(snapshot: DialogueSnapshot, directive: TaskListeningDirective?, listeningReady: Boolean = true) {
        if (snapshot.interactionId != interactionId) {
            interactionTimer?.cancel()
            interactionGeneration++
            interactionId = snapshot.interactionId
            startedAt = nowMs()
            val id = interactionId
            if (id != null) {
                val token = interactionGeneration
                interactionTimer = scope.launch {
                    delay(maxInteractionMs.coerceAtLeast(0))
                    val valid = synchronized(this@DialogueListeningController) {
                        token == interactionGeneration && current().interactionId == id
                    }
                    if (valid) onInteractionExpired(id)
                }
            }
        }
        val next = Triple(snapshot, directive, listeningReady)
        if (next == key) return
        cancelListeningTimer()
        key = next
        if (snapshot.interactionId == null) return
        if (!listeningReady || snapshot.state != DialogueState.LISTENING || snapshot.turnId != null) return
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
        cancelListeningTimer()
        interactionGeneration++
        interactionTimer?.cancel()
        interactionTimer = null
        interactionId = null
    }

    private fun cancelListeningTimer() {
        generation++
        timer?.cancel()
        timer = null
        key = null
    }
}
