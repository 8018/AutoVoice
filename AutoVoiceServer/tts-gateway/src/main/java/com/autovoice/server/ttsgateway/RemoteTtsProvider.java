package com.autovoice.server.ttsgateway;

import com.autovoice.server.contracts.Reply;
import com.autovoice.server.contracts.SessionContext;
import com.autovoice.server.contracts.TtsProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * 远程 TTS 转发（M4 TTS 独立服务）：网关不再本地合成，HTTP POST 到独立 tts-server 的
 * {@code /tts} 端点（body {@code {text, sessionId}} → {@code {mime, dataBase64}}，内部
 * 协议见 runbook，不进对外契约），合成与缓存（CachedTtsProvider）全部归 TTS 服务侧。
 *
 * <p>失败/超时 → {@link RuntimeException}（TTS 失败由调用方降级为文本回复，语义与
 * 本地合成一致，设备协议不变——仍走接入网关）。超时 15s 与 AliyunTtsProvider 对齐。</p>
 */
public final class RemoteTtsProvider implements TtsProvider {

    private static final long CALL_TIMEOUT_MS = 15_000;
    private static final MediaType JSON = MediaType.get("application/json");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final OkHttpClient client;
    private final okhttp3.HttpUrl url;
    private final long timeoutMs;

    public RemoteTtsProvider(OkHttpClient client, String remoteUrl) {
        this(client, remoteUrl, CALL_TIMEOUT_MS);
    }

    /** 测试用：可注入短超时，避免超时用例等满 15s。 */
    RemoteTtsProvider(OkHttpClient client, String remoteUrl, long timeoutMs) {
        this.client = client;
        this.url = okhttp3.HttpUrl.get(remoteUrl);
        this.timeoutMs = timeoutMs;
    }

    @Override
    public Reply synthesize(String text, SessionContext ctx) {
        return synthesize(text, ctx, "");
    }

    /** 3 参版：utteranceId 随请求 body 透传 tts-server（非空才带，兼容旧协议）。 */
    @Override
    public Reply synthesize(String text, SessionContext ctx, String utteranceId) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("text", text);
        body.put("sessionId", ctx == null ? null : ctx.sessionId());
        if (utteranceId != null && !utteranceId.isBlank()) {
            body.put("utteranceId", utteranceId);
        }
        Request request = new Request.Builder().url(url)
                .post(RequestBody.create(body.toString(), JSON))
                .build();
        okhttp3.Call call = client.newCall(request);
        call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS); // per-call 超时（共享 client 不改全局）
        try (Response response = call.execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new RuntimeException("tts remote http " + response.code());
            }
            JsonNode root = MAPPER.readTree(response.body().bytes());
            String mime = root.path("mime").asText("audio/wav");
            byte[] data = Base64.getDecoder().decode(root.path("dataBase64").asText(""));
            if (data.length == 0) {
                throw new RuntimeException("tts remote empty audio");
            }
            return Reply.ofAudio(mime, data);
        } catch (IOException | RuntimeException e) {
            if (e instanceof RuntimeException re) {
                throw re; // 服务端明确失败（非 2xx / 空音频 / 坏 JSON）
            }
            throw new RuntimeException("tts remote failed: " + e.getMessage(), e);
        }
    }
}
