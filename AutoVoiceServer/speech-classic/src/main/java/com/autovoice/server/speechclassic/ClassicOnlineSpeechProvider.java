package com.autovoice.server.speechclassic;

import com.autovoice.server.contracts.AsrException;
import com.autovoice.server.contracts.AsrProvider;
import com.autovoice.server.contracts.LlmProvider;
import com.autovoice.server.contracts.NavigationDialogState;
import com.autovoice.server.contracts.OnlineSpeechProvider;
import com.autovoice.server.contracts.OnlineSpeechResult;
import com.autovoice.server.contracts.OnlineSpeechStream;
import com.autovoice.server.contracts.OnlineAudioSink;
import com.autovoice.server.contracts.OnlineAsrSink;
import com.autovoice.server.contracts.SessionContext;
import com.autovoice.server.contracts.StreamingAsrProvider;
import com.autovoice.server.contracts.StreamingAsrSession;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** 现有在线链路适配器：PCM → ASR → DeepSeek；不改变原有请求和工具循环。 */
public final class ClassicOnlineSpeechProvider implements OnlineSpeechProvider {

    private final AsrProvider asr;
    private final LlmProvider llm;
    private final NavigationDialogState navigationDialog;

    public ClassicOnlineSpeechProvider(AsrProvider asr, LlmProvider llm) {
        this(asr, llm, new NavigationDialogState());
    }

    public ClassicOnlineSpeechProvider(AsrProvider asr, LlmProvider llm,
                                       NavigationDialogState navigationDialog) {
        this.asr = Objects.requireNonNull(asr, "asr");
        this.llm = Objects.requireNonNull(llm, "llm");
        this.navigationDialog = Objects.requireNonNull(navigationDialog, "navigationDialog");
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
            asrSink.onTurnEstablished();
            asrSink.onResult(text, true);
        } catch (Exception e) {
            if (e instanceof CompletionException completion) throw completion;
            throw new CompletionException(e);
        }
        return completeFromText(text, context, utteranceId);
    }

    @Override
    public OnlineSpeechStream openStream(SessionContext context, String utteranceId,
                                         OnlineAudioSink audioSink, OnlineAsrSink asrSink) {
        if (!(asr instanceof StreamingAsrProvider streaming)) {
            return null;
        }
        StreamingAsrSession session = streaming.start(context, asrSink);
        return new OnlineSpeechStream() {
            @Override public void append(byte[] pcm16k) { session.append(pcm16k); }
            @Override public CompletableFuture<OnlineSpeechResult> finish() {
                return session.finish().thenCompose(text -> {
                    if (text == null || text.isBlank()) {
                        return CompletableFuture.failedFuture(new AsrException("ASR returned blank text"));
                    }
                    return completeFromText(text, context, utteranceId);
                });
            }
            @Override public void cancel() { session.cancel(); }
        };
    }

    private CompletableFuture<OnlineSpeechResult> completeFromText(
            String text, SessionContext context, String utteranceId) {
        CompletableFuture<com.autovoice.server.contracts.Reply> source = navigationDialog
                .resolve(context, text)
                .map(CompletableFuture::completedFuture)
                .orElseGet(() -> llm.chat(text, context, utteranceId));
        CompletableFuture<OnlineSpeechResult> out = new CompletableFuture<>() {
            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                source.cancel(mayInterruptIfRunning);
                return super.cancel(mayInterruptIfRunning);
            }
        };
        source.whenComplete((reply, error) -> {
            if (error != null) out.completeExceptionally(error);
            else {
                out.complete(new OnlineSpeechResult(navigationDialog.remember(context, reply), text));
            }
        });
        return out;
    }

    @Override
    public String id() {
        return "classic";
    }
}
