package com.autovoice.adapterlocal.vad

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Silero VAD 真实推理冒烟测试（JVM 上用 ai.onnxruntime 加载本地模型文件）。
 *
 * 模型文件 `adapter-local/src/main/assets/silero_vad.onnx` 由版本库带入；
 * 不在时跳过（assumeTrue），编译与其余测试不受影响。
 */
class SileroVadModelTest {

    private fun modelFile(): File {
        // AGP 单测工作目录 = 模块目录（adapter-local）
        val rel = File("src/main/assets/silero_vad.onnx")
        if (rel.exists()) return rel
        return File(System.getProperty("user.dir"), "src/main/assets/silero_vad.onnx")
    }

    private fun silenceFrame(): ByteArray {
        val bb = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN)
        repeat(512) { bb.putShort(0) }
        return bb.array()
    }

    @Test
    fun `silence frames yield low speech probability`() {
        val model = modelFile()
        assumeTrue(model.exists(), "silero_vad.onnx 不在仓库中，跳过真实推理")

        val pcm = silenceFrame()
        SileroVad(model.readBytes()).use { vad ->
            // 连续 4 帧静音：v5 模型对静音的基准概率 ~0.0006（python onnxruntime 实测）
            repeat(4) { assertTrue(vad.feed(pcm) < 0.5f) }
        }
    }

    @Test
    fun `probability stays within zero and one`() {
        val model = modelFile()
        assumeTrue(model.exists(), "silero_vad.onnx 不在仓库中，跳过真实推理")

        val pcm = silenceFrame()
        SileroVad(model.readBytes()).use { vad ->
            repeat(3) {
                val p = vad.feed(pcm)
                assertTrue(p in 0f..1f, "probability out of range: $p")
            }
        }
    }

    @Test
    fun `feed rejects non-1024-byte frames`() {
        val model = modelFile()
        assumeTrue(model.exists(), "silero_vad.onnx 不在仓库中，跳过真实推理")

        val vad = SileroVad(model.readBytes())
        assertThrows(IllegalArgumentException::class.java) { vad.feed(ByteArray(480)) }
    }
}
