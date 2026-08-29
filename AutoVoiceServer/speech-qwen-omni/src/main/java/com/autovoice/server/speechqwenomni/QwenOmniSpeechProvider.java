package com.autovoice.server.speechqwenomni;

import com.autovoice.server.agentloop.AgentLoop;
import com.autovoice.server.agentloop.AgentToolCall;
import com.autovoice.server.agentloop.AgentToolResult;
import com.autovoice.server.agentloop.NavigationCandidateReplies;
import com.autovoice.server.agentloop.RequestToolExecutor;
import com.autovoice.server.agentloop.ToolSchemaCompactor;
import com.autovoice.server.contracts.FunctionTool;
import com.autovoice.server.contracts.Intent;
import com.autovoice.server.contracts.OnlineSpeechProvider;
import com.autovoice.server.contracts.OnlineSpeechResult;
import com.autovoice.server.contracts.OnlineAudioSink;
import com.autovoice.server.contracts.Reply;
import com.autovoice.server.contracts.SessionContext;
import com.autovoice.server.contracts.SlotValue;
import com.autovoice.server.contracts.SpeakTexts;
import com.autovoice.server.contracts.ToolExecutor;
import com.autovoice.server.contracts.ToolProvider;
import com.autovoice.server.contracts.VehicleAgentTools;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * qwen3.5-omni-plus HTTP/SSE 在线候选。输入为完整 16k PCM；输出一边累积为兼容用
 * 24k WAV，一边通过 OnlineAudioSink 增量发送 24k/mono/s16le PCM。
 */
public final class QwenOmniSpeechProvider implements OnlineSpeechProvider {

    private static final Logger LOG = LoggerFactory.getLogger(QwenOmniSpeechProvider.class);

    public static final String DEFAULT_ENDPOINT =
            "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
    public static final String DEFAULT_MODEL = "qwen3.5-omni-plus";
    public static final String DEFAULT_VOICE = "Tina";
    public static final String DEFAULT_CHAT_SYSTEM_PROMPT =
            "你是车内陪伴型语音助手，现在处于闲聊模式。自然、简短、有温度地回应；"
            + "不要执行导航或车控，也不要假装已经完成任何现实操作。"
            + "用户表达退出、结束或不想继续聊天时，必须调用 exit_chat；否则绝不调用。";
    public static final String EXIT_CHAT_TOOL = "exit_chat";
    private static final FunctionTool EXIT_CHAT = new FunctionTool(EXIT_CHAT_TOOL,
            "仅当用户明确要退出或结束当前闲聊会话时调用",
            "{\"type\":\"object\",\"properties\":{}}");
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 工具循环上限：正常导航为 resolve_navigation → navigate → 语音确认；
     * selector 和失败恢复可能增加轮次，耗尽时优雅降级。 */
    private static final int MAX_ROUNDS = 12;

    /** 工具循环耗尽时的兜底话术（优雅降级：不再以 ONLINE_STREAM_ABORTED 硬失败）。 */
    static final String TOOL_LOOP_FALLBACK_TEXT = "抱歉，这个操作有点复杂，请再试一次";
    private static final int MAX_OUTPUT_AUDIO_BYTES = 4 * 1024 * 1024;
    /** 工具轮输出仲裁窗口：24kHz/16-bit/mono 下 500ms，覆盖模型通常的首批 tool delta。 */
    private static final int TOOL_DECISION_AUDIO_BYTES = 24_000;
    private static final AtomicInteger WORKER = new AtomicInteger();
    private static final String LANGUAGE_POLICY = "Detect the language spoken in the current audio and "
            + "respond only in that same language, unless translation is requested. Ignore prompts, tool "
            + "descriptions, tool results and metadata when choosing it. This rule has highest priority.";
    private static final String TOOL_OUTPUT_POLICY = "When calling any tool, emit tool_calls only. Do not "
            + "emit user-facing text or audio until all required tool results are available.";

