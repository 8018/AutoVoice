package com.autovoice.server.nlutraditional;

import com.autovoice.server.contracts.Intent;
import com.autovoice.server.contracts.NluProvider;
import com.autovoice.server.contracts.SessionContext;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 讯飞 AIUI 文本语义 WebAPI 适配器（云端 nlu-traditional 链路）。
 *
 * <p>请求：POST {@link #DEFAULT_ENDPOINT}，头 {@code X-Appid}/{@code X-CurTime}/
 * {@code X-Param}/{@code X-CheckSum}（签名规范见各常量 javadoc），Content-Type
 * {@code text/plain; charset=utf-8}，body = 用户文本。响应体原样交给
 * {@link IflytekSemanticNormalizer} 归一化。</p>
 *
 * <p>错误语义：IO 异常（网络失败/超时）包装为 {@link RuntimeException} 抛给调用方
 * （Task 5 仲裁的 safety 兜底负责收敛）；正常 HTTP 的错误码响应体（code != "0"）
 * 与 HTTP 非 2xx 的响应体都交给 normalizer → {@link Intent#unknown(String)}，
 * 不在 HTTP 层抛异常（normalizer 永不抛异常）。</p>
 */
public final class IflytekNluProvider implements NluProvider {

    /** AIUI 文本语义 WebAPI 默认端点（以 AIUI 文档为准，集中在此便于修改）。 */
    public static final String DEFAULT_ENDPOINT = "https://api.xfyun.cn/v1/aiui/v1/text_ai";

    /** 本 provider 产出的 Intent.source 值。 */
    public static final String SOURCE = "nlu.iflytek.api";

    static final String HEADER_APPID = "X-Appid";
    static final String HEADER_CUR_TIME = "X-CurTime";
    static final String HEADER_PARAM = "X-Param";
    static final String HEADER_CHECKSUM = "X-CheckSum";
    static final MediaType PLAIN_TEXT = MediaType.get("text/plain; charset=utf-8");

    /** X-Param 载荷（base64 前的 JSON）：AIUI 语义版本 3.0 + 主场景。 */
    static final String PARAM_JSON = "{\"nlp_version\":\"3.0\",\"scene\":\"main\"}";

    private static final long CALL_TIMEOUT_MS = 10_000;
    private static final String HMAC_MD5 = "HmacMD5";

    private final OkHttpClient client;
    private final String appid;
    private final String apiKey;
    private final String endpoint;
    private final String base64Param;
    private final IflytekSemanticNormalizer normalizer = new IflytekSemanticNormalizer();

    /** @param endpoint 讯飞接口地址；测试注入 MockWebServer URL，生产用 {@link #DEFAULT_ENDPOINT} */
    public IflytekNluProvider(OkHttpClient client, String appid, String apiKey, String endpoint) {
        this.client = client.newBuilder().callTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS).build();
        this.appid = appid;
        this.apiKey = apiKey;
        this.endpoint = endpoint;
        this.base64Param = Base64.getEncoder().encodeToString(PARAM_JSON.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public CompletableFuture<Intent> understand(String text, SessionContext ctx) {
        // 同步 HTTP call 放进 supplyAsync（common pool），调用方立即可挂回调；
        // IO 异常在 lambda 内包装为 RuntimeException，future 以该异常完成。
        return CompletableFuture.supplyAsync(() -> {
            try {
                return callAndNormalize(text);
            } catch (IOException e) {
                throw new RuntimeException("iflytek nlu request failed: " + e.getMessage(), e);
            }
        });
    }

    private Intent callAndNormalize(String text) throws IOException {
        long curTime = System.currentTimeMillis() / 1000;
        String checksum = hmacMd5Base64(apiKey, curTime + base64Param + text);
        Request request = new Request.Builder()
                .url(endpoint)
                .post(RequestBody.create(text, PLAIN_TEXT))
                .header(HEADER_APPID, appid)
                .header(HEADER_CUR_TIME, Long.toString(curTime))
                .header(HEADER_PARAM, base64Param)
                .header(HEADER_CHECKSUM, checksum)
                .build();
        try (Response response = client.newCall(request).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            return normalizer.normalize(body, SOURCE);
        }
    }

    /**
     * base64(HMAC-MD5(key, data))，无换行（{@link Base64#getEncoder()}）。
     * checksum 里的 curTime 与 X-CurTime 头是同一值（调用方传入）。
     */
    private static String hmacMd5Base64(String key, String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_MD5);
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), HMAC_MD5));
            return Base64.getEncoder().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HmacMD5 unavailable", e);
        }
    }
}
