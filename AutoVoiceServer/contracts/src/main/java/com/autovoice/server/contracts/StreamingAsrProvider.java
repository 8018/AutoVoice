package com.autovoice.server.contracts;

import java.util.concurrent.CompletionException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;

/** 在线流式 ASR：一轮一个会话，音频块到达即送入，PGS 结果通过 sink 独立输出。 */
public interface StreamingAsrProvider extends AsrProvider {

    long DEFAULT_FINISH_TIMEOUT_MS = 10_000;

    StreamingAsrSession start(SessionContext context, OnlineAsrSink sink);

    /** Final-result deadline measured from finish; providers may override with their own SLA. */
    default long finishTimeoutMs() {
        return DEFAULT_FINISH_TIMEOUT_MS;
    }

    @Override
    default String transcribe(byte[] pcm16k, SessionContext context) {
        StreamingAsrSession session = start(context, OnlineAsrSink.NOOP);
        session.append(pcm16k);
        try {
            return session.finishWithin(finishTimeoutMs(), TimeUnit.MILLISECONDS).join();
        } catch (CompletionException error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            if (cause instanceof AsrException asr) throw asr;
            throw new AsrException("streaming ASR failed: " + cause.getMessage(), cause);
        } catch (CancellationException error) {
            throw new AsrException("streaming ASR was cancelled", error);
        }
    }
}
