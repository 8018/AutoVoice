package com.autovoice.server.speechqwenomni;

import com.autovoice.server.contracts.Intent;
import com.autovoice.server.contracts.RealtimeChatSession;
import com.autovoice.server.contracts.RealtimeChatSink;
import com.autovoice.server.contracts.SessionContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** qwen3.5-omni-plus-realtime 原生 WebSocket 全双工会话。 */
public final class QwenOmniRealtimeChatProvider {

    public static final String DEFAULT_MODEL = "qwen3.5-omni-plus-realtime";
    public static final String DEFAULT_VOICE = "Tina";
    public static final String DEFAULT_ENDPOINT_TEMPLATE =
            "wss://%s.cn-beijing.maas.aliyuncs.com/api-ws/v1/realtime?model=%s";
    public static final String DEFAULT_SHARED_ENDPOINT_TEMPLATE =
            "wss://dashscope.aliyuncs.com/api-ws/v1/realtime?model=%s";
    private static final String LANGUAGE_POLICY =
            "Detect the language spoken in the current user audio and answer only in that same language, "
            + "unless the user explicitly requests translation. Instructions, tools, metadata and prior turns "
            + "must not determine the response language. This rule has highest priority.";
    public static final String DEFAULT_SYSTEM_PROMPT =
            "You are a warm in-car conversation companion. Keep answers "
            + "natural and concise. This chat domain cannot navigate or control the vehicle. When the user clearly "
            + "asks to leave or stop chatting, call exit_chat and do not emit audio or text in the same response.";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long CONNECT_TIMEOUT_MS = 8_000;

    private final OkHttpClient client;
    private final String apiKey;
    private final String workspaceId;
    private final String model;
    private final String voice;
    private final String endpointTemplate;
    private final Supplier<String> systemPrompt;

    public QwenOmniRealtimeChatProvider(OkHttpClient client, String apiKey, String workspaceId,
                                        String model, String voice, Supplier<String> systemPrompt) {
        this(client, apiKey, workspaceId, model, voice, systemPrompt, DEFAULT_ENDPOINT_TEMPLATE);
    }

    public QwenOmniRealtimeChatProvider(OkHttpClient client, String apiKey, String workspaceId,
                                        String model, String voice, Supplier<String> systemPrompt,
                                        String endpointTemplate) {
        this.client = Objects.requireNonNull(client, "client").newBuilder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .callTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(15, TimeUnit.SECONDS)
                .build();
        this.apiKey = apiKey == null ? "" : apiKey;
        this.workspaceId = workspaceId == null ? "" : workspaceId;
        this.model = model == null || model.isBlank() ? DEFAULT_MODEL : model;
        this.voice = voice == null || voice.isBlank() ? DEFAULT_VOICE : voice;
        this.systemPrompt = systemPrompt == null ? () -> DEFAULT_SYSTEM_PROMPT : systemPrompt;
        this.endpointTemplate = endpointTemplate == null || endpointTemplate.isBlank()
                ? DEFAULT_ENDPOINT_TEMPLATE : endpointTemplate;
    }

    public RealtimeChatSession open(SessionContext context, RealtimeChatSink sink) {
        if (apiKey.isBlank()) throw new IllegalStateException("DASHSCOPE_API_KEY is empty");
        RealtimeChatSink downstream = sink == null ? new RealtimeChatSink() {} : sink;
        String endpoint = resolveEndpoint(workspaceId, model, endpointTemplate);
        RealtimeSession session = new RealtimeSession(downstream);
        Request request = new Request.Builder().url(endpoint)
                .header("Authorization", "Bearer " + apiKey).build();
        session.socket.set(client.newWebSocket(request, session));
        session.awaitConnected();
        return session;
    }

    static String resolveEndpoint(String workspaceId, String model, String endpointTemplate) {
        if ((workspaceId == null || workspaceId.isBlank())
                && DEFAULT_ENDPOINT_TEMPLATE.equals(endpointTemplate)) {
            return DEFAULT_SHARED_ENDPOINT_TEMPLATE.formatted(model);
        }
        return endpointTemplate.formatted(workspaceId == null ? "" : workspaceId, model);
    }

    private final class RealtimeSession extends WebSocketListener implements RealtimeChatSession {
        private final RealtimeChatSink sink;
        private final AtomicReference<WebSocket> socket = new AtomicReference<>();
        private final CountDownLatch connected = new CountDownLatch(1);
        private final AtomicReference<Throwable> connectError = new AtomicReference<>();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean audioStarted = new AtomicBoolean();
        private final AtomicBoolean suppressCurrentResponse = new AtomicBoolean();
        private final AtomicBoolean exitRequested = new AtomicBoolean();
        private final StringBuilder transcript = new StringBuilder();

        private RealtimeSession(RealtimeChatSink sink) { this.sink = sink; }

        @Override public void onOpen(WebSocket webSocket, Response response) {
            webSocket.send(sessionUpdate().toString());
            connected.countDown();
        }

