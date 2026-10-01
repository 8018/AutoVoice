package com.autovoice.server.app;

import com.autovoice.server.contracts.Intent;
import com.autovoice.server.contracts.OnlineAudioSink;
import com.autovoice.server.contracts.Reply;
import com.autovoice.server.contracts.SessionContext;
import com.autovoice.server.contracts.SlotValue;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class TravelGuideServiceTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SessionContext CONTEXT = new SessionContext("s-travel", "zh-CN", Map.of());

    @Test void waitsForBusinessAdmissionThenStreamsSearchGroundedMarkdown() throws Exception {
        try (MockWebServer search = new MockWebServer(); MockWebServer llm = new MockWebServer()) {
            search.start();
            llm.start();
            String page = JSON.writeValueAsString(Map.of(
                    "title", "故宫参观信息", "url", "https://example.org/gugong",
                    "passage", "故宫博物院适合安排半日参观"));
            search.enqueue(new MockResponse().setBody(JSON.writeValueAsString(
                    Map.of("Response", Map.of("Pages", List.of(page))))));
            llm.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"# 北京\"}}]}\n\n"
                    + "data: {\"choices\":[{\"delta\":{\"content\":\"两日游\\n- [故宫](https://example.org/gugong)\"}}]}\n\n"
                    + "data: [DONE]\n\n"));
            List<String> frames = new CopyOnWriteArrayList<>();
            CountDownLatch done = new CountDownLatch(1);
            OnlineAudioSink sink = new OnlineAudioSink() {
                @Override public void onDocument(String operation, String text) {
                    frames.add(operation + ":" + text);
                    if ("complete".equals(operation) || "error".equals(operation)) done.countDown();
                }
            };
            try (TravelGuideService service = new TravelGuideService(new OkHttpClient(), "", "AKIDdemo",
                    "demo-secret", "llm-key", search.url("/").toString(),
                    llm.url("/chat/completions").toString())) {
                Reply marker = service.prepare(travelReply(), "北京两日游攻略", CONTEXT, "u-1", sink);
                assertEquals("travel", marker.intent().domain());
                assertEquals(0, search.getRequestCount(), "preparing a marker must not call search");
                service.admit(CONTEXT, "u-1");
                assertTrue(done.await(5, TimeUnit.SECONDS));
                assertEquals("start:", frames.get(0));
                assertEquals("delta:# 北京", frames.get(1));
                assertTrue(frames.get(2).contains("[故宫](https://example.org/gugong)"));
                assertEquals("complete:", frames.get(3));
                var searchRequest = search.takeRequest(1, TimeUnit.SECONDS);
                assertNotNull(searchRequest);
                assertEquals("SearchPro", searchRequest.getHeader("X-TC-Action"));
                assertEquals("2025-05-08", searchRequest.getHeader("X-TC-Version"));
                assertTrue(searchRequest.getHeader("Authorization").startsWith(
                        "TC3-HMAC-SHA256 Credential=AKIDdemo/"));
                assertTrue(searchRequest.getBody().readUtf8().contains("北京 2日游"));
                assertTrue(llm.takeRequest(1, TimeUnit.SECONDS).getBody().readUtf8()
                        .contains("https://example.org/gugong"));
            }
        }
    }

    @Test void cancelledMarkerNeverStartsProviderCalls() throws Exception {
        try (MockWebServer search = new MockWebServer(); MockWebServer llm = new MockWebServer()) {
            search.start();
            llm.start();
            try (TravelGuideService service = new TravelGuideService(new OkHttpClient(), "key", "llm",
                    search.url("/SearchPro").toString(), llm.url("/chat").toString())) {
                service.prepare(travelReply(), "北京有什么好玩的", CONTEXT, "u-2", OnlineAudioSink.NOOP);
                service.cancel(CONTEXT, "u-2");
                service.admit(CONTEXT, "u-2");
                assertEquals(0, search.getRequestCount());
            }
        }
    }

    @Test void malformedTravelMarkerDoesNotCreateTask() {
        Intent intent = Intent.of("1.0", "travel", "plan_guide", Map.of(
                "city", SlotValue.stringValue("北京")), 1, "llm.plan_travel", null);
        Reply malformed = Reply.ofAction(intent, "正在生成攻略");
        try (TravelGuideService service = new TravelGuideService(new OkHttpClient(), "key", "llm",
                "https://example.org/search", "https://example.org/chat")) {
            assertSame(malformed, service.prepare(malformed, "北京攻略", CONTEXT, "u-3", OnlineAudioSink.NOOP));
            service.admit(CONTEXT, "u-3");
        }
    }

    private static Reply travelReply() {
        Intent intent = Intent.of("1.0", "travel", "plan_guide", Map.of(
                "city", SlotValue.stringValue("北京"), "days", SlotValue.number(2)),
                1, "llm.plan_travel", null);
        return Reply.ofAction(intent, "正在生成北京两日游攻略");
    }
}
