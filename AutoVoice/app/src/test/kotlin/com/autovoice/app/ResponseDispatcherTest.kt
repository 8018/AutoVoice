package com.autovoice.app

import com.autovoice.voicebusiness.ResponseDispatcher
import com.autovoice.app.business.AppBusinessHandler
import com.autovoice.tts.TtsPlaybackDriver
import com.autovoice.tts.TtsSynthesizer
import com.autovoice.tts.createTtsOutput
import com.autovoice.voicecore.ActionReply
import com.autovoice.voicecore.AudioReply
import com.autovoice.voicecore.AudioStreamEnd
import com.autovoice.voicecore.StreamingAudioReply
import com.autovoice.voicecore.Intent
import com.autovoice.voicecore.TextReply
import com.autovoice.voicecore.SlotValue
import com.autovoice.voicebusiness.navigation.NavigationExecutor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 回复文本必须由分发器上报到回复窗口(不再依赖播放器适配器的副作用)。 */
class ResponseDispatcherTest {

    private fun dispatcher(
        navigation: NavigationExecutor? = null,
        onSavedNavigationCommand: (String, String) -> String? = { _, _ -> null },
        onReplyText: (String) -> Unit,
    ): ResponseDispatcher {
        val output = createTtsOutput(
            synthesizer = TtsSynthesizer { _, _ -> null },
            cacheDir = null,
            driver = TtsPlaybackDriver { _, _ -> },
            scope = CoroutineScope(Dispatchers.Default),
            isCurrentTurn = { true },
            onEmptyOutput = {},
        )
        return ResponseDispatcher(
            output = output,
            business = AppBusinessHandler(MockVehicleState(), navigation),
            isCurrentTurn = { true },
            onRecognized = {},
            onReplyText = onReplyText,
            onSavedNavigationCommand = onSavedNavigationCommand,
        )
    }

    @Test
    fun `cloud audio reply text is shown in reply window`() {
        val texts = mutableListOf<String>()
        dispatcher { texts += it }
            .dispatchCloud("t1", AudioReply("audio/wav", byteArrayOf(1), speakText = "好的，已为您打开空调"))
        assertEquals(listOf("好的，已为您打开空调"), texts)
    }

    @Test
    fun `cloud text reply text is shown in reply window`() {
        val texts = mutableListOf<String>()
        dispatcher { texts += it }
            .dispatchCloud("t1", TextReply("已为您打开空调"))
        assertEquals(listOf("已为您打开空调"), texts)
    }

    @Test
    fun `successful cloud action reply speakText is shown in reply window`() {
        val texts = mutableListOf<String>()
        dispatcher { texts += it }
            .dispatchCloud(
                "t1",
                ActionReply(
                    intent = Intent("1.0", "climate", "power_on", emptyMap(), 0.9, "protocol"),
                    speakText = "已为您打开空调",
                ),
            )
        assertEquals(listOf("已为您打开空调"), texts)
    }

    @Test
    fun `failed action reports failure instead of success text`() {
        val texts = mutableListOf<String>()
        dispatcher { texts += it }.dispatchCloud(
            "t1",
            ActionReply(
                intent = Intent("1.0", "navigation", "navigate", emptyMap(), 0.9, "protocol"),
                speakText = "开始导航",
            ),
        )
        assertEquals(listOf("当前设备不支持这个导航操作"), texts)
    }

    @Test
    fun `successful map handoff replaces cloud audio confirmation`() {
        val texts = mutableListOf<String>()
        var opens = 0
        val dispatcher = dispatcher(NavigationExecutor { opens++; true }, onReplyText = texts::add)
        dispatcher.dispatchCloud("t1", AudioReply("audio/wav", byteArrayOf(1), "已开始导航", navigate()))

        assertEquals(1, opens)
        assertEquals(listOf("已打开高德地图，请查看导航"), texts)
    }

    @Test
    fun `map launch failure reports launch failure once`() {
        val texts = mutableListOf<String>()
        var opens = 0
        val dispatcher = dispatcher(NavigationExecutor { opens++; false }, onReplyText = texts::add)
        val reply = ActionReply(navigate(), "已开始导航")
        dispatcher.dispatchCloud("t1", reply)
        dispatcher.dispatchCloud("t1", reply)

        assertEquals(1, opens)
        assertEquals(listOf("无法打开高德地图，请检查是否已安装"), texts)
    }

    @Test
    fun `completed navigation stream discards unverified audio before reporting launch failure`() {
        val texts = mutableListOf<String>()
        val chunks = Channel<ByteArray>(Channel.UNLIMITED).also { it.trySend(byteArrayOf(1, 2)) }
        val reply = StreamingAudioReply(
            "audio/pcm", 24_000, 1, "pcm_s16le", chunks,
            CompletableDeferred(AudioStreamEnd("已开始导航", navigate())),
        )

        dispatcher(NavigationExecutor { false }, onReplyText = texts::add).dispatchCloud("t1", reply)

        assertTrue(chunks.tryReceive().isClosed)
        assertEquals(listOf("无法打开高德地图，请检查是否已安装"), texts)
    }

    @Test
    fun `saved place command consumes a competing cloud map action`() {
        val texts = mutableListOf<String>()
        var opens = 0
        val dispatcher = dispatcher(NavigationExecutor { opens++; true }, { _, text ->
            if (text == "回家") "还没有设置家" else null
        }, texts::add)

        dispatcher.dispatchCloud("t1", ActionReply(navigate(), "已开始导航", asrText = "回家"))

        assertEquals(0, opens)
        assertEquals(listOf("还没有设置家"), texts)
    }

    @Test
    fun `silent saved place replay still consumes the cloud action`() {
        val texts = mutableListOf<String>()
        var opens = 0
        val dispatcher = dispatcher(NavigationExecutor { opens++; true }, { _, _ -> "" }, texts::add)

        val outcome = dispatcher.dispatchCloud("t1", ActionReply(navigate(), "已开始导航", asrText = "回家"))

        assertEquals(ResponseDispatcher.Outcome.NO_OUTPUT, outcome)
        assertEquals(0, opens)
        assertTrue(texts.isEmpty())
    }

    private fun navigate() = Intent("1.0", "navigation", "navigate", mapOf(
        "poiname" to SlotValue.StringValue("机场"),
        "lat" to SlotValue.Number(30.3),
        "lon" to SlotValue.Number(104.4),
    ), 1.0, "test")

    @Test
    fun `duplicate callback is ignored without false failure speech`() {
        val texts = mutableListOf<String>()
        val dispatcher = dispatcher { texts += it }
        val reply = ActionReply(
            intent = Intent("1.0", "climate", "power_on", emptyMap(), 0.9, "protocol"),
            speakText = "已为您打开空调",
        )
        dispatcher.dispatchCloud("t1", reply)
        dispatcher.dispatchCloud("t1", reply)
        assertEquals(listOf("已为您打开空调"), texts)
    }

    @Test
    fun `blank reply text is not reported to reply window`() {
        val texts = mutableListOf<String>()
        dispatcher { texts += it }
            .dispatchCloud("t1", AudioReply("audio/wav", byteArrayOf(1), speakText = "  "))
        assertEquals(emptyList<String>(), texts)
    }

    @Test
    fun `local navigation without a map capability reports failure`() {
        val dispatcher = dispatcher {}
        val result = dispatcher.dispatchLocal(
            "t1",
            com.autovoice.voicecore.NluResult(
                Intent("1.0", "navigation", "navigate", emptyMap(), 0.9, "protocol"),
                "导航去机场",
            ),
        )
        assertEquals(ResponseDispatcher.Outcome.OUTPUT_REQUESTED, result)
    }
}
