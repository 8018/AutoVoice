package com.autovoice.voiceengine.local

import com.autovoice.voicecore.AsrEngine
import com.autovoice.voicecore.AsrResult
import com.autovoice.voicecore.Intent
import com.autovoice.voicecore.NluEngine
import com.autovoice.voicecore.NluResult
import com.autovoice.voiceengine.AsrModule
import com.autovoice.voiceengine.NluModule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class LocalSpeechRouteTest {
    @Test
    fun `ASR evidence and text arrive before the NLU candidate`() = runBlocking {
        val events = mutableListOf<String>()
        val asr = AsrModule().apply {
            bindLocal(AsrEngine { _, _, sink ->
                sink.onTurnEstablished()
                sink.onTranscript(AsrResult(" ", false))
                sink.onTranscript(AsrResult("打开车窗", true))
                AsrResult("打开车窗", true)
            })
        }
        val nlu = NluModule().apply {
            bindLocal(NluEngine { _, _, transcript ->
                events += "nlu:${transcript?.text}"
                NluResult(Intent.unknown("vehicle"), transcript?.text)
            })
        }
        val result = LocalSpeechRoute(
            asr, nlu,
            onRecognized = { turnId, transcript -> events += "asr:$turnId:${transcript.text}" },
            onTurnEstablished = { events += "established:$it" },
            onResult = { turnId, _, elapsed -> events += "result:$turnId:$elapsed" },
            clockMs = { if (events.isEmpty()) 100L else 125L },
        ).run(byteArrayOf(1), "turn-1")

        assertEquals("打开车窗", result.recognizedText)
        assertEquals(
            listOf("established:turn-1", "asr:turn-1:打开车窗", "nlu:打开车窗", "result:turn-1:25"),
            events,
        )
    }

    @Test
    fun `recognition failure remains an unknown candidate and reports the error`() = runBlocking {
        val asr = AsrModule().apply { bindLocal(AsrEngine { _, _, _ -> null }) }
        val nlu = NluModule().apply {
            bindLocal(NluEngine { _, _, _ -> throw IllegalStateException("SDK failed") })
        }
        val failures = mutableListOf<String>()
        val result = LocalSpeechRoute(
            asr, nlu,
            onFailure = { turnId, error, _ -> failures += "$turnId:${error.message}" },
        ).run(byteArrayOf(1), "turn-2")

        assertEquals("unknown", result.intent.intent)
        assertEquals(listOf("turn-2:SDK failed"), failures)
    }

    @Test
    fun `cancellation is not converted into an unknown candidate`() {
        val asr = AsrModule().apply { bindLocal(AsrEngine { _, _, _ -> null }) }
        val nlu = NluModule().apply {
            bindLocal(NluEngine { _, _, _ -> throw CancellationException("cancelled") })
        }
        assertThrows(CancellationException::class.java) {
            runBlocking { LocalSpeechRoute(asr, nlu).run(byteArrayOf(1), "turn-3") }
        }
    }
}
