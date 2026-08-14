package com.autovoice.app.audio

import com.autovoice.app.telemetry.TelemetryStages
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
 * 语义与旧服务器 CachedTtsProvider 一致：内存键 = 原文文本，磁盘键 = sha256(text).hex + ".wav"，
 * 写穿两级，无 TTL/淘汰；空/损坏磁盘文件视为 miss；写盘失败静默。
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
        assertArrayEquals(wav, cache.get("好的"), "内存缓存应命中")
        assertTrue(dir.listFiles()?.isEmpty() ?: true, "dir=null 不应写盘")
    }

    @Test
    fun `write-through persists one file on disk with same bytes`() {
        val probe = EventProbe()
        val cache = TtsCache(dir = dir, onEvent = probe::onEvent)
        cache.put("好的", wav)
        val files = dir.listFiles()
        assertEquals(1, files?.size, "写穿后磁盘应恰有 1 个缓存文件")
        assertArrayEquals(wav, files!![0].readBytes(), "磁盘文件内容应等于缓存音频")
        assertTrue(files[0].name.endsWith(".wav"), "磁盘键应为 sha256 hex + .wav")
    }

    @Test
    fun `cold start loads from disk and fills memory`() {
        TtsCache(dir = dir).put("好的", wav)
        val probe = EventProbe()
        val fresh = TtsCache(dir = dir, onEvent = probe::onEvent)
        assertArrayEquals(wav, fresh.get("好的"), "新实例应命中磁盘缓存")
        assertEquals(1, probe.events.count { it.first == TelemetryStages.TTS_CACHE_HIT }, "磁盘命中应记 cache_hit")
        // 回填内存：第二次 get 不依赖磁盘（若未回填，删除磁盘文件后仍应命中）
        Files.delete(dir.listFiles()!!.first().toPath())
        assertArrayEquals(wav, fresh.get("好的"), "磁盘命中应回填内存")
    }

    @Test
    fun `corrupt empty disk file is a miss`() {
        val cache = TtsCache(dir = dir)
        cache.put("好的", wav)
        // 模拟损坏：清空磁盘文件
        dir.listFiles()!!.first().writeBytes(ByteArray(0))
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
