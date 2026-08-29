package com.autovoice.server.contracts;

/**
 * 长连接全双工闲聊能力。客户端持续追加 16kHz/mono/PCM s16le，服务端可在任意时刻
 * 增量返回 24kHz 音频；输入流不因输出播放而暂停。
 */
public interface RealtimeChatProvider {

    RealtimeChatSession openRealtimeChat(SessionContext context, RealtimeChatSink sink);
}
