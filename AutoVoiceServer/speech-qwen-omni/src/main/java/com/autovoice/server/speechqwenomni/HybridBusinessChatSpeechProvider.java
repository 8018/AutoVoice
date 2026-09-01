package com.autovoice.server.speechqwenomni;

import com.autovoice.server.contracts.AsrProvider;
import com.autovoice.server.contracts.LlmProvider;
import com.autovoice.server.contracts.NavigationDialogState;
import com.autovoice.server.contracts.OnlineAsrSink;
import com.autovoice.server.contracts.OnlineAudioSink;
import com.autovoice.server.contracts.OnlineSpeechProvider;
import com.autovoice.server.contracts.OnlineSpeechResult;
import com.autovoice.server.contracts.OnlineSpeechStream;
import com.autovoice.server.contracts.Reply;
import com.autovoice.server.contracts.RealtimeChatProvider;
import com.autovoice.server.contracts.RealtimeChatSession;
import com.autovoice.server.contracts.RealtimeChatSink;
import com.autovoice.server.contracts.SessionContext;
import com.autovoice.server.contracts.StreamingAsrProvider;
import com.autovoice.server.contracts.StreamingAsrSession;
import com.autovoice.server.contracts.Intent;

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
public final class HybridBusinessChatSpeechProvider implements OnlineSpeechProvider, RealtimeChatProvider {

    public static final String ENTER_CHAT_PHRASE = "陪我聊会天";
    public static final String ENTER_CHAT_REPLY = "好呀，想聊什么？";
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
    private final QwenOmniRealtimeChatProvider realtimeChat;
    private final NavigationDialogState navigationDialog;
    private final Set<String> chatSessions = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, CompletableFuture<OnlineSpeechResult>> active =
            new ConcurrentHashMap<>();

    public HybridBusinessChatSpeechProvider(AsrProvider asr, LlmProvider businessLlm,
                                            OnlineSpeechProvider chatSpeech,
                                            NavigationDialogState navigationDialog) {
        this(asr, businessLlm, chatSpeech, navigationDialog, null);
    }

