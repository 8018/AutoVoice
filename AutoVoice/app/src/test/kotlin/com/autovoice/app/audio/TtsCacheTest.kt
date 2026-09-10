package com.autovoice.app.audio

import com.autovoice.app.telemetry.TelemetryStages
import com.autovoice.voicecore.AudioReply
import java.io.File
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * 端侧 TTS 缓存（架构变更：缓存移回端侧）纯 JVM 测试。
 * 内存键 = 原文文本，磁盘同时保存音频与 MIME；无 TTL/淘汰。
 */
class TtsCacheTest {

    @TempDir
    lateinit var dir: File

    private val wav = ByteArray(16) { it.toByte() }

    /** 收集事件 (stage, level, payload) 的探针。 */
    private class EventProbe {
        val events = mutableListOf<Triple<String, String, Map<String, Any?>>>()
        fun onEvent(stage: String, level: String, payload: Map<String, Any?>) {
            events.add(Triple(stage, level, payload))
        }
    }

    @Test
    fun `memory hit returns put bytes without disk`() {
        val probe = EventProbe()
        val cache = TtsCache(dir = null, onEvent = probe::onEvent)
        cache.put("好的", wav)
        assertArrayEquals(wav, cache.get("好的")?.data, "内存缓存应命中")
        assertTrue(dir.listFiles()?.isEmpty() ?: true, "dir=null 不应写盘")
    }

    @Test
    fun `write-through persists audio and mime`() {
        val probe = EventProbe()
        val cache = TtsCache(dir = dir, onEvent = probe::onEvent)
        cache.put("好的", wav)
        val files = dir.listFiles()
        assertEquals(2, files?.size, "写穿后磁盘应保存音频和 MIME")
        assertArrayEquals(wav, files!!.first { it.name.endsWith(".audio") }.readBytes())
        assertEquals("audio/wav", files.first { it.name.endsWith(".mime") }.readText())
    }

    @Test
    fun `cold start loads from disk and fills memory`() {
        TtsCache(dir = dir).put("好的", wav)
        val probe = EventProbe()
        val fresh = TtsCache(dir = dir, onEvent = probe::onEvent)
        val restored = fresh.get("好的")
        assertArrayEquals(wav, restored?.data, "新实例应命中磁盘缓存")
        assertEquals("audio/wav", restored?.mime)
        assertEquals(1, probe.events.count { it.first == TelemetryStages.TTS_CACHE_HIT }, "磁盘命中应记 cache_hit")
        // 回填内存：第二次 get 不依赖磁盘（若未回填，删除磁盘文件后仍应命中）
        dir.listFiles()!!.forEach { Files.delete(it.toPath()) }
        assertArrayEquals(wav, fresh.get("好的")?.data, "磁盘命中应回填内存")
    }

    @Test
    fun `cache preserves provider mime instead of assuming wav`() {
        val encoded = AudioReply("audio/mpeg", wav, speakText = "好的")
        TtsCache(dir = dir).put("好的", encoded)

        val restored = TtsCache(dir = dir).get("好的")

        assertEquals("audio/mpeg", restored?.mime)
        assertArrayEquals(wav, restored?.data)
    }

    @Test
    fun `corrupt empty disk file is a miss`() {
        val cache = TtsCache(dir = dir)
        cache.put("好的", wav)
        // 模拟损坏：清空磁盘文件
        dir.listFiles()!!.first { it.name.endsWith(".audio") }.writeBytes(ByteArray(0))
        val probe = EventProbe()
        val fresh = TtsCache(dir = dir, onEvent = probe::onEvent)
        assertNull(fresh.get("好的"), "空磁盘文件应视为损坏 → miss")
        assertEquals(1, probe.events.count { it.first == TelemetryStages.TTS_CACHE_MISS })
        assertEquals(0, probe.events.count { it.first == TelemetryStages.TTS_CACHE_HIT })
    }

    @Test
    fun `blank text is never cached or checked`() {
        val probe = EventProbe()
        val cache = TtsCache(dir = dir, onEvent = probe::onEvent)
        cache.put("  ", wav)
        cache.put("", wav)
        assertTrue(dir.listFiles().isNullOrEmpty(), "空文本不得写盘")
        assertNull(cache.get(""), "空文本 get 返回 null")
        assertNull(cache.get("  "), "空白文本 get 返回 null")
        assertTrue(probe.events.isEmpty(), "空文本不得产生任何缓存事件")
    }

    @Test
    fun `empty audio is not cached`() {
        val cache = TtsCache(dir = dir)
        cache.put("好的", ByteArray(0))
        assertTrue(dir.listFiles().isNullOrEmpty(), "空音频不得写盘")
        assertNull(cache.get("好的"), "空音频 put 后仍应 miss")
    }

    @Test
    fun `hit event sequence is check then hit with bytes`() {
        val probe = EventProbe()
        val cache = TtsCache(dir = dir, onEvent = probe::onEvent)
        cache.put("好的", wav)
        probe.events.clear()
        cache.get("好的")
        assertEquals(listOf(TelemetryStages.TTS_CACHE_CHECK, TelemetryStages.TTS_CACHE_HIT),
            probe.events.map { it.first }, "命中序列应为 check → hit")
        assertEquals("好的", probe.events[0].third["text"], "check payload 应带原文文本")
        assertEquals(16, probe.events[1].third["bytes"], "hit payload 应带字节数")
    }

    @Test
    fun `miss event sequence is check then miss`() {
        val probe = EventProbe()
        val cache = TtsCache(dir = dir, onEvent = probe::onEvent)
        cache.get("没缓存过的文本")
        assertEquals(listOf(TelemetryStages.TTS_CACHE_CHECK, TelemetryStages.TTS_CACHE_MISS),
            probe.events.map { it.first }, "未命中序列应为 check → miss")
        assertEquals("没缓存过的文本", probe.events[1].third["text"], "miss payload 应带原文文本")
    }
}
