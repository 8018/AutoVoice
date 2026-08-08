package com.autovoice.server.asrgateway;

import com.autovoice.server.contracts.AsrException;
import com.autovoice.server.contracts.AsrProvider;
import com.autovoice.server.contracts.SessionContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 阿里云一句话识别（nls-gateway one-shot ASR）适配器，同步执行。
 *
 * <p>请求：POST {@link #DEFAULT_ENDPOINT}，query {@code appkey/format=pcm/sample_rate=16000/
 * enable_punctuation_prediction=true}，header {@code X-NLS-Token: &lt;token&gt;} +
 * {@code Content-Type: audio/L16;rate=16000;channels=1}，body = PCM（S16LE / 16 kHz / 单声道）。</p>
 *
 * <p>响应：JSON {@code status == 20000000} 时取 {@code result} 字段返回识别文本；
 * 否则抛 {@link AsrException}。错误语义：token 不可用 / HTTP 非 2xx / status 非成功 /
 * JSON 解析失败 / IO 异常全部包装为 {@link AsrException}（ASR 侧失败，由调用方按
 * AsrProvider 契约处理）。</p>
 */
public final class AliyunAsrProvider implements AsrProvider {

    /** 阿里云一句话识别默认地址。 */
    public static final String DEFAULT_ENDPOINT = "https://nls-gateway-cn-shanghai.aliyuncs.com/stream/v1/asr";

    /** 一句话识别成功状态码。 */
    static final long STATUS_OK = 20_000_000L;

    private static final long CALL_TIMEOUT_MS = 10_000;
    private static final String PCM_MEDIA_TYPE = "audio/L16;rate=16000;channels=1";
    private static final String HEADER_X_NLS_TOKEN = "X-NLS-Token";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final OkHttpClient client;
    private final String appkey;
    private final String endpoint;
    private final Supplier<String> tokenSupplier;

    /**
     * @param appkey        阿里云 NLS appkey
     * @param endpoint      一句话识别地址；测试注入 MockWebServer URL，生产用 {@link #DEFAULT_ENDPOINT}
     * @param tokenSupplier 返回 {@code X-NLS-Token} 的值（生产接 {@link AliyunTokenClient#token()}，测试传 lambda）
     */
    public AliyunAsrProvider(OkHttpClient client, String appkey, String endpoint, Supplier<String> tokenSupplier) {
        // 派生 callTimeout 10s，不改动调用方传入的 client
        this.client = client.newBuilder().callTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS).build();
        this.appkey = appkey;
        this.endpoint = endpoint;
        this.tokenSupplier = tokenSupplier;
    }

    @Override
    public String transcribe(byte[] pcm16k, SessionContext ctx) {
        String token;
        try {
            token = tokenSupplier.get();
        } catch (RuntimeException e) {
            // token 获取失败 → ASR 侧失败（契约：失败抛 AsrException）
            throw new AsrException("aliyun nls token unavailable: " + e.getMessage(), e);
        }
        HttpUrl url = HttpUrl.get(endpoint).newBuilder()
                .addQueryParameter("appkey", appkey)
                .addQueryParameter("format", "pcm")
                .addQueryParameter("sample_rate", "16000")
                .addQueryParameter("enable_punctuation_prediction", "true")
                .build();
        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(pcm16k, MediaType.get(PCM_MEDIA_TYPE)))
                .header(HEADER_X_NLS_TOKEN, token)
                .build();
        try (Response response = client.newCall(request).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new AsrException("aliyun asr returned HTTP " + response.code() + ": " + body);
            }
            return parseResult(body);
        } catch (IOException e) {
            throw new AsrException("aliyun asr request failed: " + e.getMessage(), e);
        }
    }

    /** 解析 status/result；status != 20000000 或 result 缺失视为 ASR 失败。 */
    private static String parseResult(String body) {
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (IOException e) {
            throw new AsrException("aliyun asr response is not valid json: " + body, e);
        }
        if (root.path("status").asLong() != STATUS_OK) {
            throw new AsrException("aliyun asr status != 20000000: " + body);
        }
        JsonNode result = root.path("result");
        if (result.isMissingNode() || result.isNull()) {
            throw new AsrException("aliyun asr response has no result: " + body);
        }
        return result.asText();
    }
}
