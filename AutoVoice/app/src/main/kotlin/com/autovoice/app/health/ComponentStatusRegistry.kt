package com.autovoice.app.health

import com.autovoice.voicecore.health.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Collections
import java.util.UUID

/** Pure process-lifetime state. No Context, ViewModel, native handle or exception is retained. */
internal class ComponentStatusRegistry(private val eventLog: (ComponentStatusEvent, String) -> Boolean = { _, _ -> true }) : ComponentStatusSink {
    private data class Entry(val reporter: ComponentReporter, val dependencies: Set<String>, var base: ComponentStatus)
    private val entries = linkedMapOf<String, Entry>()
    private val history = ArrayDeque<ComponentStatusEvent>()
    private val session = UUID.randomUUID().toString()
    private var generation = 0L
    private var channel = ""
    private var merged = 0L
    private var dropped = 0L
    private val mutable = MutableStateFlow(ComponentHealthSnapshot(appSessionId = session))
    val snapshot: StateFlow<ComponentHealthSnapshot> = mutable.asStateFlow()

    @Synchronized fun beginGeneration(channel: String): Long {
        // Never acquire reporter locks here: a reporting callback takes reporter -> registry.
        for (entry in entries.values.filter { it.base.descriptor.scope == ComponentScope.ASSEMBLY }) {
            val s = entry.base
            record(ComponentStatusEvent(s.descriptor, ComponentToken(s.componentId, s.generation, s.attemptId), s.sequence + 1,
                ComponentUpdate(Lifecycle.RELEASED, Health.UNAVAILABLE), s.startedAt, System.currentTimeMillis(), s.durationMs, entry.reporter.identity))
        }
        entries.entries.removeAll { it.value.base.descriptor.scope == ComponentScope.ASSEMBLY }
        this.channel = channel;generation++;publish()
        return generation
    }
    @Synchronized fun reporter(descriptor: ComponentDescriptor, generation: Long, dependencies: Set<String> = emptySet()): ComponentReporter {
        require(descriptor.componentId !in dependencies)
        val actualGeneration = if (descriptor.scope == ComponentScope.PROCESS) 0 else generation
        require(descriptor.scope == ComponentScope.PROCESS || generation == this.generation) { "stale assembly generation" }
        val old = entries[descriptor.componentId]
        if (old != null && old.base.descriptor == descriptor) return old.reporter
        require(old == null || old.base.lifecycle == Lifecycle.NOT_STARTED) { "component provider already initialized" }
        val reporter = ComponentReporter(descriptor, actualGeneration, this)
        entries[descriptor.componentId] = Entry(reporter, dependencies.toSet(), ComponentStatus(descriptor, actualGeneration))
        publish();return reporter
    }
    fun releaseGeneration(generation: Long) {
        val reporters = synchronized(this) { entries.values.filter { it.base.descriptor.scope == ComponentScope.ASSEMBLY && it.base.generation == generation }.map { it.reporter } }
        reporters.forEach { it.close() }
    }
    @Synchronized override fun report(event: ComponentStatusEvent) {
        val entry = entries[event.descriptor.componentId] ?: return
        if (event.reporterId != entry.reporter.identity || event.token.generation != entry.base.generation || event.sequence <= entry.base.sequence) return
        val old = entry.base
        if (old.lifecycle == Lifecycle.RELEASED) return
        if (event.update.lifecycle != Lifecycle.INITIALIZING && old.attemptId.isNotBlank() && event.token.attemptId != old.attemptId) return
        if (old.lifecycle == Lifecycle.FAILED && event.token.attemptId == old.attemptId && event.update.lifecycle == Lifecycle.READY) return
        val u = event.update.copy(error = event.update.error?.sanitized())
        // BLOCKED is an effective dependency view, not proof that own initialization ended.
        val dependencyUpdate = u.lifecycle == Lifecycle.BLOCKED
        val next = old.copy(attemptId = event.token.attemptId, sequence = event.sequence,
            lifecycle = if (dependencyUpdate) old.lifecycle else u.lifecycle ?: old.lifecycle,
            health = if (dependencyUpdate) old.health else u.health ?: old.health, stage = u.stage ?: old.stage,
            startedAt = event.startedAt, updatedAt = event.updatedAt, durationMs = event.durationMs,
            error = if (dependencyUpdate) old.error else if (u.lifecycle != null || u.health != null) u.error else old.error,
            blockedBy = immutableSet(u.blockedBy), versions = immutableMap(if (u.versions.isEmpty()) old.versions else u.versions))
        entry.base = next
        if (u.lifecycle == null && u.health != null && old.health == next.health && old.error == next.error) { merged++;publish();return }
        record(event.copy(update = u, rootCauseId = if (u.lifecycle == Lifecycle.FAILED) event.descriptor.componentId else event.rootCauseId));publish()
    }
    private fun record(event: ComponentStatusEvent) {
        history.addLast(event);while (history.size > 128) history.removeFirst()
        if (!eventLog(event, session)) dropped++
    }
    private fun publish() {
        val effective = linkedMapOf<String, ComponentStatus>()
        fun resolve(id: String, visiting: Set<String> = emptySet()): ComponentStatus? {
            effective[id]?.let { return it }
            val entry = entries[id] ?: return null
            var s = entry.base
            if (id !in visiting && s.lifecycle !in setOf(Lifecycle.FAILED, Lifecycle.DISABLED, Lifecycle.RELEASED)) {
                val blockers = (entry.dependencies + s.blockedBy).filter { dep ->
                    val d = resolve(dep, visiting + id)
                    d == null || d.lifecycle != Lifecycle.READY || d.health == Health.UNAVAILABLE
                }.toSet()
                if (blockers.isNotEmpty()) {
                    val cause = blockers.sorted().firstNotNullOfOrNull { dep ->
                        val d = effective[dep]
                        if (d?.lifecycle == Lifecycle.FAILED || d?.health == Health.UNAVAILABLE) d.rootCauseId ?: dep else null
                    } ?: blockers.sorted().first()
                    s = s.copy(lifecycle = Lifecycle.BLOCKED, health = Health.UNAVAILABLE, blockedBy = immutableSet(blockers), rootCauseId = cause, error = null)
                } else s = s.copy(blockedBy = emptySet(), rootCauseId = null)
            }
            if (s.lifecycle == Lifecycle.FAILED) s = s.copy(rootCauseId = id)
            effective[id] = s;return s
        }
        for (id in entries.keys) resolve(id)
        val previous = mutable.value.components
        for ((id, s) in effective) {
            if (s.lifecycle == Lifecycle.BLOCKED && previous[id]?.let { it.lifecycle != Lifecycle.BLOCKED || it.rootCauseId != s.rootCauseId } == true) {
                record(ComponentStatusEvent(s.descriptor, ComponentToken(id, s.generation, s.attemptId), s.sequence,
                    ComponentUpdate.blocked(*s.blockedBy.toTypedArray()), s.startedAt, s.updatedAt, s.durationMs, entries.getValue(id).reporter.identity, s.rootCauseId))
            }
        }
        mutable.value = ComponentHealthSnapshot(session, generation, channel, immutableMap(effective), Collections.unmodifiableList(history.toList()), merged, dropped)
    }
    companion object {
        private fun <K,V> immutableMap(map: Map<K,V>): Map<K,V> = Collections.unmodifiableMap(LinkedHashMap(map))
        private fun <T> immutableSet(set: Set<T>): Set<T> = Collections.unmodifiableSet(LinkedHashSet(set))
    }
}
