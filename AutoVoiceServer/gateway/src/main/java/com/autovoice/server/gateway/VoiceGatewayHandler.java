package com.autovoice.server.gateway;

import com.autovoice.server.arbitration.DecisionSink;
import com.autovoice.server.arbitration.RaceArbiter;
import com.autovoice.server.contracts.CloudArbiterEvent;
import com.autovoice.server.contracts.DecisionEntry;
import com.autovoice.server.contracts.Intent;
import com.autovoice.server.contracts.OnlineSpeechProvider;
import com.autovoice.server.contracts.OnlineAudioSink;
import com.autovoice.server.contracts.Reply;
import com.autovoice.server.contracts.SessionContext;
import com.autovoice.server.contracts.TtsProvider;
import com.autovoice.server.contracts.telemetry.NoopTelemetryRecorder;
import com.autovoice.server.contracts.telemetry.TelemetryRecorder;
import com.autovoice.server.offlinecommand.OfflineCommandService;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 网关 WebSocket 处理器（shared/protocol.md §5 时序）：
 *
 * <ul>
 *   <li>{@code hello} → 校验通过后回 {@code ready}（sessionId 由 SessionRegistry 取或新建）；</li>
 *   <li>{@code audio_start} → 记录 utteranceId（优先采纳端侧值，缺失回退自增 {@code u-N}）并开始累积 PCM；</li>
 *   <li>二进制帧 → 累积 PCM（S16LE/16kHz/单声道，协议不校验内容）；</li>
 *   <li>{@code audio_end} → 异步（本连接串行工作线程，M2 多设备加固）：快照本段上下文后立即
 *       返回，流水线处理不占 WS 消息线程——{@code decision} 事件与最终 {@code reply} 由工作线程
 *       随后下发（协议 §5 时序不变）；上一段处理中（processing）再收 audio_end → error(BUSY)；</li>
 *   <li>{@code tts_request} → 独立 TTS 链路（不依赖本轮的识别/仲裁）：合成文本 →
 *       下发 {@code tts_response}；失败 → error（code TTS_FAILED）。</li>
 * </ul>
 *
 * <p>下行收敛（TTS 解耦，协议 v1.1）：reply 只携带语义——有 intent 时 kind=action
 * （intent + speakText），纯文本时 kind=text（text 与 speakText 同带）；<b>不再下发音频</b>，
 * 播报由端侧按回复文本另发 tts_request 获取。</p>
 *
 * <p>每连接一个 {@link SegmentPipeline} 实例（含各自注入同一连接 {@link DecisionSink} 的
 * RaceArbiter）；demo 单线程同步处理段，吞吐不是目标。非法消息 → 下发 error（hello 类错误码 BAD_HELLO，
 * 其余 INTERNAL），不关闭连接。</p>
 */
public final class VoiceGatewayHandler implements WebSocketHandler, AutoCloseable {

    public static final String PROTOCOL_VERSION = "1.1";
    public static final String DEFAULT_LANGUAGE = "zh-CN";
    private static final long DEFAULT_SAFETY_TIMEOUT_MS = 4000;
    private static final long DEFAULT_ASR_FAIL_WAIT_MS = 2000;
    private static final long DEFAULT_OFFLINE_GRACE_MS = 1500;
    /** pending 占位消息文案（B5：LLM 处理中，protocol.md §4.8）。 */
    private static final String PENDING_TEXT = "正在处理，请稍候";
    private static final int DEFAULT_MAX_CONNECTIONS = 32;
    /** 单段最多 60 秒 PCM（16kHz / 16bit / mono），防止连接持续推帧耗尽堆内存。 */
    private static final int DEFAULT_MAX_AUDIO_BYTES = 60 * 16_000 * 2;
    private static final int DEFAULT_TTS_WORKERS = 4;
    private static final int DEFAULT_TTS_QUEUE_CAPACITY = 64;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 接入策略违规（鉴权失败 / 连接数超限）统一关闭码（protocol.md §8）。 */
    private static final CloseStatus POLICY_CLOSE = new CloseStatus(4001, "policy violation");

