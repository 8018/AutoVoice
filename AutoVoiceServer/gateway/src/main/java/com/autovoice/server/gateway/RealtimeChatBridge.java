package com.autovoice.server.gateway;

import com.autovoice.server.contracts.Intent;
import com.autovoice.server.contracts.RealtimeChatProvider;
import com.autovoice.server.contracts.RealtimeChatSession;
import com.autovoice.server.contracts.RealtimeChatSink;
import com.autovoice.server.contracts.SessionContext;
import org.springframework.web.socket.WebSocketSession;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Owns the upstream realtime chat session and maps its events to gateway downlink messages. */
final class RealtimeChatBridge implements AutoCloseable {
    private final WebSocketSession webSocket;
    private final RealtimeChatProvider provider;
    private final ExecutorService executor;
    private final GatewayDownlink downlink;
    private final Supplier<SessionContext> context;
    private final Supplier<String> segmentId;
    private final AtomicBoolean requested = new AtomicBoolean();
    private final AtomicBoolean opening = new AtomicBoolean();
    private final AtomicReference<Generation> openingGeneration = new AtomicReference<>();
    private final AtomicReference<Generation> active = new AtomicReference<>();
    private final AtomicReference<String> requestedChatId = new AtomicReference<>();
    private final AtomicLong responseSequence = new AtomicLong();

    RealtimeChatBridge(WebSocketSession webSocket, RealtimeChatProvider provider,
                       ExecutorService executor, GatewayDownlink downlink,
                       Supplier<SessionContext> context, Supplier<String> segmentId) {
        this.webSocket = java.util.Objects.requireNonNull(webSocket, "webSocket");
        this.provider = provider;
        this.executor = java.util.Objects.requireNonNull(executor, "executor");
        this.downlink = java.util.Objects.requireNonNull(downlink, "downlink");
        this.context = java.util.Objects.requireNonNull(context, "context");
        this.segmentId = java.util.Objects.requireNonNull(segmentId, "segmentId");
    }

    void start(String requestedId) {
        SessionContext snapshot = context.get();
        if (snapshot == null) return;
        String chatId = requestedId == null || requestedId.isBlank()
                ? UUID.randomUUID().toString() : requestedId;
        requestedChatId.set(chatId);
        requested.set(true);
        Generation previous = active.get();
        if (previous != null) {
            if (previous.chatId.equals(chatId)) return;
            closeActive();
        }
        if (!opening.compareAndSet(false, true)) return;
        Generation generation = new Generation(chatId);
        openingGeneration.set(generation);
        if (provider == null) {
            openingGeneration.compareAndSet(generation, null);
            opening.set(false);
            error(generation, "CHAT_UNSUPPORTED", "realtime chat provider is unavailable");
            return;
        }
        try {
            executor.submit(() -> open(snapshot, generation));
        } catch (RejectedExecutionException rejected) {
            openingGeneration.compareAndSet(generation, null);
            opening.set(false);
            error(generation, "CHAT_CONNECT_FAILED", "realtime chat executor is unavailable");
        }
    }

    boolean appendIfActive(byte[] pcm) {
        Generation generation = active.get();
        if (generation == null) return false;
        synchronized (generation) {
            if (generation.closed.get() || active.get() != generation || generation.session == null) return false;
            try {
                generation.session.appendAudio(pcm);
            } catch (RuntimeException failure) {
                error(generation, "CHAT_STREAM_FAILED", failure.getMessage());
            }
        }
        return true;
    }

    void finish() {
        finish(null);
    }

    void finish(String chatId) {
        String current = requestedChatId.get();
        if (chatId != null && !chatId.equals(current)) return;
        requested.set(false);
        requestedChatId.set(null);
        Generation openingNow = openingGeneration.getAndSet(null);
        if (openingNow != null) {
            synchronized (openingNow) { openingNow.closed.set(true); }
        }
        closeActive();
    }

    @Override
    public void close() {
        finish();
    }

    private void open(SessionContext snapshot, Generation generation) {
        try {
            RealtimeChatSession session = provider.openRealtimeChat(snapshot, sink(generation));
            generation.session = java.util.Objects.requireNonNull(session, "realtime chat session");
            synchronized (generation) {
                if (!requested.get() || generation.closed.get()
                        || !generation.chatId.equals(requestedChatId.get())
                        || !active.compareAndSet(null, generation)) {
                    generation.closed.set(true);
                    closeQuietly(session);
                    return;
                }
                downlink.send(webSocket, "chat_ready", Map.of(
                        "sessionId", snapshot.sessionId(), "chatId", generation.chatId));
            }
        } catch (RuntimeException failure) {
            synchronized (generation) {
                if (!generation.closed.get() && generation.chatId.equals(requestedChatId.get())) {
                    error(generation, "CHAT_CONNECT_FAILED", failure.getMessage());
                }
                if (active.compareAndSet(generation, null)) {
                    generation.closed.set(true);
                    if (generation.session != null) closeQuietly(generation.session);
                }
            }
        } finally {
            openingGeneration.compareAndSet(generation, null);
            opening.set(false);
            String next = requestedChatId.get();
            if (next != null && !next.equals(generation.chatId) && active.get() == null) start(next);
        }
    }

