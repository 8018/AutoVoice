package com.autovoice.server.speechqwenomni;

import com.autovoice.server.contracts.AsrProvider;
import com.autovoice.server.contracts.Intent;
import com.autovoice.server.contracts.OnlineAudioSink;
import com.autovoice.server.contracts.OnlineAsrSink;
import com.autovoice.server.contracts.OnlineSpeechProvider;
import com.autovoice.server.contracts.OnlineSpeechResult;
import com.autovoice.server.contracts.SessionContext;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Omni 音频回答旁路 ASR：同一份 PCM 并发进入 Qwen 和 ASR。Qwen 负责回答/工具，
 * ASR 只生成端侧识别框所需的用户原话，不参与语义与仲裁。
 */
public final class TranscriptEnrichedSpeechProvider implements OnlineSpeechProvider {

    private static final AtomicInteger WORKER = new AtomicInteger();
    private static final ExecutorService ASR_WORKERS = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "omni-transcript-" + WORKER.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });

    private final OnlineSpeechProvider speech;
    private final AsrProvider asr;

    public TranscriptEnrichedSpeechProvider(OnlineSpeechProvider speech, AsrProvider asr) {
        this.speech = speech;
        this.asr = asr;
    }

    @Override
    public CompletableFuture<OnlineSpeechResult> process(
            byte[] pcm16k, SessionContext context, String utteranceId) {
        return process(pcm16k, context, utteranceId, OnlineAudioSink.NOOP, OnlineAsrSink.NOOP);
    }

    @Override
    public CompletableFuture<OnlineSpeechResult> process(
            byte[] pcm16k, SessionContext context, String utteranceId, OnlineAudioSink downstream) {
        return process(pcm16k, context, utteranceId, downstream, OnlineAsrSink.NOOP);
    }

    @Override
    public CompletableFuture<OnlineSpeechResult> process(
            byte[] pcm16k, SessionContext context, String utteranceId,
            OnlineAudioSink downstream, OnlineAsrSink asrSink) {
        CompletableFuture<String> transcript = CompletableFuture.supplyAsync(() -> {
            try {
                String text = asr.transcribe(pcm16k, context);
                return text == null ? "" : text.trim();
            } catch (RuntimeException ignored) {
                // 识别框是旁路能力；失败不能打断 S2S 回答。
                return "";
            }
        }, ASR_WORKERS).thenApply(text -> {
            if (!text.isBlank()) asrSink.onResult(text, true);
            return text;
        });
        AtomicReference<StreamEnd> streamEnd = new AtomicReference<>();
        OnlineAudioSink bufferingSink = new OnlineAudioSink() {
            @Override public void onStart(int rate, int channels, String encoding) {
                downstream.onStart(rate, channels, encoding);
            }
            @Override public void onChunk(byte[] pcm) { downstream.onChunk(pcm); }
            @Override public void onReplyText(String text, boolean isFinal) {
                downstream.onReplyText(text, isFinal);
            }
            @Override public void onComplete(String text, Intent intent) {
                streamEnd.set(new StreamEnd(text, intent));
            }
            @Override public void onError(Throwable error) { downstream.onError(error); }
        };
        CompletableFuture<OnlineSpeechResult> speechResult =
                speech.process(pcm16k, context, utteranceId, bufferingSink);
        CompletableFuture<OnlineSpeechResult> combined = new CompletableFuture<>() {
            @Override public boolean cancel(boolean mayInterruptIfRunning) {
                speech.cancel(utteranceId);
                speechResult.cancel(mayInterruptIfRunning);
                transcript.cancel(mayInterruptIfRunning);
                return super.cancel(mayInterruptIfRunning);
            }
        };
        speechResult.thenCombine(transcript, (result, asrText) -> {
            StreamEnd end = streamEnd.get();
            if (end != null) downstream.onComplete(end.speakText, end.intent, asrText);
            return new OnlineSpeechResult(result.reply(), asrText);
        }).whenComplete((result, error) -> {
            if (error == null) combined.complete(result);
            else combined.completeExceptionally(unwrap(error));
        });
        return combined;
    }

    @Override public String id() { return speech.id() + "+transcript"; }

    @Override public long minimumTurnTimeoutMs() { return speech.minimumTurnTimeoutMs(); }

    @Override public void cancel(String utteranceId) { speech.cancel(utteranceId); }

    private static Throwable unwrap(Throwable error) {
        return error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
    }

    private record StreamEnd(String speakText, Intent intent) {}
}
