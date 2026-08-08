package com.autovoice.app.audio

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import com.autovoice.voicecore.AudioReply
import java.io.File

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
}

/**
 * 音频回复播放器（MediaPlayer + 临时 wav 文件）。
 *
 * - 下行 [AudioReply] 若是原始 PCM（audio/pcm|L16|raw）→ 补 44 字节 wav 头写临时文件；
 *   已是 wav/mpeg 等 → 原样写文件，MediaPlayer 自解析。
 * - 播放完成回调 [onCompleted]（供 Task 19/20 状态机）；失败静默降级：
 *   所有异常捕获后 Log.w + [onError]，不抛到 UI。
 * - 临时文件在播放完成/失败/stop 后删除。
 */
class TtsPlayer(
    private val context: Context,
    private val onCompleted: (() -> Unit)? = null,
    private val onError: ((Throwable) -> Unit)? = null,
) {

    @Volatile
    private var player: MediaPlayer? = null

    @Volatile
    private var currentFile: File? = null

    /** 播放代次：stop()/新 play() 使旧代次回调失效。 */
    private var playToken = 0

    /** 播放一段音频回复；自动中断上一段。失败静默降级。 */
    fun play(reply: AudioReply) {
        stop()
        val token = ++playToken
        val file = try {
            writeAudioFile(reply)
        } catch (t: Throwable) {
            Log.w(TAG, "write audio file failed, degraded silently", t)
            onError?.invoke(t)
            return
        }
        if (token != playToken) {
            file.delete() // play 期间被 stop()/新 play() 打断
            return
        }
        currentFile = file
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setDataSource(file.absolutePath)
            mp.setOnCompletionListener {
                if (token == playToken) onCompleted?.invoke()
                finishPlayback(token, mp)
            }
            mp.setOnErrorListener { _, what, extra ->
                val err = IllegalStateException("MediaPlayer error what=$what extra=$extra")
                Log.w(TAG, "playback failed, degraded silently", err)
                if (token == playToken) onError?.invoke(err)
                finishPlayback(token, mp)
                true // 事件已消费
            }
            mp.prepare()
            mp.start()
        } catch (t: Throwable) {
            Log.w(TAG, "play failed, degraded silently", t)
            if (token == playToken) onError?.invoke(t)
            finishPlayback(token, mp)
        }
    }

    /** 停止播放并释放（幂等）。 */
    fun stop() {
        playToken++
        val mp = player
        player = null
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
        } else {
            // audio/wav、audio/mpeg 等：原样写入，MediaPlayer 自解析
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
    }
}