    private boolean isLive(Generation generation) {
        return !generation.closed.get()
                && (active.get() == generation || openingGeneration.get() == generation);
    }

    /** Serialize each generation's send with finish/close; raw PCM chunks have no chatId. */
    private void emitIfLive(Generation generation, Runnable send) {
        synchronized (generation) {
            if (isLive(generation)) send.run();
        }
    }

    private RealtimeChatSink sink(Generation generation) {
        return new RealtimeChatSink() {
            private String responseSegment;
            private boolean responseStarted;

            private String responseSegment() {
                if (responseSegment == null) {
                    responseSegment = "chat-" + responseSequence.incrementAndGet();
                }
                return responseSegment;
            }

            @Override public void onUserSpeechStarted() {
                emitIfLive(generation, () -> {
                    responseSegment = null;
                    responseStarted = false;
                    SessionContext value = context.get();
                    if (value != null) {
                        downlink.send(webSocket, "chat_speech_started",
                                Map.of("sessionId", value.sessionId(), "chatId", generation.chatId));
                    }
                });
            }

            @Override public void onUserTranscript(String text, boolean isFinal) {
                if (text == null || text.isBlank()) return;
                emitIfLive(generation, () -> {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("text", text);
                    payload.put("isFinal", isFinal);
                    payload.put("chat", true);
                    payload.put("chatId", generation.chatId);
                    downlink.send(webSocket, "asr_partial", payload);
                });
            }

            @Override public void onStart(int sampleRate, int channels, String encoding) {
                emitIfLive(generation, () -> {
                    responseStarted = true;
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("segmentId", responseSegment());
                    payload.put("mime", "audio/pcm");
                    payload.put("sampleRate", sampleRate);
                    payload.put("channels", channels);
                    payload.put("encoding", encoding);
                    payload.put("chat", true);
                    payload.put("chatId", generation.chatId);
                    downlink.send(webSocket, "audio_reply_start", payload);
                });
            }

            @Override public void onChunk(byte[] pcm) {
                if (pcm != null && pcm.length > 0) {
                    emitIfLive(generation, () -> downlink.sendBinary(webSocket, pcm));
                }
            }

            @Override public void onReplyText(String text, boolean isFinal) {
                if (text == null || text.isBlank()) return;
                emitIfLive(generation, () -> {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("segmentId", responseSegment());
                    payload.put("text", text);
                    payload.put("isFinal", isFinal);
                    payload.put("chat", true);
                    payload.put("chatId", generation.chatId);
                    downlink.send(webSocket, "reply_partial", payload);
                });
            }

            @Override public void onComplete(String text, Intent intent, String asrText) {
                emitIfLive(generation, () -> {
                    // Function-only exit_chat still emits a complete empty stream for one client path.
                    if (!responseStarted) onStart(24_000, 1, "pcm_s16le");
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("segmentId", responseSegment());
                    payload.put("chat", true);
                    payload.put("chatId", generation.chatId);
                    if (text != null && !text.isBlank()) payload.put("speakText", text);
                    if (intent != null) payload.put("intent", intent);
                    downlink.send(webSocket, "audio_reply_end", payload);
                    responseSegment = null;
                    responseStarted = false;
                    if (intent != null && "conversation".equals(intent.domain())
                            && "exit_chat".equals(intent.intent())) {
                        finish(generation.chatId);
                    }
                });
            }

            @Override public void onError(Throwable failure) {
                emitIfLive(generation, () -> error(generation, "CHAT_STREAM_FAILED", failure == null
                        ? "realtime chat failed" : String.valueOf(failure.getMessage())));
            }

            @Override public void onSessionClosed(Throwable failure) {
                synchronized (generation) {
                    boolean openingNow = openingGeneration.get() == generation;
                    generation.closed.set(true);
                    if (active.compareAndSet(generation, null) ||
                            (openingNow && generation.chatId.equals(requestedChatId.get()))) {
                        error(generation, failure != null ? "CHAT_STREAM_FAILED" : "CHAT_STREAM_CLOSED",
                                failure != null ? String.valueOf(failure.getMessage()) : "realtime chat closed");
                    }
                }
            }
        };
    }

    private void closeActive() {
        Generation generation = active.getAndSet(null);
        if (generation != null) {
            synchronized (generation) {
                generation.closed.set(true);
                if (generation.session != null) closeQuietly(generation.session);
            }
        }
    }

    private void error(Generation generation, String code, String message) {
        downlink.sendError(webSocket, context.get(), code,
                message == null ? "unknown realtime chat error" : message, segmentId.get(),
                generation.chatId);
    }

    private static void closeQuietly(RealtimeChatSession session) {
        try {
            session.close();
        } catch (RuntimeException ignored) {
        }
    }

    private static final class Generation {
        private final String chatId;
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile RealtimeChatSession session;

        private Generation(String chatId) { this.chatId = Objects.requireNonNull(chatId); }
    }
}
