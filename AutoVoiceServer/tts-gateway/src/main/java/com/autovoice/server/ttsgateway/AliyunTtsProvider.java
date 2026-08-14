package com.autovoice.server.ttsgateway;

import com.autovoice.server.contracts.Reply;
import com.autovoice.server.contracts.SessionContext;
import com.autovoice.server.contracts.TtsProvider;
import com.autovoice.server.contracts.telemetry.NoopTelemetryRecorder;
import com.autovoice.server.contracts.telemetry.TelemetryRecorder;
import com.autovoice.server.contracts.telemetry.TelemetryStages;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.HttpUrl;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.UUID;

/**
 * DashScope sambert 语音合成（TTS）适配器，同步执行。
 *
 * <p>协议：DashScope 原生 WebSocket 推理接口（HTTP 旧端点已下线，返回 400 "url error"，
 * 见 {@code docs/…} 排障记录）。握手 GET {@link #DEFAULT_ENDPOINT}，header
 * {@code Authorization: Bearer <apiKey>}；连接建立后发 run-task 帧：</p>
 *
 * <pre>{@code
 * {"header":{"action":"run-task","task_id":"<uuid>","streaming":"out"},
 *  "payload":{"task_group":"audio","task":"tts","function":"SpeechSynthesizer",
 *             "model":"sambert-zhimiao-emo-v1",
 *             "input":{"text":"<text>"},
 *             "parameters":{"text_type":"PlainText","format":"wav","sample_rate":16000}}}
 * }</pre>
 *
 * <p>事件序列：task-started →（binary 音频分片 ×N）→ result-generated → task-finished
 * （SUCCEEDED 累积二进制为 wav；FAILED 抛异常）。也处理 task-failed（服务端拒绝）。
 * 连接为任务专用，完成后关闭；每次调用独立新建。</p>
 *
 * <p>失败/超时 → {@link RuntimeException}（TTS 失败由调用方降级为文本回复，
 * 不抛 TTS 专属异常）。</p>
 */
public final class AliyunTtsProvider implements TtsProvider {

    /** DashScope 原生 WS 推理端点（sambert 等 TTS 模型共用）。 */
    public static final String DEFAULT_ENDPOINT =
            "wss://dashscope.aliyuncs.com/api-ws/v1/inference/";

    /** 输出音频 mime。 */
    static final String OUTPUT_MIME = "audio/wav";

    private static final long CALL_TIMEOUT_MS = 15_000;
    private static final String HEADER_AUTHORIZATION = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String MODEL_SAMBERT = "sambert-zhimiao-emo-v1"; // 知妙·女声（Task 63 换音色；原 sambert-zhichu-v1 知厨·男声）
    private static final String HEADER_ACTION_RUN_TASK = "run-task";
    private static final String EVENT_TASK_STARTED = "task-started";
    private static final String EVENT_RESULT_GENERATED = "result-generated";
    private static final String EVENT_TASK_FINISHED = "task-finished";
    private static final String EVENT_TASK_FAILED = "task-failed";
    private static final String STATUS_SUCCEEDED = "SUCCEEDED";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final OkHttpClient client;
    private final String apiKey;
    private final HttpUrl endpoint;
    private final long timeoutMs;
    private final TelemetryRecorder recorder;

    /**
     * @param apiKey   DashScope API Key
     * @param endpoint 合成接口地址；生产用 {@link #DEFAULT_ENDPOINT}，测试注入 MockWebServer
     *                 URL（http/https 自动转 ws/wss）
     */
    public AliyunTtsProvider(OkHttpClient client, String apiKey, String endpoint) {
        this(client, apiKey, endpoint, CALL_TIMEOUT_MS, NoopTelemetryRecorder.INSTANCE);
    }

    /** 测试用：可注入短超时，避免超时用例等满 15s。 */
    AliyunTtsProvider(OkHttpClient client, String apiKey, String endpoint, long timeoutMs) {
        this(client, apiKey, endpoint, timeoutMs, NoopTelemetryRecorder.INSTANCE);
    }

    /** 带链路记录器的生产构造（Task 5）：超时用默认 15s。 */
    public AliyunTtsProvider(OkHttpClient client, String apiKey, String endpoint, TelemetryRecorder recorder) {
        this(client, apiKey, endpoint, CALL_TIMEOUT_MS, recorder);
    }

