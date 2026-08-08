package com.autovoice.server.ttsgateway;

import com.autovoice.server.contracts.Reply;
import com.autovoice.server.contracts.SessionContext;
import com.autovoice.server.contracts.TtsProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * DashScope sambert 语音合成（TTS）适配器，同步执行。
 *
 * <p>请求：POST {@link #DEFAULT_ENDPOINT}，header {@code Authorization: Bearer &lt;apiKey&gt;} +
 * Content-Type {@code application/json}，body
 * {@code {"text":&lt;text&gt;,"format":"wav","sample_rate":16000}}。</p>
 *
 * <p>响应：2xx → wav 字节 → {@link Reply#ofAudio(String, byte[])}（kind=audio / mime=audio/wav）；
 * 非 2xx / 网络异常 / 构造 body 失败 → {@link RuntimeException}（TTS 失败由调用方降级，
 * 不抛 TTS 专属异常）。</p>
 */
public final class AliyunTtsProvider implements TtsProvider {

    /** DashScope sambert（zhichu 音色）语音合成默认地址。 */
    public static final String DEFAULT_ENDPOINT =
            "https://dashscope.aliyuncs.com/api/v1/services/aigc/text2audio/sambert-zhichu-v1";

    /** 输出音频 mime。 */
    static final String OUTPUT_MIME = "audio/wav";

    private static final long CALL_TIMEOUT_MS = 15_000;
    private static final String HEADER_AUTHORIZATION = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final OkHttpClient client;
    private final String apiKey;
    private final String endpoint;

    /**
     * @param apiKey   DashScope API Key
     * @param endpoint 合成接口地址；测试注入 MockWebServer URL，生产用 {@link #DEFAULT_ENDPOINT}
     */
    public AliyunTtsProvider(OkHttpClient client, String apiKey, String endpoint) {
        // 派生 callTimeout 15s（音频合成比 ASR 慢），不改动调用方传入的 client
        this.client = client.newBuilder().callTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS).build();
        this.apiKey = apiKey;
        this.endpoint = endpoint;
    }

    @Override
    public Reply synthesize(String text, SessionContext ctx) {
        String bodyJson;
        try {
            bodyJson = buildBody(text);
        } catch (IOException e) {
            throw new RuntimeException("aliyun tts request body failed: " + e.getMessage(), e);
        }
        Request request = new Request.Builder()
                .url(endpoint)
                .post(RequestBody.create(bodyJson, JSON_MEDIA_TYPE))
                .header(HEADER_AUTHORIZATION, BEARER_PREFIX + apiKey)
                .build();
        try (Response response = client.newCall(request).execute()) {
            byte[] bytes = response.body() == null ? new byte[0] : response.body().bytes();
            if (!response.isSuccessful()) {
                throw new RuntimeException("aliyun tts returned HTTP " + response.code() + ": "
                        + new String(bytes, StandardCharsets.UTF_8));
            }
            return Reply.ofAudio(OUTPUT_MIME, bytes);
        } catch (IOException e) {
            throw new RuntimeException("aliyun tts request failed: " + e.getMessage(), e);
        }
    }

    /** {"text":...,"format":"wav","sample_rate":16000} */
    private static String buildBody(String text) throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("text", text);
        root.put("format", "wav");
        root.put("sample_rate", 16_000);
        return MAPPER.writeValueAsString(root);
    }
}
