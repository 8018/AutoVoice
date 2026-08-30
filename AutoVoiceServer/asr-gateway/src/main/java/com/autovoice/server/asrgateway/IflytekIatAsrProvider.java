package com.autovoice.server.asrgateway;

import com.autovoice.server.contracts.AsrException;
import com.autovoice.server.contracts.AsrProvider;
import com.autovoice.server.contracts.OnlineAsrSink;
import com.autovoice.server.contracts.SessionContext;
import com.autovoice.server.contracts.StreamingAsrProvider;
import com.autovoice.server.contracts.StreamingAsrSession;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.jetbrains.annotations.NotNull;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.ArrayDeque;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 讯飞在线语音听写（中英识别大模型 API，spark_zh_iat）适配器，同步执行。
 *
 * <p>协议：WebSocket 连接 {@link #DEFAULT_ENDPOINT}，URL 携带 HMAC-SHA256 签名
 * （{@code host/date/request-line} → {@code authorization}，RFC3986 base64），
 * 音频以 JSON 帧发送：{@code common.app_id} + {@code business}（language/domain/accent/ptt）
 * + {@code data.audio}（PCM base64，每帧 8192 字节），{@code data.status} 0 首帧 /
 * 1 中间帧 / 2 尾帧。响应 JSON {@code code == 0} 时累加 {@code data.result.ws[].cw[].w}
 * 得到识别文本，收到 {@code data.status == 2} 的终帧即完成。</p>
 *
 * <p>错误语义：鉴权失败 / HTTP 非 2xx / code 非 0 / JSON 解析失败 / IO 异常 / 超时
 * 全部包装为 {@link AsrException}（ASR 侧失败，由调用方按 AsrProvider 契约处理）。</p>
 */
public final class IflytekIatAsrProvider implements StreamingAsrProvider {

    /** 讯飞语音听写（流式版）默认地址。 */
    public static final String DEFAULT_ENDPOINT = "wss://iat-api.xfyun.cn/v2/iat";

    /** 单帧音频字节数（8192B ≈ 256ms @16k 单声道；base64 后 ~11KB，低于接口帧上限）。 */
    static final int FRAME_BYTES = 8_192;

    private static final long DEFAULT_TIMEOUT_MS = 10_000;
    private static final String PCM_FORMAT = "audio/L16;rate=16000";
    private static final String PCM_ENCODING = "raw";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** RFC1123 GMT 时间戳（签名 date；显式 pattern 避免依赖 JDK zone 渲染差异）。 */
    private static final DateTimeFormatter RFC1123_GMT =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);

    private final OkHttpClient client;
    private final String appId;
    private final String apiKey;
    private final String apiSecret;
    private final String endpoint;
    private final String businessJson;
    private final Clock clock;
    private final long timeoutMs;

    /**
     * @param appId    讯飞 APPID
     * @param apiKey   讯飞 APIKey（听写服务）
     * @param apiSecret 讯飞 APISecret（听写服务）
     * @param endpoint 听写地址；测试注入 MockWebServer URL，生产用 {@link #DEFAULT_ENDPOINT}
     * @param clock    签名时间源（测试注入固定时间校验 URL）
     */
    public IflytekIatAsrProvider(OkHttpClient client, String appId, String apiKey, String apiSecret,
                                 String endpoint, Clock clock) {
        this(client, appId, apiKey, apiSecret, endpoint, DEFAULT_BUSINESS_JSON, clock, DEFAULT_TIMEOUT_MS);
    }

    /** 全参构造（测试注入 business 参数与超时）。 */
    IflytekIatAsrProvider(OkHttpClient client, String appId, String apiKey, String apiSecret,
                          String endpoint, String businessJson, Clock clock, long timeoutMs) {
        this.client = client;
        this.appId = appId;
        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
        this.endpoint = endpoint;
        this.businessJson = businessJson;
        this.clock = clock;
        this.timeoutMs = timeoutMs;
    }

    @Override
    public String transcribe(byte[] pcm16k, SessionContext ctx) {
        StreamingAsrSession session = start(ctx, OnlineAsrSink.NOOP);
        session.append(pcm16k);
        try {
            return session.finish().get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            session.cancel();
            throw new AsrException("iflytek iat timed out after " + timeoutMs + "ms", e);
        } catch (Exception e) {
            session.cancel();
            if (e.getCause() instanceof AsrException asr) {
                throw asr;
            }
            throw new AsrException("iflytek iat failed: " + e.getMessage(), e);
        }
    }

    @Override
    public StreamingAsrSession start(SessionContext ctx, OnlineAsrSink sink) {
        return new IatSession(sink == null ? OnlineAsrSink.NOOP : sink);
    }

    // ------------------------------------------------------------------ 签名

    /**
     * 构造带鉴权参数的 WS URL（讯飞文档：HMAC-SHA256 签名，authorization base64）。
     * 不校验/不打印凭据值——签名参数以 URL query 形式存在（该 API 设计固有）。
     */
    private HttpUrl buildSignedUrl() {
        // okhttp 的 HttpUrl 只接受 http/https scheme，ws/wss 需先归一化（TLS 由 https 决定，
        // 握手请求行与 Host 头不含 scheme，签名 host/path 不变）
        HttpUrl endpointUrl = HttpUrl.get(endpoint
                .replaceFirst("^wss://", "https://")
                .replaceFirst("^ws://", "http://"));
        String host = endpointUrl.host();
        String path = endpointUrl.encodedPath();
        String date = RFC1123_GMT.format(Instant.now(clock).atZone(ZoneOffset.UTC));
        String signatureOrigin = "host: " + host + "\ndate: " + date + "\nGET " + path + " HTTP/1.1";
        String signature = Base64.getEncoder().encodeToString(hmacSha256(apiSecret, signatureOrigin));
        String authorizationOrigin = "api_key=\"" + apiKey
                + "\", algorithm=\"hmac-sha256\", headers=\"host date request-line\", signature=\""
                + signature + "\"";
        String authorization = Base64.getEncoder().encodeToString(
                authorizationOrigin.getBytes(StandardCharsets.US_ASCII));
        return endpointUrl.newBuilder()
                .addQueryParameter("authorization", authorization)
                .addQueryParameter("date", date)
                .addQueryParameter("host", host)
                .build();
    }

    private static byte[] hmacSha256(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.US_ASCII), "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    // ------------------------------------------------------------------ 帧协议

    /** 一轮听写会话：保留最后一块直到 finish，以正确发送 status=2 终帧。 */
    private final class IatSession extends WebSocketListener implements StreamingAsrSession {

        private final CompletableFuture<String> result;
        private final OnlineAsrSink sink;
        private final ArrayDeque<byte[]> queued = new ArrayDeque<>();
        private final NavigableMap<Integer, String> pieces = new TreeMap<>();
        private WebSocket socket;
        private boolean opened;
        private boolean finishing;
        private boolean firstFrame = true;
        private int fallbackSequence;

        IatSession(OnlineAsrSink sink) {
            this.result = new CompletableFuture<>();
            this.sink = sink;
            this.socket = client.newWebSocket(
                    new Request.Builder().url(buildSignedUrl()).build(), this);
        }

        @Override
        public synchronized void onOpen(@NotNull WebSocket ws, @NotNull Response response) {
            if (response.code() != 101 && !response.isSuccessful()) {
                result.completeExceptionally(
                        new AsrException("iflytek iat handshake failed: HTTP " + response.code()));
                ws.close(1000, "bad handshake");
                return;
            }
            socket = ws;
            opened = true;
            drain();
        }

        @Override public synchronized void append(byte[] pcm16k) {
            if (finishing || result.isDone() || pcm16k == null || pcm16k.length == 0) return;
            for (int offset = 0; offset < pcm16k.length; offset += FRAME_BYTES) {
                int end = Math.min(offset + FRAME_BYTES, pcm16k.length);
                queued.add(java.util.Arrays.copyOfRange(pcm16k, offset, end));
            }
            drain();
        }

        @Override public synchronized CompletableFuture<String> finish() {
            finishing = true;
            drain();
            return result;
        }

        @Override public synchronized void cancel() {
            finishing = true;
            queued.clear();
            if (socket != null) socket.cancel();
            result.cancel(true);
        }

        private void drain() {
            if (!opened || result.isDone()) return;
            try {
                while (queued.size() > 1) sendNext(false);
                if (finishing) {
                    byte[] last = queued.isEmpty() ? new byte[0] : queued.removeFirst();
                    send(last, 2);
                }
            } catch (RuntimeException e) {
                result.completeExceptionally(new AsrException("iflytek iat frame send failed", e));
                socket.cancel();
            }
        }

        private void sendNext(boolean last) {
            send(queued.removeFirst(), last ? 2 : (firstFrame ? 0 : 1));
        }

        private void send(byte[] audio, int status) {
            if (!socket.send(frame(status, audio))) {
                throw new AsrException("iflytek iat websocket send rejected");
            }
            firstFrame = false;
        }

        @Override
        public void onMessage(@NotNull WebSocket ws, @NotNull String message) {
            JsonNode root;
            try {
                root = MAPPER.readTree(message);
            } catch (Exception e) {
                result.completeExceptionally(new AsrException("iflytek iat response is not valid json: " + message, e));
                ws.close(1000, "bad json");
                return;
            }
            int code = root.path("code").asInt();
            if (code != 0) {
                result.completeExceptionally(new AsrException(
                        "iflytek iat code=" + code + ": " + root.path("message").asText("(no message)")));
                ws.close(1000, "error " + code);
                return;
            }
            JsonNode data = root.path("data");
            JsonNode recognition = data.path("result");
            StringBuilder fragment = new StringBuilder();
            for (JsonNode wsNode : recognition.path("ws")) {
                for (JsonNode cw : wsNode.path("cw")) {
                    fragment.append(cw.path("w").asText());
                }
            }
            String pgs = recognition.path("pgs").asText();
            int sequence = recognition.path("sn").asInt(fallbackSequence++);
            // 老接口/测试桩可能固定返回同一个 sn 且不带 PGS；保持原来的追加语义。
            if (pgs.isBlank() && pieces.containsKey(sequence)) {
                sequence = pieces.isEmpty() ? sequence : pieces.lastKey() + 1;
            }
            if ("rpl".equals(pgs)
                    && recognition.path("rg").isArray() && recognition.path("rg").size() == 2) {
                int from = recognition.path("rg").get(0).asInt();
                int to = recognition.path("rg").get(1).asInt();
                pieces.subMap(from, true, to, true).clear();
            }
            pieces.put(sequence, fragment.toString());
            String text = String.join("", pieces.values());
            if (data.path("status").asInt() == 2) {
                sink.onResult(text, true);
                result.complete(text);
                ws.close(1000, "done"); // 终帧已收：结果确定，主动关闭
            } else if (!text.isBlank()) {
                sink.onResult(text, false);
            }
        }

        @Override
        public void onFailure(@NotNull WebSocket ws, @NotNull Throwable t, Response response) {
            result.completeExceptionally(new AsrException("iflytek iat websocket failed: " + t.getMessage(), t));
        }

        @Override
        public void onClosed(@NotNull WebSocket ws, int code, @NotNull String reason) {
            // 服务端先关且未给终帧：结果不完整
            result.completeExceptionally(new AsrException("iflytek iat closed before final result: " + reason));
        }
    }

    /** 构造一帧听写 JSON：status 0 首帧（带 common/business）/ 1 中间 / 2 尾帧。 */
    private String frame(int status, byte[] audio) {
        ObjectNode frame = MAPPER.createObjectNode();
        try {
            ObjectNode common = MAPPER.createObjectNode();
            common.put("app_id", appId);
            frame.set("common", common);
            frame.set("business", (ObjectNode) MAPPER.readTree(businessJson));
        } catch (Exception e) {
            throw new IllegalStateException("bad business json: " + businessJson, e);
        }
        ObjectNode data = MAPPER.createObjectNode();
        data.put("status", status);
        data.put("format", PCM_FORMAT);
        data.put("encoding", PCM_ENCODING);
        data.put("audio", Base64.getEncoder().encodeToString(audio));
        frame.set("data", data);
        return frame.toString();
    }

    private static final String DEFAULT_BUSINESS_JSON =
            "{\"language\":\"zh_cn\",\"domain\":\"iat\",\"accent\":\"mandarin\",\"ptt\":1}";
}
