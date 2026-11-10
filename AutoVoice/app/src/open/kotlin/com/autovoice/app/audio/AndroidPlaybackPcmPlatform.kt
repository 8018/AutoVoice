package com.autovoice.app.audio

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.media.*
import android.media.AudioFormat as PlatformAudioFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.autovoice.voicecore.AudioReply
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import kotlin.math.pow

/** Android resources are created/decoded/released on the driver's single IO owner. */
internal class AndroidPlaybackPcmPlatform(private val context: Context) : PlaybackPcmPlatform {
    override fun openTrack(onRouteChanged: () -> Unit): PlaybackPcmTrack {
        val minimum = AudioTrack.getMinBufferSize(16000,PlatformAudioFormat.CHANNEL_OUT_MONO,PlatformAudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "PCM output configuration unavailable" }
        val track = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(PlatformAudioFormat.Builder().setSampleRate(16000).setChannelMask(PlatformAudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(PlatformAudioFormat.ENCODING_PCM_16BIT).build())
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(minimum,6400)).build()
        if (track.state != AudioTrack.STATE_INITIALIZED) { track.release();error("PCM output initialization failed") }
        return try { AndroidTrack(context,track,onRouteChanged) } catch (e: Throwable) { track.release();throw e }
    }
    override suspend fun decode(reply: AudioReply,emit: suspend (PlaybackPcmFormat,ByteArray) -> Unit) {
        require(reply.data.isNotEmpty() && reply.data.size <= MAX_FILE_BYTES) { "unsupported playback size" }
        val mime = reply.mime.lowercase().substringBefore(';')
        if (mime in setOf("audio/pcm","audio/l16","audio/raw","audio/x-raw")) {
            emitPieces(reply.data,0,reply.data.size,PlaybackPcmFormat(16000,1),emit)
            return
        }
        val fixed = WavHeader.fix(reply.data)
        wavePcm(fixed)?.let { wave ->
            emitPieces(fixed,wave.offset,wave.size,wave.format,emit)
            return
        }
        require(mime.startsWith("audio/") && mime !in setOf("audio/unknown","audio/octet-stream")) { "unsupported audio container" }
        val file = File.createTempFile("open-playback-",".audio",context.cacheDir)
        try {
            file.outputStream().use { output ->
                var at = 0
                while (at < fixed.size) { currentCoroutineContext().ensureActive();val n = minOf(8192,fixed.size-at);output.write(fixed,at,n);at += n }
            }
            decodeFile(file,emit)
        } finally { file.delete() }
    }
    private suspend fun emitPieces(data: ByteArray,start: Int,size: Int,format: PlaybackPcmFormat,
        emit: suspend (PlaybackPcmFormat,ByteArray) -> Unit) {
        var offset = start
        while (offset < start+size) {
            currentCoroutineContext().ensureActive()
            val n = minOf(8192,start+size-offset);emit(format,data.copyOfRange(offset,offset+n));offset += n
        }
    }
    private suspend fun decodeFile(file: File,emit: suspend (PlaybackPcmFormat,ByteArray) -> Unit) {
        val extractor = MediaExtractor();var codec: MediaCodec? = null;var started = false
        try {
            extractor.setDataSource(file.absolutePath)
            val selected = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("audio track missing")
            extractor.selectTrack(selected)
            val inputFormat = extractor.getTrackFormat(selected)
            inputFormat.setInteger(MediaFormat.KEY_PCM_ENCODING,PlatformAudioFormat.ENCODING_PCM_16BIT)
            val mime = requireNotNull(inputFormat.getString(MediaFormat.KEY_MIME))
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat,null,null,0);codec.start();started = true
            var inputEnd = false;var outputEnd = false;var spec: PlaybackPcmFormat? = null
            val info = MediaCodec.BufferInfo();var lastProgress = System.nanoTime()
            while (!outputEnd) {
                currentCoroutineContext().ensureActive()
                check((System.nanoTime()-lastProgress)/1_000_000 < 5000) { "audio decoder stalled" }
                if (!inputEnd) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = requireNotNull(codec.getInputBuffer(index));buffer.clear()
                        val n = extractor.readSampleData(buffer,0)
                        if (n < 0) { codec.queueInputBuffer(index,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputEnd = true }
                        else { codec.queueInputBuffer(index,0,n,extractor.sampleTime,0);extractor.advance() }
                        lastProgress = System.nanoTime()
                    }
                }
                when (val index = codec.dequeueOutputBuffer(info,10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { spec = pcmFormat(codec.outputFormat);lastProgress = System.nanoTime() }
                    else -> if (index >= 0) {
                        try {
                            require(info.size in 0..4_194_304) { "oversized decoder buffer" }
                            if (info.size > 0) {
                                val buffer = requireNotNull(codec.getOutputBuffer(index))
                                buffer.position(info.offset);buffer.limit(info.offset+info.size)
                                val format = spec ?: pcmFormat(codec.outputFormat).also { spec = it }
                                while (buffer.hasRemaining()) {
                                    currentCoroutineContext().ensureActive()
                                    val pcm = ByteArray(minOf(buffer.remaining(),8192));buffer.get(pcm);emit(format,pcm)
                                }
                            }
                            outputEnd = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            lastProgress = System.nanoTime()
                        } finally { codec.releaseOutputBuffer(index,false) }
                    }
                }
            }
        } finally {
            if (started) runCatching { codec?.stop() }
            runCatching { codec?.release() };extractor.release()
        }
    }
    private fun pcmFormat(format: MediaFormat): PlaybackPcmFormat {
        val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) format.getInteger(MediaFormat.KEY_PCM_ENCODING) else PlatformAudioFormat.ENCODING_PCM_16BIT
        require(encoding == PlatformAudioFormat.ENCODING_PCM_16BIT) { "unsupported decoder PCM encoding" }
        return PlaybackPcmFormat(format.getInteger(MediaFormat.KEY_SAMPLE_RATE),format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
    }
    private data class Wave(val format: PlaybackPcmFormat,val offset: Int,val size: Int)
    private fun wavePcm(data: ByteArray): Wave? {
        if (data.size < 12 || String(data,0,4,Charsets.US_ASCII) != "RIFF" || String(data,8,4,Charsets.US_ASCII) != "WAVE") return null
        var at = 12;var format: PlaybackPcmFormat? = null;var chunks = 0
        while (at+8 <= data.size) {
            require(++chunks <= 256) { "too many WAV chunks" }
            val id = String(data,at,4,Charsets.US_ASCII);val size = u32(data,at+4);at += 8
            require(size >= 0 && size <= data.size-at) { "invalid WAV chunk size" }
            if (id == "fmt ") {
                require(size >= 16 && u16(data,at) == 1 && u16(data,at+14) == 16) { "unsupported WAV encoding" }
                val channels = u16(data,at+2)
                require(u16(data,at+12) == channels*2) { "unsupported WAV block alignment" }
                format = PlaybackPcmFormat(u32(data,at+4),channels)
            } else if (id == "data") return Wave(requireNotNull(format) { "WAV format missing" },at,size)
            at += size+(size and 1)
        }
        error("WAV PCM data missing")
    }
    private fun u16(b: ByteArray,at: Int) = (b[at].toInt() and 255) or ((b[at+1].toInt() and 255) shl 8)
    private fun u32(b: ByteArray,at: Int) = u16(b,at) or (u16(b,at+2) shl 16)
    private companion object { const val MAX_FILE_BYTES = 16*1024*1024 }
}