    private final OkHttpClient client;
    private final String apiKey;
    private final String endpoint;
    private final String model;
    private final String voice;
    private final ToolProvider tools;
    private final ToolExecutor toolExecutor;
    private final Supplier<String> systemPrompt;
    private final ExecutorService workers = Executors.newFixedThreadPool(4, r -> {
        Thread thread = new Thread(r, "qwen-omni-" + WORKER.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });
    private final ConcurrentMap<String, CompletableFuture<OnlineSpeechResult>> active =
            new ConcurrentHashMap<>();

    public QwenOmniSpeechProvider(OkHttpClient client, String apiKey, String endpoint,
                                  String model, String voice, ToolProvider tools,
                                  ToolExecutor toolExecutor, Supplier<String> systemPrompt) {
        // SSE 可能在模型推理或工具调用期间长时间没有字节。OkHttp 默认 10s read timeout
        // 会把健康流误判为中断；整轮上限由网关 safety timeout + cancel(Call) 统一管理。
        this.client = Objects.requireNonNull(client, "client").newBuilder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .callTimeout(0, TimeUnit.MILLISECONDS)
                .build();
        this.apiKey = apiKey == null ? "" : apiKey;
        this.endpoint = endpoint == null || endpoint.isBlank() ? DEFAULT_ENDPOINT : endpoint;
        this.model = model == null || model.isBlank() ? DEFAULT_MODEL : model;
        this.voice = voice == null || voice.isBlank() ? DEFAULT_VOICE : voice;
        this.tools = tools == null ? QwenOmniSpeechProvider::defaultTools : tools;
        this.toolExecutor = toolExecutor;
        this.systemPrompt = systemPrompt;
    }

    public static List<FunctionTool> defaultTools() {
        return VehicleAgentTools.definitions();
    }

    public static FunctionTool exitChatTool() {
        return EXIT_CHAT;
    }

    @Override
    public CompletableFuture<OnlineSpeechResult> process(
            byte[] pcm16k, SessionContext context, String utteranceId) {
        return process(pcm16k, context, utteranceId, OnlineAudioSink.NOOP);
    }

    @Override
    public CompletableFuture<OnlineSpeechResult> process(
            byte[] pcm16k, SessionContext context, String utteranceId, OnlineAudioSink audioSink) {
        if (apiKey.isBlank()) {
            throw new IllegalStateException("QWEN_OMNI_API_KEY/DASHSCOPE_API_KEY is empty");
        }
        AtomicReference<Call> activeCall = new AtomicReference<>();
        AtomicReference<Future<?>> task = new AtomicReference<>();
        OnlineAudioSink sink = audioSink == null ? OnlineAudioSink.NOOP : audioSink;
        CompletableFuture<OnlineSpeechResult> out = new CompletableFuture<>() {
            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                Call call = activeCall.get();
                if (call != null) call.cancel();
                Future<?> running = task.get();
                if (running != null) running.cancel(mayInterruptIfRunning);
                return super.cancel(mayInterruptIfRunning);
            }
        };
        task.set(workers.submit(() -> {
            try {
                out.complete(runConversation(pcm16k, context, activeCall, sink));
            } catch (Throwable error) {
                if (!out.isCancelled()) sink.onError(error);
                if (!out.isCancelled()) out.completeExceptionally(error);
            }
        }));
        if (utteranceId != null && !utteranceId.isBlank()) {
            active.put(utteranceId, out);
            out.whenComplete((ignored, error) -> active.remove(utteranceId, out));
        }
        return out;
    }

    @Override
    public String id() {
        return "qwen-omni";
    }

    @Override
    public long minimumTurnTimeoutMs() {
        return 45_000;
    }

    @Override
    public void cancel(String utteranceId) {
        CompletableFuture<OnlineSpeechResult> future = active.remove(utteranceId);
        if (future != null) future.cancel(true);
    }

