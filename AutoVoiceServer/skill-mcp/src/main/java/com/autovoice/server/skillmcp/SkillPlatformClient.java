package com.autovoice.server.skillmcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.util.List;

/**
 * skill 平台拉取客户端：GET {baseUrl}/api/skills?enabled=true，带 X-Skill-Service-Token。
 * baseUrl 空白 → 功能关闭（fetchEnabled 返回空表，isEnabled false）。
 */
public class SkillPlatformClient {

    public static final String SERVICE_TOKEN_HEADER = "X-Skill-Service-Token";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long CALL_TIMEOUT_MS = 5_000;

    private final OkHttpClient client;
    private final String baseUrl;
    private final String serviceToken;

    public SkillPlatformClient(OkHttpClient client, String baseUrl, String serviceToken) {
        this.client = client.newBuilder().callTimeout(CALL_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS).build();
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        this.serviceToken = serviceToken;
    }

    public boolean isEnabled() {
        return !baseUrl.isEmpty();
    }

    /** 拉取全部启用 skill；HTTP 非 2xx 或解析失败抛 IOException（调用方保留旧快照）。 */
    public List<SkillConfig> fetchEnabled() throws IOException {
        if (!isEnabled()) {
            return List.of();
        }
        Request req = new Request.Builder()
                .url(baseUrl + "/api/skills?enabled=true")
                .header(SERVICE_TOKEN_HEADER, serviceToken)
                .build();
        try (Response resp = client.newCall(req).execute()) {
            String body = resp.body() == null ? "" : resp.body().string();
            if (!resp.isSuccessful()) {
                throw new IOException("skill platform pull failed: HTTP " + resp.code() + ": " + body);
            }
            return MAPPER.readValue(body, new TypeReference<List<SkillConfig>>() {});
        }
    }

    /**
     * 拉取平台级 system prompt（GET /api/config/system-prompt）。
     * 不可用（未接入 / 非 2xx / 解析失败）返回 null —— 调用方保留现值，不抛。
     */
    public String fetchSystemPrompt() {
        return fetchPrompt("/api/config/system-prompt");
    }

    /** 拉取 S2S 闲聊域独立 system prompt；失败返回 null 并保留旧快照。 */
    public String fetchChatSystemPrompt() {
        return fetchPrompt("/api/config/chat-system-prompt");
    }

    private String fetchPrompt(String path) {
        if (!isEnabled()) {
            return null;
        }
        try {
            Request req = new Request.Builder()
                    .url(baseUrl + path)
                    .header(SERVICE_TOKEN_HEADER, serviceToken)
                    .build();
            try (Response resp = client.newCall(req).execute()) {
                if (!resp.isSuccessful()) {
                    return null;
                }
                String body = resp.body() == null ? "" : resp.body().string();
                return MAPPER.readTree(body).path("value").asText(null);
            }
        } catch (Exception e) {
            return null;
        }
    }
}
