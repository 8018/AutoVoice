package com.autovoice.app
import com.autovoice.voicebusiness.navigation.NavigationTaskContextRef

import android.util.Log
import com.autovoice.gatewayclient.GatewayClient
import com.autovoice.gatewayclient.GatewayConnectionState
import com.autovoice.gatewayclient.GatewayException
import com.autovoice.voicecore.Reply
import com.autovoice.voicecore.session.CloudRequestFailedException
import com.autovoice.voicecore.session.CloudRunner
import com.autovoice.voicecore.session.CloudUnavailableException
import com.autovoice.voiceengine.RecognitionGate
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

private const val SPEECH_CHUNK_BYTES = 16_384
/** Roughly four seconds of 16 kHz PCM. Overflow fails the turn rather than growing unbounded. */
private const val LIVE_UPLOAD_QUEUE_CAPACITY = 128

/** Ordinary business speech owns audio_start/chunk/end and per-turn reply correlation, not the socket. */
internal class GatewayBusinessSpeechChannel(
    private val client: GatewayClient,
    private val bridge: GatewayBridge,
    private val protocol: GatewayProtocolSender,
    private val scope: CoroutineScope,
    private val ensureReady: suspend () -> Unit,
    private val sessionId: () -> String,
    private val location: () -> Pair<Double, Double>?,
    private val navigationContext: () -> NavigationTaskContextRef?,
    private val onTransportFailure: (Throwable, Boolean) -> Unit,
    private val clearReplyText: (String) -> Unit,
    private val recognitionGate: RecognitionGate? = null,
) : CloudRunner, StreamingCloudRunner {
    private data class LiveUpload(
        val utteranceId: String,
        val navigationContext: NavigationTaskContextRef?,
        val coordinates: Pair<Double, Double>?,
        val segmentId: String = UUID.randomUUID().toString(),
        val chunks: Channel<ByteArray> = Channel(LIVE_UPLOAD_QUEUE_CAPACITY),
        val reply: CompletableDeferred<Reply> = CompletableDeferred(),
        val permit: RecognitionGate.InputPermit? = null,
        val inputFinalized: AtomicBoolean = AtomicBoolean(false),
        val admitted: AtomicBoolean = AtomicBoolean(false),
        val audioStarted: AtomicBoolean = AtomicBoolean(false),
        val commitSent: AtomicBoolean = AtomicBoolean(false),
    )

    private val liveUpload = AtomicReference<LiveUpload?>(null)
    @Volatile var utteranceIdProvider: () -> String = { "" }

    override fun beginStreamingTurn(utteranceId: String) {
        if (utteranceId.isBlank()) return
        val permit = recognitionGate?.admitInput()
        if (recognitionGate != null && !recognitionGate.accepts(permit)) return
        if (liveUpload.get()?.utteranceId == utteranceId) return
        // Capture position at SpeechStart, before connection setup or coroutine scheduling.
        val upload = LiveUpload(utteranceId, navigationContext(), location(), permit = permit)
        while (true) {
            val previous = liveUpload.get()
            // Multiple VAD segments from the same admitted turn belong to one upload.
            if (previous?.utteranceId == utteranceId) return
            if (liveUpload.compareAndSet(previous, upload)) {
                previous?.chunks?.close(CancellationException("superseded by new streaming turn"))
                previous?.reply?.cancel()
                break
            }
        }
        scope.launch { executeLiveUpload(upload) }
    }

    override fun appendStreamingAudio(pcm: ByteArray) {
        if (pcm.isEmpty()) return
        val upload = liveUpload.get() ?: return
        if (upload.inputFinalized.get() || !acceptsQueuedInput(upload)) return
        val result = upload.chunks.trySend(pcm.copyOf())
        if (result.isFailure && !result.isClosed) {
            val overflow = CloudRequestFailedException("streaming audio queue overflow")
            upload.chunks.close(overflow)
            upload.reply.completeExceptionally(overflow)
        }
    }

    override fun finishStreamingTurn(utteranceId: String) {
        liveUpload.get()?.takeIf { it.utteranceId == utteranceId }?.let {
            // A finalized input is allowed to finish uploading and resolve NLU after the global
            // gate closes. The pending PCM is already part of this admitted request.
            it.inputFinalized.set(true)
            it.chunks.close()
        }
    }

    fun isInputFinalized(utteranceId: String): Boolean =
        liveUpload.get()?.takeIf { it.utteranceId == utteranceId }?.inputFinalized?.get() == true

    override fun cancelStreamingTurn(utteranceId: String) {
        val upload = liveUpload.get()?.takeIf { it.utteranceId == utteranceId } ?: return
        if (!liveUpload.compareAndSet(upload, null)) return
        upload.chunks.cancel(CancellationException("streaming turn discarded"))
        upload.reply.cancel()
        if (sessionId().isNotBlank() && client.connectionState.value == GatewayConnectionState.READY) {
            runCatching { protocol.cancelTurn(upload.segmentId) }
        }
        bridge.cancelStream(upload.segmentId)
    }

    override fun commitStreamingTurn(utteranceId: String) {
        val upload = liveUpload.get()?.takeIf { it.utteranceId == utteranceId } ?: return
        upload.admitted.set(true)
        sendCommitIfReady(upload)
    }

    /** Admission can precede connection and audio_start; send only after both are established. */
    private fun sendCommitIfReady(upload: LiveUpload) {
        if (!upload.admitted.get() || !upload.audioStarted.get()) return
        if (sessionId().isBlank() || client.connectionState.value != GatewayConnectionState.READY) return
        if (!upload.commitSent.compareAndSet(false, true)) return
        runCatching { protocol.commitTurn(upload.segmentId, upload.utteranceId) }
            .onFailure {
                upload.commitSent.set(false)
                Log.w("GatewayBusinessSpeech", "turn commit send failed", it)
            }
    }

    private suspend fun executeLiveUpload(upload: LiveUpload) {
        val slot = bridge.newReplySlot(upload.segmentId, upload.utteranceId)
        try {
            requireQueuedInput(upload)
            ensureReady()
            requireQueuedInput(upload)
            val coordinates = upload.coordinates
            protocol.audioStart(
                sessionId(), upload.segmentId, upload.utteranceId,
                coordinates?.first, coordinates?.second,
                navigationContext = upload.navigationContext,
            )
            upload.audioStarted.set(true)
            sendCommitIfReady(upload)
            for (chunk in upload.chunks) {
                requireQueuedInput(upload)
                protocol.audioChunk(chunk)
            }
            requireQueuedInput(upload)
            protocol.audioEnd(sessionId())
            upload.reply.complete(slot.await())
        } catch (cancelled: CancellationException) {
            if (sessionId().isNotBlank() && client.connectionState.value == GatewayConnectionState.READY) {
                runCatching { protocol.cancelTurn(upload.segmentId) }
            }
            bridge.cancelStream(upload.segmentId)
            upload.reply.cancel(cancelled)
        } catch (error: GatewayRemoteException) {
            val lost = error.code == "CONNECTION_FAILED" || error.code == "CONNECTION_CLOSED"
            if (lost) onTransportFailure(error, true)
            upload.reply.completeExceptionally(
                if (lost) CloudUnavailableException("流式云端链路故障：${error.message}", error)
                else CloudRequestFailedException("流式云端请求失败（${error.code}）：${error.message}", error),
            )
        } catch (error: GatewayException) {
            onTransportFailure(error, true)
            upload.reply.completeExceptionally(CloudUnavailableException("流式云端链路故障：${error.message}", error))
        } catch (error: CloudRequestFailedException) {
            if (upload.audioStarted.get() && client.connectionState.value == GatewayConnectionState.READY) {
                runCatching { protocol.cancelTurn(upload.segmentId) }
            }
            bridge.cancelStream(upload.segmentId)
            upload.reply.completeExceptionally(error)
        } finally {
            bridge.clearReplySlot(slot)
        }
    }

    private fun acceptsQueuedInput(upload: LiveUpload): Boolean =
        upload.inputFinalized.get() || recognitionGate?.accepts(upload.permit) != false

    private fun requireQueuedInput(upload: LiveUpload) {
        if (!acceptsQueuedInput(upload)) {
            throw CloudRequestFailedException("recognition stopped before queued audio was sent")
        }
    }

    override suspend fun run(segment: ByteArray): Reply = run(segment, utteranceIdProvider())

    override suspend fun run(segment: ByteArray, utteranceId: String): Reply {
        liveUpload.get()?.takeIf { it.utteranceId == utteranceId }?.let { upload ->
            upload.chunks.close()
            return try { upload.reply.await() } finally { liveUpload.compareAndSet(upload, null) }
        }
        clearReplyText(utteranceId)
        val segmentId = UUID.randomUUID().toString()
        val context = navigationContext()
        val slot = bridge.newReplySlot(segmentId, utteranceId)
        try {
            // Once audio_start is attempted, a transport failure ends this turn. PCM is never replayed.
            ensureReady()
            val coordinates = location()
            protocol.audioStart(
                sessionId(), segmentId, utteranceId.takeIf { it.isNotBlank() },
                coordinates?.first, coordinates?.second, 0, navigationContext = context,
            )
            var offset = 0
            while (offset < segment.size) {
                val end = minOf(offset + SPEECH_CHUNK_BYTES, segment.size)
                protocol.audioChunk(segment.copyOfRange(offset, end))
                offset = end
            }
            protocol.audioEnd(sessionId())
            return slot.await()
        } catch (error: GatewayRemoteException) {
            clearReplyText(utteranceId)
            if (error.code == "CONNECTION_FAILED" || error.code == "CONNECTION_CLOSED") {
                onTransportFailure(error, false)
                throw CloudUnavailableException("云端链路故障，本轮已结束：${error.message}", error)
            }
            throw CloudRequestFailedException("云端请求失败（${error.code}）：${error.message}", error)
        } catch (error: GatewayException) {
            clearReplyText(utteranceId)
            onTransportFailure(error, false)
            throw CloudUnavailableException("云端链路故障，本轮已结束：${error.message}", error)
        } catch (cancelled: CancellationException) {
            clearReplyText(utteranceId)
            runCatching { protocol.cancelTurn(segmentId) }
            bridge.cancelStream(segmentId)
            throw cancelled
        } finally {
            bridge.clearReplySlot(slot)
        }
    }

    fun close() {
        liveUpload.getAndSet(null)?.let {
            it.chunks.close(CancellationException("gateway runner closed"))
            it.reply.cancel()
        }
    }
}
