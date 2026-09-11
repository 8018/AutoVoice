package com.autovoice.server.gateway;

import com.autovoice.server.arbitration.DecisionSink;
import com.autovoice.server.arbitration.RaceArbiter;
import com.autovoice.server.contracts.CloudArbiterEvent;
import com.autovoice.server.contracts.DecisionEntry;
import com.autovoice.server.contracts.Intent;
import com.autovoice.server.contracts.OnlineSpeechProvider;
import com.autovoice.server.contracts.OnlineSpeechResult;
import com.autovoice.server.contracts.OnlineAudioSink;
import com.autovoice.server.contracts.OnlineAsrSink;
import com.autovoice.server.contracts.OnlineSpeechStream;
import com.autovoice.server.contracts.Reply;
import com.autovoice.server.contracts.RealtimeChatProvider;
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
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
 *       随后下发（协议 §5 时序不变）；每连接保留一个处理轮和一个候选轮；</li>
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
    private static final int DEFAULT_MAX_CONNECTIONS = 32;
    /** 单段最多 60 秒 PCM（16kHz / 16bit / mono），防止连接持续推帧耗尽堆内存。 */
    private static final int DEFAULT_MAX_AUDIO_BYTES = 60 * 16_000 * 2;
    private static final int DEFAULT_TTS_WORKERS = 4;
    private static final int DEFAULT_TTS_QUEUE_CAPACITY = 64;
    private static final long TURN_CACHE_TTL_MS = TimeUnit.MINUTES.toMillis(2);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Logger LOG = LoggerFactory.getLogger(VoiceGatewayHandler.class);

    private final OnlineSpeechProvider online;
    private final TtsProvider tts;
    private final OfflineCommandService offline;
    private final SessionRegistry registry;
    private final long safetyTimeoutMs;
    private final long asrFailWaitMs;
    private final long offlineGraceMs;
    private final GatewayDownlink downlink = new GatewayDownlink();

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

    /** 重连重发幂等缓存：同一设备/会话 + utteranceId 只复用已完成结果，不重复执行语义。 */
    private final ConcurrentMap<TurnKey, CachedTurn> completedTurns = new ConcurrentHashMap<>();

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
            downlink.closePolicy(session, "connection limit reached");
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
            downlink.closePolicy(session, "connection not admitted");
            return;
        }
        if (message instanceof BinaryMessage bm) {
            ByteBuffer buf = bm.getPayload();
            byte[] bytes = new byte[buf.remaining()];
            buf.get(bytes);
            if (st.realtimeChat.appendIfActive(bytes)) return;
            if (st.audioActive) {
                if ((long) st.pcm.size() + bytes.length > maxAudioBytes) {
                    st.audioActive = false;
                    st.pcm.reset();
                    downlink.sendError(session, st.ctx, "AUDIO_TOO_LARGE",
                            "audio segment exceeds " + maxAudioBytes + " bytes", st.segmentId);
                    return;
                }
                st.pcm.writeBytes(bytes);
                OnlineSpeechStream stream = st.onlineStream;
                if (stream != null) {
                    try { stream.append(bytes); }
                    catch (RuntimeException error) {
                        stream.cancel();
                        st.onlineStream = null;
                        LOG.warn("streaming ASR append failed; batch fallback on audio_end", error);
                    }
                }
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
            downlink.sendError(session, st.ctx, errorCodeOf(tm.getPayload()),
                    "invalid message: " + e.getMessage(), st.segmentId);
            return;
        }
        switch ((String) msg.get("type")) {
            case "hello" -> onHello(session, st, castPayload(msg));
            case "audio_start" -> onAudioStart(st, castPayload(msg));
            case "audio_end" -> onAudioEnd(session, st);
            case "turn_commit" -> onTurnCommit(st, castPayload(msg));
            case "tts_request" -> onTtsRequest(session, st, castPayload(msg));
            case "cancel_turn" -> onCancelTurn(st, castPayload(msg));
            case "chat_start" -> st.realtimeChat.start();
            case "chat_finish" -> st.realtimeChat.finish();
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
            releaseConnection(st);
            activeConnections.decrementAndGet();
        }
    }

    /** Both transport teardown and bean destruction own the same upstream resources. */
    private void releaseConnection(ConnectionState st) {
        st.outputPermits.values().forEach(
                permit -> permit.revoke(TurnOutputPermit.RevocationReason.CONNECTION_CLOSED));
        OnlineSpeechStream stream = st.onlineStream;
        st.onlineStream = null;
        try {
            if (stream != null) stream.cancel();
            SegmentWork processing = st.turns.processing();
            SegmentWork queued = st.turns.queued();
            if (processing != null && processing.inputStream() != null) processing.inputStream().cancel();
            if (queued != null && queued.inputStream() != null) queued.inputStream().cancel();
            st.turns.clear();
        } catch (RuntimeException ignored) {
            // A failed upstream cancellation must not skip the other resource owners.
        } finally {
            st.realtimeChat.close();
            st.connExecutor.shutdownNow();
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
                downlink.sendError(session, st.ctx,
                        "BAD_AUTH", "invalid device credentials", st.segmentId);
                downlink.closePolicy(session, "bad auth");
                return;
            }
            st.deviceId = deviceId;
            LOG.info("authenticated device {} session {}", deviceId, session.getId());
        }
        if (st.ctx == null) {
            Object sessionIdRaw = payload.get("sessionId");
            String sessionId = sessionIdRaw == null ? null : String.valueOf(sessionIdRaw);
            if (authEnabled && sessionId != null && !sessionId.isBlank()) {
                // D02a：鉴权开启时按所有者 + 恢复凭据恢复会话,恢复凭据不写日志
                String resumeToken = payload.get("resumeToken") != null
                        ? String.valueOf(payload.get("resumeToken")) : null;
                SessionRegistry.ResumeResult result = registry.resume(sessionId, st.deviceId, resumeToken);
                switch (result) {
                    case OK -> st.ctx = registry.get(sessionId);
                    case NOT_FOUND -> st.ctx = registry.create(DEFAULT_LANGUAGE, st.deviceId);
                    case EXPIRED -> {
                        LOG.warn("session expired for deviceId={}", st.deviceId);
                        downlink.sendError(session, st.ctx, "SESSION_EXPIRED",
                                "session expired; reconnect without sessionId to start a new session",
                                st.segmentId);
                        downlink.closePolicy(session, "session expired");
                        return;
                    }
                    case OWNER_MISMATCH, BAD_CREDENTIAL -> {
                        LOG.warn("session recovery denied for deviceId={}", st.deviceId);
                        downlink.sendError(session, st.ctx, "SESSION_RECOVER_DENIED",
                                "session ownership or recovery credential invalid", st.segmentId);
                        downlink.closePolicy(session, "session recovery denied");
                        return;
                    }
                }
            } else {
                // 兼容路径:鉴权关闭(demo/本地)时按 sessionId 直接恢复,行为与历史一致
                SessionContext ctx = registry.get(sessionId == null ? "" : sessionId);
                if (ctx == null) {
                    ctx = authEnabled
                            ? registry.create(DEFAULT_LANGUAGE, st.deviceId)
                            : registry.create(DEFAULT_LANGUAGE);
                }
                st.ctx = ctx;
            }
        }
        Map<String, Object> ready = new LinkedHashMap<>();
        ready.put("sessionId", st.ctx.sessionId());
        ready.put("language", st.ctx.language());
        ready.put("resumeToken", st.ctx.resumeToken());
        ready.put("protocolVersion", PROTOCOL_VERSION);
        // 时钟同步：携带服务器墙钟毫秒，客户端据此估算时钟偏移（设备端 telemetry 统一换算服务器时钟）
        ready.put("serverTime", System.currentTimeMillis());
        downlink.send(session, "ready", ready);
    }

    /** audio_start：未握手不处理；开始累积，记录 utteranceId（优先采纳端侧值，缺失回退自增）与可选 segmentId（reply/error 原样回显）。 */
    private void onAudioStart(ConnectionState st, Map<String, Object> payload) {
        if (st.ctx == null) {
            return; // 未收到合法 hello 前不处理后续音频
        }
        st.audioActive = true;
        st.pcm.reset();
        String clientUtteranceId = payload.get("utteranceId") != null
                ? String.valueOf(payload.get("utteranceId")) : null;
        st.utteranceId = clientUtteranceId != null && !clientUtteranceId.isBlank()
                ? clientUtteranceId
                : "u-" + ++st.segmentSeq; // 兼容旧客户端：无 utteranceId 时回退自增
        st.segmentId = payload.get("segmentId") != null ? String.valueOf(payload.get("segmentId")) : null;
        st.activePermit = new TurnOutputPermit(st.utteranceId, st.segmentId);
        st.outputPermits.putIfAbsent(st.utteranceId, st.activePermit);
        st.activeAsrTrace = new AsrTurnTrace(recorder, st.utteranceId, online.id());
        // Snapshot before opening ASR: the streaming provider must see this turn's displayed list.
        st.ctx = st.ctx.withAttr("navigationSelectionId", payload.get("navigationSelectionId") instanceof String id ? id : null);
        // Position belongs to this audio request. Absence must clear a previous fix.
        st.ctx = st.ctx.withAttr("latitude", null).withAttr("longitude", null);
        Object latitude = payload.get("latitude");
        Object longitude = payload.get("longitude");
        if (latitude instanceof Number lat && longitude instanceof Number lon
                && Double.isFinite(lat.doubleValue()) && Double.isFinite(lon.doubleValue())
                && Math.abs(lat.doubleValue()) <= 90 && Math.abs(lon.doubleValue()) <= 180) {
            st.ctx = st.ctx.withAttr("latitude", lat.doubleValue()).withAttr("longitude", lon.doubleValue());
        }
        if (st.onlineStream != null) st.onlineStream.cancel();
        try {
            // 流式阶段只允许 ASR 旁路出字；回答音频仍由 audio_end 后的仲裁门控制。
            st.onlineStream = online.openStream(st.ctx, st.utteranceId,
                    OnlineAudioSink.NOOP,
                    asrSink(st.session, st, st.utteranceId, st.segmentId,
                            st.activePermit, st.activeAsrTrace));
            if (st.onlineStream == null) {
                st.activeAsrTrace.useBatch("streaming_unsupported", null);
            }
        } catch (RuntimeException error) {
            st.onlineStream = null;
            st.activeAsrTrace.useBatch("streaming_start_failed", error);
            LOG.warn("streaming ASR start failed; batch fallback on audio_end", error);
        }
        // audio_start only creates a recognition candidate. VAD alone must not supersede the
        // currently processing business turn; ASR/semantic admission calls commitCandidate.
    }

    /**
     * audio_end（M2 异步化）：快照本段上下文（pcm/ctx/utteranceId/segmentId）提交到本连接
     * 串行工作线程，立即返回——WS 消息线程不被最长 safetyTimeoutMs 的处理占死。上一段处理中
     * 再收 audio_end 时保留一个候选段；只有候选经 ASR/语义准入后才作废旧轮。
     */
    private void onAudioEnd(WebSocketSession session, ConnectionState st) {
        if (!st.audioActive || st.ctx == null) {
            return;
        }
        st.audioActive = false;
        byte[] pcm = st.pcm.toByteArray();
        SessionContext ctx = st.ctx;
        String utteranceId = st.utteranceId;
        String segmentId = st.segmentId;
        OnlineSpeechStream onlineStream = st.onlineStream;
        TurnOutputPermit outputPermit = st.activePermit;
        AsrTurnTrace asrTrace = st.activeAsrTrace;
        asrTrace.markFinish();
        st.onlineStream = null;
        SegmentWork work = new SegmentWork(
                pcm, ctx, utteranceId, segmentId, onlineStream, outputPermit, asrTrace);
        ConnectionTurnCoordinator.Offer offer = st.turns.offer(work);
        if (offer == ConnectionTurnCoordinator.Offer.START_NOW) {
            submitSegment(session, st, work);
        } else if (offer == ConnectionTurnCoordinator.Offer.REJECTED) {
            outputPermit.revoke(TurnOutputPermit.RevocationReason.CANCELLED);
            schedulePermitCleanup(st, outputPermit);
            if (onlineStream != null) onlineStream.cancel();
            downlink.sendError(session, st.ctx, "BUSY", "candidate queue is full", segmentId);
        }
    }

    /** Client-side ASR/NLU admission for providers whose evidence is established on the device. */
    private void onTurnCommit(ConnectionState st, Map<String, Object> payload) {
        String segmentId = String.valueOf(payload.get("segmentId"));
        String utteranceId = String.valueOf(payload.get("utteranceId"));
        commitCandidate(st, utteranceId, segmentId);
    }

    private void commitCandidate(ConnectionState st, String utteranceId, String segmentId) {
        if (utteranceId == null || utteranceId.isBlank() || segmentId == null || segmentId.isBlank()) return;
        boolean activeCandidate = segmentId.equals(st.segmentId) && utteranceId.equals(st.utteranceId);
        if (!activeCandidate && !st.turns.ownsSegment(segmentId)) return;
        SegmentWork processing = st.turns.processing();
        if (processing == null || utteranceId.equals(processing.utteranceId())) return;
        processing.outputPermit().revoke(TurnOutputPermit.RevocationReason.SUPERSEDED);
        discardDecisions(st, processing.utteranceId());
    }

    private void submitSegment(WebSocketSession session, ConnectionState st, SegmentWork work) {
        st.connExecutor.submit(() -> processSegment(session, st, work));
    }

    private void onCancelTurn(ConnectionState st, Map<String, Object> payload) {
        String segmentId = String.valueOf(payload.get("segmentId"));
        SegmentWork processing = st.turns.processing();
        if (processing != null && segmentId.equals(processing.segmentId())) {
            // 仅撤销输出权限并唤醒连接 worker；provider 与仲裁候选继续自然完成。
            processing.outputPermit().revoke(TurnOutputPermit.RevocationReason.CANCELLED);
            discardDecisions(st, processing.utteranceId());
        } else if (st.turns.ownsSegment(segmentId)) {
            SegmentWork queued = st.turns.removeQueued(segmentId);
            if (queued != null) {
                queued.outputPermit().revoke(TurnOutputPermit.RevocationReason.CANCELLED);
                discardDecisions(st, queued.utteranceId());
                schedulePermitCleanup(st, queued.outputPermit());
                if (queued.inputStream() != null) {
                    try {
                        queued.inputStream().finish();
                    } catch (RuntimeException ignored) {
                        // Output is revoked; provider reports its own normal completion failure.
                    }
                }
            }
        } else if (segmentId.equals(st.segmentId)) {
            // 尚未提交的识别候选也只撤销输出；finish 是正常收尾，不是取消计算。
            if (st.activePermit != null) {
                st.activePermit.revoke(TurnOutputPermit.RevocationReason.CANCELLED);
                discardDecisions(st, st.activePermit.utteranceId());
                schedulePermitCleanup(st, st.activePermit);
            }
            if (st.onlineStream != null) {
                try {
                    st.onlineStream.finish();
                } catch (RuntimeException ignored) {
                    // Provider owns its normal failure path; output is already revoked.
                }
                st.onlineStream = null;
            }
            st.audioActive = false;
        }
    }

    /**
     * 工作线程执行段处理：只读快照（不回写 ConnectionState——audioActive 由 onAudioEnd 同步管），
     * 发送走任务线程 session.sendMessage（Spring WS 线程安全）。handleSegment 返回后本段决策事件
     * 已全部入队（arbiter 胜方恒先 sink.log 后 complete，迟到者被 CAS 拒绝），drain 无竞态。
     */
    private void processSegment(WebSocketSession session, ConnectionState st, SegmentWork work) {
        processSegment(session, st, work.pcm(), work.ctx(), work.utteranceId(), work.segmentId(),
                work.inputStream(), work);
    }

    private void processSegment(WebSocketSession session, ConnectionState st, byte[] pcm,
                                SessionContext ctx, String utteranceId, String segmentId,
                                OnlineSpeechStream inputStream, SegmentWork ownedWork) {
        try {
            if (pcm.length == 0) {
                if (inputStream != null) inputStream.cancel();
                return;
            }
            TurnKey turnKey = new TurnKey(st.deviceId != null ? st.deviceId : ctx.sessionId(), utteranceId);
            CachedTurn cached = completedTurns.get(turnKey);
            if (cached != null && cached.expiresAtMs() > System.currentTimeMillis()) {
                if (inputStream != null) inputStream.cancel();
                // 流式首发的重放改成单帧 reply；结果携带完整音频，端侧仍走统一播放器。
                if (ownedWork.outputPermit().allowsOutput()) {
                    downlink.sendReply(session, cached.result(), segmentId);
                }
                return;
            }
            if (cached != null) completedTurns.remove(turnKey, cached);
            SegmentPipeline.SegmentResult result;
            try {
                CompletableFuture<OnlineSpeechResult> onlineCandidate = null;
                if (inputStream != null) {
                    try {
                        CompletableFuture<OnlineSpeechResult> streamed = inputStream.finish();
                        OnlineAsrSink fallbackAsr = asrSink(
                                session, st, utteranceId, segmentId,
                                ownedWork.outputPermit(), ownedWork.asrTrace());
                        onlineCandidate = streamed.handle((value, error) -> {
                            if (error == null) return CompletableFuture.completedFuture(value);
                            ownedWork.asrTrace().onFailure(error);
                            ownedWork.asrTrace().useBatch("streaming_finish_failed", error);
                            LOG.warn("streaming ASR failed; retrying once with buffered PCM", error);
                            return online.process(pcm, ctx, utteranceId,
                                    OnlineAudioSink.NOOP, fallbackAsr);
                        }).thenCompose(future -> future);
                    }
                    catch (RuntimeException error) {
                        LOG.warn("streaming ASR finish failed; batch fallback", error);
                    }
                }
                result = st.pipeline.handleSegment(pcm, ctx, utteranceId, segmentId,
                        streamSink(session, st, segmentId, ownedWork.outputPermit()),
                        asrSink(session, st, utteranceId, segmentId,
                                ownedWork.outputPermit(), ownedWork.asrTrace()),
                        onlineCandidate, ownedWork.outputPermit().revoked());
            } catch (RuntimeException e) {
                // 防御：pipeline 保证不抛异常；意外失败仍走兜底话术
                result = new SegmentPipeline.SegmentResult(null, SegmentPipeline.FALLBACK_TEXT, null, null);
            }
            if (result == null || !ownedWork.outputPermit().allowsOutput()) {
                // 会话层已撤销输出：不缓存、不下发决策、不回复；候选仍自然完成。
                drainDecisions(session, st, utteranceId, false);
                return;
            }
            CachedTurn completed = new CachedTurn(result, System.currentTimeMillis() + TURN_CACHE_TTL_MS);
            completedTurns.put(turnKey, completed);
            scheduler.schedule(() -> completedTurns.remove(turnKey, completed), TURN_CACHE_TTL_MS, TimeUnit.MILLISECONDS);
            drainDecisions(session, st, utteranceId, true);
            if (!result.streamed() && ownedWork.outputPermit().allowsOutput()) {
                downlink.sendReply(session, result, segmentId);
            }
        } finally {
            if (ownedWork != null) schedulePermitCleanup(st, ownedWork.outputPermit());
            if (ownedWork != null) {
                SegmentWork next = st.turns.complete(ownedWork);
                if (next != null) submitSegment(session, st, next);
            }
        }
    }

    /** Decision events are turn-owned; a newer audio_start must not clear or inherit another turn's log. */
    private void drainDecisions(WebSocketSession session, ConnectionState st,
                                String utteranceId, boolean emit) {
        for (DecisionEntry entry : st.pendingDecisions) {
            if (!Objects.equals(utteranceId, entry.utteranceId()) || !st.pendingDecisions.remove(entry)) continue;
            if (emit) {
                downlink.send(session, "decision", MAPPER.convertValue(entry,
                        new TypeReference<Map<String, Object>>() {}));
            }
        }
    }

    private static void discardDecisions(ConnectionState st, String utteranceId) {
        st.pendingDecisions.removeIf(entry -> Objects.equals(utteranceId, entry.utteranceId()));
    }

    private void schedulePermitCleanup(ConnectionState st, TurnOutputPermit permit) {
        if (permit == null || permit.utteranceId() == null) return;
        long delayMs = safetyTimeoutMs + offlineGraceMs + 1000;
        scheduler.schedule(() -> st.outputPermits.remove(permit.utteranceId(), permit),
                delayMs, TimeUnit.MILLISECONDS);
    }

    private OnlineAudioSink streamSink(WebSocketSession session, ConnectionState st, String segmentId,
                                       TurnOutputPermit outputPermit) {
        return new OnlineAudioSink() {
            private final long startedAtNanos = System.nanoTime();

            private boolean allowed() {
                return outputPermit.allowsOutput();
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
                downlink.send(session, "audio_reply_start", payload);
            }

            @Override
            public void onChunk(byte[] pcm) {
                if (allowed() && pcm.length > 0) downlink.sendBinary(session, pcm);
            }

            @Override
            public void onReplyText(String text, boolean isFinal) {
                if (!allowed() || segmentId == null || text == null || text.isBlank()) return;
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("segmentId", segmentId);
                payload.put("text", text);
                payload.put("isFinal", isFinal);
                downlink.send(session, "reply_partial", payload);
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
                downlink.send(session, "audio_reply_end", payload);
            }

            @Override
            public void onError(Throwable error) {
                if (!allowed()) return;
                long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
                String errorType = error == null ? "unknown" : error.getClass().getName();
                String errorMessage = error == null ? "online audio stream aborted"
                        : String.valueOf(error.getMessage());
                LOG.warn("online stream aborted: session={} segment={} elapsedMs={} errorType={} message={}",
                        st.ctx == null ? "" : st.ctx.sessionId(), segmentId, elapsedMs,
                        errorType, errorMessage, error);
                downlink.sendError(session, st.ctx, "ONLINE_STREAM_ABORTED",
                        errorMessage, segmentId);
            }
        };
    }

    /** ASR/PGS 旁路：不经过语义仲裁门，识别一出字就下发；仅拦截取消/过期轮。 */
    private OnlineAsrSink asrSink(WebSocketSession session, ConnectionState st,
                                  String utteranceId, String segmentId,
                                  TurnOutputPermit outputPermit, AsrTurnTrace asrTrace) {
        return new OnlineAsrSink() {
            private final AtomicBoolean turnEstablishedSent = new AtomicBoolean();

            private boolean active() {
                return segmentId != null && (segmentId.equals(st.segmentId)
                        || st.turns.ownsSegment(segmentId))
                        && outputPermit.allowsOutput();
            }

            @Override public void onTurnEstablished() {
                if (!active() || !turnEstablishedSent.compareAndSet(false, true)) return;
                commitCandidate(st, utteranceId, segmentId);
                downlink.send(session, "asr_turn_started", Map.of("segmentId", segmentId));
            }

            @Override public void onResult(String text, boolean isFinal) {
                asrTrace.onResult(text, isFinal);
                if (!active() || text == null || text.isBlank()) return;
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("segmentId", segmentId);
                payload.put("text", text);
                payload.put("isFinal", isFinal);
                downlink.send(session, "asr_partial", payload);
            }

            @Override public void onError(Throwable error) {
                asrTrace.onFailure(error);
            }
        };
    }

    private record TurnKey(String ownerId, String utteranceId) {}

    private record SegmentWork(byte[] pcm, SessionContext ctx, String utteranceId, String segmentId,
                               OnlineSpeechStream inputStream, TurnOutputPermit outputPermit,
                               AsrTurnTrace asrTrace)
            implements ConnectionTurnCoordinator.WorkIdentity {}

    private record CachedTurn(SegmentPipeline.SegmentResult result, long expiresAtMs) {}

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
            downlink.sendError(session, st.ctx, "TTS_BUSY", "tts queue is full", ttsSegmentId);
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
            downlink.send(session, "tts_response", out);
        } catch (Exception e) {
            downlink.sendError(session, st.ctx, "TTS_FAILED",
                    "tts failed: " + e.getMessage(), ttsSegmentId);
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
            RealtimeChatProvider chatProvider = online instanceof RealtimeChatProvider provider
                    ? provider : null;
            realtimeChat = new RealtimeChatBridge(session, chatProvider, connExecutor, downlink,
                    () -> ctx, () -> segmentId);
            // B3：仲裁过程事件（received/won/lost）经 eventSink 映射为 telemetry 插桩；
            // 迟到事件在 decide() 返回后仍可能触发（宽限期任务），utteranceId 随事件绑定正确轮次。
            // B5：PENDING 事件 → 额外下发 pending 占位消息（segmentId 用事件携带的快照，
            // 不可读本类可变字段——回调可能已被下一轮 audio_start 覆盖）。
            // 构造器内初始化：lambda 引用 final session，字段初始化器阶段它尚未赋值。
            arbiter = new RaceArbiter(safetyTimeoutMs, offlineGraceMs, scheduler, sink,
                    (uid, event) -> {
                        SegmentPipeline.recordArbiterEvent(recorder, uid, event);
                        TurnOutputPermit permit = outputPermits.get(uid);
                        if (event.kind() == CloudArbiterEvent.Kind.PENDING
                                && (permit == null || permit.allowsOutput())) {
                            downlink.sendPending(session, event.segmentId());
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
        final ConcurrentMap<String, TurnOutputPermit> outputPermits = new ConcurrentHashMap<>();
        final DecisionSink sink = entry -> {
            TurnOutputPermit permit = outputPermits.get(entry.utteranceId());
            if (permit == null || permit.allowsOutput()) pendingDecisions.add(entry);
        };
        final RaceArbiter arbiter;
        final SegmentPipeline pipeline;
        final ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        SessionContext ctx;
        String deviceId; // 鉴权通过后记录（日志/审计用；authEnabled=false 时恒 null）
        String utteranceId; // 端侧 utteranceId 或自增回退（u-N），决策事件/链路插桩复用
        String segmentId; // 当前话语的客户端生成 ID（audio_start 可选字段，reply/error 回显）
        volatile TurnOutputPermit activePermit;
        volatile AsrTurnTrace activeAsrTrace;
        final ConnectionTurnCoordinator<SegmentWork> turns = new ConnectionTurnCoordinator<>();
        final RealtimeChatBridge realtimeChat;
        boolean audioActive;
        volatile OnlineSpeechStream onlineStream;
        long segmentSeq;
    }

    /** Spring 销毁 bean 时停止共享线程池；连接级 executor 逐一关闭。 */
    @Override
    public void close() {
        for (ConnectionState st : connections.values()) {
            releaseConnection(st);
        }
        connections.clear();
        completedTurns.clear();
        activeConnections.set(0);
        ttsExecutor.shutdownNow();
        scheduler.shutdownNow();
    }
}
