package com.autovoice.adapteriflytek

import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IflytekWakeWordObserverTest {
    @Test
    fun `default wake word and ability match delivered ivw sdk`() {
        assertEquals("你好飞飞", IflytekWakeWordObserver.DEFAULT_KEYWORD)
        assertEquals("e867a88f2", IflytekWakeWordObserver.ABILITY_ID)
    }

    @Test
    fun `keyword file follows sdk custom text format`() {
        val dir = Files.createTempDirectory("autovoice-ivw").toFile()
        val observer = IflytekWakeWordObserver(
            appId = "app",
            apiKey = "key",
            apiSecret = "secret",
            keyword = "你好飞飞",
            workDir = dir.absolutePath,
            onWake = {},
        )

        observer.ensureKeywordFile()

        assertEquals(
            "你好飞飞;\n",
            dir.resolve("ivw/keyword.txt").readText(Charsets.UTF_8),
        )
    }

    @Test
    fun `changing keyword removes generated sdk cache`() {
        val dir = Files.createTempDirectory("autovoice-ivw-cache").toFile()
        val ivwDir = dir.resolve("ivw").apply { mkdirs() }
        ivwDir.resolve("keyword.txt").writeText("你好小迪;\n")
        val cache = ivwDir.resolve("keyword.bin").apply { writeBytes(byteArrayOf(1)) }
        val observer = IflytekWakeWordObserver(
            appId = "app",
            apiKey = "key",
            apiSecret = "secret",
            keyword = "你好飞飞",
            workDir = dir.absolutePath,
            onWake = {},
        )

        observer.ensureKeywordFile()

        assertEquals("你好飞飞;\n", ivwDir.resolve("keyword.txt").readText())
        assertTrue(!cache.exists())
    }

    @Test
    fun `required model list excludes demo audio and generated keyword cache`() {
        assertEquals(
            setOf("IVW_GRAM_1", "IVW_KEYWORD_1", "IVW_MLP_1", "IVW_FILLER_1"),
            IflytekWakeWordObserver.REQUIRED_MODELS.toSet(),
        )
        assertTrue(IflytekWakeWordObserver.REQUIRED_MODELS.none { it.contains("test", ignoreCase = true) })
    }
}
