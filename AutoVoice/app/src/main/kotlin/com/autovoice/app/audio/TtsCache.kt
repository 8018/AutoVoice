package com.autovoice.app.audio

import com.autovoice.app.telemetry.TelemetryStages
import com.autovoice.voicecore.AudioReply
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * 端侧 TTS 缓存（架构变更：缓存从服务器 tts-server 移回端侧——播报请求后
 * **端侧**检查缓存，命中直接播，未命中才请求服务器合成）。
 *
 * key = 播报文本；内存 [ConcurrentHashMap] + 磁盘写穿。磁盘同时保存音频字节和实际 MIME，
 * 避免把非 WAV 合成结果按 WAV 解码；旧版 `.wav` 缓存仍可读取。
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
    private val memory = ConcurrentHashMap<String, AudioReply>()

    /** 查缓存：命中返回带真实 MIME 的音频并记 hit；未命中记 miss。 */
    fun get(
        text: String,
        eventSink: ((stage: String, level: String, payload: Map<String, Any?>) -> Unit)? = null,
    ): AudioReply? {
        if (text.isBlank()) return null
        val emit = eventSink ?: onEvent
        emit(TelemetryStages.TTS_CACHE_CHECK, "info", mapOf("text" to text))
        val cached = memory[text] ?: dir?.let { readDisk(it, text) }?.also { memory.putIfAbsent(text, it) }
        if (cached != null) {
            emit(TelemetryStages.TTS_CACHE_HIT, "info", mapOf("text" to text, "bytes" to cached.data.size))
        } else {
            emit(TelemetryStages.TTS_CACHE_MISS, "info", mapOf("text" to text))
        }
        return cached
    }

    /** 写缓存（网络合成音频回传后调用）；空文本/空数据不写。 */
    fun put(text: String, reply: AudioReply) {
        if (text.isBlank() || reply.data.isEmpty()) return
        val cached = AudioReply(reply.mime, reply.data.copyOf(), speakText = text)
        memory[text] = cached
        dir?.let { writeDisk(it, text, cached) }
    }

    /** Compatibility helper for callers that explicitly hold WAV bytes. */
    fun put(text: String, data: ByteArray) = put(text, AudioReply("audio/wav", data, speakText = text))

    /** 读磁盘缓存；文件缺失或损坏（空文件/读失败）→ null（视为未命中，重新合成）。 */
    private fun readDisk(dir: File, text: String): AudioReply? = runCatching {
        val base = keyBase(text)
        val audio = File(dir, "$base.audio")
        val mime = File(dir, "$base.mime")
        if (audio.isFile && mime.isFile) {
            val data = audio.readBytes().takeIf { it.isNotEmpty() } ?: return@runCatching null
            val actualMime = mime.readText().trim().takeIf { it.isNotEmpty() } ?: return@runCatching null
            return@runCatching AudioReply(actualMime, data, speakText = text)
        }
        // Read-only migration path for cache entries written before MIME preservation.
        File(dir, "$base.wav").takeIf(File::isFile)?.readBytes()?.takeIf { it.isNotEmpty() }
            ?.let { AudioReply("audio/wav", it, speakText = text) }
    }.getOrNull()

    /** 写盘失败静默（与服务器原 CachedTtsProvider 同容错：内存缓存仍然生效）。 */
    private fun writeDisk(dir: File, text: String, reply: AudioReply) {
        runCatching {
            dir.mkdirs()
            val base = keyBase(text)
            File(dir, "$base.audio").writeBytes(reply.data)
            File(dir, "$base.mime").writeText(reply.mime)
        }
    }

    private fun keyBase(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
