package com.autovoice.server.gateway;

import com.autovoice.server.arbitration.DecisionSink;
import com.autovoice.server.arbitration.RaceArbiter;
import com.autovoice.server.contracts.AsrProvider;
import com.autovoice.server.contracts.DecisionEntry;
import com.autovoice.server.contracts.LlmProvider;
import com.autovoice.server.contracts.NluProvider;
import com.autovoice.server.contracts.SessionContext;
import com.autovoice.server.contracts.TtsProvider;
import com.autovoice.server.session.SessionRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * 网关 WebSocket 处理器（shared/protocol.md §5 时序）：
 *
 * <ul>
 *   <li>{@code hello} → 校验通过后回 {@code ready}（sessionId 由 SessionRegistry 取或新建）；</li>
 *   <li>{@code audio_start} → 记录 utteranceId（自增 {@code u-N}）并开始累积 PCM；</li>
 *   <li>二进制帧 → 累积 PCM（S16LE/16kHz/单声道，协议不校验内容）；</li>
 *   <li>{@code audio_end} → 同步执行每连接一份的 {@link SegmentPipeline#handleSegment} →
 *       先逐条下发 arbiter 的 {@code decision} 事件，再下发最终 {@code reply}（协议 §5 时序）。</li>
 * </ul>
 *
 * <p>下行收敛：reply 恒为 kind=audio（payload 携带 mime/dataBase64/speakText/intent，
 * intent 为 null 时省略字段）；唯一例外——TTS 合成失败（或 ASR 兜底）时降级 kind=text（仅 speakText）。
 * 音频超 64KB base64 也一次消息下发，不分帧。</p>
 *
 * <p>每连接一个 {@link SegmentPipeline} 实例（含各自注入同一连接 {@link DecisionSink} 的
 * RaceArbiter）；demo 单线程同步处理段，吞吐不是目标。非法消息 → 下发 error（hello 类错误码 BAD_HELLO，
 * 其余 INTERNAL），不关闭连接。</p>
 */
public final class VoiceGatewayHandler implements WebSocketHandler {

    public static final String PROTOCOL_VERSION = "1.0";
    public static final String DEFAULT_LANGUAGE = "zh-CN";
    private static final String MIME_WAV = "audio/wav";
    private static final long DEFAULT_NLU_GRACE_MS = 1500;
    private static final long DEFAULT_SAFETY_TIMEOUT_MS = 4000;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AsrProvider asr;
    private final NluProvider nlu;
    private final LlmProvider llm;
    private final TtsProvider tts;
    private final SessionRegistry registry;
    private final long nluGraceMs;
    private final long safetyTimeoutMs;

    /** 各连接共用的仲裁计时线程池（daemon，demo 规模足够）。 */
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "gateway-arbiter");
        t.setDaemon(true);
        return t;
    });

    /** 每连接状态：pipeline / 会话 / 累积 PCM / 待下发决策事件。 */
    private final ConcurrentMap<WebSocketSession, ConnectionState> connections = new ConcurrentHashMap<>();

    /** demo 默认仲裁参数（NLU 宽限 1.5s，安全兜底 4s）。 */
    public VoiceGatewayHandler(AsrProvider asr, NluProvider nlu, LlmProvider llm,
                               TtsProvider tts, SessionRegistry registry) {
        this(asr, nlu, llm, tts, registry, DEFAULT_NLU_GRACE_MS, DEFAULT_SAFETY_TIMEOUT_MS);
    }

    public VoiceGatewayHandler(AsrProvider asr, NluProvider nlu, LlmProvider llm, TtsProvider tts,
                               SessionRegistry registry, long nluGraceMs, long safetyTimeoutMs) {
        this.asr = asr;
        this.nlu = nlu;
        this.llm = llm;
        this.tts = tts;
        this.registry = registry;
        this.nluGraceMs = nluGraceMs;
        this.safetyTimeoutMs = safetyTimeoutMs;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        connections.computeIfAbsent(session, s -> new ConnectionState());
    }

    @Override
    public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) {
        ConnectionState st = connections.computeIfAbsent(session, s -> new ConnectionState());
        if (message instanceof BinaryMessage bm) {
            if (st.audioActive) {
                ByteBuffer buf = bm.getPayload();
                byte[] bytes = new byte[buf.remaining()];
                buf.get(bytes);
                st.pcm.writeBytes(bytes);
            }
            return;
        }
        if (!(message instanceof TextMessage tm)) {
            return;
        }
        Map<String, Object> msg;
        try {
            msg = GatewayCodec.decode(tm.getPayload());
        } catch (IllegalArgumentException e) {
            sendError(session, st, errorCodeOf(tm.getPayload()), "invalid message: " + e.getMessage());
            return;
        }
        switch ((String) msg.get("type")) {
            case "hello" -> onHello(session, st, castPayload(msg));
            case "audio_start" -> onAudioStart(st, castPayload(msg));
            case "audio_end" -> onAudioEnd(session, st);
            default -> {
                // ready/decision/reply/error/bye 为服务端消息，客户端不应发送，忽略
            }
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        connections.remove(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) {
        connections.remove(session);
    }

    @Override
    public boolean supportsPartialMessages() {
        return false;
    }

    /** hello：SessionRegistry 取会话，不存在则新建；回 ready（sessionId 以服务端采纳为准）。 */
    private void onHello(WebSocketSession session, ConnectionState st, Map<String, Object> payload) {
        if (st.ctx == null) {
            String sessionId = String.valueOf(payload.get("sessionId"));
            SessionContext ctx = registry.get(sessionId);
            if (ctx == null) {
                ctx = registry.create(DEFAULT_LANGUAGE);
            }
            st.ctx = ctx;
        }
        Map<String, Object> ready = new LinkedHashMap<>();
        ready.put("sessionId", st.ctx.sessionId());
        ready.put("language", st.ctx.language());
        ready.put("protocolVersion", PROTOCOL_VERSION);
        send(session, "ready", ready);
    }

    /** audio_start：未握手不处理；开始累积，记录 utteranceId 与可选 segmentId（reply/error 原样回显）。 */
    private void onAudioStart(ConnectionState st, Map<String, Object> payload) {
        if (st.ctx == null) {
            return; // 未收到合法 hello 前不处理后续音频
        }
        st.audioActive = true;
        st.pcm.reset();
        st.pendingDecisions.clear();
        st.utteranceId = "u-" + ++st.segmentSeq;
        st.segmentId = payload.get("segmentId") != null ? String.valueOf(payload.get("segmentId")) : null;
    }

    /** audio_end：同步执行流水线 → 先逐条下发 decision 事件，再下发 reply（协议 §5 时序）。 */
    private void onAudioEnd(WebSocketSession session, ConnectionState st) {
        if (!st.audioActive || st.ctx == null) {
            return;
        }
        st.audioActive = false;
        byte[] pcm = st.pcm.toByteArray();
        if (pcm.length == 0) {
            return;
        }
        SegmentPipeline.SegmentResult result;
        try {
            result = st.pipeline.handleSegment(pcm, st.ctx, st.utteranceId);
        } catch (RuntimeException e) {
            // 防御：pipeline 保证不抛异常；意外失败仍走兜底话术
            result = new SegmentPipeline.SegmentResult(null, SegmentPipeline.FALLBACK_TEXT, null);
        }
        for (DecisionEntry entry : st.pendingDecisions) {
            send(session, "decision", MAPPER.convertValue(entry, new TypeReference<Map<String, Object>>() {
            }));
        }
        sendReply(session, st, result);
    }

    /** 下行收敛：恒 kind=audio（mime/dataBase64/speakText/intent，null 省略）；wavAudio 为 null 时降级 kind=text（仅 speakText）。 */
    private void sendReply(WebSocketSession session, ConnectionState st, SegmentPipeline.SegmentResult result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (result.wavAudio() != null) {
            payload.put("kind", "audio");
            payload.put("mime", MIME_WAV);
            payload.put("dataBase64", Base64.getEncoder().encodeToString(result.wavAudio()));
            if (result.speakText() != null) {
                payload.put("speakText", result.speakText());
            }
            if (result.intent() != null) {
                payload.put("intent", result.intent());
            }
        } else {
            // TTS 合成失败 / ASR 兜底：降级为屏幕显示文本（protocol.md §4.4）
            payload.put("kind", "text");
            payload.put("speakText", result.speakText());
        }
        if (st.segmentId != null) {
            payload.put("segmentId", st.segmentId); // 回显 audio_start 的 segmentId（端侧按话语对账）
        }
        send(session, "reply", payload);
    }

    private void sendError(WebSocketSession session, ConnectionState st, String code, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (st.ctx != null) {
            payload.put("sessionId", st.ctx.sessionId());
        }
        if (st.segmentId != null) {
            payload.put("segmentId", st.segmentId); // 回显当前话语的 segmentId，供端侧丢弃他轮的 error
        }
        payload.put("code", code);
        payload.put("message", message);
        send(session, "error", payload);
    }

    private static void send(WebSocketSession session, String type, Map<String, Object> payload) {
        try {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(GatewayCodec.encode(type, payload)));
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to send " + type + " message", e);
        }
    }

    /** 解码失败时粗判错误码：hello 消息非法 → BAD_HELLO，其余 → INTERNAL。 */
    private static String errorCodeOf(String raw) {
        try {
            JsonNode node = MAPPER.readTree(raw);
            if (node != null && node.isObject() && "hello".equals(node.path("type").asText())) {
                return "BAD_HELLO";
            }
        } catch (Exception ignored) {
            // 无法解析的原始文本：按 INTERNAL 处理
        }
        return "INTERNAL";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castPayload(Map<String, Object> msg) {
        return (Map<String, Object>) msg.get("payload");
    }

    /** 每连接状态。仲裁器与流水线各连接一份；sink 收集本连接待下发的决策事件。 */
    private final class ConnectionState {
        final List<DecisionEntry> pendingDecisions = new ArrayList<>();
        final DecisionSink sink = pendingDecisions::add;
        final RaceArbiter arbiter = new RaceArbiter(nluGraceMs, safetyTimeoutMs, scheduler, sink);
        final SegmentPipeline pipeline = new SegmentPipeline(asr, arbiter, nlu, llm, tts, sink);
        final ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        SessionContext ctx;
        String utteranceId;
        String segmentId; // 当前话语的客户端生成 ID（audio_start 可选字段，reply/error 回显）
        boolean audioActive;
        long segmentSeq;
    }
}
