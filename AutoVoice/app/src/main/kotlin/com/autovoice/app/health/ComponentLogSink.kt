package com.autovoice.app.health

import android.util.Log
import com.autovoice.voicecore.health.*
import com.google.gson.Gson
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicLong

internal class ComponentLogSink(private val write: (String) -> Unit = { Log.i("ComponentHealth", it) }, capacity: Int = 128) : AutoCloseable {
    private data class Record(val event: ComponentStatusEvent, val session: String, val eventId: String)
    private val queue = ArrayBlockingQueue<Record>(capacity)
    val dropped = AtomicLong()
    @Volatile private var running = true
    private val worker = Thread({
        while (running) {
            try { val r = queue.take();write(eventJson(r.event, r.session, r.eventId)) }
            catch (_: InterruptedException) { if (!running) break }
        }
    }, "component-health-log").apply { isDaemon = true;start() }
    fun offer(event: ComponentStatusEvent, session: String): Boolean = queue.offer(Record(event, session, UUID.randomUUID().toString())).also { if (!it) dropped.incrementAndGet() }
    override fun close() { running = false;worker.interrupt() }
    companion object {
        private val gson = Gson()
        private val ids = setOf("config", "capture", "iflytek.runtime", "wake", "frontend", "vad", "vad.aux", "render_reference", "aec", "local.asr", "local.nlu", "cloud", "playback")
        private fun id(v: String) = v.takeIf { it in ids } ?: "other"
        private fun provider(v: String) = v.takeIf { it in setOf("application", "aikit", "iflytek.ivw", "iflytek.offline", "iflytek.fake-cmd", "rnnoise", "none", "silero", "webrtc.apm", "webrtc.vad", "firered.ctc.int8", "rule.nlu", "rule.text", "gateway", "android", "system.aec", "pcm.render") } ?: "other"
        private fun stage(v: String) = v.takeIf { it in setOf("not_started", "config_validate", "native_load", "resource_validate", "model_create", "authorize", "initialize", "create", "capture_open", "waiting_permission", "connect", "idle", "disabled", "process", "effects", "parameters", "resource_load", "reuse_existing", "model_ready") } ?: "other"
        private fun error(e: ComponentError?) = e?.sanitized()?.let { mapOf("code" to it.code.name, "message" to it.message, "vendorCode" to it.vendorCode, "retryable" to it.retryable, "suggestion" to it.suggestion) }
        private fun versions(values: Map<String,String>) = values.filter { (key,value) -> when (key) {
            "modelId" -> value == "sherpa-onnx-fire-red-asr2-ctc-zh_en-int8-2026-02-25"
            "modelSha256", "tokensSha256" -> value.matches(Regex("[0-9a-f]{64}"))
            "runtime", "model", "sourceRevision", "ort", "ndk", "sdk" -> value.matches(Regex("[0-9]+\\.[0-9]+(?:\\.[0-9]+)?(?:[-._][A-Za-z0-9]+)*")) || value.matches(Regex("[0-9a-f]{40}"))
            else -> false
        } }
        private fun descriptor(d: ComponentDescriptor) = mapOf("componentId" to id(d.componentId), "provider" to provider(d.provider), "channel" to d.channel.takeIf { it in setOf("iflytek", "open", "shared") }, "scope" to d.scope.name)
        fun diagnosticsJson(snapshot: ComponentHealthSnapshot): String = gson.toJson(mapOf("schemaVersion" to 1, "appSessionId" to snapshot.appSessionId,
            "generation" to snapshot.generation, "channel" to snapshot.channel.takeIf { it in setOf("iflytek", "open") },
            "mergedHealthEvents" to snapshot.mergedHealthEvents, "droppedLogEvents" to snapshot.droppedLogEvents,
            "components" to snapshot.components.values.map { s -> descriptor(s.descriptor) + mapOf("generation" to s.generation, "attemptId" to s.attemptId,
                "sequence" to s.sequence, "lifecycle" to s.lifecycle.name, "health" to s.health.name, "stage" to stage(s.stage),
                "startedAt" to s.startedAt, "updatedAt" to s.updatedAt, "durationMs" to s.durationMs, "error" to error(s.error),
                "blockedBy" to s.blockedBy.map(::id), "rootCauseId" to s.rootCauseId?.let(::id), "versions" to versions(s.versions)) }))
        fun eventJson(e: ComponentStatusEvent, session: String, eventId: String): String {
            val name = if (e.update.stage == "reuse_existing") "component_state_rebound" else when (e.update.lifecycle) {
                Lifecycle.INITIALIZING -> "component_init_started"
                Lifecycle.READY -> "component_init_succeeded"
                Lifecycle.FAILED -> "component_init_failed"
                Lifecycle.BLOCKED -> "component_blocked"
                Lifecycle.RELEASED -> "component_released"
                else -> "component_health_changed"
            }
            return gson.toJson(descriptor(e.descriptor) + mapOf("schemaVersion" to 1, "event" to name, "eventId" to eventId, "appSessionId" to session,
                "generation" to e.token.generation, "attemptId" to e.token.attemptId, "sequence" to e.sequence, "lifecycle" to e.update.lifecycle?.name,
                "health" to e.update.health?.name, "stage" to stage(e.update.stage ?: "other"), "durationMs" to e.durationMs,
                "error" to error(e.update.error), "rootCauseId" to e.rootCauseId?.let(::id), "updatedAt" to e.updatedAt,
                "startedAt" to e.startedAt, "blockedBy" to e.update.blockedBy.map(::id), "versions" to versions(e.update.versions)))
        }
    }
}
internal object ProcessComponentHealth {
    private val log = ComponentLogSink()
    val registry = ComponentStatusRegistry(log::offer)
}
