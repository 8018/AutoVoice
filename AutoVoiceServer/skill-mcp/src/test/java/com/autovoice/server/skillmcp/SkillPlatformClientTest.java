package com.autovoice.server.skillmcp;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

class SkillPlatformClientTest {

    private final MockWebServer server = new MockWebServer();

    @AfterEach
    void stop() throws IOException {
        server.shutdown();
    }

    @Test
    void fetchEnabledParsesAndSendsToken() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .setBody("[{\"id\":\"amap-maps\",\"name\":\"高德地图\",\"description\":\"导航\","
                        + "\"mcpUrl\":\"https://mcp.example.com/mcp\",\"authHeader\":\"x-api-key\","
                        + "\"authValue\":\"secret-key\",\"toolsJson\":\"[{\\\"name\\\":\\\"poi_search\\\",\\\"enabled\\\":true}]\","
                        + "\"enabled\":true,\"updatedAt\":1723500000000}]"));
        SkillPlatformClient client = new SkillPlatformClient(new OkHttpClient(),
                server.url("/").toString(), "tok-123");
        List<SkillConfig> cfgs = client.fetchEnabled();
        assertEquals(1, cfgs.size());
        SkillConfig c = cfgs.get(0);
        assertEquals("amap-maps", c.id());
        assertEquals("secret-key", c.authValue()); // 网关拉取必须拿到明文凭据
        assertEquals("[{\"name\":\"poi_search\",\"enabled\":true}]", c.toolsJson());
        RecordedRequest r = server.takeRequest(3, TimeUnit.SECONDS);
        assertEquals("/api/skills?enabled=true", r.getPath());
        assertEquals("tok-123", r.getHeader("X-Skill-Service-Token"));
    }

    @Test
    void emptyUrlDisablesClient() throws Exception {
        SkillPlatformClient client = new SkillPlatformClient(new OkHttpClient(), "  ", "t");
        assertFalse(client.isEnabled());
        assertTrue(client.fetchEnabled().isEmpty());
    }

    @Test
    void non2xxThrows() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(500));
        SkillPlatformClient client = new SkillPlatformClient(new OkHttpClient(),
                server.url("/").toString(), "t");
        assertThrows(IOException.class, client::fetchEnabled);
    }
}
