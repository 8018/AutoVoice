package com.autovoice.voicebusiness

import com.autovoice.business.BusinessHandler
import com.autovoice.business.BusinessResult
import com.autovoice.tts.PlaybackInterruptionReason
import com.autovoice.tts.RealtimePlaybackToken
import com.autovoice.tts.TtsOutput
import com.autovoice.voicebusiness.dialog.AdmissionEvidence
import com.autovoice.voicebusiness.dialog.DialogueState
import com.autovoice.voicecore.AudioReply
import com.autovoice.voicecore.AudioStreamEnd
import com.autovoice.voicecore.Intent
import com.autovoice.voicecore.StreamingAudioReply
import com.autovoice.voicecore.TextReply
import com.autovoice.voicecore.arbiter.RaceWinner
import com.autovoice.voiceengine.api.TextInputPort
import com.autovoice.voiceengine.api.TextSubmission
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VoiceBusinessServiceTest {
    @Test
    fun `typed input uses engine port without ASR and adopts cloud result`() = runTest {
        val output = RecordingOutput()
        val recognized = mutableListOf<String?>()
        val service = service(output, onRecognized = recognized::add)
        val calls = mutableListOf<String>()
        var requestId = ""
        val port = object : TextInputPort {
            override fun submitText(requestId: String, text: String): TextSubmission {
                calls += "submit:$text"
                return TextSubmission.ACCEPTED
            }
            override fun finalizeTextInput(requestId: String): Boolean {
                calls += "finalize:$requestId"
                return true
            }
        }

        assertEquals(TextSubmission.ACCEPTED, service.submitText(" 导航到机场 ", port) { requestId = it })
        assertEquals(DialogueState.PROCESSING, service.conversation.snapshot.value.state)
        assertEquals(requestId, service.conversation.snapshot.value.turnId)
        assertEquals(listOf("导航到机场"), recognized)
        assertEquals("submit:导航到机场", calls.first())
        assertEquals("finalize:$requestId", calls.last())

        service.onTurnResult(requestId, RaceWinner.Cloud(TextReply("好的")))
        assertEquals(DialogueState.RESPONDING, service.conversation.snapshot.value.state)
        assertEquals(listOf(requestId to "好的"), output.spoken)
    }

    @Test
    fun `current cloud reply reaches output and advances dialogue`() = runTest {
        val output = RecordingOutput()
        val recognized = mutableListOf<String?>()
        val replies = mutableListOf<String>()
        val service = service(output, onRecognized = recognized::add, onReply = replies::add)

        service.onWake()
        val turn = service.conversation.beginCapture()
        service.conversation.openCapture(turn)
        service.conversation.onInputFinalized(turn)
        service.onTurnResult(turn, RaceWinner.Cloud(TextReply("你好", asrText = "今天天气")))

        assertEquals(listOf("今天天气"), recognized)
        assertEquals(listOf("你好"), replies)
        assertEquals(listOf(turn to "你好"), output.spoken)
        assertEquals(DialogueState.RESPONDING, service.conversation.snapshot.value.state)
    }

    @Test
    fun `late semantic from old turn cannot execute or speak`() = runTest {
        val output = RecordingOutput()
        var executions = 0
        val service = service(output, onExecute = { executions++ })
        service.onWake()

        val old = service.conversation.beginCapture()
        service.conversation.openCapture(old)
        service.onAsrTurnEstablished(old, AdmissionEvidence.LOCAL_ASR)
        val current = service.conversation.beginCapture()
        service.conversation.openCapture(current)
        service.onAsrTurnEstablished(current, AdmissionEvidence.CLOUD_ASR)
        service.onTurnResult(old, RaceWinner.Cloud(TextReply("不应播报")))

        assertEquals(current, service.conversation.snapshot.value.turnId)
        assertTrue(output.spoken.isEmpty())
        assertEquals(0, executions)
    }

    @Test
    fun `exit closes dialogue and late semantic remains inert`() = runTest {
        val output = RecordingOutput()
        val service = service(output)
        service.onWake()
        val turn = service.conversation.beginCapture()
        service.conversation.openCapture(turn)
        service.onAsrTurnEstablished(turn, AdmissionEvidence.LOCAL_ASR)

        service.exitCurrentDialogue()
        service.onTurnResult(turn, RaceWinner.Cloud(TextReply("不应播报")))

        assertEquals(DialogueState.DORMANT, service.conversation.snapshot.value.state)
        assertTrue(output.spoken.isEmpty())
        assertTrue(PlaybackInterruptionReason.INTERACTION_CLOSED in output.stops)
    }

    private fun service(
        output: RecordingOutput,
        onRecognized: (String?) -> Unit = {},
        onReply: (String) -> Unit = {},
        onExecute: () -> Unit = {},
    ): VoiceBusinessService = VoiceBusinessService(
        tts = output,
        business = BusinessHandler {
            onExecute()
            BusinessResult.applied()
        },
        scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.test.StandardTestDispatcher()),
        thinkingTimeoutMs = 60_000,
        onRecognizedText = onRecognized,
        onReplyText = onReply,
    )

    private class RecordingOutput : TtsOutput {
        val spoken = mutableListOf<Pair<String, String>>()
        val stops = mutableListOf<PlaybackInterruptionReason>()

        override fun speak(turnId: String, text: String) { spoken += turnId to text }
        override fun play(turnId: String, reply: AudioReply) = Unit
        override fun playStream(turnId: String, reply: StreamingAudioReply, onComplete: (AudioStreamEnd) -> Unit) = Unit
        override fun playRealtimeStream(
            token: RealtimePlaybackToken,
            reply: StreamingAudioReply,
            onComplete: (AudioStreamEnd) -> Unit,
        ) = Unit
        override fun stop(reason: PlaybackInterruptionReason) { stops += reason }
        override fun acceptPlaybackEvent(stage: String, level: String, payload: Map<String, Any?>) = Unit
    }
}
