package com.autovoice.adapterlocal.vad

/**
 * VAD 语音段切分器（Task 49：按钮录音双路架构的云端路段提取）。
 *
 * 按住录音期间只攒原始 1024B 块（16k PCM16），抬手后把整段重喂本组件：
 * [feed] 逐块喂 [SileroVad] + [VoiceActivityGate]，SpeechStart 记起点块序号、
 * SpeechEnd 切出 [startIndex, 当前块) 的段；[finish] 把未闭合的尾段（语音结束
 * 后静音不足 [VoiceActivityGate.minSilenceMs] / 抬手时仍在语音中）强制切出。
 *
 * 段 = 原始 1024B 块拼接（16k mono PCM16，与录音网格对齐），< [minSegmentBytes]
 * （默认 9600B = 300ms）的段丢弃（瞬时噪声/关门声误触发过滤，spec §3）。
 * 一次 [finish] 返回本段录音的全部语音段，按时间顺序。
 *
 * [VadEvent] 是 gate 的回流值，供调用方观测；不观测可忽略。
 */
class VadSegmenter(
    private val vad: SileroVad,
    private val gate: VoiceActivityGate = VoiceActivityGate(),
    private val minSegmentBytes: Int = DEFAULT_MIN_SEGMENT_BYTES,
) : AutoCloseable {

    /** 本段录音的原始块（1024B/块，块序号 = 段边界坐标）。 */
    private val blocks = mutableListOf<ByteArray>()

    /** 当前开放段的起点块序号（SpeechStart 置位，SpeechEnd/finish 切出后复位）。 */
    private var openStartIndex = -1

    /** 已切出的语音段（SpeechEnd 时累积，finish 时取走）。 */
    private val segments = mutableListOf<ByteArray>()

    /**
     * 喂入一块原始 16k PCM16（必须 1024 字节），返回该块产生的 [VadEvent]（无则 null）。
     *
     * @Synchronized：录音线程（recorder IO）逐块喂入，抬手时主线程调 [finish] 取段，
     * [blocks] 跨线程读写，必须互斥（Task 50 按钮双路接线）。
     */
    @Synchronized
    fun feed(block: ByteArray): VadEvent? {
        require(block.size == 1024) {
            "vad segmenter expects 1024-byte PCM16 frames @16k, got ${block.size} bytes"
        }
        val event = gate.feed(vad.feed(block))
        blocks.add(block)
        when (event) {
            VadEvent.SpeechStart -> openStartIndex = blocks.size - 1
            VadEvent.SpeechEnd -> closeOpenSegment()
            null -> Unit
        }
        return event
    }

    /**
     * 结束本段录音：强制切出未闭合尾段（SpeechEnd 后静音不足 / 抬手时仍在语音），
     * 返回全部语音段（时间顺序）并清空内部状态，可复用切下一段录音。
     *
     * @Synchronized：与 [feed]（录音线程）互斥——录音线程可能还在往 [blocks] 追尾块。
     */
    @Synchronized
    fun finish(): List<ByteArray> {
        closeOpenSegment()
        val out = segments.toList()
        segments.clear()
        return out
    }

    /** 切出 [openStartIndex, 当前块) 的段；长度不足最小段阈值则丢弃。 */
    private fun closeOpenSegment() {
        if (openStartIndex < 0) return
        val start = openStartIndex
        openStartIndex = -1
        var total = 0
        for (i in start until blocks.size) total += blocks[i].size
        if (total < minSegmentBytes) return
        val seg = ByteArray(total)
        var offset = 0
        for (i in start until blocks.size) {
            val b = blocks[i]
            b.copyInto(seg, offset)
            offset += b.size
        }
        segments.add(seg)
    }

    override fun close() {
        vad.close()
    }

    companion object {
        /** 最小语音段字节数（300ms @16k 16bit = 9600B；瞬时误触发过滤，spec §3）。 */
        const val DEFAULT_MIN_SEGMENT_BYTES = 9_600
    }
}
