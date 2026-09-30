package com.autovoice.server.speechclassic;

import com.autovoice.server.contracts.AsrException;
import com.autovoice.server.contracts.AsrProvider;
import com.autovoice.server.contracts.DialogueControlNlu;
import com.autovoice.server.contracts.LlmProvider;
import com.autovoice.server.contracts.NavigationDialog;
import com.autovoice.server.contracts.OnlineSpeechProvider;
import com.autovoice.server.contracts.OnlineSpeechResult;
import com.autovoice.server.contracts.OnlineSpeechStream;
import com.autovoice.server.contracts.OnlineTextProvider;
import com.autovoice.server.contracts.OnlineAudioSink;
import com.autovoice.server.contracts.OnlineAsrSink;
import com.autovoice.server.contracts.Reply;
import com.autovoice.server.contracts.SessionContext;
import com.autovoice.server.contracts.StreamingAsrProvider;
import com.autovoice.server.contracts.StreamingAsrSession;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

/** 现有在线链路适配器：PCM → ASR → DeepSeek；不改变原有请求和工具循环。 */
public final class ClassicOnlineSpeechProvider implements OnlineSpeechProvider, OnlineTextProvider {

    private final AsrProvider asr;
    private final LlmProvider llm;
    private final NavigationDialog navigationDialog;

    public ClassicOnlineSpeechProvider(AsrProvider asr, LlmProvider llm) {
        this(asr, llm, NavigationDialog.NONE);
    }

    public ClassicOnlineSpeechProvider(AsrProvider asr, LlmProvider llm,
                                       NavigationDialog navigationDialog) {
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
            asrSink.onError(e);
            if (e instanceof CompletionException completion) throw completion;
            throw new CompletionException(e);
        }
        return completeFromText(text, context, utteranceId, text);
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
                return session.finishWithin(streaming.finishTimeoutMs(), TimeUnit.MILLISECONDS)
                        .thenCompose(text -> {
                    if (text == null || text.isBlank()) {
                        return CompletableFuture.failedFuture(new AsrException("ASR returned blank text"));
                    }
                    return completeFromText(text, context, utteranceId, text);
                });
            }
            @Override public void cancel() { session.cancel(); }
        };
    }

    @Override
    public CompletableFuture<OnlineSpeechResult> processText(
            String text, SessionContext context, String utteranceId) {
        if (text == null || text.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("text input is blank"));
        }
        return completeFromText(text, context, utteranceId, "");
    }

    private CompletableFuture<OnlineSpeechResult> completeFromText(
            String text, SessionContext context, String utteranceId, String asrText) {
        CompletableFuture<com.autovoice.server.contracts.Reply> source = DialogueControlNlu
                .understand(text)
                .map(intent -> CompletableFuture.completedFuture(
                        Reply.ofAction(intent, "好的，已退出当前对话")))
                .orElseGet(() -> navigationDialog.complete(
                        context, text, () -> llm.chat(text, context, utteranceId)));
        CompletableFuture<OnlineSpeechResult> out = new CompletableFuture<>() {
            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                source.cancel(mayInterruptIfRunning);
                return super.cancel(mayInterruptIfRunning);
            }
        };
        source.whenComplete((reply, error) -> {
            if (error != null) out.completeExceptionally(error);
            else out.complete(new OnlineSpeechResult(reply, asrText));
        });
        return out;
    }

    @Override
    public String id() {
        return "classic";
    }
}
