package com.autovoice.server.llm;

import com.autovoice.server.contracts.LlmProvider;
import com.autovoice.server.contracts.Reply;
import com.autovoice.server.contracts.SessionContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * DeepSeek（OpenAI 兼容 chat/completions）云端 LLM 适配器。
 *
 * <p>请求：POST {@link #DEFAULT_ENDPOINT}，头 {@code Authorization: Bearer &lt;apiKey&gt;}，
 * Content-Type {@code application/json; charset=utf-8}，body = OpenAI 兼容 messages
 * （system 提示词 + user 文本），{@code "stream":false}。</p>
 *
 * <p>响应：解析 {@code choices[0].message.content} → {@link Reply#ofText(String)}；
 * choices 为空或 content 缺失抛 {@link LlmException}（LLM 侧异常）。</p>
 *
 * <p>错误语义：IO 异常（网络失败/超时）包装为 {@link RuntimeException}，HTTP 非 2xx
 * 抛 {@link LlmException}——均在 supplyAsync lambda 内包装使 future 异常完成，
 * 由仲裁（RaceArbiter）safety 兜底收敛。</p>
 */
public final class DeepSeekLlmProvider implements LlmProvider {

    /** DeepSeek OpenAI 兼容端点默认地址。 */
    public static final String DEFAULT_ENDPOINT = "https://api.deepseek.com/chat/completions";

    /** OpenAI 兼容 model 名。 */
    static final String MODEL = "deepseek-chat";

    /** 系统提示词（brief 原文，逐字保留）。 */
    static final String SYSTEM_PROMPT = "你是车载语音助手，回答简短口语化，不超过两句话。";

    static final String HEADER_AUTHORIZATION = "Authorization";

    private static final long CALL_TIMEOUT_MS = 10_000;
    private static final String BEARER_PREFIX = "Bearer ";
    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final OkHttpClient client;
    private final String apiKey;
    private final String endpoint;

    /** @param endpoint DeepSeek 接口地址；测试注入 MockWebServer URL，生产用 {@link #DEFAULT_ENDPOINT} */
    public DeepSeekLlmProvider(OkHttpClient client, String apiKey, String endpoint) {
        // 派生 callTimeout 10s，不改动调用方传入的 client
        this.client = client.newBuilder().callTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS).build();
        this.apiKey = apiKey;
        this.endpoint = endpoint;
    }

    @Override
    public CompletableFuture<Reply> chat(String text, SessionContext ctx) {
        // 同步 HTTP call 放进 supplyAsync（common pool），调用方立即可挂回调；
        // IO 异常在 lambda 内包装为 RuntimeException，future 以该异常完成。
        return CompletableFuture.supplyAsync(() -> {
            try {
                return callAndParse(text);
            } catch (IOException e) {
                throw new RuntimeException("deepseek llm request failed: " + e.getMessage(), e);
            }
        });
    }

    private Reply callAndParse(String text) throws IOException {
        Request request = new Request.Builder()
                .url(endpoint)
                .post(RequestBody.create(buildRequestBody(text), JSON_MEDIA_TYPE))
                .header(HEADER_AUTHORIZATION, BEARER_PREFIX + apiKey)
                .build();
        try (Response response = client.newCall(request).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new LlmException("deepseek llm returned HTTP " + response.code() + ": " + body);
            }
            return parseCompletion(body);
        }
    }

    /** OpenAI 兼容请求体：system + user 两条消息，stream=false。 */
    private static String buildRequestBody(String text) throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", MODEL);
        ArrayNode messages = root.putArray("messages");
        messages.addObject().put("role", "system").put("content", SYSTEM_PROMPT);
        messages.addObject().put("role", "user").put("content", text);
        root.put("stream", false);
        return MAPPER.writeValueAsString(root);
    }

    /** 解析 choices[0].message.content；choices 为空或 content 缺失视为 LLM 侧异常。 */
    private static Reply parseCompletion(String body) throws IOException {
        JsonNode root = MAPPER.readTree(body);
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new LlmException("deepseek llm response has no choices: " + body);
        }
        JsonNode content = choices.get(0).path("message").path("content");
        if (content.isMissingNode() || content.isNull()) {
            throw new LlmException("deepseek llm response message has no content: " + body);
        }
        return Reply.ofText(content.asText());
    }
}
