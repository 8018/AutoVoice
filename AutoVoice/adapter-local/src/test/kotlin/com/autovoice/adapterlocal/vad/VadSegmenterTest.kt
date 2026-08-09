package com.autovoice.adapterlocal.vad

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * [VadSegmenter] 切段逻辑测试（JVM 真实推理，Task 49）：
 *  - 真实语音 wav（test resources 切片）切出 ≥1 段且总长合理；
 *  - 纯静音 → 0 段；
 *  - 未闭合尾段（不等 SpeechEnd 直接 finish）强制切出；
 *  - 非 1024B 帧拒绝。
 */
class VadSegmenterTest {

    private fun modelFile(): File {
        val rel = File("src/main/assets/silero_vad.onnx")
        if (rel.exists()) return rel
        return File(System.getProperty("user.dir"), "src/main/assets/silero_vad.onnx")
    }

    private fun wavDataBytes(): ByteArray {
        val wav = javaClass.getResourceAsStream("/silero_ref_speech.wav")!!.use { it.readBytes() }
        return wav.copyOfRange(44, wav.size) // 16k mono PCM16 data 区
    }

    private fun framesOf(pcm: ByteArray): List<ByteArray> {
        val frames = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < pcm.size) {
            val len = minOf(1024, pcm.size - offset)
            val frame = ByteArray(1024)
            pcm.copyInto(frame, 0, offset, offset + len)
            frames.add(frame)
            offset += len
        }
        return frames
    }

    private fun silenceFrames(count: Int): List<ByteArray> =
        List(count) { ByteArray(1024) }

    @Test
    fun `真实语音 wav 切出至少一个长度达标的语音段`() {
        val model = modelFile()
        assumeTrue(model.exists(), "silero_vad.onnx 不在仓库中，跳过真实推理")

        SileroVad(model.readBytes()).use { vad ->
            val segmenter = VadSegmenter(vad)
            for (frame in framesOf(wavDataBytes())) segmenter.feed(frame)
            val segments = segmenter.finish()
            assertTrue(segments.isNotEmpty(), "真实语音应切出至少一个语音段")
            assertTrue(
                segments.all { it.size >= VadSegmenter.DEFAULT_MIN_SEGMENT_BYTES },
                "每段必须 ≥ 最小段阈值（300ms）",
            )
        }
    }

    @Test
    fun `纯静音不产生任何语音段`() {
        val model = modelFile()
        assumeTrue(model.exists(), "silero_vad.onnx 不在仓库中，跳过真实推理")

        SileroVad(model.readBytes()).use { vad ->
            val segmenter = VadSegmenter(vad)
            for (frame in silenceFrames(40)) segmenter.feed(frame) // 1.28s 静音
            assertEquals(emptyList<ByteArray>(), segmenter.finish())
        }
    }

    @Test
    fun `未闭合尾段在 finish 时强制切出`() {
        val model = modelFile()
        assumeTrue(model.exists(), "silero_vad.onnx 不在仓库中，跳过真实推理")

        SileroVad(model.readBytes()).use { vad ->
            val segmenter = VadSegmenter(vad)
            // 只喂语音开头（SpeechStart 已触发但 SpeechEnd 未到）直接 finish
            val frames = framesOf(wavDataBytes())
            for (frame in frames.take(60)) segmenter.feed(frame)
            val segments = segmenter.finish()
            assertTrue(segments.isNotEmpty(), "未闭合语音段应在 finish 时强制切出")
        }
    }

    @Test
    fun `非 1024 字节帧被拒绝`() {
        val model = modelFile()
        assumeTrue(model.exists(), "silero_vad.onnx 不在仓库中，跳过真实推理")

        SileroVad(model.readBytes()).use { vad ->
            val segmenter = VadSegmenter(vad)
            val thrown = runCatching { segmenter.feed(ByteArray(480)) }.exceptionOrNull()
            assertTrue(thrown is IllegalArgumentException, "480B 帧应被拒绝，实际 $thrown")
        }
    }

    @Test
    fun `短段（低于最小阈值）被丢弃`() {
        // 纯逻辑断言：minSegmentBytes 很大时，任何切出的段都过不了阈值
        val model = modelFile()
        assumeTrue(model.exists(), "silero_vad.onnx 不在仓库中，跳过真实推理")

        SileroVad(model.readBytes()).use { vad ->
            val segmenter = VadSegmenter(vad, minSegmentBytes = 10_000_000) // 大到不可能达标
            for (frame in framesOf(wavDataBytes()).take(120)) segmenter.feed(frame)
            assertEquals(emptyList<ByteArray>(), segmenter.finish())
        }
    }
}
