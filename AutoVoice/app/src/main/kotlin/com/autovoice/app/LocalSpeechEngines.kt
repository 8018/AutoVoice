package com.autovoice.app

import android.util.Log
import com.autovoice.adapteriflytek.FakeCommandAsrProvider
import com.autovoice.adapteriflytek.IflytekOfflineCommandAsrStage
import com.autovoice.adapteriflytek.RuleNluProvider
import com.autovoice.app.telemetry.TelemetryClient
import com.autovoice.app.telemetry.TelemetryStages
import com.autovoice.voicecore.AsrEngine
import com.autovoice.voicecore.AsrResult
import com.autovoice.voicecore.AsrSink
import com.autovoice.voicecore.DemoConfig
import com.autovoice.voicecore.Intent
import com.autovoice.voicecore.NluEngine
import com.autovoice.voicecore.NluResult
import com.autovoice.voicecore.SpeechRouteModules
import com.autovoice.voicecore.session.LocalChainRunner
import kotlinx.coroutines.CancellationException

private const val LOCAL_SPEECH_TAG = "LocalSpeechEngines"

/** The current 2C command SDK does not provide an independent ASR result. */
internal class LocalAsrEngine : AsrEngine {
    override fun recognize(turnId: String, segment: ByteArray, sink: AsrSink): AsrResult? = null
}

/** A 2C command result is a semantic candidate with optional recognized text, not an ASR partial. */
internal class LocalNluEngine(
    private val configuredAsr: String,
    private val offline: (ByteArray) -> String?,
) : NluEngine<NluResult> {
    override suspend fun understand(turnId: String, segment: ByteArray, asr: AsrResult?): NluResult {
        val command = try {
            recognizeLocalCommand(configuredAsr, segment) { offline(segment) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.w(LOCAL_SPEECH_TAG, "本地 2C 命令词异常，按未命中继续", error)
            null
        }
        return NluResult(
            intent = RuleNluProvider.understand(command.orEmpty()),
            recognizedText = command,
        )
    }
}

/** VoiceEngine-owned local route: ASR output is immediate; only NLU enters arbitration. */
internal class LocalSpeechChain(
    override val asr: AsrEngine,
    override val nlu: NluEngine<NluResult>,
    private val onRecognized: (String, String) -> Unit,
    private val onTurnEstablished: (String) -> Unit,
    private val telemetry: TelemetryClient,
) : LocalChainRunner, SpeechRouteModules<NluResult> {
    override suspend fun run(segment: ByteArray): NluResult = run(segment, "")

    override suspend fun run(segment: ByteArray, utteranceId: String): NluResult {
        val startMs = System.currentTimeMillis()
        return try {
            val asrResult = asr.recognize(utteranceId, segment, object : AsrSink {
                override fun onTurnEstablished() = onTurnEstablished(utteranceId)

                override fun onTranscript(result: AsrResult) {
                    if (result.text.isNotBlank()) {
                        onRecognized(utteranceId, result.text)
                        telemetry.record(
                            TelemetryStages.LOCAL_ASR,
                            "info",
                            mapOf("text" to result.text, "isFinal" to result.isFinal),
                        )
                    }
                }
            })
            val result = nlu.understand(utteranceId, segment, asrResult)
            val intent = result.intent
            Log.i(LOCAL_SPEECH_TAG, "本地 NLU 意图: ${intent.domain}/${intent.intent} (${intent.slots})")
            telemetry.record(
                TelemetryStages.LOCAL_NLU,
                "info",
                mapOf(
                    "text" to (result.recognizedText ?: ""),
                    "intent" to "${intent.domain}/${intent.intent}",
                    "durationMs" to (System.currentTimeMillis() - startMs),
                ),
            )
            result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.w(LOCAL_SPEECH_TAG, "本地链路异常，降级 unknown 意图", error)
            telemetry.record(
                TelemetryStages.LOCAL_NLU,
                "warn",
                mapOf("intent" to "unknown/vehicle", "durationMs" to (System.currentTimeMillis() - startMs)),
            )
            NluResult(Intent.unknown("vehicle"))
        }
    }
}

/** Production failures remain failures; the deterministic fake is an explicit demo provider only. */
internal fun recognizeLocalCommand(
    configuredAsr: String,
    segment: ByteArray,
    offline: () -> String?,
): String? = when (configuredAsr) {
    DemoConfig.LOCAL_ASR_IFLYTEK -> try {
        offline()
    } catch (error: IllegalStateException) {
        if (error.message?.contains(IflytekOfflineCommandAsrStage.NOT_CONFIGURED_MSG) == true) null else throw error
    }
    DemoConfig.LOCAL_ASR_FAKE -> FakeCommandAsrProvider.recognize(segment)
    else -> throw IllegalArgumentException("unsupported local.asr '$configuredAsr'")
}