    private OnlineSpeechResult runConversation(byte[] pcm16k, SessionContext context,
                                               AtomicReference<Call> activeCall,
                                               OnlineAudioSink audioSink)
            throws IOException {
        ArrayNode messages = MAPPER.createArrayNode();
        ObjectNode system = messages.addObject();
        system.put("role", "system");
        String configuredPrompt = systemPrompt == null ? "" : systemPrompt.get();
        String basePrompt = configuredPrompt == null || configuredPrompt.isBlank()
                ? "You are an in-car voice assistant. Keep responses brief and natural. "
                    + "Vehicle control and navigation must use tools."
                : configuredPrompt;
        List<FunctionTool> requestToolSnapshot = ToolSchemaCompactor.compact(tools.enabledTools());
        system.put("content", LANGUAGE_POLICY + "\n" + TOOL_OUTPUT_POLICY + "\nRules: " + basePrompt
                + navigationDialogPolicy(requestToolSnapshot)
                + locationPolicy(context, requestToolSnapshot));

        ObjectNode user = messages.addObject();
        user.put("role", "user");
        ArrayNode content = user.putArray("content");
        ObjectNode audio = content.addObject();
        audio.put("type", "input_audio");
        audio.putObject("input_audio")
                .put("data", "data:audio/wav;base64," + Base64.getEncoder().encodeToString(wav(pcm16k, 16_000)))
                .put("format", "wav");

        AtomicReference<Intent> terminalIntent = new AtomicReference<>();
        AtomicReference<String> lastAssistantText = new AtomicReference<>("");
        RequestToolExecutor requestTools = new RequestToolExecutor(call -> {
            ToolCall qwenCall = new ToolCall(call.id(), call.name(), call.argumentsJson());
            if (VehicleAgentTools.CAR_CONTROL.equals(call.name())
                    || VehicleAgentTools.NAVIGATE.equals(call.name())
                    || EXIT_CHAT_TOOL.equals(call.name())) {
                terminalIntent.set(parseTerminal(qwenCall));
                return "Action validated. Reply briefly in the user's spoken language.";
            }
            long started = System.currentTimeMillis();
            String result = executeTool(qwenCall);
            LOG.info("qwen omni tool {} completed in {}ms", call.name(),
                    Math.max(1, System.currentTimeMillis() - started));
            return result;
        }, (call, error) -> "工具执行失败：" + error.getMessage());

        AgentLoop<StreamResult, OnlineSpeechResult> loop = new AgentLoop<>(
                new AgentLoop.Policy(MAX_ROUNDS, Long.MAX_VALUE, false), requestTools,
                new AgentLoop.Adapter<>() {
                    @Override
                    public StreamResult callModel(int round, boolean toolsAllowed) throws Exception {
                        boolean effectiveTools = toolsAllowed && terminalIntent.get() == null
                                && !requestToolSnapshot.isEmpty();
                        long started = System.currentTimeMillis();
                        StreamResult result = call(messages, effectiveTools, requestToolSnapshot,
                                activeCall, audioSink);
                        LOG.info("qwen omni round {} completed in {}ms: {} tool call(s), toolsAllowed={}",
                                round, Math.max(1, System.currentTimeMillis() - started),
                                result.toolCalls.size(), effectiveTools);
                        if (!result.text.isBlank()) lastAssistantText.set(result.text);
                        return result;
                    }

                    @Override
                    public List<AgentToolCall> toolCalls(StreamResult result) {
                        return result.toolCalls.stream()
                                .map(call -> new AgentToolCall(call.id, call.name, call.arguments))
                                .toList();
                    }

                    @Override
                    public Optional<OnlineSpeechResult> terminal(
                            StreamResult message, List<AgentToolCall> calls) {
                        // Omni must make one final no-tools call to synthesize the spoken confirmation.
                        return Optional.empty();
                    }

                    @Override
                    public Optional<OnlineSpeechResult> terminalAfterTools(
                            StreamResult message, List<AgentToolResult> results) {
                        return NavigationCandidateReplies.from(results)
                                .map(reply -> new OnlineSpeechResult(reply, ""));
                    }

                    @Override
                    public void appendToolResults(StreamResult result, List<AgentToolResult> results) {
                        ObjectNode assistant = messages.addObject();
                        assistant.put("role", "assistant");
                        assistant.put("content", result.text);
                        ArrayNode assistantCalls = assistant.putArray("tool_calls");
                        result.toolCalls.forEach(call -> assistantCalls.add(call.toJson()));
                        for (AgentToolResult toolResult : results) {
                            if (toolResult.cached()) {
                                LOG.info("qwen omni reused duplicate tool result: {}", toolResult.call().name());
                            }
                            ObjectNode tool = messages.addObject();
                            tool.put("role", "tool");
                            tool.put("tool_call_id", toolResult.call().id());
                            tool.put("content", toolResult.content());
                        }
                    }

                    @Override
                    public OnlineSpeechResult finish(StreamResult result) throws Exception {
                        Intent intent = terminalIntent.get();
                        if (result.audio.length > 0) {
                            audioSink.onComplete(result.text, intent);
                            return new OnlineSpeechResult(Reply.ofAudio("audio/wav",
                                    normalizeOutput(result.audio), result.text, intent), "");
                        }
                        if (!result.text.isBlank()) {
                            return new OnlineSpeechResult(intent == null ? Reply.ofText(result.text)
                                    : Reply.ofAction(intent, result.text), "");
                        }
                        throw new IOException("qwen omni returned no text/audio/tool calls");
                    }

                    @Override
                    public OnlineSpeechResult exhausted(StreamResult ignored) {
                        Intent intent = terminalIntent.get();
                        String lastText = lastAssistantText.get();
                        if (intent != null) {
                            String speak = lastText.isBlank() ? SpeakTexts.speak(intent) : lastText;
                            audioSink.onReplyText(speak, true);
                            return new OnlineSpeechResult(Reply.ofAction(intent, speak), "");
                        }
                        if (!lastText.isBlank()) return new OnlineSpeechResult(Reply.ofText(lastText), "");
                        audioSink.onReplyText(TOOL_LOOP_FALLBACK_TEXT, true);
                        return new OnlineSpeechResult(Reply.ofText(TOOL_LOOP_FALLBACK_TEXT), "");
                    }
                });
        try {
            return loop.run();
        } catch (IOException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("qwen omni request cancelled", e);
        } catch (Exception e) {
            throw new IOException("qwen omni agent loop failed", e);
        }
    }

