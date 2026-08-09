package com.autovoice.adapterlocal.vad

/** VAD 门控事件。 */
enum class VadEvent {
    /** 语音开始（连续热帧数达到 [VoiceActivityGate.minSpeechMs] 要求）。 */
    SpeechStart,

    /** 语音结束（静音累计达到 [VoiceActivityGate.minSilenceMs] 要求）。 */
    SpeechEnd,
}

/**
 * 语音活动门控状态机（纯 Kotlin，无 Android 依赖，host 可注入概率测试）。
 *
 * 帧周期 32ms（512 samples @16kHz）隐含在参数默认值里：`minSpeechMs / 32` 帧概率 > 阈值
 * → [VadEvent.SpeechStart]；之后连续概率 < 阈值累计 `minSilenceMs / 32` 帧 → [VadEvent.SpeechEnd]。
 *
 * 状态机本身不感知帧大小，只按"帧"计数，帧周期由调用方保证。
 */
class VoiceActivityGate(
    val threshold: Float = 0.5f,
    val minSpeechMs: Int = 64,
    val minSilenceMs: Int = 960,
    val sampleRate: Int = 16000,
) {
    /** 单帧时长 ms（512 samples @16k）。 */
    private val frameMs = 1000.0 * 512.0 / sampleRate   // 32.0 @16k

    /** 触发 [VadEvent.SpeechStart] 所需的连续热帧数（向上取整）。 */
    private val startFrames = Math.ceil(minSpeechMs / frameMs).toInt().coerceAtLeast(1)

    /** 触发 [VadEvent.SpeechEnd] 所需的连续冷帧数（向上取整）。 */
    private val endFrames = Math.ceil(minSilenceMs / frameMs).toInt().coerceAtLeast(1)

    private var inSpeech = false
    private var hotFrames = 0
    private var coldFrames = 0

    /**
     * 喂入一帧的语音概率（0..1），返回该帧产生的 [VadEvent]（无事件则为 null）。
     */
    fun feed(probability: Float): VadEvent? {
        if (probability > threshold) {
            hotFrames++
            coldFrames = 0
            if (!inSpeech && hotFrames >= startFrames) {
                inSpeech = true
                return VadEvent.SpeechStart
            }
        } else {
            coldFrames++
            hotFrames = 0
            if (inSpeech && coldFrames >= endFrames) {
                inSpeech = false
                coldFrames = 0
                return VadEvent.SpeechEnd
            }
        }
        return null
    }

    /** 是否处于语音段内。 */
    val isInSpeech: Boolean get() = inSpeech

    /**
     * 新一轮录音开始前重置门控状态（inSpeech 跨轮残留会吞掉新一轮的 SpeechStart：
     * 上一轮 SpeechEnd 静音不足时 inSpeech 保持 true，下一轮语音被当作仍在说话）。
     */
    fun reset() {
        inSpeech = false
        hotFrames = 0
        coldFrames = 0
    }
}
