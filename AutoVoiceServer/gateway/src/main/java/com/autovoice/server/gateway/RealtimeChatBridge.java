package com.autovoice.server.gateway;

import com.autovoice.server.contracts.Intent;
import com.autovoice.server.contracts.RealtimeChatProvider;
import com.autovoice.server.contracts.RealtimeChatSession;
import com.autovoice.server.contracts.RealtimeChatSink;
import com.autovoice.server.contracts.SessionContext;
import org.springframework.web.socket.WebSocketSession;

import java.util.LinkedHashMap;
import java.util.Map;
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
    private final AtomicReference<Generation> active = new AtomicReference<>();
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

    void start() {
        SessionContext snapshot = context.get();
        if (snapshot == null) return;
        requested.set(true);
        if (active.get() != null || !opening.compareAndSet(false, true)) return;
        if (provider == null) {
            opening.set(false);
            error("CHAT_UNSUPPORTED", "realtime chat provider is unavailable");
            return;
        }
        try {
            executor.submit(() -> open(snapshot));
        } catch (RejectedExecutionException rejected) {
            opening.set(false);
            error("CHAT_CONNECT_FAILED", "realtime chat executor is unavailable");
        }
    }

    boolean appendIfActive(byte[] pcm) {
        Generation generation = active.get();
        if (generation == null) return false;
        try {
            generation.session.appendAudio(pcm);
        } catch (RuntimeException failure) {
            error("CHAT_STREAM_FAILED", failure.getMessage());
        }
        return true;
    }

    void finish() {
        requested.set(false);
        closeActive();
    }

    @Override
    public void close() {
        finish();
    }

    private void open(SessionContext snapshot) {
        Generation generation = new Generation();
        try {
            RealtimeChatSession session = provider.openRealtimeChat(snapshot, sink(generation));
            generation.session = java.util.Objects.requireNonNull(session, "realtime chat session");
            if (!requested.get() || generation.closed.get()
                    || !active.compareAndSet(null, generation)) {
                closeQuietly(session);
                return;
            }
            downlink.send(webSocket, "chat_ready", Map.of("sessionId", snapshot.sessionId()));
        } catch (RuntimeException failure) {
            error("CHAT_CONNECT_FAILED", failure.getMessage());
        } finally {
            opening.set(false);
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
                responseSegment = null;
                responseStarted = false;
                SessionContext value = context.get();
                if (value != null) {
                    downlink.send(webSocket, "chat_speech_started",
                            Map.of("sessionId", value.sessionId()));
                }
            }

            @Override public void onUserTranscript(String text, boolean isFinal) {
                if (text == null || text.isBlank()) return;
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("text", text);
                payload.put("isFinal", isFinal);
                payload.put("chat", true);
                downlink.send(webSocket, "asr_partial", payload);
            }

            @Override public void onStart(int sampleRate, int channels, String encoding) {
                responseStarted = true;
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("segmentId", responseSegment());
                payload.put("mime", "audio/pcm");
                payload.put("sampleRate", sampleRate);
                payload.put("channels", channels);
                payload.put("encoding", encoding);
                payload.put("chat", true);
                downlink.send(webSocket, "audio_reply_start", payload);
            }

            @Override public void onChunk(byte[] pcm) {
                if (pcm != null && pcm.length > 0) downlink.sendBinary(webSocket, pcm);
            }

            @Override public void onReplyText(String text, boolean isFinal) {
                if (text == null || text.isBlank()) return;
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("segmentId", responseSegment());
                payload.put("text", text);
                payload.put("isFinal", isFinal);
                payload.put("chat", true);
                downlink.send(webSocket, "reply_partial", payload);
            }

            @Override public void onComplete(String text, Intent intent, String asrText) {
                // Function-only exit_chat still emits a complete empty stream for one client path.
                if (!responseStarted) onStart(24_000, 1, "pcm_s16le");
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("segmentId", responseSegment());
                payload.put("chat", true);
                if (text != null && !text.isBlank()) payload.put("speakText", text);
                if (intent != null) payload.put("intent", intent);
                downlink.send(webSocket, "audio_reply_end", payload);
                responseSegment = null;
                responseStarted = false;
                if (intent != null && "conversation".equals(intent.domain())
                        && "exit_chat".equals(intent.intent())) {
                    finish();
                }
            }

            @Override public void onError(Throwable failure) {
                error("CHAT_STREAM_FAILED", failure == null
                        ? "realtime chat failed" : String.valueOf(failure.getMessage()));
            }

            @Override public void onSessionClosed(Throwable failure) {
                generation.closed.set(true);
                if (active.compareAndSet(generation, null)) {
                    if (failure != null) onError(failure);
                    else error("CHAT_STREAM_CLOSED", "realtime chat closed");
                }
            }
        };
    }

    private void closeActive() {
        Generation generation = active.getAndSet(null);
        if (generation != null && generation.session != null) closeQuietly(generation.session);
    }

    private void error(String code, String message) {
        downlink.sendError(webSocket, context.get(), code,
                message == null ? "unknown realtime chat error" : message, segmentId.get());
    }

    private static void closeQuietly(RealtimeChatSession session) {
        try {
            session.close();
        } catch (RuntimeException ignored) {
        }
    }

    private static final class Generation {
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile RealtimeChatSession session;
    }
}