    private static String locationPolicy(SessionContext context, List<FunctionTool> enabledTools) {
        Object lat = context == null ? null : context.attrs().get("latitude");
        Object lon = context == null ? null : context.attrs().get("longitude");
        if (!(lat instanceof Number) || !(lon instanceof Number)) return "";
        String location = "\n车辆坐标(lon,lat)=" + ((Number) lon).doubleValue() + ","
                + ((Number) lat).doubleValue() + ". ";
        return enabledTools.stream().anyMatch(t -> "resolve_navigation".equals(t.name()))
                ? location + "Pass it as resolve_navigation.location; prefer nearby candidates. Preserve stop order."
                : location + "Prefer nearby candidates and preserve stop order.";
    }

    private static String navigationDialogPolicy(List<FunctionTool> enabledTools) {
        return enabledTools.stream().anyMatch(t -> "resolve_navigation".equals(t.name()))
                ? "\nFor a single destination, call resolve_navigation first to show candidates. "
                    + "Do not choose one or call navigate in that turn; navigation starts only after "
                    + "the user's next-turn ordinal or place-name selection."
                : "";
    }

    private StreamResult call(ArrayNode messages, boolean allowTools, List<FunctionTool> enabledTools,
                              AtomicReference<Call> activeCall, OnlineAudioSink audioSink)
            throws IOException {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);
        body.put("stream", true);
        body.putArray("modalities").add("text").add("audio");
        body.putObject("audio").put("voice", voice).put("format", "wav");
        body.set("messages", messages);
        if (allowTools) {
            ArrayNode requestTools = body.putArray("tools");
            for (FunctionTool spec : enabledTools) {
                ObjectNode item = requestTools.addObject();
                item.put("type", "function");
                ObjectNode fn = item.putObject("function");
                fn.put("name", spec.name());
                fn.put("description", spec.description());
                fn.set("parameters", MAPPER.readTree(spec.parametersJson()));
            }
            body.put("tool_choice", "auto");
            // 多途经点的 POI 查询互不依赖。让模型在同一轮返回多个查询可省掉一轮或多轮
            // Qwen 推理；执行结果仍按各自 tool_call_id 回填，依赖结果的 navigate 留到下一轮。
            body.put("parallel_tool_calls", true);
        }

