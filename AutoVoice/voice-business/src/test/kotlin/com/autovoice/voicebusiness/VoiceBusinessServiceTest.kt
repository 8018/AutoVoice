package com.autovoice.voicebusiness

import com.autovoice.business.BusinessHandler
import com.autovoice.business.BusinessResult
import com.autovoice.tts.PlaybackInterruptionReason
import com.autovoice.tts.PlaybackStage
import com.autovoice.tts.RealtimePlaybackToken
import com.autovoice.tts.TtsOutput
import com.autovoice.voicebusiness.dialog.AdmissionEvidence
import com.autovoice.voicebusiness.dialog.DialogueState
import com.autovoice.voicecore.AudioReply
import com.autovoice.voicecore.AudioStreamEnd
import com.autovoice.voicecore.Intent
import com.autovoice.voicecore.StreamingAudioReply
import com.autovoice.voicecore.TextReply
import com.autovoice.voicecore.SlotValue
import com.autovoice.voicebusiness.navigation.AppDialogueManager
import com.autovoice.voicebusiness.navigation.NavigationTarget
import com.autovoice.voicebusiness.navigation.NavigationTrip
import com.autovoice.voicebusiness.navigation.NavigationNormalizationResult
import com.autovoice.voicebusiness.navigation.SavedNavigationPlace
import com.autovoice.voicecore.arbiter.RaceWinner
import com.autovoice.voiceengine.api.TextInputPort
import com.autovoice.voiceengine.api.TextSubmission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
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

    @Test
    fun `each dialogue transition renews ten seconds and repeated playback state does not`() = runTest {
        val output = RecordingOutput()
        val service = service(output, scope = backgroundScope)
        service.onWake()
        val turn = service.conversation.beginCapture()
        service.conversation.openCapture(turn)
        service.onAsrTurnEstablished(turn, AdmissionEvidence.LOCAL_ASR)

        advanceTimeBy(9_000)
        service.conversation.onInputFinalized(turn)
        advanceTimeBy(9_000)
        assertEquals(DialogueState.PROCESSING, service.conversation.snapshot.value.state)
        service.onTurnResult(turn, RaceWinner.Cloud(TextReply("好的")))
        advanceTimeBy(9_000)
        assertEquals(DialogueState.RESPONDING, service.conversation.snapshot.value.state)
        service.onPlaybackLifecycle(turn, PlaybackStage.STARTED)
        advanceTimeBy(9_999)
        assertEquals(DialogueState.SPEAKING, service.conversation.snapshot.value.state)
        service.onPlaybackLifecycle(turn, PlaybackStage.STARTED)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(DialogueState.DORMANT, service.conversation.snapshot.value.state)
        assertEquals(PlaybackInterruptionReason.INTERACTION_CLOSED, output.stops.last())
    }

    @Test
    fun `ten seconds of processing expires the turn and discards late semantic`() = runTest {
        val output = RecordingOutput()
        val service = service(output, scope = backgroundScope)
        service.onWake()
        val turn = service.conversation.beginCapture()
        service.conversation.openCapture(turn)
        service.onAsrTurnEstablished(turn, AdmissionEvidence.LOCAL_ASR)
        service.conversation.onInputFinalized(turn)

        advanceTimeBy(9_999)
        assertEquals(DialogueState.PROCESSING, service.conversation.snapshot.value.state)
        advanceTimeBy(1)
        runCurrent()
        service.onTurnResult(turn, RaceWinner.Cloud(TextReply("迟到的结果")))

        assertEquals(DialogueState.DORMANT, service.conversation.snapshot.value.state)
        assertTrue(output.spoken.isEmpty())
    }

    @Test
    fun `ten seconds after wake exits an unanswered dialogue`() = runTest {
        val service = service(RecordingOutput(), scope = backgroundScope)
        service.onWake()

        advanceTimeBy(9_999)
        assertEquals(DialogueState.LISTENING, service.conversation.snapshot.value.state)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(DialogueState.DORMANT, service.conversation.snapshot.value.state)
    }

    @Test
    fun `accepted voice destination change retires the pending trip before cloud semantics`() = runTest {
        lateinit var service: VoiceBusinessService
        val opened = mutableListOf<String>()
        val navigation = AppDialogueManager(
            interactionIdProvider = { service.conversation.snapshot.value.interactionId!! },
            onNavigation = {},
            launchNavigation = { opened += it; true },
            savedPlace = { SavedNavigationPlace(NavigationTarget("地址", 30.31, 104.4), true) },
            normalizeTrip = {},
        )
        service = VoiceBusinessService(
            tts = RecordingOutput(),
            business = BusinessHandler { error("No semantic reply has arrived") },
            scope = backgroundScope,
            onDialogueState = navigation::onDialogueState,
            onAcceptedVoiceTranscript = { _, text -> navigation.replaceNormalizingDraftUnless { navigation.draftCommand(text) != null } },
        )
        service.onWake()
        val intent = Intent("1.0", "navigation", "draft_trip", mapOf("stops" to SlotValue.StringValue(
            """[{"query":"学校","candidates":[{"poiname":"学校","lat":30.6,"lon":104.0}]},{"query":"家","savedPlace":"home"}]""",
        )), 1.0, "test")
        assertTrue(navigation.navigationExecutor.execute(intent, "draft"))
        val revision = navigation.navigationSession.snapshot.draft!!.revision
        navigation.confirmDraft(revision)
        service.conversation.openCapture("new-voice")
        service.onAsrTurnEstablished("new-voice", AdmissionEvidence.CLOUD_ASR)
        service.onRecognized("new-voice", "改去机场", final = false)
        service.onRecognized("stale-voice", "改去机场", final = true)
        service.onRecognized("new-voice", "确认行程", final = true)
        assertTrue(navigation.navigationSession.snapshot.normalizingDraft)

        service.conversation.openCapture("destination-change")
        service.onRecognized("destination-change", "改去机场", final = true)
        assertTrue(navigation.navigationSession.snapshot.normalizingDraft)
        service.onAsrTurnEstablished("destination-change", AdmissionEvidence.LOCAL_ASR)
        assertEquals(DialogueState.LISTENING, service.conversation.snapshot.value.state)

        val converted = NavigationTrip(NavigationTarget("家庭地址", 30.32, 104.41), listOf(
            NavigationTarget("学校", 30.6, 104.0),
        ))
        assertEquals(null, navigation.navigationExecutor.completeDraftNormalization(
            revision, NavigationNormalizationResult.Converted(converted),
        ))
        assertTrue(opened.isEmpty())

        assertTrue(navigation.navigationExecutor.execute(intent, "replacement-draft"))
        val replacement = navigation.navigationSession.snapshot.draft!!.revision
        navigation.confirmDraft(replacement)
        service.onRecognized("destination-change", "改去机场", final = true)

        assertEquals(replacement, navigation.normalizingDraftRevision())
    }

    private fun service(
        output: RecordingOutput,
        onRecognized: (String?) -> Unit = {},
        onReply: (String) -> Unit = {},
        onExecute: () -> Unit = {},
        scope: CoroutineScope = CoroutineScope(kotlinx.coroutines.test.StandardTestDispatcher()),
    ): VoiceBusinessService = VoiceBusinessService(
        tts = output,
        business = BusinessHandler {
            onExecute()
            BusinessResult.applied()
        },
        scope = scope,
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
