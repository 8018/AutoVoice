package com.autovoice.tts

import com.autovoice.voicecore.AudioReply
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Remote or on-device synthesis adapter. It is an implementation detail of [TtsService]. */
fun interface TtsSynthesizer {
    suspend fun synthesize(text: String, turnId: String): AudioReply?
}

/** The only TTS API exposed to consumers. Cache lookup and generation stay behind this boundary. */
fun interface TtsService {
    suspend fun audioFor(text: String, turnId: String): AudioReply?
}

fun interface TtsEventSink {
    fun emit(event: String, level: String, payload: Map<String, Any?>)
}

/** Composition entry point; callers cannot access or coordinate the cache directly. */
fun createTtsService(
    synthesizer: TtsSynthesizer,
    cacheDir: File?,
    events: TtsEventSink = TtsEventSink { _, _, _ -> },
): TtsService = CachingTtsService(synthesizer, AudioCache(cacheDir), events)

private class CachingTtsService(
    private val synthesizer: TtsSynthesizer,
    private val cache: AudioCache,
    private val events: TtsEventSink,
) : TtsService {
    override suspend fun audioFor(text: String, turnId: String): AudioReply? {
        if (text.isBlank()) return null
        val context = mapOf("text" to text, "turnId" to turnId)
        events.emit("tts_cache_check", "info", context)
        cache[text]?.let {
            events.emit("tts_cache_hit", "info", context + ("bytes" to it.data.size))
            return it
        }
        events.emit("tts_cache_miss", "info", context)
        return synthesizer.synthesize(text, turnId)?.also { cache.put(text, it) }
    }
}

/** Memory + disk write-through cache, deliberately private to the module. */
private class AudioCache(private val dir: File?) {
    private val memory = ConcurrentHashMap<String, AudioReply>()

    operator fun get(text: String): AudioReply? =
        memory[text] ?: dir?.let { readDisk(it, text) }?.also { memory.putIfAbsent(text, it) }

    fun put(text: String, reply: AudioReply) {
        if (text.isBlank() || reply.data.isEmpty()) return
        val cached = AudioReply(reply.mime, reply.data.copyOf(), speakText = text)
        memory[text] = cached
        dir?.let { writeDisk(it, text, cached) }
    }

    private fun readDisk(dir: File, text: String): AudioReply? = runCatching {
        val base = keyBase(text)
        val audio = File(dir, "$base.audio")
        val mime = File(dir, "$base.mime")
        if (audio.isFile && mime.isFile) {
            val bytes = audio.readBytes().takeIf { it.isNotEmpty() } ?: return@runCatching null
            val actualMime = mime.readText().trim().takeIf { it.isNotEmpty() } ?: return@runCatching null
            return@runCatching AudioReply(actualMime, bytes, speakText = text)
        }
        File(dir, "$base.wav").takeIf(File::isFile)?.readBytes()?.takeIf { it.isNotEmpty() }
            ?.let { AudioReply("audio/wav", it, speakText = text) }
    }.getOrNull()

    private fun writeDisk(dir: File, text: String, reply: AudioReply) {
        runCatching {
            dir.mkdirs()
            val base = keyBase(text)
            File(dir, "$base.audio").writeBytes(reply.data)
            File(dir, "$base.mime").writeText(reply.mime)
        }
    }

    private fun keyBase(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
