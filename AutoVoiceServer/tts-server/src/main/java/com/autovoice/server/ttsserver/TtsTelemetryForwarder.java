package com.autovoice.server.ttsserver;

import com.autovoice.server.contracts.telemetry.TelemetryEvent;
import com.autovoice.server.contracts.telemetry.TelemetryRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;

/**
 * tts-server → 网关 telemetry 转发（Task 5）：独立进程不跨进程写库，事件经 HTTP
 * POST 到网关 {@code {gatewayTelemetryUrl}/events}（body {@code {utteranceId, events:[event]}}，
 * 每事件一条 POST——事件量小，不做批量）。
 *
 * <p>{@code gatewayTelemetryUrl} 语义：配置值可为 {@code http://127.0.0.1:8080} 或已带
 * {@code /api/telemetry} 前缀；实现按配置值原样 + {@code /events}（尾部斜杠归一）。
 * 异步 enqueue，失败/非 2xx 只 {@code Log.w} 静默不抛——telemetry 故障不得影响 TTS 主链路。</p>
 */
public final class TtsTelemetryForwarder implements TelemetryRecorder {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(TtsTelemetryForwarder.class);
    private static final MediaType JSON = MediaType.get("application/json");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final OkHttpClient client;
    private final HttpUrl eventsUrl;

    public TtsTelemetryForwarder(OkHttpClient client, String gatewayTelemetryUrl) {
        this.client = client;
        this.eventsUrl = HttpUrl.get(stripTrailingSlash(gatewayTelemetryUrl) + "/events");
    }

    private static String stripTrailingSlash(String url) {
        String u = url == null ? "" : url.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    @Override
    public void record(String utteranceId, TelemetryEvent event) {
        try {
            ObjectNode body = MAPPER.createObjectNode();
            body.put("utteranceId", utteranceId); // null → JSON null
            ArrayNode events = body.putArray("events");
            events.add(MAPPER.valueToTree(event));
            Request request = new Request.Builder().url(eventsUrl)
                    .post(RequestBody.create(body.toString(), JSON))
                    .build();
            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    LOG.warn("telemetry forward failed: {}", String.valueOf(e.getMessage()));
                }

                @Override
                public void onResponse(Call call, Response response) {
                    try (Response r = response) {
                        if (!r.isSuccessful()) {
                            LOG.warn("telemetry forward http {} -> {}", r.code(), eventsUrl);
                        }
                    }
                }
            });
        } catch (Exception e) {
            // 序列化/构造失败也静默：telemetry 不影响 TTS 主链路
            LOG.warn("telemetry forward failed: {}", String.valueOf(e.getMessage()));
        }
    }
}
