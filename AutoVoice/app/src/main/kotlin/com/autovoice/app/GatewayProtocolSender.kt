package com.autovoice.app

import com.autovoice.gatewayclient.GatewayClient

/**
 * AutoVoice protocol commands over a transport-only [GatewayClient].
 *
 * Audio lifecycle, chat, TTS and turn control are business protocol concerns. Keeping them here
 * prevents the WebSocket channel from acquiring speech-domain state or APIs.
 */
internal class GatewayProtocolSender(
    private val channel: GatewayClient,
    private val sampleRate: Int = 16_000,
    private val channels: Int = 1,
    private val encoding: String = "pcm_s16le",
) {
    private var segmentBytes = 0L

    fun navigationSelection(sessionId: String, context: NavigationTaskContextRef) = channel.send(
        "navigation_selection_start",
        mapOf(
            "sessionId" to sessionId,
            "selectionId" to context.selectionId,
            "taskId" to context.taskId,
            "taskRevision" to context.revision,
            "interactionId" to context.interactionId,
            "active" to context.active,
        ),
    )

    fun audioStart(
        sessionId: String,
        segmentId: String? = null,
        utteranceId: String? = null,
        latitude: Double? = null,
        longitude: Double? = null,
        attempt: Int = 0,
        navigationContext: NavigationTaskContextRef? = null,
    ) {
        val payload = linkedMapOf<String, Any?>(
            "sessionId" to sessionId,
            "sampleRate" to sampleRate,
            "channels" to channels,
            "encoding" to encoding,
            "attempt" to attempt,
            // Presence tells a new server not to reuse server-side dialogue state after reconnect.
            // Old clients omit this field and keep the legacy selectionId-only behaviour.
            "taskDialogVersion" to 1,
        )
        segmentId?.let { payload["segmentId"] = it }
        utteranceId?.let { payload["utteranceId"] = it }
        if (latitude != null && longitude != null) {
            payload["latitude"] = latitude
            payload["longitude"] = longitude
        }
        navigationContext?.let {
            payload["navigationSelectionId"] = it.selectionId
            payload["navigationTaskId"] = it.taskId
            payload["navigationTaskRevision"] = it.revision
            payload["navigationInteractionId"] = it.interactionId
        }
        channel.send("audio_start", payload)
        segmentBytes = 0
    }

    fun audioChunk(pcm: ByteArray) {
        channel.send(pcm)
        segmentBytes += pcm.size
    }

    fun audioEnd(sessionId: String) = channel.send(
        "audio_end",
        mapOf("sessionId" to sessionId, "durationMs" to segmentBytes * 1000 / (2L * sampleRate)),
    )

    fun chatStart(sessionId: String) = channel.send("chat_start", mapOf("sessionId" to sessionId))
    fun chatAudio(pcm: ByteArray) = channel.send(pcm)
    fun chatFinish(sessionId: String) = channel.send("chat_finish", mapOf("sessionId" to sessionId))

    fun cancelTurn(segmentId: String, reason: String = "device_local_won") = channel.send(
        "cancel_turn",
        mapOf("segmentId" to segmentId, "reason" to reason),
    )

    fun commitTurn(segmentId: String, utteranceId: String) {
        require(segmentId.isNotBlank() && utteranceId.isNotBlank())
        channel.send("turn_commit", mapOf("segmentId" to segmentId, "utteranceId" to utteranceId))
    }

    fun tts(text: String, segmentId: String? = null, utteranceId: String? = null) {
        val payload = linkedMapOf<String, Any?>("text" to text)
        segmentId?.let { payload["segmentId"] = it }
        utteranceId?.let { payload["utteranceId"] = it }
        channel.send("tts_request", payload)
    }
}

internal data class NavigationTaskContextRef(
    val taskId: String,
    val revision: Long,
    val interactionId: String,
    val selectionId: String,
    val active: Boolean = true,
) {
    init {
        require(taskId.isNotBlank() && revision > 0 && interactionId.isNotBlank())
        if (active) require(selectionId.isNotBlank())
    }
}
