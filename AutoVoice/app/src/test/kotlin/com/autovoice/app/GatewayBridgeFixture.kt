package com.autovoice.app

import com.autovoice.gatewayclient.GatewayClient
import com.autovoice.gatewayclient.GatewayPayloadParser
import com.autovoice.voicecore.StreamingAudioReply
import com.autovoice.voicecore.arbiter.DecisionSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel

/** Test composition mirrors VoiceEngineFactory without making GatewayBridge own ASR/NLU. */
@Suppress("FunctionName")
internal fun GatewayBridge(
    client: GatewayClient,
    sink: DecisionSink,
    scope: CoroutineScope,
    pendingSignals: SendChannel<Unit> = Channel(Channel.BUFFERED),
    onPendingReceived: (String) -> Unit = {},
    onAsrResult: (String, Boolean, String) -> Unit = { _, _, _ -> },
    onAsrTurnEstablished: (String) -> Unit = {},
    onReplyText: (String, Boolean, String) -> Unit = { _, _, _ -> },
    onChatReply: (StreamingAudioReply) -> Unit = {},
    onChatSpeechStarted: () -> Unit = {},
    onChatFailure: () -> Unit = {},
): GatewayBridge {
    val bridge = GatewayBridge(client, scope, onChatReply, onChatSpeechStarted, onChatFailure)
    CloudAsrEngine(bridge, onAsrResult, onAsrTurnEstablished).register()
    CloudNluEngine(
        bridge, GatewayPayloadParser(), sink, pendingSignals, onPendingReceived, onReplyText,
    ).register()
    return bridge
}
