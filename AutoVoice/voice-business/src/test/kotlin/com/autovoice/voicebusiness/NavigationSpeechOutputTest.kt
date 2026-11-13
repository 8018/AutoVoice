package com.autovoice.voicebusiness

import com.autovoice.business.BusinessHandler
import com.autovoice.business.BusinessResult
import com.autovoice.tts.*
import com.autovoice.voicebusiness.navigation.NavigationExecutor
import com.autovoice.voicecore.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NavigationSpeechOutputTest {
    @Test
    fun `displayed candidates retain numbered names at the speech output boundary`() {
        val spoken = mutableListOf<String>()
        val opened = mutableListOf<String>()
        val executor = NavigationExecutor(opener = { opened += it; true })
        val output = object : TtsOutput {
            override fun speak(turnId: String, text: String) { spoken += text }
            override fun play(turnId: String, reply: AudioReply) = Unit
            override fun playStream(turnId: String, reply: StreamingAudioReply, onComplete: (AudioStreamEnd) -> Unit) = Unit
            override fun playRealtimeStream(token: RealtimePlaybackToken, reply: StreamingAudioReply, onComplete: (AudioStreamEnd) -> Unit) = Unit
            override fun stop(reason: PlaybackInterruptionReason) = Unit
            override fun acceptPlaybackEvent(stage: String, level: String, payload: Map<String, Any?>) = Unit
        }
        val dispatcher = ResponseDispatcher(output, BusinessHandler { command ->
            BusinessResult.applied(executor.executeWithResult(command.intent, command.turnId).userFeedback)
        }, isCurrentTurn = { it == "choices" }, onRecognized = {}, onReplyText = {})
        val candidates = """[{"candidateId":"c1","poiname":"上海博物馆(人民广场馆)","lat":31.23,"lon":121.47},{"candidateId":"c2","poiname":"上海博物馆(东馆)","lat":31.22,"lon":121.54}]"""
        val intent = Intent("1.0", "navigation", "choose_destination", mapOf(
            "selectionId" to SlotValue.StringValue("selection"),
            "candidates" to SlotValue.StringValue(candidates),
        ), 1.0, "cloud")
        val speech = "第1个，上海博物馆(人民广场馆)；第2个，上海博物馆(东馆)。请说第几个。"
        assertEquals(ResponseDispatcher.Outcome.OUTPUT_REQUESTED,
            dispatcher.dispatchCloud("choices", ActionReply(intent, speech)))
        assertEquals(listOf(speech), spoken)
        assertEquals(2, executor.session.snapshot.candidates.size)
        assertTrue(opened.isEmpty())
    }
}