    /** 完整构造（Task 5）：recorder 为链路事件记录器（B4 插桩：tts_synth_request/ok/failed）。 */
    public AliyunTtsProvider(OkHttpClient client, String apiKey, String endpoint, long timeoutMs,
                             TelemetryRecorder recorder) {
        this.client = client; // WS 不受 callTimeout 约束，超时由 future.get 兜底
        this.apiKey = apiKey;
        this.endpoint = toWsUrl(endpoint);
        this.timeoutMs = timeoutMs;
        this.recorder = recorder == null ? NoopTelemetryRecorder.INSTANCE : recorder;
    }

    /**
     * OkHttp 的 HttpUrl 只接受 http/https scheme（parse 时即拒绝 wss），而
     * newWebSocket 把 http 当 ws、https 当 wss 用。因此先把 wss→https、ws→http
     * 再解析；测试注入的 http URL 原样通过。
     */
    private static HttpUrl toWsUrl(String endpoint) {
        String httpLike = endpoint.replaceFirst("^wss://", "https://").replaceFirst("^ws://", "http://");
        return HttpUrl.get(httpLike);
    }

    @Override
    public Reply synthesize(String text, SessionContext ctx) {
        return synthesize(text, ctx, "");
    }

    @Override
    public Reply synthesize(String text, SessionContext ctx, String utteranceId) {
        long startedNanos = System.nanoTime();
        // B4 需求 1 事件细分：生成请求（发起合成前）→ tts_synth_request
        recorder.record(utteranceId, TelemetryStages.TTS_SYNTH_REQUEST, "info", Map.of("text", text));
        CompletableFuture<byte[]> audio = new CompletableFuture<>();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Request request = new Request.Builder().url(endpoint)
                .header(HEADER_AUTHORIZATION, BEARER_PREFIX + apiKey) // WS 握手 Bearer 鉴权
                .build();
        WebSocket socket = client.newWebSocket(request, new Listener(audio, out, text));
        try {
            byte[] wav = audio.get(timeoutMs, TimeUnit.MILLISECONDS);
            Reply reply = Reply.ofAudio(OUTPUT_MIME, fixWavHeader(wav));
            recorder.record(utteranceId, TelemetryStages.TTS_SYNTH_OK, "info", Map.of(
                    "text", text, "bytes", reply.data().length, "durationMs", elapsedMs(startedNanos)));
            return reply;
        } catch (TimeoutException e) {
            socket.close(1000, "timeout");
            RuntimeException re = new RuntimeException("aliyun tts timeout after " + timeoutMs + "ms", e);
            recordSynthError(utteranceId, text, startedNanos, re);
            throw re;
        } catch (Exception e) {
            socket.close(1000, "error");
            if (e.getCause() instanceof RuntimeException re) {
                recordSynthError(utteranceId, text, startedNanos, re);
                throw re; // 服务端明确失败（task-finished FAILED / task-failed / 网络错误）
            }
            RuntimeException re = new RuntimeException("aliyun tts failed: " + e.getMessage(), e);
            recordSynthError(utteranceId, text, startedNanos, re);
            throw re;
        } finally {
            socket.close(1000, "done");
        }
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private void recordSynthError(String utteranceId, String text, long startedNanos, RuntimeException e) {
        recorder.record(utteranceId, TelemetryStages.TTS_SYNTH_FAILED, "error", Map.of(
                "text", text, "durationMs", elapsedMs(startedNanos), "error", String.valueOf(e.getMessage())));
    }

    /**
     * 校验并修复 wav 头尺寸字段。DashScope sambert 返回的 wav RIFF chunkSize/dataSize
     * 声明 ~2GB（0x7FFFFFFF 附近）而实际仅几十 KB，MediaPlayer 按头读到 EOF 提前截断
     * （端侧播"好的"就断）。标准 44 字节头按实际数据长度重写两字段；非标准原样返回。
     */
    static byte[] fixWavHeader(byte[] data) {
        if (data.length < 44
                || !asciiAt(data, 0, "RIFF") || !asciiAt(data, 8, "WAVE") || !asciiAt(data, 36, "data")) {
            return data;
        }
        long dataSize = data.length - 44L;
        if (dataSize == readU32(data, 40)) {
            return data; // 头已正确
        }
        byte[] fixed = data.clone();
        writeU32(fixed, 4, 36L + dataSize);
        writeU32(fixed, 40, dataSize);
        return fixed;
    }

    private static boolean asciiAt(byte[] b, int off, String expect) {
        for (int i = 0; i < expect.length(); i++) {
            if (b[off + i] != (byte) expect.charAt(i)) return false;
        }
        return true;
    }

    private static long readU32(byte[] b, int off) {
        return (b[off] & 0xFFL) | ((b[off + 1] & 0xFFL) << 8)
                | ((b[off + 2] & 0xFFL) << 16) | ((b[off + 3] & 0xFFL) << 24);
    }

    private static void writeU32(byte[] b, int off, long v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >> 8) & 0xFF);
        b[off + 2] = (byte) ((v >> 16) & 0xFF);
        b[off + 3] = (byte) ((v >> 24) & 0xFF);
    }

    /** WS 回调 → CompletableFuture 桥接（回调在 okhttp 线程，synthesize 同步等待）。 */
    private static final class Listener extends WebSocketListener {
        private final CompletableFuture<byte[]> audio;
        private final ByteArrayOutputStream out;
        private final String text;

        Listener(CompletableFuture<byte[]> audio, ByteArrayOutputStream out, String text) {
            this.audio = audio;
            this.out = out;
            this.text = text;
        }

        @Override
        public void onOpen(@NotNull WebSocket ws, @NotNull Response response) {
            try {
                ws.send(MAPPER.writeValueAsString(runTask()));
            } catch (Exception e) {
                fail(ws, "build run-task failed: " + e.getMessage(), e);
            }
        }

        @Override
        public void onMessage(@NotNull WebSocket ws, @NotNull String frame) {
            try {
                JsonNode root = MAPPER.readTree(frame);
                String event = root.path("header").path("event").asText("");
                JsonNode payload = root.path("payload");
                switch (event) {
                    case EVENT_TASK_STARTED, EVENT_RESULT_GENERATED -> { /* 进行中，等待音频与终帧 */ }
                    case EVENT_TASK_FINISHED -> {
                        // task-finished 本身即成功信号（无 task_status 字段；失败走 task-failed 事件）
                        String status = root.path("header").path("task_status").asText("");
                        if (status.isEmpty() || STATUS_SUCCEEDED.equals(status)) {
                            audio.complete(out.toByteArray());
                        } else {
                            fail(ws, "task " + status + ": " + payload, null);
                        }
                    }
                    case EVENT_TASK_FAILED -> fail(ws, "task-failed: " + payload, null);
                    default -> fail(ws, "unexpected event: " + event + " (" + frame + ")", null);
                }
            } catch (Exception e) {
                fail(ws, "bad server frame: " + frame, e);
            }
        }

        @Override
        public void onMessage(@NotNull WebSocket ws, @NotNull okio.ByteString bytes) {
            try {
                bytes.write(out); // 音频分片累积
            } catch (java.io.IOException e) {
                fail(ws, "write audio failed: " + e.getMessage(), e);
            }
        }

        @Override
        public void onFailure(@NotNull WebSocket ws, @NotNull Throwable t, Response response) {
            fail(ws, "ws failed: " + t.getMessage(), t);
        }

        @Override
        public void onClosed(@NotNull WebSocket ws, int code, @NotNull String reason) {
            fail(ws, "ws closed before task-finished: " + code + " " + reason, null);
        }

        /** 首个失败者胜：completeExceptionally 只生效一次，后续回调/关闭不再覆盖。 */
        private void fail(WebSocket ws, String message, Throwable cause) {
            audio.completeExceptionally(new RuntimeException("aliyun tts " + message, cause));
            ws.close(1000, "error");
        }

        private ObjectNode runTask() {
            ObjectNode header = MAPPER.createObjectNode();
            header.put("action", HEADER_ACTION_RUN_TASK);
            header.put("task_id", UUID.randomUUID().toString());
            header.put("streaming", "out");
            ObjectNode parameters = MAPPER.createObjectNode();
            parameters.put("text_type", "PlainText");
            parameters.put("format", "wav");
            parameters.put("sample_rate", 16_000);
            ObjectNode input = MAPPER.createObjectNode();
            input.put("text", text);
            ObjectNode payload = MAPPER.createObjectNode();
            payload.put("task_group", "audio");
            payload.put("task", "tts");
            payload.put("function", "SpeechSynthesizer");
            payload.put("model", MODEL_SAMBERT);
            payload.set("input", input);
            payload.set("parameters", parameters);
            ObjectNode root = MAPPER.createObjectNode();
            root.set("header", header);
            root.set("payload", payload);
            return root;
        }
    }
}
