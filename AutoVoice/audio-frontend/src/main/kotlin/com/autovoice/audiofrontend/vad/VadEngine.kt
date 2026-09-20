package com.autovoice.audiofrontend.vad

/** Atomic VAD capability: one fixed-size PCM frame in, one speech probability out. */
interface VadEngine : AutoCloseable {
    val maxProbability: Float
    fun feed(pcm16k: ByteArray): Float
    fun reset()
}
