package com.autovoice.server.gateway;

import com.autovoice.server.contracts.OnlineSpeechResult;
import com.autovoice.server.contracts.OnlineTextProvider;
import com.autovoice.server.contracts.Reply;
import com.autovoice.server.contracts.SessionContext;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Explicit text input boundary. No PCM, ASR events, or audio-only arbitration is fabricated. */
final class GatewayTextEndpoint {
    static final String CAPABILITY = "text_recognition_v1";
    private static final int MAX_TEXT_LENGTH = 2_000;
    private static final int MAX_ID_LENGTH = 128;
    private static final long PROCESS_TIMEOUT_SECONDS = 60;

    private final OnlineTextProvider provider;

    GatewayTextEndpoint(OnlineTextProvider provider) {
        this.provider = provider;
    }

    boolean supported() {
        return provider != null;
    }

    TextRequest validate(Map<String, Object> payload, SessionContext context) {
        if (!supported()) throw new InvalidTextRequest("UNSUPPORTED_CAPABILITY", "text input is not supported");
        if (context == null) throw new InvalidTextRequest("BAD_SESSION", "hello is required first");
        if (!Objects.equals(payload.get("sessionId"), context.sessionId())) {
            throw new InvalidTextRequest("BAD_SESSION", "sessionId does not match this connection");
        }
        String requestId = identity(payload.get("requestId"), "requestId");
        String utteranceId = identity(payload.get("utteranceId"), "utteranceId");
        String segmentId = identity(payload.get("segmentId"), "segmentId");
        if (!(payload.get("text") instanceof String text) || text.isBlank()
                || text.length() > MAX_TEXT_LENGTH) {
            throw new InvalidTextRequest("BAD_TEXT_REQUEST", "text must contain 1-2000 characters");
        }
        if (!"text".equals(payload.get("inputSource"))
                || !(payload.get("contextVersion") instanceof Number version)
                || version.intValue() != 1 || version.doubleValue() != 1.0
                || !(payload.get("context") instanceof Map<?, ?> extra) || !extra.isEmpty()) {
            throw new InvalidTextRequest("BAD_TEXT_REQUEST", "unsupported input source or context version");
        }
        if (!Objects.equals(payload.get("language"), context.language())) {
            throw new InvalidTextRequest("BAD_TEXT_REQUEST", "language does not match this session");
        }
        return new TextRequest(requestId, utteranceId, segmentId, text.strip());
    }

    SegmentPipeline.SegmentResult process(TextRequest request, SessionContext context,
                                          CompletableFuture<?> stopWaiting) {
        CompletableFuture<OnlineSpeechResult> future = provider.processText(
                request.text(), context, request.utteranceId());
        Object settled = CompletableFuture.anyOf(
                future.orTimeout(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS), stopWaiting).join();
        if (!(settled instanceof OnlineSpeechResult result)) return null;
        Reply reply = result.reply();
        return new SegmentPipeline.SegmentResult(reply.text(), reply.speakText(), reply.intent(), "",
                reply.mime(), reply.data(), false, null, 0L);
    }

    private static String identity(Object value, String field) {
        if (!(value instanceof String id) || id.isBlank() || id.length() > MAX_ID_LENGTH
                || !id.matches("[A-Za-z0-9_-]+")) {
            throw new InvalidTextRequest("BAD_TEXT_REQUEST", field + " must be a simple nonblank ID");
        }
        return id;
    }

    record TextRequest(String requestId, String utteranceId, String segmentId, String text) {}

    static final class InvalidTextRequest extends IllegalArgumentException {
        private final String code;

        InvalidTextRequest(String code, String message) {
            super(message);
            this.code = code;
        }

        String code() { return code; }
    }
}
