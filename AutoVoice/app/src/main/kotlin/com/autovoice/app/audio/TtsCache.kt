package com.autovoice.app.audio

import com.autovoice.app.telemetry.TelemetryStages
import java.io.File
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap

/**
 * 端侧 TTS 缓存（架构变更：缓存从服务器 tts-server 移回端侧——播报请求后
 * **端侧**检查缓存，命中直接播，未命中才请求服务器合成）。
 *
 * key = 播报文本；内存 [ConcurrentHashMap] + 磁盘写穿
 * （`dir/sha256(text UTF-8).hex + ".wav"`，与服务器原 CachedTtsProvider 同键方案）。
 * 命中直接回放（`audio/wav`），未命中由调用方走网络合成后 [put] 写穿缓存。
 *
 * 事件（B4 语义迁移到端侧）：[get] 记 `tts_cache_check` →
 * 命中记 `tts_cache_hit`（带 bytes）/ 未命中记 `tts_cache_miss`。
 *
 * 容错：磁盘文件缺失或空/损坏 → 视为未命中（重新合成覆盖）；写盘失败静默
 * （内存缓存仍然生效）。空文本不缓存（返回 null，调用方走网络/兜底）。
 */
class TtsCache(
    /** 磁盘缓存目录；null = 仅内存（测试注入）。 */
    private val dir: File?,
    private val onEvent: (stage: String, level: String, payload: Map<String, Any?>) -> Unit = { _, _, _ -> },
) {
    private val memory = ConcurrentHashMap<String, ByteArray>()

    /** 查缓存：命中返回音频字节并记 hit（带 bytes）；未命中记 miss 返回 null。空文本不缓存。 */
    fun get(text: String): ByteArray? {
        if (text.isBlank()) return null
        onEvent(TelemetryStages.TTS_CACHE_CHECK, "info", mapOf("text" to text))
        val cached = memory[text] ?: dir?.let { readDisk(it, text) }?.also { memory.putIfAbsent(text, it) }
        if (cached != null) {
            onEvent(TelemetryStages.TTS_CACHE_HIT, "info", mapOf("text" to text, "bytes" to cached.size))
        } else {
            onEvent(TelemetryStages.TTS_CACHE_MISS, "info", mapOf("text" to text))
        }
        return cached
    }

    /** 写缓存（网络合成音频回传后调用）；空文本/空数据不写。 */
    fun put(text: String, data: ByteArray) {
        if (text.isBlank() || data.isEmpty()) return
        memory[text] = data
        dir?.let { writeDisk(it, text, data) }
    }

    /** 读磁盘缓存；文件缺失或损坏（空文件/读失败）→ null（视为未命中，重新合成）。 */
    private fun readDisk(dir: File, text: String): ByteArray? =
        runCatching { File(dir, keyFile(text)).readBytes() }.getOrNull()?.takeIf { it.isNotEmpty() }

    /** 写盘失败静默（与服务器原 CachedTtsProvider 同容错：内存缓存仍然生效）。 */
    private fun writeDisk(dir: File, text: String, data: ByteArray) {
        runCatching {
            dir.mkdirs()
            File(dir, keyFile(text)).writeBytes(data)
        }
    }

    /** 磁盘键：sha256(text UTF-8).hex + ".wav"（与服务器原 CachedTtsProvider 同方案）。 */
    private fun keyFile(text: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))) +
            ".wav"
}
