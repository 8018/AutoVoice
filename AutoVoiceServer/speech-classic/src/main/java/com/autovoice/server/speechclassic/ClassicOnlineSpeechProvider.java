package com.autovoice.server.speechclassic;

import com.autovoice.server.contracts.AsrException;
import com.autovoice.server.contracts.AsrProvider;
import com.autovoice.server.contracts.LlmProvider;
import com.autovoice.server.contracts.OnlineSpeechProvider;
import com.autovoice.server.contracts.OnlineSpeechResult;
import com.autovoice.server.contracts.OnlineAudioSink;
import com.autovoice.server.contracts.OnlineAsrSink;
import com.autovoice.server.contracts.SessionContext;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** 现有在线链路适配器：PCM → ASR → DeepSeek；不改变原有请求和工具循环。 */
public final class ClassicOnlineSpeechProvider implements OnlineSpeechProvider {

    private final AsrProvider asr;
    private final LlmProvider llm;

    public ClassicOnlineSpeechProvider(AsrProvider asr, LlmProvider llm) {
        this.asr = Objects.requireNonNull(asr, "asr");
        this.llm = Objects.requireNonNull(llm, "llm");
    }

    @Override
    public CompletableFuture<OnlineSpeechResult> process(
            byte[] pcm16k, SessionContext context, String utteranceId) {
        return process(pcm16k, context, utteranceId, OnlineAudioSink.NOOP, OnlineAsrSink.NOOP);
    }

    @Override
    public CompletableFuture<OnlineSpeechResult> process(
            byte[] pcm16k, SessionContext context, String utteranceId,
            OnlineAudioSink replySink, OnlineAsrSink asrSink) {
        final String text;
        try {
            text = asr.transcribe(pcm16k, context);
            if (text == null || text.isBlank()) {
                throw new CompletionException(new AsrException("ASR returned blank text"));
            }
            // ASR 一完成立即独立输出，不等待 LLM/NLU，更不等待语义仲裁。
            asrSink.onResult(text, true);
        } catch (Exception e) {
            if (e instanceof CompletionException completion) throw completion;
            throw new CompletionException(e);
        }
        CompletableFuture<com.autovoice.server.contracts.Reply> source =
                llm.chat(text, context, utteranceId);
        CompletableFuture<OnlineSpeechResult> out = new CompletableFuture<>() {
            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                source.cancel(mayInterruptIfRunning);
                return super.cancel(mayInterruptIfRunning);
            }
        };
        source.whenComplete((reply, error) -> {
            if (error != null) out.completeExceptionally(error);
            else out.complete(new OnlineSpeechResult(reply, text));
        });
        return out;
    }

    @Override
    public String id() {
        return "classic";
    }
}
