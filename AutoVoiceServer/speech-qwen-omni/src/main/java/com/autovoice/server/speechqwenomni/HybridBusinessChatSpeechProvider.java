package com.autovoice.server.speechqwenomni;

import com.autovoice.server.contracts.AsrProvider;
import com.autovoice.server.contracts.LlmProvider;
import com.autovoice.server.contracts.NavigationDialogState;
import com.autovoice.server.contracts.OnlineAsrSink;
import com.autovoice.server.contracts.OnlineAudioSink;
import com.autovoice.server.contracts.OnlineSpeechProvider;
import com.autovoice.server.contracts.OnlineSpeechResult;
import com.autovoice.server.contracts.Reply;
import com.autovoice.server.contracts.SessionContext;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 混合在线链路：ASR 先决定会话域；默认业务域走文本 LLM，只有明确口令进入后才把
 * 原始音频交给 Qwen S2S 闲聊。两个模型不共享 prompt、Skill 或工具执行权限。
 */
public final class HybridBusinessChatSpeechProvider implements OnlineSpeechProvider {

    public static final String ENTER_CHAT_PHRASE = "陪我聊会天";
    public static final String EXIT_CHAT_REPLY = "好的，已退出闲聊";
    private static final int MAX_CHAT_SESSIONS = 1_000;
    private static final AtomicInteger WORKER = new AtomicInteger();
    private static final ExecutorService ASR_WORKERS = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "hybrid-route-asr-" + WORKER.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });
    private static final Set<String> EXIT_PHRASES = Set.of(
            "退出闲聊", "结束闲聊", "不聊了", "先不聊了", "停止聊天", "结束聊天");

    private final AsrProvider asr;
    private final LlmProvider businessLlm;
    private final OnlineSpeechProvider chatSpeech;
    private final NavigationDialogState navigationDialog;
    private final Set<String> chatSessions = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, CompletableFuture<OnlineSpeechResult>> active =
            new ConcurrentHashMap<>();

    public HybridBusinessChatSpeechProvider(AsrProvider asr, LlmProvider businessLlm,
                                            OnlineSpeechProvider chatSpeech,
                                            NavigationDialogState navigationDialog) {
        this.asr = asr;
        this.businessLlm = businessLlm;
        this.chatSpeech = chatSpeech;
        this.navigationDialog = navigationDialog;
    }

    @Override
    public CompletableFuture<OnlineSpeechResult> process(byte[] pcm16k, SessionContext context,
                                                          String utteranceId) {
        return process(pcm16k, context, utteranceId, OnlineAudioSink.NOOP, OnlineAsrSink.NOOP);
    }

    @Override
    public CompletableFuture<OnlineSpeechResult> process(byte[] pcm16k, SessionContext context,
                                                          String utteranceId,
                                                          OnlineAudioSink audioSink,
                                                          OnlineAsrSink asrSink) {
        AtomicReference<CompletableFuture<?>> stage = new AtomicReference<>();
        CompletableFuture<OnlineSpeechResult> out = new CompletableFuture<>() {
            @Override public boolean cancel(boolean mayInterruptIfRunning) {
                CompletableFuture<?> running = stage.get();
                if (running != null) running.cancel(mayInterruptIfRunning);
                if (utteranceId != null) chatSpeech.cancel(utteranceId);
                return super.cancel(mayInterruptIfRunning);
            }
        };
        CompletableFuture<String> transcript = CompletableFuture.supplyAsync(() -> {
            String text = asr.transcribe(pcm16k, context);
            return text == null ? "" : text.trim();
        }, ASR_WORKERS);
        stage.set(transcript);
        transcript.whenComplete((text, asrError) -> {
            if (asrError != null) {
                out.completeExceptionally(asrError);
                return;
            }
            if (text.isBlank()) {
                out.completeExceptionally(new IllegalStateException("ASR returned blank text"));
                return;
            }
            asrSink.onResult(text, true);
            CompletableFuture<OnlineSpeechResult> routed = route(
                    pcm16k, context, utteranceId, text, audioSink);
            stage.set(routed);
            routed.whenComplete((result, error) -> {
                if (error != null) out.completeExceptionally(error);
                else out.complete(result);
            });
        });
        if (utteranceId != null && !utteranceId.isBlank()) {
            active.put(utteranceId, out);
            out.whenComplete((ignored, error) -> active.remove(utteranceId, out));
        }
        return out;
    }

    private CompletableFuture<OnlineSpeechResult> route(byte[] pcm16k, SessionContext context,
                                                         String utteranceId, String transcript,
                                                         OnlineAudioSink audioSink) {
        String key = context == null || context.sessionId() == null ? "" : context.sessionId();
        String normalized = normalize(transcript);
        if (isExit(normalized) && chatSessions.remove(key)) {
            return CompletableFuture.completedFuture(
                    new OnlineSpeechResult(Reply.ofText(EXIT_CHAT_REPLY), transcript));
        }
        if (isEnter(normalized)) {
            if (chatSessions.size() >= MAX_CHAT_SESSIONS) chatSessions.clear();
            chatSessions.add(key);
        }
        if (chatSessions.contains(key)) {
            return chatSpeech.process(pcm16k, context, utteranceId, audioSink)
                    .thenApply(result -> new OnlineSpeechResult(result.reply(), transcript));
        }
        Optional<Reply> deterministic = navigationDialog.resolve(context, transcript);
        CompletableFuture<Reply> reply = deterministic
                .map(CompletableFuture::completedFuture)
                .orElseGet(() -> businessLlm.chat(transcript, context, utteranceId));
        return reply.thenApply(value -> {
            navigationDialog.remember(context, value);
            return new OnlineSpeechResult(value, transcript);
        });
    }

    private static boolean isEnter(String text) {
        return text.contains(ENTER_CHAT_PHRASE) || text.contains("陪我聊聊天")
                || text.contains("进入闲聊");
    }

    private static boolean isExit(String text) {
        return EXIT_PHRASES.stream().anyMatch(text::contains);
    }

    private static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[\\s，。！？、,.!?;；:：]", "");
    }

    boolean isChatting(SessionContext context) {
        return context != null && chatSessions.contains(context.sessionId());
    }

    @Override public String id() { return "deepseek-business+qwen-omni-chat"; }

    @Override public long minimumTurnTimeoutMs() { return chatSpeech.minimumTurnTimeoutMs(); }

    @Override public void cancel(String utteranceId) {
        if (utteranceId == null) return;
        CompletableFuture<OnlineSpeechResult> future = active.remove(utteranceId);
        if (future != null) future.cancel(true);
        chatSpeech.cancel(utteranceId);
    }
}