    private static final Logger LOG = LoggerFactory.getLogger(VoiceGatewayHandler.class);

    private final OnlineSpeechProvider online;
    private final TtsProvider tts;
    private final OfflineCommandService offline;
    private final SessionRegistry registry;
    private final long safetyTimeoutMs;
    private final long asrFailWaitMs;
    private final long offlineGraceMs;

    /** 接入网关（M1）：authEnabled=false → 裸连兼容（本地）；否则 hello 须携带合法 deviceId+authToken。 */
    private final boolean authEnabled;
    /** 合法设备表 {@code {deviceId: token}}（值不打印，仅 MessageDigest.isEqual 比对）。 */
    private final Map<String, String> authDevices;
    /** 并发连接上限（含所有设备），超限新连接直接 close(4001) 不登记。 */
    private final int maxConnections;
    /** 单段 PCM 累积上限。 */
    private final int maxAudioBytes;
    /** 原子连接配额；size()+put 不是原子操作，不能作为并发接入守卫。 */
    private final AtomicInteger activeConnections = new AtomicInteger();

    /** 各连接共用的仲裁计时线程池（daemon，demo 规模足够）。 */
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "gateway-arbiter");
        t.setDaemon(true);
        return t;
    });

    /** TTS 是阻塞 HTTP 调用：从 WS 收包线程移到有界池，队列满时快速失败。 */
    private final ExecutorService ttsExecutor = new ThreadPoolExecutor(
            DEFAULT_TTS_WORKERS, DEFAULT_TTS_WORKERS, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(DEFAULT_TTS_QUEUE_CAPACITY),
            r -> {
                Thread t = new Thread(r, "gateway-tts");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy());

    /** 链路事件记录器（Task 4 插桩，注入各连接 SegmentPipeline；telemetry 禁用时是 Noop）。 */
    private final TelemetryRecorder recorder;

    /** 每连接串行工作线程命名序号（M2：audio_end 异步化）。 */
    private static final AtomicInteger CONN_SEQ = new AtomicInteger();

    /** 每连接状态：pipeline / 会话 / 累积 PCM / 待下发决策事件。 */
    private final ConcurrentMap<WebSocketSession, ConnectionState> connections = new ConcurrentHashMap<>();

    /** demo 默认仲裁参数（安全兜底 4s，ASR 失败等离线窗口 2s，离线宽限期 1.5s）；鉴权关、连接上限 32。 */
    public VoiceGatewayHandler(OnlineSpeechProvider online, TtsProvider tts,
                               OfflineCommandService offline, SessionRegistry registry) {
        this(online, tts, offline, registry, DEFAULT_SAFETY_TIMEOUT_MS, DEFAULT_ASR_FAIL_WAIT_MS,
                DEFAULT_OFFLINE_GRACE_MS);
    }

    public VoiceGatewayHandler(OnlineSpeechProvider online, TtsProvider tts,
                               OfflineCommandService offline, SessionRegistry registry,
                               long safetyTimeoutMs, long asrFailWaitMs) {
        this(online, tts, offline, registry, safetyTimeoutMs, asrFailWaitMs, DEFAULT_OFFLINE_GRACE_MS);
    }

    public VoiceGatewayHandler(OnlineSpeechProvider online, TtsProvider tts,
                               OfflineCommandService offline, SessionRegistry registry,
                               long safetyTimeoutMs, long asrFailWaitMs, long offlineGraceMs) {
        this(online, tts, offline, registry, safetyTimeoutMs, asrFailWaitMs, offlineGraceMs,
                false, Map.of(), DEFAULT_MAX_CONNECTIONS, DEFAULT_MAX_AUDIO_BYTES,
                NoopTelemetryRecorder.INSTANCE);
    }

    /**
     * 完整构造：接入策略（鉴权开关/设备表/连接上限）由 AppConfig 从 {@code autovoice.gateway.*}
     * 注入；recorder 为链路事件记录器（Task 4，AppConfig 注入 TelemetryService/Noop）。
     */
    public VoiceGatewayHandler(OnlineSpeechProvider online, TtsProvider tts,
                               OfflineCommandService offline, SessionRegistry registry,
                               long safetyTimeoutMs, long asrFailWaitMs, long offlineGraceMs,
                               boolean authEnabled, Map<String, String> authDevices, int maxConnections,
                               TelemetryRecorder recorder) {
        this(online, tts, offline, registry, safetyTimeoutMs, asrFailWaitMs, offlineGraceMs,
                authEnabled, authDevices, maxConnections, DEFAULT_MAX_AUDIO_BYTES, recorder);
    }

    public VoiceGatewayHandler(OnlineSpeechProvider online, TtsProvider tts,
                               OfflineCommandService offline, SessionRegistry registry,
                               long safetyTimeoutMs, long asrFailWaitMs, long offlineGraceMs,
                               boolean authEnabled, Map<String, String> authDevices, int maxConnections,
                               int maxAudioBytes, TelemetryRecorder recorder) {
        this.online = online;
        this.tts = tts;
        this.offline = offline;
        this.registry = registry;
        this.safetyTimeoutMs = safetyTimeoutMs;
        this.asrFailWaitMs = asrFailWaitMs;
        this.offlineGraceMs = offlineGraceMs;
        this.authEnabled = authEnabled;
        this.authDevices = authDevices;
        this.maxConnections = maxConnections;
        this.maxAudioBytes = maxAudioBytes < 1 ? DEFAULT_MAX_AUDIO_BYTES : maxAudioBytes;
        this.recorder = recorder;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        int admitted = activeConnections.incrementAndGet();
        if (admitted > maxConnections) {
            activeConnections.decrementAndGet();
            LOG.warn("connection limit reached ({}), rejecting {}", maxConnections, session.getId());
            closeQuietly(session, "connection limit reached");
            return;
        }
        ConnectionState previous = connections.putIfAbsent(session, new ConnectionState(session));
        if (previous != null) {
            activeConnections.decrementAndGet();
        }
    }

    @Override
    public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) {
        ConnectionState st = connections.get(session);
        if (st == null) {
            closeQuietly(session, "connection not admitted");
            return;
        }
        if (message instanceof BinaryMessage bm) {
            if (st.audioActive) {
                ByteBuffer buf = bm.getPayload();
                if ((long) st.pcm.size() + buf.remaining() > maxAudioBytes) {
                    st.audioActive = false;
                    st.pcm.reset();
                    sendError(session, st, "AUDIO_TOO_LARGE",
                            "audio segment exceeds " + maxAudioBytes + " bytes");
                    return;
                }
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
            case "tts_request" -> onTtsRequest(session, st, castPayload(msg));
            case "cancel_turn" -> onCancelTurn(st, castPayload(msg));
            default -> {
                // ready/decision/reply/error/bye/tts_response 为服务端消息，客户端不应发送，忽略
            }
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        removeConnection(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) {
        removeConnection(session);
    }

    /** 连接拆除：移除状态并关闭本连接串行工作线程（未决任务随线程池中断丢弃）。 */
    private void removeConnection(WebSocketSession session) {
        ConnectionState st = connections.remove(session);
        if (st != null) {
            st.connExecutor.shutdownNow();
            activeConnections.decrementAndGet();
        }
    }

    @Override
    public boolean supportsPartialMessages() {
        return false;
    }

    /**
     * hello：接入策略校验（M1，authEnabled 时）→ SessionRegistry 取会话，不存在则新建 →
     * 回 ready（sessionId 以服务端采纳为准）。鉴权失败：error(BAD_AUTH) + close(4001)。
     */
    private void onHello(WebSocketSession session, ConnectionState st, Map<String, Object> payload) {
        if (authEnabled) {
            String deviceId = payload.get("deviceId") != null ? String.valueOf(payload.get("deviceId")) : null;
            String authToken = payload.get("authToken") != null ? String.valueOf(payload.get("authToken")) : null;
            String expected = deviceId == null ? null : authDevices.get(deviceId);
            if (expected == null || authToken == null
                    || !MessageDigest.isEqual(authToken.getBytes(StandardCharsets.UTF_8),
                                              expected.getBytes(StandardCharsets.UTF_8))) {
                LOG.warn("auth failed for deviceId={} session={}", deviceId, session.getId());
                sendError(session, st, "BAD_AUTH", "invalid device credentials");
                closeQuietly(session, "bad auth");
                return;
            }
            st.deviceId = deviceId;
            LOG.info("authenticated device {} session {}", deviceId, session.getId());
        }
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
        // 时钟同步：携带服务器墙钟毫秒，客户端据此估算时钟偏移（设备端 telemetry 统一换算服务器时钟）
        ready.put("serverTime", System.currentTimeMillis());
        send(session, "ready", ready);
    }

    /** audio_start：未握手不处理；开始累积，记录 utteranceId（优先采纳端侧值，缺失回退自增）与可选 segmentId（reply/error 原样回显）。 */
    private void onAudioStart(ConnectionState st, Map<String, Object> payload) {
        if (st.ctx == null) {
            return; // 未收到合法 hello 前不处理后续音频
        }
        st.audioActive = true;
        st.pcm.reset();
        st.pendingDecisions.clear();
        String clientUtteranceId = payload.get("utteranceId") != null
                ? String.valueOf(payload.get("utteranceId")) : null;
        st.utteranceId = clientUtteranceId != null && !clientUtteranceId.isBlank()
                ? clientUtteranceId
                : "u-" + ++st.segmentSeq; // 兼容旧客户端：无 utteranceId 时回退自增
        st.segmentId = payload.get("segmentId") != null ? String.valueOf(payload.get("segmentId")) : null;
    }

    /**
     * audio_end（M2 异步化）：快照本段上下文（pcm/ctx/utteranceId/segmentId）提交到本连接
     * 串行工作线程，立即返回——WS 消息线程不被最长 safetyTimeoutMs 的处理占死。上一段处理中
     * （processing）再收 audio_end → error(BUSY)（本段音频已丢弃，端侧可依赖本地兜底链路）。
     */
    private void onAudioEnd(WebSocketSession session, ConnectionState st) {
        if (!st.audioActive || st.ctx == null) {
            return;
        }
        st.audioActive = false;
        if (st.processing) {
            LOG.warn("segment dropped: previous segment still processing (session={})", st.ctx.sessionId());
            sendError(session, st, "BUSY", "previous segment still processing");
            return;
        }
        st.processing = true;
        byte[] pcm = st.pcm.toByteArray();
        SessionContext ctx = st.ctx;
        String utteranceId = st.utteranceId;
        String segmentId = st.segmentId;
        st.processingUtteranceId = utteranceId;
        st.processingSegmentId = segmentId;
        st.connExecutor.submit(() -> processSegment(session, st, pcm, ctx, utteranceId, segmentId));
    }

    private void onCancelTurn(ConnectionState st, Map<String, Object> payload) {
        String segmentId = String.valueOf(payload.get("segmentId"));
        String cancelUtteranceId;
        if (segmentId.equals(st.processingSegmentId)) {
            cancelUtteranceId = st.processingUtteranceId;
        } else if (segmentId.equals(st.segmentId)) {
            cancelUtteranceId = st.utteranceId;
        } else {
            return;
        }
        st.cancelledSegments.add(segmentId);
        online.cancel(cancelUtteranceId);
    }

    /**
     * 工作线程执行段处理：只读快照（不回写 ConnectionState——audioActive 由 onAudioEnd 同步管），
     * 发送走任务线程 session.sendMessage（Spring WS 线程安全）。handleSegment 返回后本段决策事件
     * 已全部入队（arbiter 胜方恒先 sink.log 后 complete，迟到者被 CAS 拒绝），drain 无竞态。
     */
    private void processSegment(WebSocketSession session, ConnectionState st, byte[] pcm,
                                SessionContext ctx, String utteranceId, String segmentId) {
        try {
            if (pcm.length == 0) {
                return;
            }
            if (isCancelled(st, segmentId)) {
                return;
            }
            SegmentPipeline.SegmentResult result;
            try {
                result = st.pipeline.handleSegment(pcm, ctx, utteranceId, segmentId,
                        streamSink(session, st, segmentId));
            } catch (RuntimeException e) {
                // 防御：pipeline 保证不抛异常；意外失败仍走兜底话术
                result = new SegmentPipeline.SegmentResult(null, SegmentPipeline.FALLBACK_TEXT, null, null);
            }
            DecisionEntry entry;
            while ((entry = st.pendingDecisions.poll()) != null) {
                send(session, "decision", MAPPER.convertValue(entry, new TypeReference<Map<String, Object>>() {
                }));
            }
            if (!result.streamed() && !isCancelled(st, segmentId)) {
                sendReply(session, st, result, segmentId);
            }
        } finally {
            if (segmentId != null) st.cancelledSegments.remove(segmentId);
            if (segmentId != null && segmentId.equals(st.processingSegmentId)) {
                st.processingSegmentId = null;
                st.processingUtteranceId = null;
            }
            st.processing = false;
        }
    }

    private OnlineAudioSink streamSink(WebSocketSession session, ConnectionState st, String segmentId) {
        return new OnlineAudioSink() {
            private boolean allowed() {
                return !isCancelled(st, segmentId);
            }

            @Override
            public void onStart(int sampleRate, int channels, String encoding) {
                if (!allowed()) return;
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("segmentId", segmentId);
                payload.put("mime", "audio/pcm");
                payload.put("sampleRate", sampleRate);
                payload.put("channels", channels);
                payload.put("encoding", encoding);
                send(session, "audio_reply_start", payload);
            }

            @Override
            public void onChunk(byte[] pcm) {
                if (allowed() && pcm.length > 0) sendBinary(session, pcm);
            }

            @Override
            public void onComplete(String speakText, Intent intent) {
                onComplete(speakText, intent, "");
            }

            @Override
            public void onComplete(String speakText, Intent intent, String asrText) {
                if (!allowed()) return;
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("segmentId", segmentId);
                if (speakText != null && !speakText.isBlank()) payload.put("speakText", speakText);
                if (intent != null) payload.put("intent", intent);
                if (asrText != null && !asrText.isBlank()) payload.put("asrText", asrText);
                send(session, "audio_reply_end", payload);
            }
        };
    }

    private static boolean isCancelled(ConnectionState st, String segmentId) {
        return segmentId != null && st.cancelledSegments.contains(segmentId);
    }

    /**
     * 下行收敛（协议 v1.1）：S2S → kind=audio；其他 intent 非空 → kind=action
     * （intent + speakText）；纯文本 → kind=text 且 <b>text 与 speakText 同带</b>
     * （端侧 parseReply 对 kind=text 强读 text 字段，text 缺失会丢回复）。
     * asrText（Task 61：识别文本，端侧云端胜出时写进识别区）非空时附带。
     * segmentId 用快照（工作线程回显本段的值，此时 st.segmentId 可能已被下一段覆盖）。
     */
    private void sendReply(WebSocketSession session, ConnectionState st, SegmentPipeline.SegmentResult result,
                           String segmentId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (result.asrText() != null && !result.asrText().isBlank()) {
            payload.put("asrText", result.asrText());
        }
        if (result.audio() != null) {
            payload.put("kind", "audio");
            payload.put("mime", result.mime());
            payload.put("dataBase64", java.util.Base64.getEncoder().encodeToString(result.audio()));
            if (result.speakText() != null && !result.speakText().isBlank()) {
                payload.put("speakText", result.speakText());
            }
            if (result.intent() != null) {
                payload.put("intent", result.intent());
            }
        } else if (result.intent() != null) {
            payload.put("kind", "action");
            payload.put("intent", result.intent());
            if (result.speakText() != null) {
                payload.put("speakText", result.speakText());
            }
        } else {
            payload.put("kind", "text");
            if (result.text() != null) {
                payload.put("text", result.text());
            }
            if (result.speakText() != null) {
                payload.put("speakText", result.speakText());
            }
        }
        if (segmentId != null) {
            payload.put("segmentId", segmentId); // 回显 audio_start 的 segmentId（端侧按话语对账）
        }
        send(session, "reply", payload);
    }

    /**
     * pending 占位消息下发（B5，protocol.md §4.8）：LLM 处理中时随 PENDING 事件由
     * offline 回调线程触发。send 在连接关闭时抛 IllegalStateException——try/catch 包裹，
     * 不污染 offline 回调线程（连接拆除路径有 removeConnection 兜底，此处仅告警）。
     */
    private void sendPending(WebSocketSession session, String segmentId) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            if (segmentId != null) {
                payload.put("segmentId", segmentId); // 回显 audio_start 的 segmentId（端侧按话语对账）
            }
            payload.put("text", PENDING_TEXT);
            send(session, "pending", payload);
        } catch (RuntimeException e) {
            LOG.warn("pending downlink failed (session closing?): {}", e.getMessage());
        }
    }

    private void sendError(WebSocketSession session, ConnectionState st, String code, String message) {
        sendError(session, st, code, message, st.segmentId);
    }

    private void sendError(WebSocketSession session, ConnectionState st, String code, String message,
                           String segmentId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (st.ctx != null) {
            payload.put("sessionId", st.ctx.sessionId());
        }
        if (segmentId != null) {
            payload.put("segmentId", segmentId); // 回显本请求的 segmentId，供端侧对账
        }
        payload.put("code", code);
        payload.put("message", message);
        send(session, "error", payload);
    }

    /**
     * tts_request：独立 TTS 链路（与识别/仲裁解耦，协议 v1.1 §4.5）——要求已握手；
     * 同步合成文本 → 下发 {@code tts_response}{mime, dataBase64, text, segmentId}；
     * 合成失败 → error(TTS_FAILED)，不关连接（与音频链路错误语义一致）。
     */
    private void onTtsRequest(WebSocketSession session, ConnectionState st, Map<String, Object> payload) {
        if (st.ctx == null) {
            return; // 未收到合法 hello 前不处理（与音频链路一致）
        }
        String text = String.valueOf(payload.get("text"));
        String ttsSegmentId = payload.get("segmentId") != null ? String.valueOf(payload.get("segmentId")) : null;
        // 链路插桩（Task 5）：tts_request 的 utteranceId（GatewayCodec 白名单，Task 2）透传合成链，缺省 ""
        String utteranceId = payload.get("utteranceId") != null ? String.valueOf(payload.get("utteranceId")) : "";
        try {
            ttsExecutor.execute(() -> synthesizeAndSend(session, st, text, ttsSegmentId, utteranceId));
        } catch (RejectedExecutionException e) {
            sendError(session, st, "TTS_BUSY", "tts queue is full", ttsSegmentId);
        }
    }

    private void synthesizeAndSend(WebSocketSession session, ConnectionState st, String text,
                                   String ttsSegmentId, String utteranceId) {
        try {
            Reply reply = tts.synthesize(text, st.ctx, utteranceId);
            if (!"audio".equals(reply.kind()) || reply.data() == null || reply.data().length == 0) {
                throw new IllegalStateException("tts returned non-audio reply: kind=" + reply.kind());
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("mime", reply.mime());
            out.put("dataBase64", Base64.getEncoder().encodeToString(reply.data()));
            out.put("text", text);
            if (ttsSegmentId != null) {
                out.put("segmentId", ttsSegmentId);
            }
            send(session, "tts_response", out);
        } catch (Exception e) {
            sendError(session, st, "TTS_FAILED", "tts failed: " + e.getMessage(), ttsSegmentId);
        }
    }

    private static void send(WebSocketSession session, String type, Map<String, Object> payload) {
        try {
            // Spring 的原始 WebSocketSession 不保证并发 send 安全；以 session 为锁统一串行下行。
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(GatewayCodec.encode(type, payload)));
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to send " + type + " message", e);
        }
    }

    private static void sendBinary(WebSocketSession session, byte[] bytes) {
        try {
            synchronized (session) {
                if (session.isOpen()) session.sendMessage(new BinaryMessage(bytes));
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to send audio chunk", e);
        }
    }

    /** 接入策略关闭（4001）：send 已抛错时也尽力关；close 失败仅记日志不抛。 */
    private static void closeQuietly(WebSocketSession session, String reason) {
        try {
            if (session.isOpen()) {
                session.close(new CloseStatus(POLICY_CLOSE.getCode(), reason));
            }
        } catch (IOException e) {
            LOG.warn("failed to close {}: {}", session.getId(), e.getMessage());
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

    /**
     * 每连接状态。仲裁器与流水线各连接一份；sink 收集本连接待下发的决策事件
     * （CLQ：audio_start 的 clear 与 arbiter 回调 add 跨线程，M2）。
     */
    private final class ConnectionState {
        /** 本连接 WS 会话（B5：pending 占位消息经此下发，eventSink 异步回调需要）。 */
        final WebSocketSession session;

        ConnectionState(WebSocketSession session) {
            this.session = session;
            // B3：仲裁过程事件（received/won/lost）经 eventSink 映射为 telemetry 插桩；
            // 迟到事件在 decide() 返回后仍可能触发（宽限期任务），utteranceId 随事件绑定正确轮次。
            // B5：PENDING 事件 → 额外下发 pending 占位消息（segmentId 用事件携带的快照，
            // 不可读本类可变字段——回调可能已被下一轮 audio_start 覆盖）。
            // 构造器内初始化：lambda 引用 final session，字段初始化器阶段它尚未赋值。
            arbiter = new RaceArbiter(safetyTimeoutMs, offlineGraceMs, scheduler, sink,
                    (uid, event) -> {
                        SegmentPipeline.recordArbiterEvent(recorder, uid, event);
                        if (event.kind() == CloudArbiterEvent.Kind.PENDING) {
                            VoiceGatewayHandler.this.sendPending(session, event.segmentId());
                        }
                    });
            pipeline = new SegmentPipeline(online, arbiter, offline, asrFailWaitMs, sink, recorder);
        }

        /** 本连接串行工作线程（M2）：audio_end 后的段处理在此执行，不占 WS 消息线程。 */
        final ExecutorService connExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "gateway-conn-" + CONN_SEQ.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        final Queue<DecisionEntry> pendingDecisions = new ConcurrentLinkedQueue<>();
        final DecisionSink sink = pendingDecisions::add;
        final RaceArbiter arbiter;
        final SegmentPipeline pipeline;
        final ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        SessionContext ctx;
        String deviceId; // 鉴权通过后记录（日志/审计用；authEnabled=false 时恒 null）
        String utteranceId; // 端侧 utteranceId 或自增回退（u-N），决策事件/链路插桩复用
        String segmentId; // 当前话语的客户端生成 ID（audio_start 可选字段，reply/error 回显）
        volatile String processingUtteranceId; // 工作线程当前处理轮快照（cancel_turn 不读下一轮字段）
        volatile String processingSegmentId;
        final Set<String> cancelledSegments = ConcurrentHashMap.newKeySet();
        boolean audioActive;
        volatile boolean processing; // 本连接一段流水线处理中（audio_end 的 in-flight 守卫）
        long segmentSeq;
    }

    /** Spring 销毁 bean 时停止共享线程池；连接级 executor 逐一关闭。 */
    @Override
    public void close() {
        for (ConnectionState st : connections.values()) {
            st.connExecutor.shutdownNow();
        }
        connections.clear();
        activeConnections.set(0);
        ttsExecutor.shutdownNow();
        scheduler.shutdownNow();
    }
}
