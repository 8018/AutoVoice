package com.autovoice.voicecore.health

import java.util.UUID

class ComponentReporter(val descriptor: ComponentDescriptor, val generation: Long, private val sink: ComponentStatusSink,
    private val monotonicMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val wallClockMs: () -> Long = System::currentTimeMillis) : AutoCloseable {
    val identity: String = UUID.randomUUID().toString()
    private var sequence = 0L
    private var token: ComponentToken? = null
    private var state = Lifecycle.NOT_STARTED
    private var startedMono = 0L
    private var startedWall: Long? = null
    private var completedDuration: Long? = null
    private var stage = "not_started"

    @Synchronized fun start(stage: String): ComponentToken {
        check(state != Lifecycle.RELEASED) { "component reporter released" }
        if (state == Lifecycle.INITIALIZING) return requireNotNull(token)
        token = ComponentToken(descriptor.componentId, generation, UUID.randomUUID().toString())
        startedMono = monotonicMs();startedWall = wallClockMs();completedDuration = null
        state = Lifecycle.INITIALIZING
        this.stage = stage
        emit(requireNotNull(token), ComponentUpdate(Lifecycle.INITIALIZING, Health.UNKNOWN, stage))
        return requireNotNull(token)
    }
    @Synchronized fun report(token: ComponentToken, update: ComponentUpdate): Boolean {
        if (token != this.token || state == Lifecycle.RELEASED) return false
        if (state in setOf(Lifecycle.FAILED, Lifecycle.DISABLED) && update.lifecycle != Lifecycle.RELEASED) return false
        if (update.lifecycle == Lifecycle.READY && state != Lifecycle.INITIALIZING) return false
        if (update.lifecycle != null) state = update.lifecycle
        if (state in setOf(Lifecycle.READY, Lifecycle.FAILED, Lifecycle.DISABLED) && completedDuration == null) completedDuration = (monotonicMs() - startedMono).coerceAtLeast(0)
        if (update.stage != null) stage = update.stage
        emit(token, update.copy(stage = stage, error = update.error?.sanitized(), blockedBy = update.blockedBy.toSet(), versions = update.versions.toMap()))
        return true
    }
    private fun emit(token: ComponentToken, update: ComponentUpdate) {
        sink.report(ComponentStatusEvent(descriptor, token, ++sequence, update, startedWall, wallClockMs(),
            if (startedWall == null) null else completedDuration ?: (monotonicMs() - startedMono).coerceAtLeast(0), identity))
    }
    @Synchronized fun close(token: ComponentToken): Boolean {
        if (this.token != token || state == Lifecycle.RELEASED) return false
        state = Lifecycle.RELEASED
        emit(token, ComponentUpdate(Lifecycle.RELEASED, Health.UNAVAILABLE, stage))
        return true
    }
    @Synchronized override fun close() {
        if (state == Lifecycle.RELEASED) return
        state = Lifecycle.RELEASED
        emit(token ?: ComponentToken(descriptor.componentId, generation, "unstarted"), ComponentUpdate(Lifecycle.RELEASED, Health.UNAVAILABLE))
    }
    companion object { fun none() = ComponentReporter(ComponentDescriptor("unreported", "none", "shared"), 0, ComponentStatusSink.NONE) }
}
