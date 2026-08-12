package com.autovoice.server.telemetry;

import com.autovoice.server.contracts.telemetry.TelemetryEvent;
import com.autovoice.server.contracts.telemetry.TelemetryRecorder;
import com.autovoice.server.contracts.telemetry.TelemetryStages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * 链路数据平台核心服务（实现 {@link TelemetryRecorder}）：单写线程串行落库
 * （sqlite-jdbc），查询走同一线程保证读写序（调用方同步等待）；SSE 注册监听在
 * recordDeviceRound 完成后推送 round 摘要。
 *
 * <p>装配：enabled 时注册为 Spring bean（{@code autovoice.telemetry.enabled=true}，
 * 默认 true）；禁用时由 TelemetryConfig 提供 {@code NoopTelemetryRecorder}。</p>
 */
@Component
@ConditionalOnProperty(prefix = "autovoice.telemetry", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class TelemetryService implements TelemetryRecorder {

    /** 端侧轮次事件包（POST /api/telemetry/round body；聚合列由 service 从 events 推导）。 */
    public record DeviceRoundPayload(String utteranceId, String sessionId, String deviceId,
                                     String source, Long startMs, Long endMs,
                                     List<TelemetryEvent> events) {
    }

    private static final Logger LOG = LoggerFactory.getLogger(TelemetryService.class);

    private final TelemetryProperties props;
    private final LongSupplier clock;
    private final SqliteTelemetryStore store;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "telemetry-writer");
        t.setDaemon(true);
        return t;
    });
    /** 已见 utteranceId（防并发下 record 重复建 round 骨架）。 */
    private final Set<String> knownRounds = ConcurrentHashMap.newKeySet();
    private final List<Consumer<RoundSummary>> listeners = new CopyOnWriteArrayList<>();

    /** Spring 装配入口（两个构造器必须显式标记 @Autowired，否则容器无法选择）。 */
    @Autowired
    public TelemetryService(TelemetryProperties props) {
        this(props, System::currentTimeMillis);
    }

    public TelemetryService(TelemetryProperties props, LongSupplier clock) {
        this.props = props;
        this.clock = clock;
        this.store = new SqliteTelemetryStore(props.dbPath());
        this.store.init();
    }

    // ---------- 写入（TelemetryRecorder） ----------

    /**
     * 记录单阶段事件：陌生 utteranceId 首见建 round 骨架（created_ms=now、start_ms=事件时刻），
     * 之后仅插事件行（幂等）。服务端插桩（Task 4 起）与 /events 转发都走这里。
     */
    @Override
    public void record(String utteranceId, TelemetryEvent event) {
        if (utteranceId == null || utteranceId.isBlank() || event == null) {
            return;
        }
        submit(() -> {
            if (knownRounds.add(utteranceId)) {
                store.upsertRound(utteranceId,
                        Map.of("created_ms", clock.getAsLong(), "start_ms", event.tsMs()));
            }
            // Task 4 服务端插桩走 record() 路径：关键 stage 补聚合列——纯云端轮次（端侧不上报
            // /round）的决策/识别/LLM/TTS 缓存列不落 NULL，面板决策分布统计有数据
            Map<String, Object> agg = aggregateFromEvent(event);
            if (!agg.isEmpty()) {
                store.upsertRound(utteranceId, agg);
            }
            store.insertEvent(utteranceId, event);
        });
    }

    /** 端侧事件包：upsert 聚合行 + 逐条插事件 + SSE 推摘要。 */
    public void recordDeviceRound(DeviceRoundPayload p) {
        if (p == null || p.utteranceId() == null || p.utteranceId().isBlank()) {
            throw new IllegalArgumentException("utteranceId is required");
        }
        submit(() -> {
            Map<String, Object> fields = new HashMap<>();
            if (knownRounds.add(p.utteranceId())) {
                fields.put("created_ms", clock.getAsLong());
            }
            putIfNotNull(fields, "session_id", p.sessionId());
            putIfNotNull(fields, "device_id", p.deviceId());
            putIfNotNull(fields, "source", p.source());
            putIfNotNull(fields, "start_ms", p.startMs());
            putIfNotNull(fields, "end_ms", p.endMs());
            fields.putAll(deriveAggregates(p.events()));
            store.upsertRound(p.utteranceId(), fields);
            if (p.events() != null) {
                for (TelemetryEvent e : p.events()) {
                    store.insertEvent(p.utteranceId(), e);
                }
            }
            RoundDetail detail = store.queryRound(p.utteranceId());
            if (detail != null) {
                for (Consumer<RoundSummary> l : listeners) {
                    l.accept(detail.summary());
                }
            }
        });
    }

    /**
     * PCM（s16le 16k mono）加 44 字节 WAV 头落盘 {audioDir}/{utteranceId}.wav，成功后记
     * audio_path=完整文件名（含 .wav，review finding 3：面板用 audio_path 拼回放 URL，
     * readAudio 原样 resolve）。
     */
    public void saveAudio(String utteranceId, byte[] pcm) {
        String safe = safeFileName(utteranceId);
        byte[] wav = new byte[44 + pcm.length];
        System.arraycopy(wavHeader(pcm.length), 0, wav, 0, 44);
        System.arraycopy(pcm, 0, wav, 44, pcm.length);
        try {
            Files.createDirectories(Path.of(props.audioDir()));
            Files.write(Path.of(props.audioDir()).resolve(safe + ".wav"), wav);
        } catch (IOException e) {
            throw new IllegalStateException("saveAudio failed: " + utteranceId, e);
        }
        submit(() -> store.upsertRound(utteranceId, Map.of("audio_path", safe + ".wav")));
    }

    /**
     * 读回 WAV 音频（GET /api/telemetry/audio/{file}）：fileName 为 DB audio_path 原值
     * （含 .wav 的消毒后纯文件名，review finding 3 与 saveAudio 的 upsert 一致）；
     * 不存在或非法路径 → Optional.empty。
     */
    public Optional<byte[]> readAudio(String fileName) {
        String safe;
        try {
            safe = safeFileName(fileName);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        Path file = Path.of(props.audioDir()).resolve(safe);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readAllBytes(file));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * 每日 04:03 定时清理（review finding 1）：spec 的 @Scheduled 要求。telemetry 禁用时
     * 本 bean 不注册，调度自然不存在。
     */
    @Scheduled(cron = "0 3 4 * * *")
    public void scheduledCleanup() {
        cleanupOld();
    }

    /** 清理超期轮次（created_ms < now - retentionDays）：rounds+events 行联动音频文件删除。 */
    public void cleanupOld() {
        submit(() -> {
            long cutoff = clock.getAsLong() - (long) props.retentionDays() * 86400000L;
            for (String id : store.deleteOlderThan(cutoff)) {
                knownRounds.remove(id);
                try {
                    Files.deleteIfExists(Path.of(props.audioDir()).resolve(safeFileName(id) + ".wav"));
                } catch (IOException | IllegalArgumentException ignored) {
                    // 音频缺失/ID 非法：行已删，文件尽力而为
                }
            }
        });
    }

    // ---------- 查询（与写线程串行，保证读写序） ----------

    public List<RoundSummary> queryRounds(String device, long fromMs, long toMs) {
        return syncQuery(() -> store.queryRounds(device, fromMs, toMs));
    }

    public RoundDetail queryRound(String utteranceId) {
        return syncQuery(() -> store.queryRound(utteranceId));
    }

    // ---------- SSE ----------

    public void addListener(Consumer<RoundSummary> listener) {
        listeners.add(listener);
    }

    /** 移除 SSE 监听（review finding 2：emitter 完成/超时/出错时自移除，防滞留）。 */
    public void removeListener(Consumer<RoundSummary> listener) {
        listeners.remove(listener);
    }

    // ---------- 内部 ----------

    private void submit(Runnable task) {
        writer.execute(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                LOG.warn("telemetry write failed: {}", String.valueOf(t.getMessage()));
            }
        });
    }

    private <T> T syncQuery(Callable<T> task) {
        try {
            return writer.submit(task).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("telemetry query interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException("telemetry query failed", cause);
        }
    }

    private static void putIfNotNull(Map<String, Object> f, String key, Object value) {
        if (value != null) {
            f.put(key, value);
        }
    }

    /**
     * 聚合列推导（recordDeviceRound 用）：取每 stage 末事件的 payload 摘要（route→决策、
     * text→识别/LLM/TTS 文本、result→执行/播放结果、hit→缓存命中）。推导不到（事件缺失/
     * 键缺失）则跳过，对应列保持 NULL。字段映射与 {@link #aggregateFromEvent} 同一套
     * （Task 4 起：record() 与 recordDeviceRound() 两路径共用）。
     */
    private static Map<String, Object> deriveAggregates(List<TelemetryEvent> events) {
        Map<String, Object> f = new HashMap<>();
        if (events != null) {
            for (TelemetryEvent e : events) {
                f.putAll(aggregateFromEvent(e));
            }
        }
        return f;
    }

    /**
     * 单事件 → 聚合列映射（record() 逐事件 upsert 与 recordDeviceRound() 批量推导共用）：
     * cloud_arbiter.route → cloud_decision；LLM.text → llm_reply；CLOUD_ASR.text →
     * asr_cloud；tts_cache.hit → tts_cache_hit；端侧 stage（device_arbiter/local_asr/…）同规则。
     */
    private static Map<String, Object> aggregateFromEvent(TelemetryEvent e) {
        Map<String, Object> f = new HashMap<>();
        if (e == null || e.stage() == null || e.payload() == null) {
            return f;
        }
        Map<String, Object> payload = e.payload();
        switch (e.stage()) {
            case TelemetryStages.DEVICE_ARBITER -> {
                putPayloadValue(f, "local_decision", payload, "route");
                putPayloadValue(f, "final_decision", payload, "route");
            }
            case TelemetryStages.CLOUD_ARBITER -> putPayloadValue(f, "cloud_decision", payload, "route");
            case TelemetryStages.LOCAL_ASR -> putPayloadValue(f, "asr_local", payload, "text");
            case TelemetryStages.CLOUD_ASR -> putPayloadValue(f, "asr_cloud", payload, "text");
            case TelemetryStages.LLM -> putPayloadValue(f, "llm_reply", payload, "text");
            case TelemetryStages.EXECUTE -> putPayloadValue(f, "execute_result", payload, "result");
            case TelemetryStages.TTS_REQUEST -> putPayloadValue(f, "tts_text", payload, "text");
            case TelemetryStages.TTS_PLAY -> putPayloadValue(f, "playback_result", payload, "result");
            case TelemetryStages.TTS_CACHE -> {
                if (payload.get("hit") != null) {
                    Object v = payload.get("hit");
                    boolean hit;
                    if (v instanceof Boolean b) {
                        hit = b;
                    } else if (v instanceof Number n) {
                        hit = n.intValue() != 0;
                    } else {
                        hit = Boolean.parseBoolean(String.valueOf(v));
                    }
                    f.put("tts_cache_hit", hit ? 1 : 0);
                }
            }
            default -> {
                // 未知 stage：无聚合列可推导
            }
        }
        return f;
    }

    private static void putPayloadValue(Map<String, Object> f, String col,
                                        Map<String, Object> payload, String key) {
        if (payload != null && payload.get(key) != null) {
            f.put(col, String.valueOf(payload.get(key)));
        }
    }

    /**
     * 路径防穿越：fileName 必须是纯文件名（{@code Path.of(fn).getFileName()} 与其一致）；
     * getFileName() 为 null（根路径等）也视为非法（review finding 4：原实现 NPE → 500）。
     */
    private static String safeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("file name is required");
        }
        Path p = Path.of(fileName);
        Path namePath = p.getFileName();
        if (namePath == null) {
            throw new IllegalArgumentException("invalid file name: " + fileName);
        }
        String name = namePath.toString();
        if (!name.equals(fileName) || name.equals(".") || name.equals("..")) {
            throw new IllegalArgumentException("invalid file name: " + fileName);
        }
        return name;
    }

    /** 44 字节标准 WAV 头：RIFF/fmt(16)/data，LITTLE_ENDIAN，16k/16bit mono。 */
    private static byte[] wavHeader(int pcmBytes) {
        int dataSize = pcmBytes;
        int byteRate = 16000 * 2; // 16k * 16bit mono
        ByteBuffer b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        b.putInt(36 + dataSize);
        b.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        b.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        b.putInt(16);
        b.putShort((short) 1);
        b.putShort((short) 1);
        b.putInt(16000);
        b.putInt(byteRate);
        b.putShort((short) 2);
        b.putShort((short) 16);
        b.put("data".getBytes(StandardCharsets.US_ASCII));
        b.putInt(dataSize);
        return b.array();
    }
}
