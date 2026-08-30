package com.autovoice.server.contracts;

/** 在线流式 ASR：一轮一个会话，音频块到达即送入，PGS 结果通过 sink 独立输出。 */
public interface StreamingAsrProvider extends AsrProvider {

    StreamingAsrSession start(SessionContext context, OnlineAsrSink sink);

    @Override
    default String transcribe(byte[] pcm16k, SessionContext context) {
        StreamingAsrSession session = start(context, OnlineAsrSink.NOOP);
        session.append(pcm16k);
        return session.finish().join();
    }
}
