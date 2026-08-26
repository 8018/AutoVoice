package com.autovoice.app.audio

import android.content.Context
import android.media.MediaPlayer
import android.media.AudioAttributes
import android.media.AudioTrack
import android.util.Log
import com.autovoice.voicecore.AudioReply
import com.autovoice.voicecore.StreamingAudioReply
import java.io.File
import kotlinx.coroutines.CancellationException

/**
 * WAV/RIFF 头写入（44 字节，纯函数，JVM 可测）。
 *
 * 布局：`RIFF` + chunkSize(36+data) + `WAVE` + `fmt `(16) + [PCM 编码, 声道数,
 * sampleRate, byteRate, blockAlign, bitsPerSample] + `data` + dataSize，全部小端。
 */
object WavHeader {

    /** 44 字节标准 WAV/RIFF 头（PCM 编码，无扩展 chunk）。 */
    fun write(
        dataSize: Int,
        sampleRate: Int = AudioFormat.SAMPLE_RATE,
        channels: Int = AudioFormat.CHANNELS,
        bitsPerSample: Int = AudioFormat.BITS_PER_SAMPLE,
    ): ByteArray {
        require(dataSize >= 0) { "dataSize must be >= 0, got $dataSize" }
        require(channels > 0 && bitsPerSample % 8 == 0) { "channels=$channels bits=$bitsPerSample" }
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val header = ByteArray(44)
        writeAscii(header, 0, "RIFF")
        writeU32(header, 4, 36L + dataSize) // RIFF chunk 大小 = fmt(24) + data 头(8) + data
        writeAscii(header, 8, "WAVE")
        writeAscii(header, 12, "fmt ")
        writeU32(header, 16, 16) // fmt chunk 大小
        writeU16(header, 20, 1) // 编码 = PCM
        writeU16(header, 22, channels)
        writeU32(header, 24, sampleRate.toLong())
        writeU32(header, 28, byteRate.toLong())
        writeU16(header, 32, blockAlign)
        writeU16(header, 34, bitsPerSample)
        writeAscii(header, 36, "data")
        writeU32(header, 40, dataSize.toLong())
        return header
    }

    private fun writeAscii(b: ByteArray, offset: Int, s: String) {
        s.toByteArray(Charsets.US_ASCII).copyInto(b, offset)
    }

    private fun writeU16(b: ByteArray, offset: Int, v: Int) {
        b[offset] = (v and 0xFF).toByte()
        b[offset + 1] = ((v shr 8) and 0xFF).toByte()
    }

    private fun writeU32(b: ByteArray, offset: Int, v: Long) {
        b[offset] = (v and 0xFF).toByte()
        b[offset + 1] = ((v shr 8) and 0xFF).toByte()
        b[offset + 2] = ((v shr 16) and 0xFF).toByte()
        b[offset + 3] = ((v shr 24) and 0xFF).toByte()
    }

    private fun readU32(b: ByteArray, offset: Int): Long =
        (b[offset].toLong() and 0xFF) or ((b[offset + 1].toLong() and 0xFF) shl 8) or
            ((b[offset + 2].toLong() and 0xFF) shl 16) or ((b[offset + 3].toLong() and 0xFF) shl 24)

    /**
     * 校验并修复 wav 头尺寸字段。DashScope sambert 返回的 wav RIFF chunkSize/dataSize
     * 声明 ~2GB（0x7FFFFFFF 附近）而实际仅几十 KB，MediaPlayer 按头读到 EOF 提前截断
     * （播"好的"就断）。标准 44 字节头按实际数据长度重写两字段；非标准/非 wav 原样返回。
     */
    fun fix(data: ByteArray): ByteArray {
        fun asciiAt(off: Int, expect: String): Boolean =
            data.size >= off + expect.length && String(data, off, expect.length, Charsets.US_ASCII) == expect
        if (data.size < 44 || !asciiAt(0, "RIFF") || !asciiAt(8, "WAVE") || !asciiAt(36, "data")) {
            return data
        }
        val dataSize = data.size - 44L
        if (dataSize == readU32(data, 40)) return data // 头已正确，原样
        val fixed = data.copyOf()
        writeU32(fixed, 4, 36L + dataSize) // RIFF chunkSize = fmt(24) + data 头(8) + data
        writeU32(fixed, 40, dataSize)
        return fixed
    }
}

/**
 * 音频回复播放器（MediaPlayer + 临时 wav 文件）。
 *
 * - 下行 [AudioReply] 若是原始 PCM（audio/pcm|L16|raw）→ 补 44 字节 wav 头写临时文件；
 *   已是 wav/mpeg 等 → 原样写文件，MediaPlayer 自解析。
 * - 播放完成回调 [onCompleted]（供 Task 19/20 状态机）；失败静默降级：
 *   所有异常捕获后 Log.w + [onError]，不抛到 UI。
 * - 播放事件回调 [onPlayEvent]（T7 数据平台插桩，默认 no-op）：stage =
 *   `start` / `completed` / `failed` / `interrupted`，level = info / error / warn，
 *   payload 含 bytes/mime/error 等；装配方（VoiceEngine.create）接到
 *   telemetry.record(tts_play)，enabled=false 时整体零影响。
 * - 临时文件在播放完成/失败/stop 后删除。
 */
