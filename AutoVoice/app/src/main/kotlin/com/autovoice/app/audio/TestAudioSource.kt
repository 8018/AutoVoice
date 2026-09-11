package com.autovoice.app.audio

import android.content.Context
import android.util.Log

/**
 * 测试音频源（Task 58 云端联调）：以预置语音代替麦克风输入。
 *
 * demo-full.json 声明 {@code "testAudio": "<asset>.pcm"}（16k 单声道 PCM16 raw，无 wav 头）时
 * [AudioRecorder] 走该源：按真实 32ms/块节奏循环读取，双网格管线（VAD 切段 + RNNoise 降噪）
 * 完全复用——除"麦克风采集"外的整条端云链路（端侧 VAD → WS 上传 → 云端 ASR/LLM/TTS →
 * 端侧播报）都可验证，且无需 RECORD_AUDIO 权限。
 *
 * 资源缺失 → [fromConfig] 返回 null（AudioRecorder 降级麦克风并记录日志）。
 */
class TestAudioSource internal constructor(
    private val pcm: ByteArray,
) {
    private var cursor = 0

    /**
     * 重置播放游标（每轮录音开始时调用）：每轮从源开头播放。游标跨轮不重置会让
     * 按下相位随机——VAD 段起点落在源内随机位置（Task 58 联调：识别文本带杂质）。
     */
    fun reset() {
        cursor = 0
    }

    /**
     * 循环切下一块（[AudioFormat.BLOCK_BYTES]）：从游标续读，读到底无缝回绕到开头
     * （loop 语义——按住期间持续有输入，数据流连续无静音间隙，VAD/降噪节奏与真实录音一致）。
     */
    fun nextBlock(block: ByteArray) {
        require(block.size == AudioFormat.BLOCK_BYTES)
        var offset = 0
        while (offset < block.size) {
            if (cursor >= pcm.size) cursor = 0
            val n = minOf(block.size - offset, pcm.size - cursor)
            pcm.copyInto(block, offset, cursor, cursor + n)
            cursor += n
            offset += n
        }
    }

    /** 诊断：源描述（预置语音时长 ms）。 */
    fun describe(): String =
        "${pcm.size * 1000 / (AudioFormat.SAMPLE_RATE * 2)}ms 预置语音"

    companion object {
        /**
         * 加载已由 [com.autovoice.voicecore.DemoConfig] 解析出的 testAudio asset；
         * 字段缺失或资产缺失 → null（麦克风）。
         */
        fun fromConfig(context: Context, name: String?): TestAudioSource? {
            if (name.isNullOrBlank()) return null
            val pcm = runCatching { context.assets.open(name).use { it.readBytes() } }.getOrElse {
                Log.w(TAG, "测试音频资产 $name 缺失，降级麦克风", it)
                return null
            }
            if (pcm.isEmpty()) {
                Log.w(TAG, "测试音频资产 $name 为空，降级麦克风")
                return null
            }
            return TestAudioSource(pcm)
        }

        private const val TAG = "TestAudioSource"
    }
}
