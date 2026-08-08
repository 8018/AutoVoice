package com.autovoice.server.asrgateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * 阿里云 NLS token 客户端：AK/SK 换取一句话识别 / 实时识别 token。
 *
 * <p>请求：GET {@link #DEFAULT_ENDPOINT}，query 携带 {@code AccessKeyId}/{@code AccessKeySecret}
 * （阿里云 NLS token 接口的实际签名方式为 AK/SK 明文 query），响应
 * {@code {"Token":"...","ExpireTime":<epochSec>}}。</p>
 *
 * <p>缓存：首次获取后缓存，距离 {@code ExpireTime} 不足 60s 时（{@link System#currentTimeMillis()}
 * 比较）视为过期并重新获取；{@code synchronized} 双检锁保证并发下只重取一次。</p>
 *
 * <p>安全：真实 AK/SK 仅经构造参数注入，绝不写入代码或 git。</p>
 */
public final class AliyunTokenClient {

    /** 阿里云 NLS token 接口默认地址。 */
    public static final String DEFAULT_ENDPOINT = "https://nls-meta-cn-shanghai.aliyuncs.com/api/v1/ws/token";

    /** 提前于 ExpireTime 多少秒视作过期，避免用边界 token 被服务端拒绝。 */
    private static final long REFRESH_AHEAD_SECONDS = 60;

    private static final long CALL_TIMEOUT_MS = 10_000;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final OkHttpClient client;
    private final String accessKeyId;
    private final String accessKeySecret;
    private final String endpoint;

    private volatile String cachedToken;
    private volatile long cachedExpireTimeMs;

    /**
     * @param endpoint token 接口地址；测试注入 MockWebServer URL，生产用 {@link #DEFAULT_ENDPOINT}
     */
    public AliyunTokenClient(OkHttpClient client, String accessKeyId, String accessKeySecret, String endpoint) {
        // 派生 callTimeout 10s，不改动调用方传入的 client
        this.client = client.newBuilder().callTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS).build();
        this.accessKeyId = accessKeyId;
        this.accessKeySecret = accessKeySecret;
        this.endpoint = endpoint;
    }

    /** 默认端点便捷构造。 */
    public AliyunTokenClient(OkHttpClient client, String accessKeyId, String accessKeySecret) {
        this(client, accessKeyId, accessKeySecret, DEFAULT_ENDPOINT);
    }

    /** 返回可用 token；缓存过期（ExpireTime - 60s）时自动重新获取。 */
    public String token() {
        String token = cachedToken;
        if (isFresh(token)) {
            return token;
        }
        synchronized (this) {
            // 双检锁：竞态只允许一次网络请求
            if (isFresh(cachedToken)) {
                return cachedToken;
            }
            return fetchToken();
        }
    }

    private boolean isFresh(String token) {
        return token != null && System.currentTimeMillis() < cachedExpireTimeMs - REFRESH_AHEAD_SECONDS * 1000L;
    }

    private String fetchToken() {
        HttpUrl url = HttpUrl.get(endpoint).newBuilder()
                .addQueryParameter("AccessKeyId", accessKeyId)
                .addQueryParameter("AccessKeySecret", accessKeySecret)
                .build();
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = client.newCall(request).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IllegalStateException("aliyun nls token returned HTTP " + response.code() + ": " + body);
            }
            return parseAndCache(body);
        } catch (IOException e) {
            throw new IllegalStateException("aliyun nls token request failed: " + e.getMessage(), e);
        }
    }

    /** 解析 {"Token":...,"ExpireTime":<epochSec>} 并写入缓存；格式不符视为失败。 */
    private String parseAndCache(String body) {
        try {
            JsonNode root = MAPPER.readTree(body);
            JsonNode tokenNode = root.path("Token");
            long expireSeconds = root.path("ExpireTime").asLong();
            if (tokenNode.isMissingNode() || tokenNode.isNull() || expireSeconds <= 0) {
                throw new IllegalStateException("aliyun nls token response malformed: " + body);
            }
            String token = tokenNode.asText();
            this.cachedToken = token;
            this.cachedExpireTimeMs = expireSeconds * 1000L;
            return token;
        } catch (IOException e) {
            throw new IllegalStateException("aliyun nls token response is not valid json: " + body, e);
        }
    }
}
