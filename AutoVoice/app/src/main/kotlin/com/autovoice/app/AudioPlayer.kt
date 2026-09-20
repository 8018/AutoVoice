package com.autovoice.app

import com.autovoice.voicecore.AudioReply
import com.autovoice.voicecore.StreamingAudioReply
import java.io.ByteArrayOutputStream

/** Android playback adapter retained at the composition edge; orchestration lives in :tts. */
fun interface AudioPlayer {
    fun play(reply: AudioReply)
    fun play(reply: AudioReply, identity: com.autovoice.tts.PlaybackIdentity) = play(reply)
    suspend fun playStream(
        reply: StreamingAudioReply,
        identity: com.autovoice.tts.PlaybackIdentity,
    ) = playStream(reply)
    fun stop() = Unit

    suspend fun playStream(reply: StreamingAudioReply) {
        val pcm = ByteArrayOutputStream()
        for (chunk in reply.chunks) pcm.write(chunk)
        val end = reply.completion.await()
        play(AudioReply("audio/pcm", pcm.toByteArray(), end.speakText, end.intent, end.asrText))
    }
}