        @Override public void onMessage(WebSocket webSocket, String text) {
            try {
                JsonNode event = JSON.readTree(text);
                String type = event.path("type").asText();
                switch (type) {
                    case "response.created" -> {
                        audioStarted.set(false);
                        suppressCurrentResponse.set(false);
                        transcript.setLength(0);
                    }
                    case "input_audio_buffer.speech_started" -> {
                        // 不取消模型调用；只通知端侧拦截旧播报。麦克风上行始终继续。
                        suppressCurrentResponse.set(true);
                        sink.onUserSpeechStarted();
                    }
                    case "response.audio.delta" -> {
                        if (suppressCurrentResponse.get()) return;
                        if (audioStarted.compareAndSet(false, true)) {
                            sink.onStart(24_000, 1, "pcm_s16le");
                        }
                        byte[] pcm = Base64.getDecoder().decode(event.path("delta").asText());
                        if (pcm.length > 0) sink.onChunk(pcm);
                    }
                    case "response.audio_transcript.delta" -> {
                        if (suppressCurrentResponse.get()) return;
                        String delta = event.path("delta").asText("");
                        transcript.append(delta);
                        if (!delta.isBlank()) sink.onReplyText(transcript.toString(), false);
                    }
                    case "response.audio_transcript.done" -> {
                        if (suppressCurrentResponse.get()) return;
                        String complete = event.path("transcript").asText(transcript.toString());
                        transcript.setLength(0);
                        transcript.append(complete);
                        if (!complete.isBlank()) sink.onReplyText(complete, true);
                    }
                    case "response.output_item.done" -> inspectToolCall(event.path("item"));
                    case "response.done" -> finishResponse();
                    case "error" -> failSession(
                            new IllegalStateException(event.path("error").toString()));
                    default -> { }
                }
            } catch (Exception error) {
                failSession(error);
            }
        }

        private void inspectToolCall(JsonNode item) {
            if ("function_call".equals(item.path("type").asText())
                    && QwenOmniSpeechProvider.EXIT_CHAT_TOOL.equals(item.path("name").asText())) {
                exitRequested.set(true);
            }
        }

        private void finishResponse() {
            if (suppressCurrentResponse.getAndSet(false)) {
                audioStarted.set(false);
                transcript.setLength(0);
                return;
            }
            Intent intent = exitRequested.getAndSet(false)
                    ? Intent.of("1.0", "conversation", "exit_chat", Map.of(), 1.0,
                            "qwen-realtime", null)
                    : null;
            sink.onComplete(transcript.toString(), intent, "");
            audioStarted.set(false);
            transcript.setLength(0);
        }

        @Override public void onFailure(WebSocket webSocket, Throwable error, Response response) {
            connectError.compareAndSet(null, error);
            connected.countDown();
            failSession(error);
        }

        @Override public void onClosed(WebSocket webSocket, int code, String reason) {
            connected.countDown();
            if (closed.compareAndSet(false, true)) sink.onSessionClosed(null);
        }

        @Override public void appendAudio(byte[] pcm16k) {
            if (closed.get() || pcm16k == null || pcm16k.length == 0) return;
            ObjectNode event = event("input_audio_buffer.append");
            event.put("audio", Base64.getEncoder().encodeToString(pcm16k));
            WebSocket ws = socket.get();
            if (ws == null || !ws.send(event.toString())) {
                failSession(new IllegalStateException("Qwen Realtime websocket is not open"));
            }
        }

        private void failSession(Throwable error) {
            if (!closed.compareAndSet(false, true)) return;
            WebSocket ws = socket.getAndSet(null);
            if (ws != null) ws.cancel();
            sink.onSessionClosed(error);
        }

        @Override public void close() {
            if (!closed.compareAndSet(false, true)) return;
            WebSocket ws = socket.getAndSet(null);
            if (ws != null) {
                ws.send(event("session.finish").toString());
                ws.close(1000, "chat finished");
            }
        }

        private void awaitConnected() {
            try {
                if (!connected.await(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    close();
                    throw new IllegalStateException("Qwen Realtime websocket connect timeout");
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                close();
                throw new IllegalStateException("Qwen Realtime websocket connect interrupted", error);
            }
            Throwable error = connectError.get();
            if (error != null) throw new IllegalStateException("Qwen Realtime websocket connect failed", error);
        }

        private ObjectNode sessionUpdate() {
            ObjectNode root = event("session.update");
            ObjectNode session = root.putObject("session");
            ArrayNode modalities = session.putArray("modalities");
            modalities.add("text").add("audio");
            session.put("voice", voice);
            session.put("instructions", configuredPrompt());
            session.put("enable_input_audio_transcription", false);
            ObjectNode audio = session.putObject("audio");
            audio.putObject("input").putObject("format").put("type", "pcm").put("sample_rate", 16_000);
            audio.putObject("output").putObject("format").put("type", "pcm").put("sample_rate", 24_000);
            ObjectNode vad = session.putObject("turn_detection");
            vad.put("type", "semantic_vad");
            vad.put("threshold", 0.5);
            vad.put("prefix_padding_ms", 500);
            vad.put("silence_duration_ms", 800);
            ArrayNode tools = session.putArray("tools");
            ObjectNode exit = tools.addObject();
            exit.put("type", "function");
            exit.put("name", QwenOmniSpeechProvider.EXIT_CHAT_TOOL);
            exit.put("description", "Call only when the user clearly asks to stop or leave chat mode.");
            exit.set("parameters", JSON.createObjectNode().put("type", "object")
                    .set("properties", JSON.createObjectNode()));
            session.put("tool_choice", "auto");
            return root;
        }

        private String configuredPrompt() {
            String configured = systemPrompt.get();
            String base = configured == null || configured.isBlank() ? DEFAULT_SYSTEM_PROMPT : configured;
            return LANGUAGE_POLICY + "\n" + base;
        }

        private ObjectNode event(String type) {
            ObjectNode event = JSON.createObjectNode();
            event.put("event_id", "event_" + UUID.randomUUID());
            event.put("type", type);
            return event;
        }
    }
}
