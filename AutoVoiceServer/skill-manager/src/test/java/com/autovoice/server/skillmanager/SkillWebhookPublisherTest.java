package com.autovoice.server.skillmanager;

import static org.junit.jupiter.api.Assertions.*;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

class SkillWebhookPublisherTest {

    @Test
    void postsRefreshWithToken() throws Exception {
        MockWebServer server = new MockWebServer();
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
        SkillWebhookPublisher p = new SkillWebhookPublisher(new OkHttpClient(),
                server.url("/").toString(), "svc-secret");
        p.notifySkillChanged("amap-maps");
        RecordedRequest r = server.takeRequest(3, TimeUnit.SECONDS);
        assertEquals("/api/internal/skills/refresh", r.getPath());
        assertEquals("svc-secret", r.getHeader("X-Skill-Service-Token"));
        server.shutdown();
    }

    @Test
    void failureIsSwallowed() throws Exception {
        MockWebServer server = new MockWebServer();
        server.enqueue(new MockResponse().setResponseCode(500));
        SkillWebhookPublisher p = new SkillWebhookPublisher(new OkHttpClient(),
                server.url("/").toString(), "svc-secret");
        p.notifySkillChanged("x"); // 不抛：webhook 是尽力而为
        RecordedRequest r = server.takeRequest(3, TimeUnit.SECONDS);
        assertNotNull(r, "请求应确实发出（失败仅吞掉响应，不跳过发送）");
        assertEquals("/api/internal/skills/refresh", r.getPath());
        server.shutdown();
    }
}
