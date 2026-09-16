package com.autovoice.server.llm;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.autovoice.server.contracts.Reply;
import com.autovoice.server.contracts.SessionContext;
import com.autovoice.server.contracts.telemetry.NoopTelemetryRecorder;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;

/** D06b:关闭后所有在途 chat 请求进入确定性终态,等待方不挂起。 */
class DeepSeekCloseTest {

    @Test
    void closeFailsInFlightChatDeterministically() throws Exception {
        MockWebServer server = new MockWebServer();
        // 上游挂起不响应:请求在途,等待方必须由 close 的排空逻辑给出终态
        CountDownLatch release = new CountDownLatch(1);
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                release.await(5, TimeUnit.SECONDS);
                return new MockResponse().setBody("{\"choices\":[]}");
            }
        });
        server.start();
        try {
            DeepSeekLlmProvider provider = new DeepSeekLlmProvider(new OkHttpClient(), "k",
                    server.url("/").toString(), NoopTelemetryRecorder.INSTANCE,
                    () -> java.util.List.of(), 5_000, (name, args) -> "x", null);
            CompletableFuture<Reply> inFlight = provider.chat(
                    "hi", new SessionContext("s", "zh", Map.of()));

            provider.close();

            CompletionException error = assertThrows(CompletionException.class,
                    () -> inFlight.join());
            assertTrue(error.getCause() instanceof IllegalStateException,
                    "关闭后等待方必须获得明确终态异常");
            assertTrue(error.getCause().getMessage().contains("closed"));
        } finally {
            release.countDown();
            server.shutdown();
        }
    }
}
