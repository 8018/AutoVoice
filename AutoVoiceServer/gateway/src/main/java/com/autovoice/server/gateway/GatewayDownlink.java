package com.autovoice.server.gateway;

import com.autovoice.server.contracts.SessionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** Serializes all protocol downlink writes for one or more WebSocket sessions. */
final class GatewayDownlink {
    static final String PENDING_TEXT = "正在处理，请稍候";
    private static final CloseStatus POLICY_CLOSE = new CloseStatus(4001, "policy violation");
    private static final Logger LOG = LoggerFactory.getLogger(GatewayDownlink.class);

    void send(WebSocketSession session, String type, Map<String, Object> payload) {
        try {
            // Spring's raw WebSocketSession does not guarantee concurrent send safety.
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(GatewayCodec.encode(type, payload)));
                }
            }
        } catch (IOException error) {
            throw new IllegalStateException("failed to send " + type + " message", error);
        }
    }

    void sendBinary(WebSocketSession session, byte[] bytes) {
        try {
            synchronized (session) {
                if (session.isOpen()) session.sendMessage(new BinaryMessage(bytes));
            }
        } catch (IOException error) {
            throw new IllegalStateException("failed to send audio chunk", error);
        }
    }

    void sendReply(WebSocketSession session, SegmentPipeline.SegmentResult result,
                   String segmentId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (result.asrText() != null && !result.asrText().isBlank()) {
            payload.put("asrText", result.asrText());
        }
        if (result.audio() != null) {
            payload.put("kind", "audio");
            payload.put("mime", result.mime());
            payload.put("dataBase64", Base64.getEncoder().encodeToString(result.audio()));
            if (result.speakText() != null && !result.speakText().isBlank()) {
                payload.put("speakText", result.speakText());
            }
            if (result.intent() != null) payload.put("intent", result.intent());
        } else if (result.intent() != null) {
            payload.put("kind", "action");
            payload.put("intent", result.intent());
            if (result.speakText() != null) payload.put("speakText", result.speakText());
        } else {
            payload.put("kind", "text");
            if (result.text() != null) payload.put("text", result.text());
            if (result.speakText() != null) payload.put("speakText", result.speakText());
        }
        if (segmentId != null) payload.put("segmentId", segmentId);
        if (result.actionId() != null) {
            payload.put("actionId", result.actionId());
            // D14c:随回复下发支持窗口截止时刻(客户端据此明确拒绝过期动作)
            if (result.actionExpiresAtMs() > 0) {
                payload.put("actionExpiresAtMs", result.actionExpiresAtMs());
            }
        }
        send(session, "reply", payload);
    }

    void sendPending(WebSocketSession session, String segmentId) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            if (segmentId != null) payload.put("segmentId", segmentId);
            payload.put("text", PENDING_TEXT);
            send(session, "pending", payload);
        } catch (RuntimeException error) {
            LOG.warn("pending downlink failed (session closing?): {}", error.getMessage());
        }
    }

    void sendError(WebSocketSession session, SessionContext context,
                   String code, String message, String segmentId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (context != null) payload.put("sessionId", context.sessionId());
        if (segmentId != null) payload.put("segmentId", segmentId);
        payload.put("code", code);
        payload.put("message", message);
        send(session, "error", payload);
    }

    void closePolicy(WebSocketSession session, String reason) {
        try {
            if (session.isOpen()) {
                session.close(new CloseStatus(POLICY_CLOSE.getCode(), reason));
            }
        } catch (IOException error) {
            LOG.warn("failed to close {}: {}", session.getId(), error.getMessage());
        }
    }
}
