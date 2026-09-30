package com.autovoice.voiceengine.request

import com.autovoice.voicecore.DemoConfig
import com.autovoice.voicecore.arbiter.ArbitrationOutput
import com.autovoice.voicecore.arbiter.LocalAdmission
import com.autovoice.voicecore.arbiter.OnDeviceRaceArbiter
import com.autovoice.voicecore.session.CloudRequestFailedException
import com.autovoice.voicecore.session.CloudRunner
import com.autovoice.voicecore.session.CloudUnavailableException
import com.autovoice.voicecore.session.LocalChainRunner
import com.autovoice.voicecore.session.ResultListener
import com.autovoice.voiceengine.CloudTextNluEngine
import com.autovoice.voiceengine.api.TextSubmission
import com.autovoice.voiceengine.api.TextInputPort
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 本地/云端候选生产协调器。它只拥有采集资源事实、云端音频聚合和候选任务，完全不拥有
 * 用户对话状态，也不知道哪个 turn 是当前轮。当前轮、思考、响应、播报和延时聆听统一由
 * DialogueStateMachine 管理。
 *
 * 采集块与业务轮刻意分离：录音开始调用 [beginCapture]，VAD 段调用 [appendCloudSegment]，
 * 抬手调用 [submitTurn] 原子取走云端音频并启动双候选。提交后采集立即关闭，但候选自然完成；
 * 仲裁结果无条件交给 [ResultListener]，是否仍可采用由下游状态机判断。
 */
