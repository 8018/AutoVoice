package com.autovoice.app.navigation

import java.util.UUID

internal enum class NavigationOperation {
    PLAN, START, STOP, SEARCH, CANCEL_SEARCH, MAP_VISIBILITY, MAP_CONTROL, AUDIO_MUTE, AUDIO_VOLUME,
}

internal enum class NavigationOutcome {
    SUCCEEDED, ACCEPTED, UNKNOWN, UNSUPPORTED, MISSING_LOCATION, FAILED, TIMEOUT, CANCELLED, NO_RESULTS, REJECTED,
}

internal data class NavigationOperationSample(
    val id: String,
    val operation: NavigationOperation,
    val outcome: NavigationOutcome,
    val durationMs: Long,
)

/** Pending IDs are local. Removing an ID before emitting makes late and duplicate callbacks harmless. */
internal class NavigationOperationTracker(
    private val elapsedMs: () -> Long,
    private val captureRecorder: () -> ((NavigationOperationSample) -> Unit)?,
) {
    private data class Pending(
        val operation: NavigationOperation,
        val startMs: Long,
        val recorder: ((NavigationOperationSample) -> Unit)?,
    )
    private val pending = mutableMapOf<String, Pending>()

    @Synchronized
    fun begin(operation: NavigationOperation): String {
        val id = "navigation-${UUID.randomUUID()}"
        pending[id] = Pending(operation, elapsedMs(), captureRecorder())
        return id
    }

    @Synchronized
    fun finish(id: String?, outcome: NavigationOutcome) {
        val attempt = pending.remove(id) ?: return
        val durationMs = (elapsedMs() - attempt.startMs).coerceAtLeast(0)
        attempt.recorder?.invoke(NavigationOperationSample(requireNotNull(id), attempt.operation, outcome, durationMs))
    }

    @Synchronized
    fun finishPending(outcome: NavigationOutcome) {
        pending.keys.toList().forEach { finish(it, outcome) }
    }
}
