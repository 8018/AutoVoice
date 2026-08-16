package com.autovoice.server.speechqwenomni;

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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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

    public static final String DEFAULT_ENDPOINT =
            "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
    public static final String DEFAULT_MODEL = "qwen3.5-omni-plus";
    public static final String DEFAULT_VOICE = "Tina";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_ROUNDS = 7;
    private static final int MAX_OUTPUT_AUDIO_BYTES = 4 * 1024 * 1024;
    private static final AtomicInteger WORKER = new AtomicInteger();

    private static final String CAR_PARAMETERS = """
            {"type":"object","properties":{
            "domain":{"type":"string","enum":["climate","window"]},
            "action":{"type":"string","enum":["power_on","power_off","set_temperature"]},
            "temperature":{"type":"number"}},"required":["domain","action"]}
            """;
    private static final String NAV_PARAMETERS = """
            {"type":"object","properties":{
            "poiname":{"type":"string"},"lat":{"type":"number"},"lon":{"type":"number"},
            "waypoints":{"type":"array","items":{"type":"object","properties":{
            "poiname":{"type":"string"},"lat":{"type":"number"},"lon":{"type":"number"}},
            "required":["poiname","lat","lon"]}}},"required":["poiname","lat","lon"]}
            """;

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
        this.client = Objects.requireNonNull(client, "client");
        this.apiKey = apiKey == null ? "" : apiKey;
        this.endpoint = endpoint == null || endpoint.isBlank() ? DEFAULT_ENDPOINT : endpoint;
        this.model = model == null || model.isBlank() ? DEFAULT_MODEL : model;
        this.voice = voice == null || voice.isBlank() ? DEFAULT_VOICE : voice;
        this.tools = tools == null ? QwenOmniSpeechProvider::defaultTools : tools;
        this.toolExecutor = toolExecutor;
        this.systemPrompt = systemPrompt;
    }

    public static List<FunctionTool> defaultTools() {
        return List.of(
                new FunctionTool("car_control", "执行车载控制指令", CAR_PARAMETERS),
                new FunctionTool("navigate", "发起导航，支持途经点", NAV_PARAMETERS));
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
                out.complete(runConversation(pcm16k, activeCall, sink));
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

    private OnlineSpeechResult runConversation(byte[] pcm16k, AtomicReference<Call> activeCall,
                                               OnlineAudioSink audioSink)
            throws IOException {
        ArrayNode messages = MAPPER.createArrayNode();
        ObjectNode system = messages.addObject();
        system.put("role", "system");
        String configuredPrompt = systemPrompt == null ? "" : systemPrompt.get();
        system.put("content", configuredPrompt == null || configuredPrompt.isBlank()
                ? "你是车载语音助手。回答简短自然；车控和导航必须调用工具。" : configuredPrompt);

        ObjectNode user = messages.addObject();
        user.put("role", "user");
        ArrayNode content = user.putArray("content");
        ObjectNode audio = content.addObject();
        audio.put("type", "input_audio");
        audio.putObject("input_audio")
                .put("data", "data:audio/wav;base64," + Base64.getEncoder().encodeToString(wav(pcm16k, 16_000)))
                .put("format", "wav");
        content.addObject().put("type", "text")
                .put("text", "理解这段语音并直接用简短中文语音回答；需要工具时先调用工具。");

        Intent terminalIntent = null;
        boolean allowTools = true;
        for (int round = 0; round < MAX_ROUNDS; round++) {
            StreamResult result = call(messages, allowTools, activeCall, audioSink);
            if (result.toolCalls.isEmpty()) {
                if (result.audio.length > 0) {
                    audioSink.onComplete(result.text, terminalIntent);
                    return new OnlineSpeechResult(
                            Reply.ofAudio("audio/wav", normalizeOutput(result.audio), result.text, terminalIntent), "");
                }
                if (!result.text.isBlank()) {
                    return new OnlineSpeechResult(terminalIntent == null
                            ? Reply.ofText(result.text)
                            : Reply.ofAction(terminalIntent, result.text), "");
                }
                throw new IOException("qwen omni returned no text/audio/tool calls");
            }

            ObjectNode assistant = messages.addObject();
            assistant.put("role", "assistant");
            assistant.put("content", result.text);
            ArrayNode assistantCalls = assistant.putArray("tool_calls");
            for (ToolCall tc : result.toolCalls) assistantCalls.add(tc.toJson());

            for (ToolCall tc : result.toolCalls) {
                String toolResult;
                if ("car_control".equals(tc.name) || "navigate".equals(tc.name)) {
                    terminalIntent = parseTerminal(tc);
                    toolResult = "动作已校验，将由车端执行。请生成简短确认语音。";
                    allowTools = false;
                } else {
                    toolResult = executeTool(tc);
                }
                ObjectNode tool = messages.addObject();
                tool.put("role", "tool");
                tool.put("tool_call_id", tc.id);
                tool.put("content", toolResult);
            }
        }
        throw new IOException("qwen omni tool loop exceeded " + MAX_ROUNDS + " rounds");
    }

    private StreamResult call(ArrayNode messages, boolean allowTools, AtomicReference<Call> activeCall,
                              OnlineAudioSink audioSink)
            throws IOException {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);
        body.put("stream", true);
        body.putArray("modalities").add("text").add("audio");
        body.putObject("audio").put("voice", voice).put("format", "wav");
        body.set("messages", messages);
        if (allowTools) {
            ArrayNode requestTools = body.putArray("tools");
            for (FunctionTool spec : tools.enabledTools()) {
                ObjectNode item = requestTools.addObject();
                item.put("type", "function");
                ObjectNode fn = item.putObject("function");
                fn.put("name", spec.name());
                fn.put("description", spec.description());
                fn.set("parameters", MAPPER.readTree(spec.parametersJson()));
            }
            body.put("tool_choice", "auto");
            body.put("parallel_tool_calls", false);
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
                    response.body().byteStream(), StandardCharsets.UTF_8)), audioSink);
        } finally {
            activeCall.compareAndSet(call, null);
        }
    }

    private static StreamResult parseSse(BufferedReader reader, OnlineAudioSink audioSink) throws IOException {
        StringBuilder text = new StringBuilder();
        ByteArrayOutputStream audio = new ByteArrayOutputStream();
        StringBuilder audioBase64 = new StringBuilder();
        Map<Integer, MutableToolCall> calls = new LinkedHashMap<>();
        boolean streamStarted = false;
        String line;
        while ((line = reader.readLine()) != null) {
            if (!line.startsWith("data:")) continue;
            String data = line.substring(5).trim();
            if (data.isEmpty() || "[DONE]".equals(data)) continue;
            JsonNode delta = MAPPER.readTree(data).path("choices").path(0).path("delta");
            JsonNode content = delta.path("content");
            if (content.isTextual()) text.append(content.asText());
            JsonNode audioData = delta.path("audio").path("data");
            if (audioData.isTextual() && !audioData.asText().isEmpty()) {
                // audio.data is one continuous Base64 value split across SSE deltas.
                // Decode only complete quartets and retain 0..3 chars for the next delta.
                audioBase64.append(audioData.asText());
                int completeChars = audioBase64.length() - audioBase64.length() % 4;
                if (completeChars > 0) {
                    byte[] chunk = Base64.getDecoder().decode(audioBase64.substring(0, completeChars));
                    audioBase64.delete(0, completeChars);
                    if (audio.size() + chunk.length > MAX_OUTPUT_AUDIO_BYTES) {
                        throw new IOException("qwen omni audio exceeded " + MAX_OUTPUT_AUDIO_BYTES + " bytes");
                    }
                    audio.write(chunk);
                    if (!streamStarted) {
                        audioSink.onStart(24_000, 1, "pcm_s16le");
                        streamStarted = true;
                    }
                    // Official example decodes the concatenated bytes directly as int16 @ 24kHz.
                    audioSink.onChunk(chunk);
                }
            }
            for (JsonNode tc : delta.path("tool_calls")) {
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
        if (!audioBase64.isEmpty()) {
            byte[] tail = Base64.getDecoder().decode(audioBase64.toString());
            if (audio.size() + tail.length > MAX_OUTPUT_AUDIO_BYTES) {
                throw new IOException("qwen omni audio exceeded " + MAX_OUTPUT_AUDIO_BYTES + " bytes");
            }
            audio.write(tail);
            if (!streamStarted) {
                audioSink.onStart(24_000, 1, "pcm_s16le");
                streamStarted = true;
            }
            audioSink.onChunk(tail);
        }
        List<ToolCall> complete = new ArrayList<>();
        for (Map.Entry<Integer, MutableToolCall> entry : calls.entrySet()) {
            MutableToolCall tc = entry.getValue();
            complete.add(new ToolCall(tc.id.isBlank() ? "call_" + entry.getKey() : tc.id,
                    tc.name.toString(), tc.arguments.toString()));
        }
        if (streamStarted && !complete.isEmpty()) {
            throw new IOException("qwen omni mixed audio and tool_calls in one round");
        }
        return new StreamResult(text.toString(), audio.toByteArray(), complete);
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
