package com.autovoice.server.speechqwenomni;

import com.autovoice.server.contracts.OnlineSpeechResult;
import com.autovoice.server.contracts.SessionContext;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QwenOmniSpeechProviderTest {

    private final MockWebServer server = new MockWebServer();

    @AfterEach
    void close() throws Exception {
        server.close();
    }

    @Test
    void accumulatesSseTextAndAudioAndWrapsPcmAs24kWav() throws Exception {
        byte[] first = {1, 2};
        byte[] second = {3, 4};
        server.enqueue(sse(
                delta("content", "好"),
                audio(first),
                delta("content", "的"),
                audio(second)));

        QwenOmniSpeechProvider provider = provider((name, args) -> "unused");
        OnlineSpeechResult result = provider.process(new byte[]{9, 8, 7, 6}, context(), "u1")
                .get(2, TimeUnit.SECONDS);

        assertEquals("audio", result.reply().kind());
        assertEquals("好的", result.reply().speakText());
        assertEquals("audio/wav", result.reply().mime());
        assertEquals('R', result.reply().data()[0]);
        assertArrayEquals(new byte[]{1, 2, 3, 4},
                java.util.Arrays.copyOfRange(result.reply().data(), 44, 48));

        RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals("Bearer test-key", request.getHeader("Authorization"));
        String body = request.getBody().readUtf8();
        assertTrue(body.contains("qwen3.5-omni-plus"));
        assertTrue(body.contains("input_audio"));
        assertTrue(body.contains("data:audio/wav;base64,"));
    }

    @Test
    void concatenatesToolCallArgumentsReusesExecutorThenReturnsAudio() throws Exception {
        server.enqueue(sse(
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call-1\",\"function\":{\"name\":\"weather\",\"arguments\":\"{\\\"city\\\":\"}}]}}]}",
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"\\\"杭州\\\"}\"}}]}}]}"));
        server.enqueue(sse(delta("content", "杭州晴天"), audio(new byte[]{5, 6})));
        AtomicReference<String> invocation = new AtomicReference<>();
        QwenOmniSpeechProvider provider = new QwenOmniSpeechProvider(
                new OkHttpClient(), "test-key", server.url("/chat").toString(),
                null, null,
                () -> java.util.List.of(new com.autovoice.server.contracts.FunctionTool(
                        "weather", "天气", "{\"type\":\"object\"}")),
                (name, args) -> {
                    invocation.set(name + ":" + args);
                    return "晴天";
                }, () -> "测试");

        OnlineSpeechResult result = provider.process(new byte[]{1, 2}, context(), "u2")
                .get(2, TimeUnit.SECONDS);

        assertEquals("weather:{\"city\":\"杭州\"}", invocation.get());
        assertEquals("audio", result.reply().kind());
        assertEquals(2, server.getRequestCount());
    }

    private QwenOmniSpeechProvider provider(com.autovoice.server.contracts.ToolExecutor executor) {
        return new QwenOmniSpeechProvider(new OkHttpClient(), "test-key", server.url("/chat").toString(),
                null, null, QwenOmniSpeechProvider::defaultTools, executor, () -> "测试");
    }

    private static SessionContext context() {
        return new SessionContext("s1", "zh-CN", Map.of());
    }

    private static MockResponse sse(String... events) {
        StringBuilder body = new StringBuilder();
        for (String event : events) body.append("data: ").append(event).append("\n\n");
        body.append("data: [DONE]\n\n");
        return new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body.toString());
    }

    private static String delta(String field, String value) {
        return "{\"choices\":[{\"delta\":{\"" + field + "\":\"" + value + "\"}}]}";
    }

    private static String audio(byte[] pcm) {
        return "{\"choices\":[{\"delta\":{\"audio\":{\"data\":\""
                + Base64.getEncoder().encodeToString(pcm) + "\"}}}]}";
    }
}
