package com.autovoice.server.gateway;

import com.autovoice.server.contracts.Reply;
import com.autovoice.server.contracts.SessionContext;
import com.autovoice.server.contracts.TtsProvider;
import org.springframework.web.socket.WebSocketSession;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Independent TTS request admission, bounded synthesis and response delivery on the shared socket. */
final class GatewayTtsEndpoint implements AutoCloseable {
    private static final int MAX_TEXT_CHARS = 500;
    private static final int WORKERS = 4;
    private static final int QUEUE_CAPACITY = 64;

    private final TtsProvider provider;
    private final GatewayDownlink downlink;
    private final ExecutorService executor = new ThreadPoolExecutor(
            WORKERS, WORKERS, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(QUEUE_CAPACITY),
            runnable -> {
                Thread thread = new Thread(runnable, "gateway-tts");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy());

    GatewayTtsEndpoint(TtsProvider provider, GatewayDownlink downlink) {
        this.provider = provider;
        this.downlink = downlink;
    }

    void request(WebSocketSession session, SessionContext context, DownlinkBudget budget,
                 Map<String, Object> payload) {
        if (context == null) return; // A valid hello is required before business requests.
        String text = String.valueOf(payload.get("text"));
        String segmentId = payload.get("segmentId") != null
                ? String.valueOf(payload.get("segmentId")) : null;
        if (text.length() > MAX_TEXT_CHARS) {
            downlink.sendError(session, context, "TTS_TEXT_TOO_LONG",
                    "tts text exceeds " + MAX_TEXT_CHARS + " chars", segmentId);
            return;
        }
        String utteranceId = payload.get("utteranceId") != null
                ? String.valueOf(payload.get("utteranceId")) : "";
        try {
            executor.execute(() -> synthesizeAndSend(session, context, budget,
                    text, segmentId, utteranceId));
        } catch (RejectedExecutionException error) {
            downlink.sendError(session, context, "TTS_BUSY", "tts queue is full", segmentId);
        }
    }

    private void synthesizeAndSend(WebSocketSession session, SessionContext context,
                                   DownlinkBudget budget, String text, String segmentId,
                                   String utteranceId) {
        try {
            Reply reply = provider.synthesize(text, context, utteranceId);
            if (!"audio".equals(reply.kind()) || reply.data() == null || reply.data().length == 0) {
                throw new IllegalStateException("tts returned non-audio reply: kind=" + reply.kind());
            }
            byte[] audio = reply.data();
            if (!budget.tryReserve(audio.length)) {
                downlink.sendError(session, context, "DOWNLINK_OVERLOADED",
                        "downlink budget exhausted", segmentId);
                return;
            }
            try {
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("mime", reply.mime());
                response.put("dataBase64", Base64.getEncoder().encodeToString(audio));
                response.put("text", text);
                if (segmentId != null) response.put("segmentId", segmentId);
                downlink.send(session, "tts_response", response);
            } finally {
                budget.release(audio.length);
            }
        } catch (Exception error) {
            downlink.sendError(session, context, "TTS_FAILED",
                    "tts failed: " + error.getMessage(), segmentId);
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
