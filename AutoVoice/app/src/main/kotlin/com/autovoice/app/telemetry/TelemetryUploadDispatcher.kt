package com.autovoice.app.telemetry

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Best-effort, bounded uploads. JSON order is preserved independently of diagnostic audio. */
internal class TelemetryUploadDispatcher(
    private val scope: CoroutineScope,
    private val transport: TelemetryTransport,
    jsonCapacity: Int = 64,
    audioCapacity: Int = 2,
    private val closeGraceMs: Long = 5_000L,
) {
    private sealed interface JsonUpload {
        data class Round(val body: TelemetryRoundPayload) : JsonUpload
        data class Events(val body: TelemetryEventBatch) : JsonUpload
    }

    private data class AudioUpload(val utteranceId: String, val deviceId: String?, val pcm: ByteArray)

    private val jsonQueue = Channel<JsonUpload>(jsonCapacity)
    private val audioQueue = Channel<AudioUpload>(audioCapacity)
    private val droppedJson = AtomicInteger()
    private val droppedAudio = AtomicInteger()
    private val closed = AtomicBoolean()

    private val jsonWorker = scope.launch(Dispatchers.IO) {
        for (upload in jsonQueue) {
            send(if (upload is JsonUpload.Round) "round" else "events") {
                when (upload) {
                    is JsonUpload.Round -> transport.postRound(upload.body)
                    is JsonUpload.Events -> transport.postEvents(upload.body)
                }
            }
        }
    }
    private val audioWorker = scope.launch(Dispatchers.IO) {
        for (upload in audioQueue) {
            send("audio") { transport.postAudio(upload.utteranceId, upload.deviceId, upload.pcm) }
        }
    }

    fun postRound(round: TelemetryRoundPayload): Boolean = enqueueJson(JsonUpload.Round(round))

    fun postEvents(batch: TelemetryEventBatch): Boolean = enqueueJson(JsonUpload.Events(batch))

    fun postAudio(utteranceId: String, deviceId: String?, pcm: ByteArray): Boolean {
        if (pcm.size > MAX_AUDIO_UPLOAD_BYTES) {
            noteDrop("audio", droppedAudio)
            return false
        }
        val accepted = audioQueue.trySend(AudioUpload(utteranceId, deviceId, pcm)).isSuccess
        if (!accepted) noteDrop("audio", droppedAudio)
        return accepted
    }

    /** Drain briefly, then cancel pending calls so an engine close cannot retain a long upload backlog. */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        jsonQueue.close()
        audioQueue.close()
        scope.launch(Dispatchers.IO) {
            delay(closeGraceMs)
            jsonWorker.cancel()
            audioWorker.cancel()
        }
    }

    private fun enqueueJson(upload: JsonUpload): Boolean {
        val accepted = jsonQueue.trySend(upload).isSuccess
        if (!accepted) noteDrop("JSON", droppedJson)
        return accepted
    }

    private suspend fun send(kind: String, request: suspend () -> Int) {
        try {
            val code = request()
            if (code !in 200..299) Log.w(TAG, "telemetry $kind upload failed: http $code")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w(TAG, "telemetry $kind upload failed", failure)
        }
    }

    private fun noteDrop(kind: String, counter: AtomicInteger) {
        val count = counter.incrementAndGet()
        if (count == 1 || count % 64 == 0) Log.w(TAG, "telemetry $kind queue full or closed; dropped=$count")
    }

    private companion object {
        const val TAG = "TelemetryClient"
        const val MAX_AUDIO_UPLOAD_BYTES = 1_920_000
    }
}
