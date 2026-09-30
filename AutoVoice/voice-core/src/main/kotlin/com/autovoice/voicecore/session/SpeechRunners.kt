package com.autovoice.voicecore.session

import com.autovoice.voicecore.Intent
import com.autovoice.voicecore.NluResult
import com.autovoice.voicecore.Reply
import com.autovoice.voicecore.arbiter.RaceWinner

/** Local route emits ASR separately; only its NLU result becomes an arbitration candidate. */
fun interface LocalChainRunner {
    suspend fun run(segment: ByteArray): NluResult

    /** Preserve the capture identity for late ASR callbacks. */
    suspend fun run(segment: ByteArray, utteranceId: String): NluResult = run(segment)
}

/** Compatibility helper for existing call sites that supply only an Intent. */
@Suppress("FunctionName")
fun LocalChainRunner(block: suspend (ByteArray) -> Intent): LocalChainRunner =
    object : LocalChainRunner {
        override suspend fun run(segment: ByteArray): NluResult = NluResult(block(segment))
    }

/** Cloud route returns a semantic candidate without owning the dialogue state. */
fun interface CloudRunner {
    suspend fun run(segment: ByteArray): Reply

    /** A delayed producer must keep the capture ID snapshotted at submission. */
    suspend fun run(segment: ByteArray, utteranceId: String): Reply = run(segment)
}

/** Only real winners are delivered; rejection and silence are handled by the dialogue timer. */
fun interface ResultListener {
    fun onResult(utteranceId: String, winner: RaceWinner)
}

/** Transport failure opens the local fallback gate without replaying the cloud request. */
class CloudUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A connected cloud rejected this request; do not mark the transport unreachable. */
class CloudRequestFailedException(message: String, cause: Throwable? = null) : Exception(message, cause)
