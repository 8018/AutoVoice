package com.autovoice.voiceengine.local

import com.autovoice.voicecore.AsrResult
import com.autovoice.voicecore.AsrSink
import com.autovoice.voicecore.Intent
import com.autovoice.voicecore.NluResult
import com.autovoice.voicecore.session.LocalChainRunner
import com.autovoice.voiceengine.AsrModule
import com.autovoice.voiceengine.NluModule
import kotlinx.coroutines.CancellationException

/** Engine-owned local route. ASR is observable before NLU, while only NLU enters arbitration. */
class LocalSpeechRoute(
    private val asr: AsrModule,
    private val nlu: NluModule,
    private val onRecognized: (String, AsrResult) -> Unit = { _, _ -> },
    private val onTurnEstablished: (String) -> Unit = {},
    private val onResult: (String, NluResult, Long) -> Unit = { _, _, _ -> },
    private val onFailure: (String, Throwable, Long) -> Unit = { _, _, _ -> },
    private val clockMs: () -> Long = System::currentTimeMillis,
) : LocalChainRunner {
    override suspend fun run(segment: ByteArray): NluResult = run(segment, "")

    override suspend fun run(segment: ByteArray, utteranceId: String): NluResult {
        val startedAtMs = clockMs()
        return try {
            val asrResult = asr.recognizeLocal(utteranceId, segment, object : AsrSink {
                override fun onTurnEstablished() = onTurnEstablished(utteranceId)

                override fun onTranscript(result: AsrResult) {
                    if (result.text.isNotBlank()) onRecognized(utteranceId, result)
                }
            })
            nlu.understandLocal(utteranceId, segment, asrResult).also { result ->
                onResult(utteranceId, result, clockMs() - startedAtMs)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            onFailure(utteranceId, error, clockMs() - startedAtMs)
            NluResult(Intent.unknown("vehicle"))
        }
    }
}
