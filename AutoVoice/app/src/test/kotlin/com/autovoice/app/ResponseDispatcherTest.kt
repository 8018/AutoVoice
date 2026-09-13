package com.autovoice.app

import com.autovoice.app.audio.TtsCache
import com.autovoice.app.telemetry.TelemetryClient
import com.autovoice.voicecore.ActionReply
import com.autovoice.voicecore.AudioReply
import com.autovoice.voicecore.Intent
import com.autovoice.voicecore.TextReply
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** 回复文本必须由分发器上报到回复窗口(不再依赖播放器适配器的副作用)。 */
class ResponseDispatcherTest {

    private fun dispatcher(onReplyText: (String) -> Unit): ResponseDispatcher {
        val telemetry = TelemetryClient(
            OkHttpClient(), "http://unused", null,
            CoroutineScope(Dispatchers.Default), enabled = false,
        )
        val output = SpeechOutputService(
            tts = TtsRequester { null },
            cache = TtsCache(null),
            playback = PlaybackCoordinator(AudioPlayer { }, { _, _, _, _ -> }),
            telemetry = telemetry,
            scope = CoroutineScope(Dispatchers.Default),
            isCurrentTurn = { true },
            onEmptyOutput = {},
        )
        return ResponseDispatcher(
            output = output,
            vehicle = MockVehicleState(),
            navigation = null,
            telemetry = telemetry,
            isCurrentTurn = { true },
            onVehicleApplied = {},
            onRecognized = {},
            onReplyText = onReplyText,
            onConversationMode = {},
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
        assertEquals(listOf("这个操作没有执行，请再说一次"), texts)
    }

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
}
