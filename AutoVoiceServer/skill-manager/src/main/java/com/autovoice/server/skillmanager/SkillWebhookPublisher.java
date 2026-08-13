package com.autovoice.server.skillmanager;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/** skill 变更后推送网关刷新（尽力而为：失败仅日志，不阻断写操作）。 */
public final class SkillWebhookPublisher implements SkillWebhookNotifier {

    private static final Logger LOG = LoggerFactory.getLogger(SkillWebhookPublisher.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient client;
    private final String gatewayWebhookUrl;
    private final String serviceToken;

    public SkillWebhookPublisher(OkHttpClient client, String gatewayWebhookUrl, String serviceToken) {
        this.client = client.newBuilder()
                .callTimeout(3_000, TimeUnit.MILLISECONDS)
                .build();
        this.gatewayWebhookUrl = gatewayWebhookUrl == null ? "" : gatewayWebhookUrl.trim();
        this.serviceToken = serviceToken;
    }

    @Override
    public void notifySkillChanged(String skillId) {
        if (gatewayWebhookUrl.isEmpty()) {
            return; // 未配置网关地址（单机开发）→ 跳过推送
        }
        String url = gatewayWebhookUrl.endsWith("/")
                ? gatewayWebhookUrl + "api/internal/skills/refresh"
                : gatewayWebhookUrl + "/api/internal/skills/refresh";
        Request req = new Request.Builder()
                .url(url)
                .post(RequestBody.create("{\"skillId\":\"" + skillId + "\"}", JSON))
                .header("X-Skill-Service-Token", serviceToken)
                .build();
        try (Response resp = client.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                LOG.warn("skill webhook push failed: HTTP {}", resp.code());
            }
        } catch (Exception e) {
            LOG.warn("skill webhook push failed: {}", String.valueOf(e.getMessage()));
        }
    }
}