        Request request = new Request.Builder().url(endpoint)
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "text/event-stream")
                .post(RequestBody.create(MAPPER.writeValueAsBytes(body), JSON))
                .build();
        Call call = client.newCall(request);
        activeCall.set(call);
        try (Response response = call.execute()) {
            if (!response.isSuccessful()) {
                String error = response.body() == null ? "" : response.body().string();
                throw new IOException("qwen omni HTTP " + response.code() + ": "
                        + error.substring(0, Math.min(error.length(), 1000)));
            }
            if (response.body() == null) throw new IOException("qwen omni empty response body");
            return parseSse(new BufferedReader(new InputStreamReader(
                    response.body().byteStream(), StandardCharsets.UTF_8)), audioSink, allowTools);
        } finally {
            activeCall.compareAndSet(call, null);
        }
    }

    private static StreamResult parseSse(BufferedReader reader, OnlineAudioSink audioSink,
                                         boolean arbitrateToolOutput) throws IOException {
        StringBuilder text = new StringBuilder();
        StreamOutputArbiter output = new StreamOutputArbiter(audioSink, arbitrateToolOutput);
        StringBuilder audioBase64 = new StringBuilder();
        Map<Integer, MutableToolCall> calls = new LinkedHashMap<>();
        String line;
        while ((line = reader.readLine()) != null) {
            if (!line.startsWith("data:")) continue;
            String data = line.substring(5).trim();
            if (data.isEmpty() || "[DONE]".equals(data)) continue;
            JsonNode delta = MAPPER.readTree(data).path("choices").path(0).path("delta");
            JsonNode toolCalls = delta.path("tool_calls");
            boolean acceptToolCalls = false;
            if (toolCalls.isArray() && !toolCalls.isEmpty()) {
                acceptToolCalls = output.onToolCallDelta();
            }
            if (acceptToolCalls) {
                for (JsonNode tc : toolCalls) {
                    int index = tc.path("index").asInt(0);
                    MutableToolCall target = calls.computeIfAbsent(index, ignored -> new MutableToolCall());
                    if (tc.path("id").isTextual()) target.id = tc.path("id").asText();
                    JsonNode function = tc.path("function");
                    if (function.path("name").isTextual()) target.name.append(function.path("name").asText());
                    if (function.path("arguments").isTextual()) {
                        target.arguments.append(function.path("arguments").asText());
                    }
                }
            }
            JsonNode content = delta.path("content");
            if (content.isTextual()) {
                text.append(content.asText());
                output.onReplyText(text.toString());
            }
            JsonNode audioData = delta.path("audio").path("data");
            if (audioData.isTextual() && !audioData.asText().isEmpty()) {
                // audio.data is one continuous Base64 value split across SSE deltas.
                // Decode only complete quartets and retain 0..3 chars for the next delta.
                audioBase64.append(audioData.asText());
                int completeChars = audioBase64.length() - audioBase64.length() % 4;
                if (completeChars > 0) {
                    byte[] chunk = Base64.getDecoder().decode(audioBase64.substring(0, completeChars));
                    audioBase64.delete(0, completeChars);
                    output.onAudio(chunk);
                }
            }
        }
        if (!audioBase64.isEmpty()) {
            byte[] tail = Base64.getDecoder().decode(audioBase64.toString());
            output.onAudio(tail);
        }
        output.finish(text.toString());
        List<ToolCall> complete = new ArrayList<>();
        if (output.toolsWon()) {
            for (Map.Entry<Integer, MutableToolCall> entry : calls.entrySet()) {
                MutableToolCall tc = entry.getValue();
                complete.add(new ToolCall(tc.id.isBlank() ? "call_" + entry.getKey() : tc.id,
                        tc.name.toString(), tc.arguments.toString()));
            }
        }
        if (output.suppressedAudioBytes() > 0) {
            LOG.warn("qwen omni tool output won arbitration; suppressed {} intermediate audio bytes",
                    output.suppressedAudioBytes());
        }
        if (output.ignoredLateToolCalls()) {
            LOG.warn("qwen omni audio output was already committed; ignored late tool_calls");
        }
        return new StreamResult(text.toString(), output.resultAudio(), complete);
    }

    /** 单个模型轮只允许工具或音频一种输出通道胜出，避免向客户端提交不可撤销的混合流。 */
    private static final class StreamOutputArbiter {
        private enum Lane { UNDECIDED, AUDIO, TOOLS }

        private final OnlineAudioSink sink;
        private final ByteArrayOutputStream audio = new ByteArrayOutputStream();
        private Lane lane;
        private boolean streamStarted;
        private boolean ignoredLateToolCalls;
        private String latestText = "";

        StreamOutputArbiter(OnlineAudioSink sink, boolean arbitrateToolOutput) {
            this.sink = sink;
            lane = arbitrateToolOutput ? Lane.UNDECIDED : Lane.AUDIO;
        }

        boolean onToolCallDelta() {
            if (lane == Lane.AUDIO) {
                // 即使请求已禁用工具，模型仍可能违规返回纯 tool_calls。只要还没有
                // 向客户端提交音频，就允许工具通道接管，交给 AgentLoop 上限收敛。
                if (!streamStarted && audio.size() == 0) {
                    lane = Lane.TOOLS;
                    return true;
                }
                ignoredLateToolCalls = true;
                return false;
            }
            lane = Lane.TOOLS;
            return true;
        }

        void onReplyText(String text) {
            latestText = text;
            if (lane == Lane.AUDIO) sink.onReplyText(text, false);
        }

        void onAudio(byte[] chunk) throws IOException {
            if (chunk.length == 0) return;
            if (audio.size() + chunk.length > MAX_OUTPUT_AUDIO_BYTES) {
                throw new IOException("qwen omni audio exceeded " + MAX_OUTPUT_AUDIO_BYTES + " bytes");
            }
            audio.write(chunk);
            if (lane == Lane.AUDIO) {
                startIfNeeded();
                sink.onChunk(chunk);
            } else if (lane == Lane.UNDECIDED && audio.size() >= TOOL_DECISION_AUDIO_BYTES) {
                lane = Lane.AUDIO;
                startIfNeeded();
                if (!latestText.isBlank()) sink.onReplyText(latestText, false);
                sink.onChunk(audio.toByteArray());
            }
        }

        void finish(String text) {
            if (lane == Lane.UNDECIDED) {
                lane = Lane.AUDIO;
                if (audio.size() > 0) {
                    startIfNeeded();
                    if (!latestText.isBlank()) sink.onReplyText(latestText, false);
                    sink.onChunk(audio.toByteArray());
                }
            }
            if (lane == Lane.AUDIO && !text.isBlank()) sink.onReplyText(text, true);
        }

        boolean toolsWon() {
            return lane == Lane.TOOLS;
        }

        int suppressedAudioBytes() {
            return toolsWon() ? audio.size() : 0;
        }

        boolean ignoredLateToolCalls() {
            return ignoredLateToolCalls;
        }

        byte[] resultAudio() {
            return toolsWon() ? new byte[0] : audio.toByteArray();
        }

        private void startIfNeeded() {
            if (streamStarted) return;
            sink.onStart(24_000, 1, "pcm_s16le");
            streamStarted = true;
        }
    }

    private String executeTool(ToolCall call) {
        if (toolExecutor == null) return "工具执行不可用";
        try {
            return toolExecutor.execute(call.name, call.arguments);
        } catch (RuntimeException error) {
            return "工具执行失败：" + error.getMessage();
        }
    }

    private static Intent parseTerminal(ToolCall call) throws IOException {
        JsonNode args = MAPPER.readTree(call.arguments);
        if (EXIT_CHAT_TOOL.equals(call.name)) {
            return Intent.of("1.0", "conversation", "exit_chat", Map.of(), 1.0,
                    "qwen-omni.exit-chat", call.arguments);
        }
        if ("car_control".equals(call.name)) {
            String domain = args.path("domain").asText("");
            String action = args.path("action").asText("");
            if (domain.isBlank() || action.isBlank()) throw new IOException("invalid car_control arguments");
            Map<String, SlotValue> slots = new LinkedHashMap<>();
            if (args.path("temperature").isNumber()) {
                slots.put(SpeakTexts.SLOT_TEMPERATURE, SlotValue.number(args.path("temperature").asDouble()));
            }
            return Intent.of("1.0", domain, action, slots, 1.0, "qwen-omni.car_control", call.arguments);
        }
        String poiname = args.path("poiname").asText("");
        if (poiname.isBlank() || !args.path("lat").isNumber() || !args.path("lon").isNumber()) {
            throw new IOException("invalid navigate arguments");
        }
        Map<String, SlotValue> slots = new LinkedHashMap<>();
        slots.put(SpeakTexts.SLOT_POINAME, SlotValue.stringValue(poiname));
        slots.put("lat", SlotValue.number(args.path("lat").asDouble()));
        slots.put("lon", SlotValue.number(args.path("lon").asDouble()));
        JsonNode waypoints = args.path(SpeakTexts.SLOT_WAYPOINTS);
        if (waypoints.isArray() && !waypoints.isEmpty()) {
            slots.put(SpeakTexts.SLOT_WAYPOINTS, SlotValue.stringValue(MAPPER.writeValueAsString(waypoints)));
        }
        return Intent.of("1.0", "navigation", "navigate", slots, 1.0,
                "qwen-omni.navigate", call.arguments);
    }

    private static byte[] normalizeOutput(byte[] bytes) {
        if (bytes.length >= 4 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F') {
            return bytes;
        }
        return wav(bytes, 24_000);
    }

    static byte[] wav(byte[] pcm, int sampleRate) {
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        header.putInt(36 + pcm.length);
        header.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII));
        header.putInt(16).putShort((short) 1).putShort((short) 1);
        header.putInt(sampleRate).putInt(sampleRate * 2).putShort((short) 2).putShort((short) 16);
        header.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(pcm.length);
        ByteArrayOutputStream out = new ByteArrayOutputStream(44 + pcm.length);
        out.writeBytes(header.array());
        out.writeBytes(pcm);
        return out.toByteArray();
    }

    private record StreamResult(String text, byte[] audio, List<ToolCall> toolCalls) {}

    private record ToolCall(String id, String name, String arguments) {
        ObjectNode toJson() {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("id", id).put("type", "function");
            node.putObject("function").put("name", name).put("arguments", arguments);
            return node;
        }
    }

    private static final class MutableToolCall {
        String id = "";
        final StringBuilder name = new StringBuilder();
        final StringBuilder arguments = new StringBuilder();
    }
}