private class AndroidTrack(private val context: Context,private val track: AudioTrack,onRouteChanged: () -> Unit) : PlaybackPcmTrack {
    private val audio = runCatching { context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }.getOrNull()
    @Volatile private var lastRoute: Int? = track.routedDevice?.id
    private val routing = AudioRouting.OnRoutingChangedListener {
        val next = track.routedDevice?.id;val previous = lastRoute;lastRoute = next
        if (previous != null && previous != next) onRouteChanged()
    }
    private val volume = object : BroadcastReceiver() {
        override fun onReceive(context: Context,intent: Intent) {
            if (intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE",-1) == AudioManager.STREAM_MUSIC) onRouteChanged()
        }
    }
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
    private var routeObserved = false;private var volumeObserved = false
    private var headWrap = 0L;private var previousHead = 0L
    init {
        routeObserved = runCatching { track.addOnRoutingChangedListener(routing,Handler(Looper.getMainLooper())) }.isSuccess
        volumeObserved = runCatching {
            val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(volume,filter,Context.RECEIVER_NOT_EXPORTED)
            else context.registerReceiver(volume,filter)
        }.isSuccess
    }
    override fun play() { track.play() }
    override fun write(pcm: ByteArray,offset: Int,size: Int) = track.write(pcm,offset,size,AudioTrack.WRITE_NON_BLOCKING)
    override fun playedFrames(): Long {
        val head = track.playbackHeadPosition.toLong() and 0xffffffffL
        if (head < previousHead) headWrap += 1L shl 32
        previousHead = head;return headWrap+head
    }
    override fun renderTiming(frame: Long): PlaybackRenderTiming? {
        if (!routeObserved || !volumeObserved) return null
        val route = track.routedDevice ?: return null
        if (lastRoute == null) lastRoute = route.id
        val manager = audio ?: return null
        if (Build.VERSION.SDK_INT < 28) return null // No reliable system gain observation.
        val gain = if (manager.isStreamMute(AudioManager.STREAM_MUSIC)) 0f else {
            val db = manager.getStreamVolumeDb(AudioManager.STREAM_MUSIC,manager.getStreamVolume(AudioManager.STREAM_MUSIC),route.type)
            if (db == Float.NEGATIVE_INFINITY) 0f else 10.0.pow(db/20.0).toFloat().coerceIn(0f,1f)
        }
        val timestamp = AudioTimestamp()
        val nanos = if (track.getTimestamp(timestamp)) timestamp.nanoTime+(frame-timestamp.framePosition)*62_500
        else {
            val played = playedFrames()
            if (played == 0L) return null
            System.nanoTime()+(frame-played)*62_500 // Observed playhead estimate; reference is always marked limited.
        }
        return PlaybackRenderTiming(nanos,gain,route.id)
    }
    override fun interrupt() { runCatching { track.pause() };runCatching { track.flush() } }
    override fun close() {
        if (!closed.compareAndSet(false,true)) return
        if (routeObserved) runCatching { track.removeOnRoutingChangedListener(routing) }
        if (volumeObserved) runCatching { context.unregisterReceiver(volume) }
        runCatching { track.pause() };runCatching { track.flush() };track.release()
    }
}
