package com.autovoice.voiceengine

import com.autovoice.voicecore.AsrEngine
import com.autovoice.voicecore.AsrResult
import com.autovoice.voicecore.AsrSink
import com.autovoice.voicecore.Intent
import com.autovoice.voicecore.NluEngine
import com.autovoice.voicecore.NluResult
import com.autovoice.voicecore.TextReply
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SpeechModulesTest {
    @Test
    fun `asr routes preserve independent text and cloud release`() {
        val events = mutableListOf<String>()
        val module = AsrModule()
        module.bindLocal(AsrEngine { turnId, _, sink ->
            events += "local:$turnId"
            sink.onTranscript(AsrResult("local text"))
            AsrResult("local text")
        })
        module.bindCloud(
            AsrEngine { turnId, _, sink ->
                events += "cloud:$turnId"
                sink.onTurnEstablished()
                null
            },
            release = { events += "release:$it" },
            close = { events += "close" },
        )
        val sink = object : AsrSink {
            override fun onTranscript(result: AsrResult) { events += result.text }
            override fun onTurnEstablished() { events += "established" }
        }

        assertEquals("local text", module.recognizeLocal("local-1", byteArrayOf(1), sink)?.text)
        assertNull(module.recognizeCloud("cloud-1", byteArrayOf(2), sink))
        module.releaseCloud("cloud-1")
        module.closeCloud()

        assertEquals(
            listOf("local:local-1", "local text", "cloud:cloud-1", "established", "release:cloud-1", "close"),
            events,
        )
    }

    @Test
    fun `nlu routes preserve local result and cloud reply types`() = runBlocking {
        val events = mutableListOf<String>()
        val module = NluModule()
        module.bindLocal(NluEngine { turnId, _, asr ->
            events += "local:$turnId:${asr?.text}"
            NluResult(Intent.unknown("local"), asr?.text)
        })
        module.bindCloud(NluEngine { turnId, _, asr ->
            events += "cloud:$turnId:${asr?.text}"
            TextReply("cloud answer")
        })

        val local = module.understandLocal("local-1", byteArrayOf(1), AsrResult("hello"))
        val cloud = module.understandCloud("cloud-1", byteArrayOf(2), null)

        assertEquals("hello", local.recognizedText)
        assertEquals("cloud answer", (cloud as TextReply).text)
        assertEquals(listOf("local:local-1:hello", "cloud:cloud-1:null"), events)
    }

    @Test
    fun `routes cannot be rebound after composition`() {
        val asr = AsrModule()
        val engine = AsrEngine { _, _, _ -> null }
        asr.bindLocal(engine)
        assertThrows(IllegalStateException::class.java) { asr.bindLocal(engine) }

        val nlu = NluModule()
        val local = NluEngine<NluResult> { _, _, _ -> NluResult(Intent.unknown("local")) }
        nlu.bindLocal(local)
        assertThrows(IllegalStateException::class.java) { nlu.bindLocal(local) }
    }
}
