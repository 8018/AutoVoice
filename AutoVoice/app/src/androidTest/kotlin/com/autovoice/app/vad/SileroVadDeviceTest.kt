package com.autovoice.app.vad

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.autovoice.adapterlocal.vad.SileroVad
import com.autovoice.adapterlocal.vad.VadEvent
import com.autovoice.adapterlocal.vad.VoiceActivityGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Silero VAD 真机验证（Task 48，用户要求"测通了再集成"）。
 *
 * 确定性输入，不走麦克风：assets 里打包的真实 TTS 语音 wav（cmd_open_ac.wav，
 * "打开空调" 1.43s + 1.5s 尾部静音）逐 1024B 帧喂入，喂帧方式与
 * [com.autovoice.app.audio.AudioRecorder.processBlock] 完全一致：
 * `gate.feed(vad.feed(block))`。
 *
 * 验证目标（真机 onnxruntime-android 运行时 + APK 合并的模型资产）：
 *  - 模型能从 APK assets 加载并真实推理（AudioRecorder 用的正是这条路径）；
 *  - 真实语音 → VoiceActivityGate 依次产生 SpeechStart、SpeechEnd（默认门控参数
 *    threshold=0.5 / minSpeechMs=64ms / minSilenceMs=960ms）；
 *  - 静音 → 概率低于阈值、无任何事件。
 */
@RunWith(AndroidJUnit4::class)
class SileroVadDeviceTest {

    /** 被测 APP 上下文（模型资产从这里加载——AudioRecorder 用的同一条 APK assets 路径）。 */
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** 测试 APK 上下文（wav fixture 打包在测试 APK 的 assets 里）。 */
    private val testContext get() = InstrumentationRegistry.getInstrumentation().context

    /** WAV 头 44B 后按 1024B 切帧，尾帧补零到整帧（对齐 AudioRecorder 块语义）。 */
    private fun wavFrames(asset: String): List<ByteArray> {
        val pcm = testContext.assets.open(asset).use { it.readBytes() }
        val data = pcm.copyOfRange(44, pcm.size)
        val frames = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < data.size) {
            val len = minOf(1024, data.size - offset)
            val frame = ByteArray(1024)
            data.copyInto(frame, 0, offset, offset + len) // 尾帧余量自动为 0
            frames.add(frame)
            offset += len
        }
        return frames
    }

    @Test
    fun speechWavProducesStartThenEnd() {
        SileroVad(context, "silero_vad.onnx").use { vad ->
            val gate = VoiceActivityGate()
            val events = mutableListOf<VadEvent>()
            var maxProb = 0f
            for (frame in wavFrames("cmd_open_ac.wav")) {
                val prob = vad.feed(frame)
                maxProb = maxOf(maxProb, prob)
                gate.feed(prob)?.let { events.add(it) }
            }
            assertEquals(
                "语音段边界事件必须按 SpeechStart → SpeechEnd 顺序产生",
                listOf(VadEvent.SpeechStart, VadEvent.SpeechEnd),
                events,
            )
            assertTrue("TTS 语音帧最高概率应远高于阈值 0.5，实际 $maxProb", maxProb > 0.9f)
        }
    }

    @Test
    fun silenceFramesProduceNoEvents() {
        SileroVad(context, "silero_vad.onnx").use { vad ->
            val gate = VoiceActivityGate()
            val silence = ByteArray(1024)
            repeat(40) { // 40 帧 = 1.28s 静音
                val prob = vad.feed(silence)
                assertTrue("静音帧概率应低于阈值，实际 $prob", prob < 0.5f)
                assertTrue("静音不应产生 VAD 事件", gate.feed(prob) == null)
            }
        }
    }
}
