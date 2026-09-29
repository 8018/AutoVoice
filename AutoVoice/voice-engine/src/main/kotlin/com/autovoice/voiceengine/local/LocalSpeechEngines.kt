package com.autovoice.voiceengine.local

import com.autovoice.voicecore.AsrEngine
import com.autovoice.voicecore.AsrResult
import com.autovoice.voicecore.AsrSink
import com.autovoice.voicecore.Intent
import com.autovoice.voicecore.NluEngine
import com.autovoice.voicecore.NluResult
import kotlinx.coroutines.CancellationException

/** The current 2C command SDK has no independent ASR transcript. Do not manufacture a partial. */
class LocalAsrEngine : AsrEngine {
    override fun recognize(turnId: String, segment: ByteArray, sink: AsrSink): AsrResult? = null
}

/** The vendor command recognizer and rule NLU are adapters injected by the App composition root. */
class LocalNluEngine(
    private val recognizeCommand: (ByteArray) -> String?,
    private val understandCommand: (String) -> Intent,
    private val onRecognizerFailure: (Throwable) -> Unit = {},
) : NluEngine<NluResult> {
    override suspend fun understand(turnId: String, segment: ByteArray, asr: AsrResult?): NluResult {
        val command = try {
            recognizeCommand(segment)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            onRecognizerFailure(error)
            null
        }
        return NluResult(
            intent = understandCommand(command.orEmpty()),
            recognizedText = command,
        )
    }
}
