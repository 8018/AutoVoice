package com.autovoice.voicecore.session

import com.autovoice.voicecore.DemoConfig
import com.autovoice.voicecore.Intent
import com.autovoice.voicecore.NluResult
import com.autovoice.voicecore.Reply
import com.autovoice.voicecore.arbiter.ArbitrationOutput
import com.autovoice.voicecore.arbiter.LocalAdmission
import com.autovoice.voicecore.arbiter.OnDeviceRaceArbiter
import com.autovoice.voicecore.arbiter.RaceWinner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 端侧本地 NLU 链路由（spec §5.1 本地兜底链路）：话语段 → 语义候选。
 * ASR 在链内先独立输出到 UI；这里只把 NLU 结果交给仲裁器。
 */
fun interface LocalChainRunner {
    suspend fun run(segment: ByteArray): NluResult

    /** 独立 ASR 回调需要保留所属 capture/turn，避免迟到文本覆盖另一轮 UI。 */
    suspend fun run(segment: ByteArray, utteranceId: String): NluResult = run(segment)
}

/** 兼容只返回 Intent 的旧装配/测试；新 2C 适配器应直接返回带文本的 [NluResult]。 */
@Suppress("FunctionName")
fun LocalChainRunner(block: suspend (ByteArray) -> Intent): LocalChainRunner =
    object : LocalChainRunner {
        override suspend fun run(segment: ByteArray): NluResult = NluResult(block(segment))
    }

/**
 * 云端链路由（spec §5.1 云端优先链路）：话语段 → 网关回复。
 * gateway-client 装配由 Task 20 注入实现。
 */
fun interface CloudRunner {
    suspend fun run(segment: ByteArray): Reply

    /** 并发轮次使用显式快照 ID，避免迟启动的旧音频被标成新轮。 */
    suspend fun run(segment: ByteArray, utteranceId: String): Reply = run(segment)
}

/**
 * 收敛结果通道（Task 18/19/20 消费）：每轮话语恰好回调一次，
 * 只下发真实胜出语义；拒识和无候选不伪造失败结果，由会话状态定时器回 IDLE。
 */
fun interface ResultListener {
    fun onResult(utteranceId: String, winner: RaceWinner)
}

/**
 * 云端链路故障（Task 15 M1）：连接失败 / ready 后中途断开时由云端链实现抛出。
 * 候选协调器捕获后打开本地入队门。是否 latch 不可达由云端链实现决定。
 */
class CloudUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 云端已连接但拒绝/处理失败；本轮转本地，不把健康连接误判为断网并重连。 */
class CloudRequestFailedException(message: String, cause: Throwable? = null) : Exception(message, cause)

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
) {
    private val candidateScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val captureLock = Any()

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

}
