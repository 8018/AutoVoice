package com.autovoice.server.speechqwenomni;

import com.autovoice.server.contracts.Intent;
import com.autovoice.server.contracts.RealtimeChatSession;
import com.autovoice.server.contracts.RealtimeChatSink;
import com.autovoice.server.contracts.SessionContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QwenOmniRealtimeChatProviderTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final MockWebServer server = new MockWebServer();

    @AfterEach void close() throws Exception { server.close(); }

    @Test
    void usesPlusRealtimeAndStreamsContinuouslyWithoutCancellingOnSpeechStart() throws Exception {
        List<String> clientEvents = new CopyOnWriteArrayList<>();
        CountDownLatch appendSeen = new CountDownLatch(1);
        server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
            @Override public void onMessage(WebSocket ws, String text) {
                clientEvents.add(text);
                if (text.contains("input_audio_buffer.append")) {
                    appendSeen.countDown();
                    ws.send("{\"type\":\"response.created\"}");
                    ws.send("{\"type\":\"input_audio_buffer.speech_started\"}");
                    ws.send("{\"type\":\"response.audio.delta\",\"delta\":\""
                            + Base64.getEncoder().encodeToString(new byte[]{1, 2, 3}) + "\"}");
                    ws.send("{\"type\":\"response.audio_transcript.done\",\"transcript\":\"hello\"}");
                    ws.send("{\"type\":\"response.done\"}");
                    ws.send("{\"type\":\"response.created\"}");
                    ws.send("{\"type\":\"response.audio.delta\",\"delta\":\""
                            + Base64.getEncoder().encodeToString(new byte[]{4, 5}) + "\"}");
                    ws.send("{\"type\":\"response.audio_transcript.done\",\"transcript\":\"new reply\"}");
                    ws.send("{\"type\":\"response.done\"}");
                }
            }
            @Override public void onClosing(WebSocket ws, int code, String reason) { ws.close(code, reason); }
        }));

        CountDownLatch speechStarted = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<byte[]> audio = new AtomicReference<>();
        QwenOmniRealtimeChatProvider provider = provider();
        RealtimeChatSession session = provider.open(context(), new RealtimeChatSink() {
            @Override public void onUserSpeechStarted() { speechStarted.countDown(); }
            @Override public void onChunk(byte[] pcm) { audio.set(pcm); }
            @Override public void onComplete(String text, Intent intent, String asrText) {
                completed.countDown();
            }
        });

        session.appendAudio(new byte[]{9, 8, 7, 6});
        assertTrue(appendSeen.await(2, TimeUnit.SECONDS));
        assertTrue(speechStarted.await(2, TimeUnit.SECONDS));
        assertTrue(completed.await(2, TimeUnit.SECONDS));
        // speech_started 后旧回答被输出门拦截；下一回答正常播出，且未发送 response.cancel。
        assertArrayEquals(new byte[]{4, 5}, audio.get());
        assertFalse(clientEvents.stream().anyMatch(value -> value.contains("response.cancel")));

        JsonNode update = JSON.readTree(clientEvents.get(0));
        assertEquals("session.update", update.path("type").asText());
        assertEquals("semantic_vad", update.at("/session/turn_detection/type").asText());
        assertFalse(update.at("/session/enable_input_audio_transcription").asBoolean());
        assertEquals(16_000, update.at("/session/audio/input/format/sample_rate").asInt());
        assertEquals(24_000, update.at("/session/audio/output/format/sample_rate").asInt());
        assertEquals("exit_chat", update.at("/session/tools/0/name").asText());
        assertTrue(update.at("/session/instructions").asText().contains("current user audio"));

        session.close();
        RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
        assertNotNull(request);
        assertTrue(request.getPath().contains("workspace/qwen3.5-omni-plus-realtime"));
        assertEquals("Bearer key", request.getHeader("Authorization"));
    }

    @Test
    void mapsExitChatFunctionCallToConversationIntent() throws Exception {
        AtomicReference<WebSocket> peer = new AtomicReference<>();
        server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
            @Override public void onOpen(WebSocket ws, Response response) { peer.set(ws); }
            @Override public void onClosing(WebSocket ws, int code, String reason) { ws.close(code, reason); }
        }));
        CountDownLatch exited = new CountDownLatch(1);
        AtomicReference<Intent> exitIntent = new AtomicReference<>();
        RealtimeChatSession session = provider().open(context(), new RealtimeChatSink() {
            @Override public void onComplete(String text, Intent intent, String asrText) {
                exitIntent.set(intent);
                exited.countDown();
            }
        });
        WebSocket ws = peer.get();
        assertNotNull(ws);
        ws.send("{\"type\":\"response.created\"}");
        ws.send("{\"type\":\"response.output_item.done\",\"item\":{\"type\":\"function_call\","
                + "\"name\":\"exit_chat\",\"arguments\":\"{}\"}}");
        ws.send("{\"type\":\"response.done\"}");

        assertTrue(exited.await(2, TimeUnit.SECONDS));
        assertEquals("conversation", exitIntent.get().domain());
        assertEquals("exit_chat", exitIntent.get().intent());
        session.close();
    }

    private QwenOmniRealtimeChatProvider provider() {
        String template = server.url("/%s/%s").toString().replace("http://", "ws://");
        return new QwenOmniRealtimeChatProvider(new OkHttpClient(), "key", "workspace",
                QwenOmniRealtimeChatProvider.DEFAULT_MODEL, "Tina", () -> "chat prompt", template);
    }

    private SessionContext context() { return new SessionContext("s1", "zh-CN", java.util.Map.of()); }
}