class CandidateCoordinator(
    private val cfg: DemoConfig,
    private val arbiter: OnDeviceRaceArbiter,
    private val local: LocalChainRunner,
    private val cloud: CloudRunner,
    private val resultListener: ResultListener = ResultListener { _, _ -> },
    private val cloudText: CloudTextNluEngine? = null,
    private val onTextFailure: (String, Throwable) -> Unit = { _, _ -> },
) : TextInputPort {
    private val candidateScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val captureLock = Any()
    private val textInFlight = AtomicInteger()
    private val acceptedTextIds = LinkedHashSet<String>()
    private val textInputFinalized = HashMap<String, CompletableDeferred<Unit>>()

    @Volatile
    var currentCaptureId: String = ""
        private set

    /** 云端可达性（spec §5.1）：`onCloudUnavailable()` 置 false 后不再启动云端链；
     *  `onCloudAvailable()` 恢复（Task 20 engine 在话语开始时按网络状态调用）。
     *  @Volatile：会话协程写（onCloudUnavailable/onCloudAvailable），
     *  runTurn 协程读（cloudRouteActive），跨线程可见（Task 14 M2）。 */
    @Volatile
    private var cloudAvailable = true

    /**
     * 本轮云端 VAD 片段。S2S 在首个音频 chunk 到达时就返回 StreamingAudioReply，若把
     * 每个 VAD 片段分别调用 cloud.run，下一片段会在上一回复仍播放/生成时撞上网关 BUSY，
     * 且模型看不到完整话语。这里只收集片段，在 [submitTurn] 原子取快照并整轮调用一次。
     */
    private val cloudSegments = ArrayList<ByteArray>()

    fun beginCapture(captureId: String) {
        require(captureId.isNotBlank())
        synchronized(captureLock) {
            currentCaptureId = captureId
            cloudSegments.clear()
        }
    }

    fun cancelCapture(captureId: String = currentCaptureId): Boolean = synchronized(captureLock) {
        if (captureId.isBlank() || currentCaptureId != captureId) return@synchronized false
        currentCaptureId = ""
        cloudSegments.clear()
        true
    }

    fun isCapturing(captureId: String = currentCaptureId): Boolean = synchronized(captureLock) {
        captureId.isNotBlank() && currentCaptureId == captureId
    }

    fun appendCloudSegment(captureId: String, segment: ByteArray): Boolean {
        if (segment.isEmpty() || !cloudRouteActive()) return false
        return synchronized(captureLock) {
            if (currentCaptureId != captureId) return@synchronized false
            cloudSegments.add(segment.copyOf())
            true
        }
    }

    fun submitTurn(captureId: String, localAudio: ByteArray): Boolean {
        val cloudAudio = synchronized(captureLock) {
            if (captureId.isBlank() || currentCaptureId != captureId) return false
            currentCaptureId = ""
            takeCloudAudioLocked()
        }
        runTurn(captureId, localAudio, cloudAudio)
        return true
    }

    /** Explicit text has no local audio candidate; the cloud NLU reply enters the same arbiter. */
    override fun submitText(requestId: String, text: String): TextSubmission {
        require(requestId.isNotBlank() && text.isNotBlank())
        val runner = cloudText ?: return TextSubmission.NO_ROUTE
        if (!cfg.cloud.enabled) return TextSubmission.NO_ROUTE
        val inputReady = synchronized(acceptedTextIds) {
            if (requestId in acceptedTextIds) return TextSubmission.DUPLICATE_REQUEST
            if (textInFlight.get() >= MAX_TEXT_IN_FLIGHT) return TextSubmission.BUSY
            textInFlight.incrementAndGet()
            acceptedTextIds.add(requestId)
            val ready = CompletableDeferred<Unit>()
            textInputFinalized[requestId] = ready
            if (acceptedTextIds.size > MAX_RETAINED_TEXT_IDS) acceptedTextIds.remove(acceptedTextIds.first())
            ready
        }
        arbiter.openTurn(requestId) { output ->
            if (output is ArbitrationOutput.Winner) resultListener.onResult(requestId, output.value)
        }
        candidateScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                inputReady.await()
                arbiter.submitCloud(requestId, runner.understand(requestId, text))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                onTextFailure(requestId, failure)
            } finally {
                synchronized(acceptedTextIds) { textInputFinalized.remove(requestId) }
                textInFlight.decrementAndGet()
            }
        }
        return TextSubmission.ACCEPTED
    }

    /** Releases cloud processing only after synthetic VAD end and input-finalized are delivered. */
    override fun finalizeTextInput(requestId: String): Boolean = synchronized(acceptedTextIds) {
        textInputFinalized[requestId]?.complete(Unit) ?: false
    }

    /** 云端不可达（断网/认证失效等，spec §5.1 可达性检查）：此后只跑本地链。 */
    fun onCloudUnavailable() {
        cloudAvailable = false
    }

    /** 云端可达性恢复（Task 20）：engine 在话语开始且网络可用时调用，重新启用云端链。 */
    fun onCloudAvailable() {
        cloudAvailable = true
    }

    /**
     * Starts candidate producers and returns immediately. The coordinator never waits for the
     * arbiter: candidates post messages to a process-lived FIFO pipeline, whose winner callback is
     * later checked against the dialogue state by the application layer.
     */
    private fun runTurn(utteranceId: String, segment: ByteArray, cloudAudio: ByteArray?) {
        arbiter.openTurn(utteranceId) { output ->
            if (output is ArbitrationOutput.Winner) resultListener.onResult(utteranceId, output.value)
        }

        if (cloudAudio == null) {
            val reason = if (cloudRouteActive()) "no_cloud_segment" else "cloud_unreachable"
            candidateScope.launch {
                arbiter.submitLocal(
                    utteranceId,
                    local.run(segment, utteranceId),
                    admission = LocalAdmission.IMMEDIATE,
                    immediateReason = reason,
                )
            }
            return
        }

        // Start the cloud producer first. FIFO order is determined by actual submitted messages;
        // a synchronous cloud failure must open the local gate before a fast local result is held.
        candidateScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                arbiter.submitCloud(utteranceId, cloud.run(cloudAudio, utteranceId))
            } catch (error: CloudUnavailableException) {
                onCloudUnavailable()
                arbiter.submitCloudUnavailable(utteranceId, "cloud_unreachable")
            } catch (error: CloudRequestFailedException) {
                arbiter.submitCloudUnavailable(utteranceId, "cloud_request_failed")
            }
        }
        candidateScope.launch {
            arbiter.submitLocal(utteranceId, local.run(segment, utteranceId))
        }
    }

    fun close() {
        candidateScope.cancel()
        arbiter.close()
        synchronized(captureLock) {
            currentCaptureId = ""
            cloudSegments.clear()
        }
    }

    /** 原子取走本轮 VAD 片段并按时间顺序拼接；空片段轮返回 null，走本地链。 */
    private fun takeCloudAudioLocked(): ByteArray? {
        val snapshot = cloudSegments.toList()
        cloudSegments.clear()
        if (snapshot.isEmpty()) return null
        val total = snapshot.sumOf { it.size }
        val joined = ByteArray(total)
        var offset = 0
        snapshot.forEach { part ->
            part.copyInto(joined, offset)
            offset += part.size
        }
        return joined
    }

    /** 云端链启动条件（spec §5.1 可达性）：配置开启且云端可达。 */
    private fun cloudRouteActive(): Boolean = cfg.cloud.enabled && cloudAvailable

    companion object {
        private const val MAX_TEXT_IN_FLIGHT = 2
        private const val MAX_RETAINED_TEXT_IDS = 64
    }

}
