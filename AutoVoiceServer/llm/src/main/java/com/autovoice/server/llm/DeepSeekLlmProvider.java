package com.autovoice.server.llm;

import com.autovoice.server.contracts.Intent;
import com.autovoice.server.contracts.LlmProvider;
import com.autovoice.server.contracts.Reply;
import com.autovoice.server.contracts.SessionContext;
import com.autovoice.server.contracts.SlotValue;
import com.autovoice.server.contracts.SpeakTexts;
import com.autovoice.server.contracts.telemetry.TelemetryRecorder;
import com.autovoice.server.contracts.telemetry.TelemetryStages;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * DeepSeek（OpenAI 兼容 chat/completions）云端 LLM 适配器，带 function calling。
 *
 * <p>请求：POST {@link #DEFAULT_ENDPOINT}，头 {@code Authorization: Bearer &lt;apiKey&gt;}，
 * Content-Type {@code application/json; charset=utf-8}，body = OpenAI 兼容 messages
 * （system 提示词 + user 文本）+ {@code tools}（车控 skill）+ {@code stream:false}。</p>
 *
 * <p>响应解析（两条路）：</p>
 * <ul>
 *   <li>模型调用 {@code car_control} 工具 → 解析 {@code tool_calls[0].function.arguments}
 *       JSON（domain/action/temperature）→ {@link Intent} → {@link Reply#ofAction(Intent, String)}
 *       （speakText 按 domain/action 模板生成，不依赖模型自由文本）；</li>
 *   <li>无工具调用（闲聊/拒识）→ {@code choices[0].message.content} →
 *       {@link Reply#ofText(String)}。</li>
 * </ul>
 *
 * <p>错误语义：IO 异常（网络失败/超时）包装为 {@link RuntimeException}，HTTP 非 2xx
 * 抛 {@link LlmException}；工具调用解析失败（非 car_control、参数不完整）抛
 * {@link LlmException} —— 均在 supplyAsync lambda 内包装使 future 异常完成，
 * 由调用方（SegmentPipeline）safety 兜底收敛。</p>
 */
public final class DeepSeekLlmProvider implements LlmProvider {

    /** DeepSeek OpenAI 兼容端点默认地址。 */
    public static final String DEFAULT_ENDPOINT = "https://api.deepseek.com/chat/completions";

    /** OpenAI 兼容 model 名。 */
    static final String MODEL = "deepseek-chat";

    /** 系统提示词：车控指令 → car_control 工具，其余口语回答。 */
    static final String SYSTEM_PROMPT =
            "你是车载语音助手。用户发出车控指令（开关空调、调节温度等）时，调用 car_control 工具输出结构化语义；"
                    + "其他情况用简短口语化回答，不超过两句话。";

    /** 车控 skill：结构化语义由模型以工具调用产出（skill 定义见 {@link #TOOLS_JSON}）。 */
    static final String TOOL_NAME = "car_control";

    /** intent.source 值：LLM 工具调用产出的意图。 */
    static final String INTENT_SOURCE = "llm.car_control";

    /** 槽位名（与端侧 RuleNluProvider / shared contracts 对齐）。 */
    static final String SLOT_TEMPERATURE = SpeakTexts.SLOT_TEMPERATURE;

    static final String HEADER_AUTHORIZATION = "Authorization";

    private static final long CALL_TIMEOUT_MS = 10_000;
    private static final String BEARER_PREFIX = "Bearer ";
    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** OpenAI 兼容 tools 定义：car_control（domain/action/temperature）。 */
    private static final String TOOLS_JSON = """
            [{"type":"function","function":{"name":"car_control","description":"执行车载控制指令（开关空调、调节温度等）",
            "parameters":{"type":"object","properties":{
            "domain":{"type":"string","enum":["climate","window"],"description":"控制领域"},
            "action":{"type":"string","enum":["power_on","power_off","set_temperature"],"description":"执行的操作"},
            "temperature":{"type":"number","description":"目标温度，仅 action=set_temperature 时必填"}},
            "required":["domain","action"]}}}]
            """;

    private final OkHttpClient client;
    private final String apiKey;
    private final String endpoint;
    /** 链路事件记录器（Task 4 插桩：llm；telemetry 禁用时是 Noop）。 */
    private final TelemetryRecorder recorder;

    /**
     * @param endpoint DeepSeek 接口地址；测试注入 MockWebServer URL，生产用 {@link #DEFAULT_ENDPOINT}
     * @param recorder 链路事件记录器（Task 4 起）。llm 事件按调用方透传的 utteranceId 记录
     *                 （时间线"大模型"阶段与 llm_reply 聚合列依赖此贯通）。
     */
    public DeepSeekLlmProvider(OkHttpClient client, String apiKey, String endpoint,
                               TelemetryRecorder recorder) {
        // 派生 callTimeout 10s，不改动调用方传入的 client
        this.client = client.newBuilder().callTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS).build();
        this.apiKey = apiKey;
        this.endpoint = endpoint;
        this.recorder = recorder;
    }

    @Override
    public CompletableFuture<Reply> chat(String text, SessionContext ctx) {
        // 旧入口（无 utteranceId）：事件无从归属，交由 record(null, …) 丢弃，不产生幽灵 round
        return chat(text, ctx, null);
    }

    @Override
    public CompletableFuture<Reply> chat(String text, SessionContext ctx, String utteranceId) {
        // 同步 HTTP call 放进 supplyAsync（common pool），调用方立即可挂回调；
        // IO 异常在 lambda 内包装为 RuntimeException，future 以该异常完成。
        return CompletableFuture.supplyAsync(() -> {
            long start = System.currentTimeMillis();
            try {
                Reply reply = callAndParse(text);
                recorder.record(utteranceId, TelemetryStages.LLM, "info",
                        Map.of("text", text, "reply", replySummary(reply),
                                "durationMs", Math.max(1, System.currentTimeMillis() - start)));
                return reply;
            } catch (IOException e) {
                String msg = String.valueOf(e.getMessage());
                recorder.record(utteranceId, TelemetryStages.LLM, "error",
                        Map.of("text", text, "error", msg,
                                "durationMs", Math.max(1, System.currentTimeMillis() - start)));
                throw new RuntimeException("deepseek llm request failed: " + msg, e);
            } catch (RuntimeException e) {
                // LlmException（HTTP 非 2xx / 解析失败）等：记事件后原样抛（future 异常完成）
                recorder.record(utteranceId, TelemetryStages.LLM, "error",
                        Map.of("text", text, "error", String.valueOf(e.getMessage()),
                                "durationMs", Math.max(1, System.currentTimeMillis() - start)));
                throw e;
            }
        });
    }

    /** reply 摘要：action → intent 摘要（domain/intent），text → 截断 80 字符。 */
    private static String replySummary(Reply reply) {
        Intent intent = reply.intent();
        if (intent != null) {
            return "action:" + intent.domain() + "/" + intent.intent();
        }
        String t = reply.text() == null ? "" : reply.text();
        return "text:" + (t.length() <= 80 ? t : t.substring(0, 80));
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

    /** OpenAI 兼容请求体：system + user 两条消息，tools 定义车控 skill，stream=false。 */
    private static String buildRequestBody(String text) throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", MODEL);
        ArrayNode messages = root.putArray("messages");
        messages.addObject().put("role", "system").put("content", SYSTEM_PROMPT);
        messages.addObject().put("role", "user").put("content", text);
        root.set("tools", MAPPER.readTree(TOOLS_JSON));
        root.put("stream", false);
        return MAPPER.writeValueAsString(root);
    }

    /**
     * 解析 choices[0].message：优先 tool_calls（car_control）→ action 回复；
     * 无工具调用 → content → text 回复。choices 为空视为 LLM 侧异常。
     */
    private static Reply parseCompletion(String body) throws IOException {
        JsonNode root = MAPPER.readTree(body);
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new LlmException("deepseek llm response has no choices: " + body);
        }
        JsonNode message = choices.get(0).path("message");
        Reply toolReply = parseToolCall(message);
        if (toolReply != null) {
            return toolReply;
        }
        JsonNode content = message.path("content");
        if (content.isMissingNode() || content.isNull() || content.asText().isBlank()) {
            throw new LlmException("deepseek llm response message has no content or tool_calls: " + body);
        }
        return Reply.ofText(content.asText());
    }

    /** 解析 message.tool_calls[0] → car_control action 回复；无工具调用返回 null。 */
    private static Reply parseToolCall(JsonNode message) throws IOException {
        JsonNode toolCalls = message.path("tool_calls");
        if (!toolCalls.isArray() || toolCalls.isEmpty()) {
            return null;
        }
        JsonNode fn = toolCalls.get(0).path("function");
        String name = fn.path("name").asText("");
        String arguments = fn.path("arguments").asText("");
        if (!TOOL_NAME.equals(name)) {
            throw new LlmException("deepseek llm called unexpected tool: " + name);
        }
        JsonNode args = MAPPER.readTree(arguments);
        String domain = args.path("domain").asText("");
        String action = args.path("action").asText("");
        if (domain.isBlank() || action.isBlank()) {
            throw new LlmException("deepseek llm car_control missing domain/action: " + arguments);
        }
        Map<String, SlotValue> slots = new LinkedHashMap<>();
        JsonNode temperature = args.path("temperature");
        if (temperature.isNumber()) {
            slots.put(SLOT_TEMPERATURE, SlotValue.number(temperature.asDouble()));
        }
        Intent intent = Intent.of("1.0", domain, action, slots, 1.0, INTENT_SOURCE, arguments);
        return Reply.ofAction(intent, SpeakTexts.speak(intent));
    }
}