class TtsPlayer(
    private val context: Context,
    private val onCompleted: (() -> Unit)? = null,
    private val onError: ((Throwable) -> Unit)? = null,
    private val onPlayEvent: (stage: String, level: String, payload: Map<String, Any?>) -> Unit =
        { _, _, _ -> },
) {

    @Volatile
    private var player: MediaPlayer? = null

    @Volatile
    private var streamPlayer: AudioTrack? = null

    @Volatile
    private var currentFile: File? = null

    /** 播放代次：stop()/新 play() 使旧代次回调失效。 */
    private var playToken = 0

    /** 播放中标志（回声抑制：播报期间按录音 → 端侧丢弃本轮，防扬声器回声被 ASR 当指令）。 */
    @Volatile
    private var isPlayingFlag = false

    /** 最近一次播放结束时刻（毫秒）：播完即刻按住会录到残留回声，短窗口内同样抑制）。 */
    @Volatile
    private var lastPlayEndMs = 0L

    /** 播报中（含刚播完的短回声窗口）？[windowMs] 内新发声被视为可能混入残留回声。 */
    fun isSpeaking(windowMs: Long = 0L): Boolean =
        isPlayingFlag || (windowMs > 0L && System.currentTimeMillis() - lastPlayEndMs < windowMs)

    /** 播放一段音频回复；自动中断上一段。失败静默降级。 */
    fun play(reply: AudioReply) {
        stop()
        val token = ++playToken
        val file = try {
            writeAudioFile(reply)
        } catch (t: Throwable) {
            Log.w(TAG, "write audio file failed, degraded silently", t)
            onError?.invoke(t)
            onPlayEvent("failed", "error",
                mapOf("result" to "failed", "error" to (t.message ?: t.javaClass.simpleName)))
            return
        }
        if (token != playToken) {
            file.delete() // play 期间被 stop()/新 play() 打断
            return
        }
        currentFile = file
        val mp = MediaPlayer()
        player = mp
        Log.i(TAG, "play start: data=${reply.data.size}B mime=${reply.mime} durationMs=${reply.data.size * 1000 / (SAMPLE_RATE_BYTES_PER_SEC)}")
        try {
            mp.setAudioAttributes(VOICE_AUDIO_ATTRIBUTES)
            mp.setDataSource(file.absolutePath)
            mp.setOnCompletionListener {
                Log.i(TAG, "play completed: data=${reply.data.size}B")
                onPlayEvent("completed", "info", mapOf("bytes" to reply.data.size))
                if (token == playToken) onCompleted?.invoke()
                finishPlayback(token, mp)
            }
            mp.setOnErrorListener { _, what, extra ->
                val err = IllegalStateException("MediaPlayer error what=$what extra=$extra")
                Log.w(TAG, "playback failed, degraded silently", err)
                onPlayEvent("failed", "error", mapOf("result" to "failed", "error" to err.message))
                if (token == playToken) onError?.invoke(err)
                finishPlayback(token, mp)
                true // 事件已消费
            }
            mp.prepare()
            mp.start()
            isPlayingFlag = true
            // T7 评审 M1：start 事件在真正开始播放后发出——prepare/start 抛异常时只有
            // failed 事件，不产生 start+failed 的失真成对
            onPlayEvent("start", "info", mapOf("bytes" to reply.data.size, "mime" to reply.mime))
        } catch (t: Throwable) {
            Log.w(TAG, "play failed, degraded silently", t)
            onPlayEvent("failed", "error",
                mapOf("result" to "failed", "error" to (t.message ?: t.javaClass.simpleName)))
            if (token == playToken) onError?.invoke(t)
            finishPlayback(token, mp)
        }
    }

    /** 24k PCM 流式输入：由两级仲裁放行后调用，使用 AudioTrack 边收边播。 */
    suspend fun playStream(reply: StreamingAudioReply) {
        require(reply.encoding == "pcm_s16le" && reply.channels == 1) {
            "unsupported stream format: ${reply.encoding}/${reply.channels}ch"
        }
        stop()
        val token = ++playToken
        val channelMask = android.media.AudioFormat.CHANNEL_OUT_MONO
        val queriedBuffer = AudioTrack.getMinBufferSize(
            reply.sampleRate,
            channelMask,
            android.media.AudioFormat.ENCODING_PCM_16BIT,
        )
        val minBuffer = queriedBuffer.takeIf { it > 0 } ?: (reply.sampleRate / 2)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                android.media.AudioFormat.Builder()
                    .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(reply.sampleRate)
                    .setChannelMask(channelMask)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(minBuffer)
            .build()
        streamPlayer = track
        var bytes = 0L
        try {
            track.play()
            isPlayingFlag = true
            onPlayEvent("start", "info", mapOf("mime" to reply.mime, "streaming" to true))
            for (chunk in reply.chunks) {
                if (token != playToken) break
                var offset = 0
                while (offset < chunk.size && token == playToken) {
                    val written = track.write(chunk, offset, chunk.size - offset, AudioTrack.WRITE_BLOCKING)
                    if (written <= 0) throw IllegalStateException("AudioTrack.write failed: $written")
                    offset += written
                    bytes += written
                }
            }
            if (token == playToken) {
                reply.completion.await()
                onPlayEvent("completed", "info", mapOf("bytes" to bytes, "streaming" to true))
                onCompleted?.invoke()
            }
        } catch (cancelled: CancellationException) {
            if (token == playToken) {
                onPlayEvent("interrupted", "warn", mapOf("result" to "interrupted", "streaming" to true))
            }
            throw cancelled
        } catch (t: Throwable) {
            if (token == playToken) {
                Log.w(TAG, "stream playback failed", t)
                onPlayEvent("failed", "error", mapOf("error" to (t.message ?: t.javaClass.simpleName)))
                onError?.invoke(t)
            }
        } finally {
            if (streamPlayer === track) streamPlayer = null
            runCatching { track.stop() }
            runCatching { track.release() }
            if (token == playToken) {
                isPlayingFlag = false
                lastPlayEndMs = System.currentTimeMillis()
            }
        }
    }

    /** 停止播放并释放（幂等）。 */
    fun stop() {
        if (isPlayingFlag || player != null || streamPlayer != null) {
            Log.i(TAG, "play interrupted by stop()")
            onPlayEvent("interrupted", "warn", mapOf("result" to "interrupted"))
        }
        playToken++
        isPlayingFlag = false
        lastPlayEndMs = System.currentTimeMillis()
        val mp = player
        player = null
        val audioTrack = streamPlayer
        streamPlayer = null
        if (mp != null) {
            try {
                if (mp.isPlaying) mp.stop()
            } catch (_: Throwable) {
            }
            try {
                mp.release()
            } catch (_: Throwable) {
            }
        }
        if (audioTrack != null) {
            runCatching { audioTrack.stop() }
            runCatching { audioTrack.release() }
        }
        deleteCurrentFile()
    }

    /** 释放资源（等同 [stop]，幂等）。 */
    fun release() = stop()

    // ------------------------------------------------------------------ 内部

    private fun writeAudioFile(reply: AudioReply): File {
        val file = File.createTempFile("autovoice_tts_", ".wav", context.cacheDir)
        val bytes = if (isRawPcmMime(reply.mime)) {
            // 云端下行原始 PCM → 补 wav 头（16k 单声道 16bit，与录音格式一致）
            WavHeader.write(reply.data.size) + reply.data
        } else if (reply.mime.startsWith("audio/wav")) {
            // DashScope sambert 返回的 wav 头尺寸字段错误（RIFF chunkSize/dataSize 声明
            // ~2GB，实际仅几十 KB，MediaPlayer 读到 EOF 提前截断播报）→ 按实际数据重写头
            WavHeader.fix(reply.data)
        } else {
            // audio/mpeg 等：原样写入，MediaPlayer 自解析
            reply.data
        }
        file.writeBytes(bytes)
        return file
    }

    private fun isRawPcmMime(mime: String): Boolean =
        mime.startsWith("audio/pcm") || mime.startsWith("audio/l16") ||
            mime.startsWith("audio/raw") || mime.startsWith("audio/x-raw")

    /**
     * 播放结束统一收尾（除 [stop] 外的唯一释放点）：
     * - 本代播放（token 匹配）：清空 player/currentFile 并删除临时文件；
     * - 过期回调（token 不匹配，实例已被 [stop]/新 [play] 处理）：只释放本地 mp，
     *   不触碰当前代状态（修复陈旧回调误清新播放的竞态）。
     * [MediaPlayer.release] 幂等（AOSP 对已 release 实例的 reset 异常有捕获），
     * 与 [stop] 并发/重复调用安全。
     */
    private fun finishPlayback(token: Int, mp: MediaPlayer) {
        if (token == playToken) {
            if (player === mp) player = null
            isPlayingFlag = false
            lastPlayEndMs = System.currentTimeMillis()
            deleteCurrentFile()
        }
        try {
            mp.release()
        } catch (_: Throwable) {
        }
    }

    private fun deleteCurrentFile() {
        val file = currentFile
        currentFile = null
        try {
            file?.delete()
        } catch (_: Throwable) {
        }
    }

    private companion object {
        const val TAG = "TtsPlayer"

        val VOICE_AUDIO_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        /** 音频字节率（16k 单声道 16bit）：估算时长用，MediaPlayer 实际以文件头为准。 */
        const val SAMPLE_RATE_BYTES_PER_SEC = 32_000
    }
}