    public HybridBusinessChatSpeechProvider(AsrProvider asr, LlmProvider businessLlm,
                                            OnlineSpeechProvider chatSpeech,
                                            NavigationDialogState navigationDialog,
                                            QwenOmniRealtimeChatProvider realtimeChat) {
        this.asr = asr;
        this.businessLlm = businessLlm;
        this.chatSpeech = chatSpeech;
        this.navigationDialog = navigationDialog;
        this.realtimeChat = realtimeChat;
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
        if (isChatting(context)) {
            return track(utteranceId, processChat(pcm16k, context, utteranceId, audioSink, null));
        }
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
            asrSink.onTurnEstablished();
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

    @Override
    public OnlineSpeechStream openStream(SessionContext context, String utteranceId,
                                         OnlineAudioSink audioSink, OnlineAsrSink asrSink) {
        if (isChatting(context) || !(asr instanceof StreamingAsrProvider streaming)) {
            return null;
        }
        StreamingAsrSession session = streaming.start(context, asrSink);
        java.io.ByteArrayOutputStream pcm = new java.io.ByteArrayOutputStream();
        return new OnlineSpeechStream() {
            @Override public synchronized void append(byte[] chunk) {
                if (chunk == null || chunk.length == 0) return;
                pcm.writeBytes(chunk);
                session.append(chunk);
            }
            @Override public CompletableFuture<OnlineSpeechResult> finish() {
                return session.finish().thenCompose(text -> {
                    if (text == null || text.isBlank()) {
                        return CompletableFuture.failedFuture(new IllegalStateException("ASR returned blank text"));
                    }
                    return track(utteranceId, route(pcm.toByteArray(), context, utteranceId,
                            text.trim(), audioSink));
                });
            }
            @Override public void cancel() { session.cancel(); }
        };
    }

    private CompletableFuture<OnlineSpeechResult> track(
            String utteranceId, CompletableFuture<OnlineSpeechResult> future) {
        if (utteranceId != null && !utteranceId.isBlank()) {
            active.put(utteranceId, future);
            future.whenComplete((ignored, error) -> active.remove(utteranceId, future));
        }
        return future;
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
            // 入口只切域，不再调用普通 qwen3.5-omni-plus HTTP 模型。客户端收到控制意图后
            // 立即建立 qwen3.5-omni-plus-realtime 长会话，后续音频全走该连接。
            return CompletableFuture.completedFuture(new OnlineSpeechResult(
                    Reply.ofAction(enterChatIntent(), ENTER_CHAT_REPLY), transcript));
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

    private CompletableFuture<OnlineSpeechResult> processChat(
            byte[] pcm16k, SessionContext context, String utteranceId,
            OnlineAudioSink audioSink, Intent forcedIntent) {
        OnlineAudioSink controlled = controlSink(audioSink, forcedIntent, context);
        return chatSpeech.process(pcm16k, context, utteranceId, controlled).thenApply(result -> {
            Reply reply = forcedIntent == null ? result.reply() : withIntent(result.reply(), forcedIntent);
            if (isConversationIntent(reply.intent(), "exit_chat")) {
                chatSessions.remove(sessionKey(context));
            }
            return new OnlineSpeechResult(reply, "");
        });
    }

    private OnlineAudioSink controlSink(OnlineAudioSink downstream, Intent forcedIntent,
                                        SessionContext context) {
        OnlineAudioSink sink = downstream == null ? OnlineAudioSink.NOOP : downstream;
        return new OnlineAudioSink() {
            @Override public void onStart(int rate, int channels, String encoding) {
                sink.onStart(rate, channels, encoding);
            }
            @Override public void onChunk(byte[] pcm) { sink.onChunk(pcm); }
            @Override public void onReplyText(String text, boolean isFinal) {
                sink.onReplyText(text, isFinal);
            }
            @Override public void onComplete(String text, Intent intent, String asrText) {
                Intent control = forcedIntent == null ? intent : forcedIntent;
                if (isConversationIntent(control, "exit_chat")) {
                    // 流结束事件发出前先解除服务端锁域；下一段立即恢复业务链。
                    chatSessions.remove(sessionKey(context));
                }
                sink.onComplete(text, control, "");
            }
            @Override public void onError(Throwable error) { sink.onError(error); }
        };
    }

    private static Reply withIntent(Reply reply, Intent intent) {
        if ("audio".equals(reply.kind())) {
            return Reply.ofAudio(reply.mime(), reply.data(), reply.speakText(), intent);
        }
        String text = reply.speakText() == null ? reply.text() : reply.speakText();
        return Reply.ofAction(intent, text == null ? "" : text);
    }

    private static Intent enterChatIntent() {
        return Intent.of("1.0", "conversation", "enter_chat", java.util.Map.of(), 1.0,
                "hybrid-chat-router", null);
    }

    private static boolean isConversationIntent(Intent intent, String action) {
        return intent != null && "conversation".equals(intent.domain()) && action.equals(intent.intent());
    }

    private static String sessionKey(SessionContext context) {
        return context == null || context.sessionId() == null ? "" : context.sessionId();
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
        return chatSessions.contains(sessionKey(context));
    }

    @Override
    public RealtimeChatSession openRealtimeChat(SessionContext context, RealtimeChatSink sink) {
        if (!isChatting(context)) {
            throw new IllegalStateException("session is not in chat domain");
        }
        if (realtimeChat == null) {
            throw new IllegalStateException("Qwen Realtime chat is not configured");
        }
        RealtimeChatSink controlled = new RealtimeChatSink() {
            @Override public void onUserSpeechStarted() { sink.onUserSpeechStarted(); }
            @Override public void onStart(int rate, int channels, String encoding) {
                sink.onStart(rate, channels, encoding);
            }
            @Override public void onChunk(byte[] pcm) { sink.onChunk(pcm); }
            @Override public void onReplyText(String text, boolean isFinal) {
                sink.onReplyText(text, isFinal);
            }
            @Override public void onComplete(String text, Intent intent, String asrText) {
                if (isConversationIntent(intent, "exit_chat")) {
                    chatSessions.remove(sessionKey(context));
                }
                sink.onComplete(text, intent, "");
            }
            @Override public void onError(Throwable error) { sink.onError(error); }
            @Override public void onSessionClosed(Throwable error) { sink.onSessionClosed(error); }
        };
        return realtimeChat.open(context, controlled);
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
