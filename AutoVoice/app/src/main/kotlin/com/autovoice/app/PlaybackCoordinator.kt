package com.autovoice.app

import com.autovoice.voicecore.AudioReply
import com.autovoice.voicecore.StreamingAudioReply
import java.util.UUID

data class PlaybackIdentity(val turnId: String, val playbackId: String = UUID.randomUUID().toString()) {
    fun payload(): Map<String, Any?> = mapOf("turnId" to turnId, "playbackId" to playbackId)
}

enum class PlaybackStage(val wire: String) {
    STARTED("start"), COMPLETED("completed"), FAILED("failed"), INTERRUPTED("interrupted")
}

/** Owns output identities, not dialogue turns or arbitration. Drivers must echo their fixed identity. */
class PlaybackCoordinator(
    private val player: AudioPlayer,
    private val event: (PlaybackIdentity, PlaybackStage, String, Map<String, Any?>) -> Unit,
) {
    private var current: PlaybackIdentity? = null
    private var started = false
    private var streamJob: kotlinx.coroutines.Job? = null
    private val driverLock = Any()
    private val eventQueue = ArrayDeque<() -> Unit>()
    private var drainingEvents = false

    fun prepare(turnId: String): PlaybackIdentity {
        val next = PlaybackIdentity(turnId)
        var shouldDrain = false
        synchronized(driverLock) {
            synchronized(this) {
                val old = current
                val wasStarted = started
                val oldJob = streamJob
                current = next
                started = false
                streamJob = null
                if (old != null && wasStarted) {
                    shouldDrain = enqueueEvent {
                        event(old, PlaybackStage.INTERRUPTED, "warn", old.payload())
                    }
                }
                Triple(old, wasStarted, oldJob)
            }.also { stopped ->
                stopped.third?.cancel()
                if (stopped.first != null) player.stop()
            }
        }
        if (shouldDrain) drainEvents()
        return next
    }

    @Synchronized
    fun isActive(identity: PlaybackIdentity): Boolean = current == identity

    fun play(identity: PlaybackIdentity, reply: AudioReply) {
        try {
            synchronized(driverLock) {
                if (!synchronized(this) { current == identity }) return
                player.play(reply, identity)
            }
        }
        catch (error: Exception) { failed(identity, error) }
    }

    suspend fun playStream(identity: PlaybackIdentity, reply: StreamingAudioReply) {
        val job = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
        synchronized(this) {
            if (current != identity) return
            streamJob = job
        }
        try { player.playStream(reply, identity) }
        catch (error: kotlinx.coroutines.CancellationException) {
            accept("interrupted", "warn", identity.payload())
            throw error
        } catch (error: Exception) { failed(identity, error) }
        finally { synchronized(this) { if (streamJob === job) streamJob = null } }
    }

    fun failed(identity: PlaybackIdentity, error: Throwable) =
        accept("failed", "error", identity.payload() + ("error" to error.toString()))

    fun accept(stage: String, level: String, payload: Map<String, Any?>) {
        var shouldDrain = false
        val accepted = synchronized(this) {
            val identity = current ?: return@synchronized false
            if (payload["playbackId"] != identity.playbackId || payload["turnId"] != identity.turnId) {
                return@synchronized false
            }
            val kind = PlaybackStage.entries.firstOrNull { it.wire == stage } ?: return@synchronized false
            if (kind == PlaybackStage.STARTED) {
                if (started) return@synchronized false
                started = true
            } else {
                current = null
            }
            shouldDrain = enqueueEvent { event(identity, kind, level, payload) }
            true
        }
        if (!accepted) return
        if (shouldDrain) drainEvents()
    }

    fun stop() {
        var shouldDrain = false
        synchronized(driverLock) {
            synchronized(this) {
                val old = current
                val wasStarted = started
                val oldJob = streamJob
                current = null // Invalidate before synchronous driver callbacks.
                started = false
                streamJob = null
                if (old != null && wasStarted) {
                    shouldDrain = enqueueEvent {
                        event(old, PlaybackStage.INTERRUPTED, "warn", old.payload())
                    }
                }
                Triple(old, wasStarted, oldJob)
            }.also { previous ->
                previous.third?.cancel()
                player.stop()
            }
        }
        if (shouldDrain) drainEvents()
    }

    /** Reserve one FIFO drainer while holding playback state; callbacks run without state/driver locks. */
    private fun enqueueEvent(effect: () -> Unit): Boolean = synchronized(eventQueue) {
        eventQueue.add(effect)
        if (drainingEvents) false else true.also { drainingEvents = true }
    }

    private fun drainEvents() {
        while (true) {
            val effect = synchronized(eventQueue) {
                if (eventQueue.isEmpty()) {
                    drainingEvents = false
                    null
                } else {
                    eventQueue.removeFirst()
                }
            } ?: return
            try {
                effect()
            } catch (error: Throwable) {
                synchronized(eventQueue) { drainingEvents = false }
                throw error
            }
        }
    }
}
