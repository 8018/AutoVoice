package com.autovoice.server.speechqwenomni;

import com.autovoice.server.contracts.AsrProvider;
import com.autovoice.server.contracts.Intent;
import com.autovoice.server.contracts.NavigationDialogState;
import com.autovoice.server.contracts.OnlineAudioSink;
import com.autovoice.server.contracts.OnlineAsrSink;
import com.autovoice.server.contracts.OnlineSpeechProvider;
import com.autovoice.server.contracts.OnlineSpeechResult;
import com.autovoice.server.contracts.SessionContext;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

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
    private static final ScheduledExecutorService AUDIO_GATE_TIMER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "omni-transcript-audio-gate");
                thread.setDaemon(true);
                return thread;
            });

    /** 从 Qwen 首个音频事件起最多等旁路 ASR 的时间；超时优先保住首音频。 */
    static final long MAX_TRANSCRIPT_FIRST_AUDIO_HOLD_MS = 800;

    private final OnlineSpeechProvider speech;
    private final AsrProvider asr;
    private final NavigationDialogState navigationDialog;

    public TranscriptEnrichedSpeechProvider(OnlineSpeechProvider speech, AsrProvider asr) {
        this(speech, asr, new NavigationDialogState());
    }

    public TranscriptEnrichedSpeechProvider(OnlineSpeechProvider speech, AsrProvider asr,
                                            NavigationDialogState navigationDialog) {
        this.speech = speech;
        this.asr = asr;
        this.navigationDialog = navigationDialog;
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
        TranscriptFirstAudioGate transcriptGate = new TranscriptFirstAudioGate(
                downstream, navigationDialog.hasPending(context));
        CompletableFuture<String> transcript = CompletableFuture.supplyAsync(() -> {
            try {
                String text = asr.transcribe(pcm16k, context);
                return text == null ? "" : text.trim();
            } catch (RuntimeException ignored) {
                // 识别框是旁路能力；失败不能打断 S2S 回答。
                return "";
            }
        }, ASR_WORKERS);
        CompletableFuture<TranscriptDecision> transcriptDecision = transcript.thenApply(text -> {
            if (!text.isBlank()) asrSink.onResult(text, true);
            java.util.Optional<com.autovoice.server.contracts.Reply> selection =
                    navigationDialog.resolve(context, text);
            // asr_partial 调用先于音频门释放或丢弃，保证同一 WS 上识别文本先上屏。
            if (selection.isPresent()) transcriptGate.discard();
            else transcriptGate.release();
            return new TranscriptDecision(text, selection);
        });
        AtomicReference<StreamEnd> streamEnd = new AtomicReference<>();
        OnlineAudioSink bufferingSink = new OnlineAudioSink() {
            @Override public void onStart(int rate, int channels, String encoding) {
                transcriptGate.onStart(rate, channels, encoding);
            }
            @Override public void onChunk(byte[] pcm) { transcriptGate.onChunk(pcm); }
            @Override public void onReplyText(String text, boolean isFinal) {
                transcriptGate.onReplyText(text, isFinal);
            }
            @Override public void onComplete(String text, Intent intent) {
                streamEnd.set(new StreamEnd(text, intent));
            }
            @Override public void onError(Throwable error) { transcriptGate.onError(error); }
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
        // 明确的序号/名称在 ASR 完成后即可成为在线候选。Qwen 仍在后台自然完成，
        // 但其输出被门拦截，不主动取消模型或任何仲裁候选。
        transcriptDecision.thenAccept(decision -> decision.selection.ifPresent(reply ->
                combined.complete(new OnlineSpeechResult(reply, decision.text))));
        speechResult.thenCombine(transcriptDecision, (result, decision) -> {
            if (decision.selection.isPresent()) {
                return new OnlineSpeechResult(decision.selection.get(), decision.text);
            }
            navigationDialog.remember(context, result.reply());
            StreamEnd end = streamEnd.get();
            if (end != null) downstream.onComplete(end.speakText, end.intent, decision.text);
            return new OnlineSpeechResult(result.reply(), decision.text);
        }).whenComplete((result, error) -> {
            if (error == null) combined.complete(result);
            else if (!combined.isDone()) combined.completeExceptionally(unwrap(error));
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
    private record TranscriptDecision(
            String text, java.util.Optional<com.autovoice.server.contracts.Reply> selection) {}

    /**
     * 旁路 ASR 首字幕门：Qwen 仍与 ASR 完全并发，只暂存下行音频/回复字幕。
     * ASR 完成时先发 asr_partial 再释放；超过有限窗口则自动放行音频。
     * 这是输出门控，不取消 Qwen、ASR 或任何仲裁候选。
     */
    private static final class TranscriptFirstAudioGate implements OnlineAudioSink {
        private final OnlineAudioSink downstream;
        private final boolean holdUntilTranscript;
        private final List<Consumer<OnlineAudioSink>> buffered = new ArrayList<>();
        private boolean released;
        private boolean discarded;
        private boolean timerStarted;

        TranscriptFirstAudioGate(OnlineAudioSink downstream) {
            this(downstream, false);
        }

        TranscriptFirstAudioGate(OnlineAudioSink downstream, boolean holdUntilTranscript) {
            this.downstream = downstream == null ? OnlineAudioSink.NOOP : downstream;
            this.holdUntilTranscript = holdUntilTranscript;
        }

        void release() {
            synchronized (this) {
                if (released || discarded) return;
                released = true;
                buffered.forEach(event -> event.accept(downstream));
                buffered.clear();
            }
        }

        void discard() {
            synchronized (this) {
                if (released || discarded) return;
                discarded = true;
                buffered.clear();
            }
        }

        private void submit(Consumer<OnlineAudioSink> event, boolean startsAudioTimer) {
            synchronized (this) {
                if (discarded) return;
                if (released) {
                    event.accept(downstream);
                    return;
                }
                buffered.add(event);
                if (startsAudioTimer && !holdUntilTranscript && !timerStarted) {
                    timerStarted = true;
                    AUDIO_GATE_TIMER.schedule(this::release,
                            MAX_TRANSCRIPT_FIRST_AUDIO_HOLD_MS, TimeUnit.MILLISECONDS);
                }
            }
        }

        @Override public void onStart(int rate, int channels, String encoding) {
            submit(sink -> sink.onStart(rate, channels, encoding), true);
        }

        @Override public void onChunk(byte[] pcm) {
            byte[] snapshot = pcm.clone();
            submit(sink -> sink.onChunk(snapshot), true);
        }

        @Override public void onReplyText(String text, boolean isFinal) {
            submit(sink -> sink.onReplyText(text, isFinal), false);
        }

        @Override public void onError(Throwable error) {
            synchronized (this) {
                if (discarded) return;
                if (!released) {
                    released = true;
                    buffered.clear();
                }
            }
            downstream.onError(error);
        }
    }
}
