package com.autovoice.server.llm;

import com.autovoice.server.contracts.FunctionTool;
import com.autovoice.server.contracts.Intent;
import com.autovoice.server.contracts.LlmProvider;
import com.autovoice.server.contracts.Reply;
import com.autovoice.server.contracts.SessionContext;
import com.autovoice.server.contracts.SlotValue;
import com.autovoice.server.contracts.SpeakTexts;
import com.autovoice.server.contracts.ToolExecutor;
import com.autovoice.server.contracts.ToolProvider;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * DeepSeek（OpenAI 兼容 chat/completions）云端 LLM 适配器，带 function calling 与多轮工具循环。
 *
 * <p>请求：POST {@link #DEFAULT_ENDPOINT}，头 {@code Authorization: Bearer &lt;apiKey&gt;}，
 * Content-Type {@code application/json; charset=utf-8}，body = OpenAI 兼容 messages
 * （system 提示词 + user 文本，工具轮追加 assistant tool_calls / tool 结果消息）
 * + {@code tools}（car_control skill + 注入的 MCP 工具）+ {@code stream:false}。</p>
 *
 * <p>多轮工具循环（spec §6）：最多 {@link #MAX_LLM_ROUNDS} 次 LLM 调用。第 1-2 次带 tools
 * （toolLoopBudgetMs 预算内）；第 3 次（最后）不带 tools 强制直答。每轮前检查
 * {@code now - start > budget} → 后续调用不带 tools。模型调用 {@code car_control} → 立即终局
 * （action 回复，不续轮）；MCP 工具 → {@link ToolExecutor#execute}（异常 → 错误文本作为
 * tool_result 回 LLM 续轮）；无 tools 的调用仍返回 tool_calls → {@link LlmException}。</p>
 *
 * <p>响应解析（两条路）：</p>
 * <ul>
 *   <li>模型调用 {@code car_control} 工具 → 解析 {@code tool_calls[].function.arguments}
 *       JSON（domain/action/temperature）→ {@link Intent} → {@link Reply#ofAction(Intent, String)}
 *       （speakText 按 domain/action 模板生成，不依赖模型自由文本）；</li>
 *   <li>无工具调用（闲聊/拒识）→ {@code choices[0].message.content} →
 *       {@link Reply#ofText(String)}。</li>
 * </ul>
 *
 * <p>错误语义：IO 异常（网络失败/超时）包装为 {@link RuntimeException}，HTTP 非 2xx
 * 抛 {@link LlmException}；工具调用解析失败（参数不完整、无 tools 仍调工具）抛
 * {@link LlmException} —— 均在 supplyAsync lambda 内包装使 future 异常完成，
 * 由调用方（SegmentPipeline）safety 兜底收敛。</p>
 */
public final class DeepSeekLlmProvider implements LlmProvider {

    /** DeepSeek OpenAI 兼容端点默认地址。 */
    public static final String DEFAULT_ENDPOINT = "https://api.deepseek.com/chat/completions";

    /** 工具循环预算（毫秒）：起算于首次 LLM 调用，超时后后续调用不再带 tools。 */
    public static final long DEFAULT_TOOL_LOOP_BUDGET_MS = 5_000;

    /** 单次 chat 的最大 LLM 调用轮数（最后 1 轮不带 tools 强制直答）。 */
    static final int MAX_LLM_ROUNDS = 3;

    /** OpenAI 兼容 model 名。 */
    static final String MODEL = "deepseek-chat";

    /** 系统提示词：车控指令 → car_control 工具，其余口语回答。 */
    static final String DEFAULT_SYSTEM_PROMPT =
            "你是车载语音助手。用户发出车控指令（开关空调、调节温度等）时，调用 car_control 工具输出结构化语义；"
                    + "其他情况用简短口语化回答，不超过两句话。";

    /** 车控 skill：结构化语义由模型以工具调用产出（skill 定义见 {@link #defaultTools()}）。 */
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

    /** OpenAI 兼容 tools 定义：car_control（domain/action/temperature）的 parameters 对象文本。 */
    private static final String CAR_CONTROL_PARAMETERS_JSON = """
            {"type":"object","properties":{
            "domain":{"type":"string","enum":["climate","window"],"description":"控制领域"},
            "action":{"type":"string","enum":["power_on","power_off","set_temperature"],"description":"执行的操作"},
            "temperature":{"type":"number","description":"目标温度，仅 action=set_temperature 时必填"}},
            "required":["domain","action"]}
            """;

    private final OkHttpClient client;
    private final String apiKey;
    private final String endpoint;
    /** 链路事件记录器（Task 4 插桩：llm；telemetry 禁用时是 Noop）。 */
    private final TelemetryRecorder recorder;
    /** 注入 LLM 的启用工具（car_control + MCP 工具合并；Task 9 起）。 */
    private final ToolProvider tools;
    /** MCP 工具执行器；null（4 参构造器）时工具结果回固定"不可用"文本。 */
    private final ToolExecutor executor;
    /** 工具循环预算（毫秒）；0 表示不进行任何工具轮（首轮即直答）。 */
    private final long toolLoopBudgetMs;
    /** system 提示词提供者（平台化配置）；null 或返回 null/空白 → 回退 {@link #DEFAULT_SYSTEM_PROMPT}。 */
    private final Supplier<String> systemPrompt;

    /**
     * 默认工具集：仅 car_control 车控 skill。
     *
     * @return 单个 {@link FunctionTool}（car_control）
     */
    public static List<FunctionTool> defaultTools() {
        return List.of(new FunctionTool(TOOL_NAME, "执行车载控制指令（开关空调、调节温度等）",
                CAR_CONTROL_PARAMETERS_JSON));
    }

    /**
     * 单轮适配器（与 Task 9 之前行为一致）：默认工具 + 默认预算 + 无 executor。
     *
     * @param endpoint DeepSeek 接口地址；测试注入 MockWebServer URL，生产用 {@link #DEFAULT_ENDPOINT}
     * @param recorder 链路事件记录器（Task 4 起）。llm 事件按调用方透传的 utteranceId 记录
     *                 （时间线"大模型"阶段与 llm_reply 聚合列依赖此贯通）。
     */
    public DeepSeekLlmProvider(OkHttpClient client, String apiKey, String endpoint,
                               TelemetryRecorder recorder) {
        this(client, apiKey, endpoint, recorder, DeepSeekLlmProvider::defaultTools,
                DEFAULT_TOOL_LOOP_BUDGET_MS, null, null);
    }

    /**
     * 多轮工具循环适配器（Task 9 起）。
     *
     * @param endpoint DeepSeek 接口地址；测试注入 MockWebServer URL，生产用 {@link #DEFAULT_ENDPOINT}
     * @param recorder 链路事件记录器
     * @param tools 注入 LLM 的工具提供者；null 时用 {@link #defaultTools()}
     * @param toolLoopBudgetMs 工具循环预算（毫秒）；0 表示首轮即直答（不带 tools），负数按 0 处理
     * @param executor 工具执行器；null 时工具调用以固定"不可用"文本回 LLM
     * @param systemPrompt system 提示词提供者；null 或返回 null/空白时回退内置默认文案
     */
    public DeepSeekLlmProvider(OkHttpClient client, String apiKey, String endpoint, TelemetryRecorder recorder,
                               ToolProvider tools, long toolLoopBudgetMs, ToolExecutor executor,
                               Supplier<String> systemPrompt) {
        // 派生 callTimeout 10s，不改动调用方传入的 client
        this.client = client.newBuilder().callTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS).build();
        this.apiKey = apiKey;
        this.endpoint = endpoint;
        this.recorder = recorder;
        this.tools = tools == null ? DeepSeekLlmProvider::defaultTools : tools;
        this.toolLoopBudgetMs = Math.max(0, toolLoopBudgetMs);
        this.executor = executor;
        this.systemPrompt = systemPrompt;
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

    /**
     * 多轮工具循环：最多 {@link #MAX_LLM_ROUNDS} 次 LLM 调用。每轮前检查预算
     * （超预算 → 不带 tools），最后一轮强制直答。car_control 工具调用立即终局。
     */
    private Reply callAndParse(String text) throws IOException {
        List<ObjectNode> messages = new ArrayList<>();
        messages.add(systemMessage());
        messages.add(userMessage(text));
        long start = System.currentTimeMillis();
        for (int round = 1; round <= MAX_LLM_ROUNDS; round++) {
            boolean budgetOk = toolLoopBudgetMs >= 1 && System.currentTimeMillis() - start <= toolLoopBudgetMs;
            boolean lastRound = round == MAX_LLM_ROUNDS;
            List<FunctionTool> tools = (budgetOk && !lastRound) ? this.tools.enabledTools() : List.of();
            JsonNode message = callChat(messages, tools);
            JsonNode toolCalls = message.path("tool_calls");
            if (!toolCalls.isArray() || toolCalls.isEmpty()) {
                return textReply(message);
            }
            if (isCarControl(toolCalls)) {
                return carControlReply(toolCalls);   // 终局：action 回复
            }
            if (tools.isEmpty()) {
                throw new LlmException("deepseek llm called tool without tools enabled");
            }
            for (JsonNode tc : toolCalls) {
                String id = tc.path("id").asText("");
                String name = tc.path("function").path("name").asText("");
                String args = tc.path("function").path("arguments").asText("");
                messages.add(assistantToolCallMessage(tc));
                messages.add(toolResultMessage(id, runTool(name, args)));
            }
        }
        // 不可达（第 3 轮必走上方 return/LlmException 分支：最后一轮 tools 为空，
        // tool_calls 非空即抛 LlmException）——仅为编译器可达性，轮数契约见 MAX_LLM_ROUNDS
        throw new LlmException("deepseek llm tool loop ended without terminal result");
    }

    /** 一次 LLM 调用：组装 messages + tools（空则不设 tools 字段），返回 choices[0].message。 */
    private JsonNode callChat(List<ObjectNode> messages, List<FunctionTool> tools) throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", MODEL);
        ArrayNode messagesArr = root.putArray("messages");
        messages.forEach(messagesArr::add);
        if (!tools.isEmpty()) {
            ArrayNode toolsArr = root.putArray("tools");
            for (FunctionTool tool : tools) {
                ObjectNode toolNode = toolsArr.addObject();
                toolNode.put("type", "function");
                ObjectNode fn = toolNode.putObject("function");
                fn.put("name", tool.name());
                fn.put("description", tool.description());
                fn.set("parameters", MAPPER.readTree(tool.parametersJson()));
            }
        }
        root.put("stream", false);
        Request request = new Request.Builder()
                .url(endpoint)
                .post(RequestBody.create(MAPPER.writeValueAsString(root), JSON_MEDIA_TYPE))
                .header(HEADER_AUTHORIZATION, BEARER_PREFIX + apiKey)
                .build();
        try (Response response = client.newCall(request).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new LlmException("deepseek llm returned HTTP " + response.code() + ": " + body);
            }
            JsonNode parsed = MAPPER.readTree(body);
            JsonNode choices = parsed.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                throw new LlmException("deepseek llm response has no choices: " + body);
            }
            return choices.get(0).path("message");
        }
    }

    /** 无工具调用 → content → text 回复；content 缺失视为 LLM 侧异常。 */
    private static Reply textReply(JsonNode message) {
        JsonNode content = message.path("content");
        if (content.isMissingNode() || content.isNull() || content.asText().isBlank()) {
            throw new LlmException("deepseek llm response message has no content or tool_calls: " + message);
        }
        return Reply.ofText(content.asText());
    }

    /** tool_calls 中任一 function.name == car_control。 */
    private static boolean isCarControl(JsonNode toolCalls) {
        for (JsonNode tc : toolCalls) {
            if (TOOL_NAME.equals(tc.path("function").path("name").asText(""))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解析 tool_calls 中第一个 car_control → action 回复（多工具混合时优先 car_control 终局，
     * 忽略其他工具调用）。
     */
    private static Reply carControlReply(JsonNode toolCalls) throws IOException {
        for (JsonNode tc : toolCalls) {
            String name = tc.path("function").path("name").asText("");
            if (!TOOL_NAME.equals(name)) {
                continue;
            }
            String arguments = tc.path("function").path("arguments").asText("");
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
        throw new LlmException("deepseek llm called unexpected tool: "
                + toolCalls.path(0).path("function").path("name").asText(""));
    }

    /**
     * 执行一次工具调用：executor 缺失返回固定"不可用"文本；工具抛 RuntimeException →
     * 错误文本作为 tool_result 回 LLM 续轮（不中断多轮循环）。
     */
    private String runTool(String name, String argumentsJson) {
        if (executor == null) {
            return "工具执行不可用";
        }
        try {
            return executor.execute(name, argumentsJson);
        } catch (RuntimeException e) {
            return "工具执行失败：" + e.getMessage();   // 错误文本回 LLM 续轮
        }
    }

    /** system 消息（OpenAI 兼容）；prompt 未配置（null/空白）回退内置默认。 */
    private ObjectNode systemMessage() {
        String prompt = systemPrompt == null ? null : systemPrompt.get();
        if (prompt == null || prompt.isBlank()) {
            prompt = DEFAULT_SYSTEM_PROMPT;
        }
        ObjectNode m = MAPPER.createObjectNode();
        m.put("role", "system");
        m.put("content", prompt);
        return m;
    }

    /** user 消息（OpenAI 兼容）。 */
    private static ObjectNode userMessage(String text) {
        ObjectNode m = MAPPER.createObjectNode();
        m.put("role", "user");
        m.put("content", text);
        return m;
    }

    /** assistant 工具调用消息：原样回传模型的 tool_calls。 */
    private static ObjectNode assistantToolCallMessage(JsonNode tc) {
        ObjectNode m = MAPPER.createObjectNode();
        m.put("role", "assistant");
        m.set("tool_calls", MAPPER.createArrayNode().add(tc));
        return m;
    }

    /** tool 结果消息：工具执行结果文本回 LLM 续轮。 */
    private static ObjectNode toolResultMessage(String toolCallId, String content) {
        ObjectNode m = MAPPER.createObjectNode();
        m.put("role", "tool");
        m.put("tool_call_id", toolCallId);
        m.put("content", content);
        return m;
    }
}
