# 链路数据平台（Telemetry Platform）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建链路数据平台——追踪从话语开始到 TTS 播放结束的完整链路（VAD 后语音可回放、双端识别/仲裁/执行/TTS 缓存/播放结果），实时面板可视化。

**Architecture:** 端侧每轮生成 utteranceId 并插桩收集事件 → 轮次结束 HTTP POST 网关 `/api/telemetry/round`（PCM 另路上传）；服务端插桩事件（ASR/离线池/LLM/仲裁/TTS）落同一 SQLite；telemetry 模块（AutoVoiceServer 新子模块，同进程同端口）提供查询 API + SSE + React 面板。

**Tech Stack:** 服务端 Java 21 + Spring Boot 3.3.4 + `org.xerial:sqlite-jdbc`；端侧 Kotlin + OkHttp；面板 Vite + React + TypeScript（SSE 实时）。

Spec: `docs/superpowers/specs/2026-08-12-telemetry-platform-design.md`

## Global Constraints

- 业务 WS 协议向后兼容：`audio_start`/`tts_request` 仅**新增可选字段** `utteranceId`（白名单加字段，不进 REQUIRED）；hello 形态不变；旧客户端不带该字段时服务端回退现有 `u-N` 自增。
- 插桩只**追加事件记录**，不改变任何业务行为（日志、时序、语义、下行消息不变）。
- telemetry 不可用时全链路零影响：recorder 装配 `NoopTelemetryRecorder`，端侧 `enabled=false` 不发请求；插桩失败静默（Log.w，不抛）。
- 绝不打印 secret 值；服务端 `.env` secrets 部分不动只追加。
- SQLite 单线程串行写（TelemetryService 内部单线程 executor）；保留 7 天（`retention-days`，默认 7），清理联动删音频文件。
- 音频：16kHz/16bit/mono s16le PCM + 44 字节标准 WAV 头落盘 `{audioDir}/{utteranceId}.wav`。
- 语言惯例：服务端 Java（新模块沿 tts-server 的 build.gradle.kts 写法）、端侧 Kotlin；版本一律走 `AutoVoiceServer/gradle/libs.versions.toml`。
- 测试命令：服务端 `cd AutoVoiceServer && ./gradlew test`；端侧 `cd AutoVoice && ./gradlew :gateway-client:test :voice-core:test :app:testDebugUnitTest`。
- 每任务完成跑相关测试 + 提交（工作流：提交一版代码，提完告知）。

---

### Task 1: contracts——TelemetryRecorder 接口与事件模型

**Files:**
- Create: `AutoVoiceServer/contracts/src/main/java/com/autovoice/server/contracts/telemetry/TelemetryStages.java`
- Create: `AutoVoiceServer/contracts/src/main/java/com/autovoice/server/contracts/telemetry/TelemetryEvent.java`
- Create: `AutoVoiceServer/contracts/src/main/java/com/autovoice/server/contracts/telemetry/TelemetryRecorder.java`
- Create: `AutoVoiceServer/contracts/src/main/java/com/autovoice/server/contracts/telemetry/NoopTelemetryRecorder.java`

**Interfaces:**
- Produces（后续所有任务依赖）：
  - `TelemetryRecorder`：`void record(String utteranceId, TelemetryEvent event)` + default 快捷重载 `record(String utteranceId, String stage, String level, Map<String,Object> payload)`
  - `TelemetryEvent(String stage, long tsMs, String level, Map<String,Object> payload)`（record，Jackson 可序列化）
  - `TelemetryStages`：String 常量类，13 个 stage：`UTTERANCE_START="utterance_start"`、`VAD="vad"`、`LOCAL_ASR="local_asr"`、`CLOUD_ASR="cloud_asr"`、`LLM="llm"`、`OFFLINE_POOL="offline_pool"`、`CLOUD_ARBITER="cloud_arbiter"`、`DEVICE_ARBITER="device_arbiter"`、`EXECUTE="execute"`、`TTS_REQUEST="tts_request"`、`TTS_CACHE="tts_cache"`、`TTS_SYNTH="tts_synth"`、`TTS_PLAY="tts_play"`
  - `NoopTelemetryRecorder`：单例 `INSTANCE`，record 空实现（供未启用/测试时装配）

- [ ] **Step 1: 写 TelemetryStages**

```java
package com.autovoice.server.contracts.telemetry;

/** 链路追踪阶段枚举（TelemetryEvent.stage 取值）。 */
public final class TelemetryStages {
    public static final String UTTERANCE_START = "utterance_start";
    public static final String VAD = "vad";
    public static final String LOCAL_ASR = "local_asr";
    public static final String CLOUD_ASR = "cloud_asr";
    public static final String LLM = "llm";
    public static final String OFFLINE_POOL = "offline_pool";
    public static final String CLOUD_ARBITER = "cloud_arbiter";
    public static final String DEVICE_ARBITER = "device_arbiter";
    public static final String EXECUTE = "execute";
    public static final String TTS_REQUEST = "tts_request";
    public static final String TTS_CACHE = "tts_cache";
    public static final String TTS_SYNTH = "tts_synth";
    public static final String TTS_PLAY = "tts_play";

    private TelemetryStages() {
    }
}
```

- [ ] **Step 2: 写 TelemetryEvent + TelemetryRecorder + Noop**

```java
package com.autovoice.server.contracts.telemetry;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/** 链路单阶段事件：stage + 时刻 + 级别 + 自由 payload。 */
public record TelemetryEvent(
        @JsonProperty("stage") String stage,
        @JsonProperty("tsMs") long tsMs,
        @JsonProperty("level") String level,
        @JsonProperty("payload") Map<String, Object> payload) {

    @JsonCreator
    public TelemetryEvent {
    }
}
```

```java
package com.autovoice.server.contracts.telemetry;

import java.util.Map;

/** 链路事件记录器（插桩 SPI）：按 utteranceId 记录单阶段事件。实现可为存储/转发/Noop。 */
public interface TelemetryRecorder {

    void record(String utteranceId, TelemetryEvent event);

    default void record(String utteranceId, String stage, String level, Map<String, Object> payload) {
        record(utteranceId, new TelemetryEvent(stage, System.currentTimeMillis(), level, payload));
    }
}
```

```java
package com.autovoice.server.contracts.telemetry;

/** 空实现：telemetry 未启用/测试时装配，record 不做事（零影响）。 */
public final class NoopTelemetryRecorder implements TelemetryRecorder {

    public static final NoopTelemetryRecorder INSTANCE = new NoopTelemetryRecorder();

    private NoopTelemetryRecorder() {
    }

    @Override
    public void record(String utteranceId, TelemetryEvent event) {
    }
}
```

- [ ] **Step 3: 编译 + 全量测试保绿**

Run: `cd AutoVoiceServer && ./gradlew test`
Expected: BUILD SUCCESSFUL（纯声明代码，无行为变更）

- [ ] **Step 4: 提交**

```bash
git add AutoVoiceServer/contracts/src/main/java/com/autovoice/server/contracts/telemetry/
git commit -m "feat(contracts): TelemetryRecorder SPI + stage model (telemetry)"
```

---

### Task 2: 服务端 utteranceId 贯通

**Files:**
- Modify: `AutoVoiceServer/gateway/src/main/java/com/autovoice/server/gateway/GatewayCodec.java:42`（audio_start 白名单）、`:50`（tts_request 白名单）
- Modify: `AutoVoiceServer/gateway/src/main/java/com/autovoice/server/gateway/VoiceGatewayHandler.java:249-258`（onAudioStart）
- Modify: `AutoVoiceServer/arbitration/src/main/java/com/autovoice/server/arbitration/RaceArbiter.java:68-122`（decide 签名 + entry）
- Modify: `AutoVoiceServer/gateway/src/main/java/com/autovoice/server/gateway/SegmentPipeline.java:96`（decide 调用处）
- Test: `AutoVoiceServer/gateway/src/test/java/com/autovoice/server/gateway/GatewayCodecTest.java`、`AutoVoiceServer/app/src/test/java/com/autovoice/server/app/VoiceGatewayHandlerTest.java`、`AutoVoiceServer/arbitration/src/test/java/com/autovoice/server/arbitration/RaceArbiterTest.java`

**Interfaces:**
- Consumes: Task 1 的 `TelemetryStages`（本任务只用常量语义，不依赖 recorder）
- Produces: `RaceArbiter.decide(CompletableFuture<OfflineCommandHit> offline, CompletableFuture<Reply> llm, SessionContext ctx, String utteranceId)`——utteranceId 由调用方传入，决策事件填真实值；`onAudioStart` 优先采纳端侧 utteranceId

- [ ] **Step 1: 写失败测试（GatewayCodecTest：audio_start/tts_request 解码 utteranceId）**

在 `GatewayCodecTest` 加：

```java
@Test
void audioStartCarriesOptionalUtteranceId() {
    String raw = "{\"type\":\"audio_start\",\"payload\":{\"sessionId\":\"s1\",\"sampleRate\":16000,"
            + "\"channels\":1,\"encoding\":\"pcm_s16le\",\"utteranceId\":\"utt-1\"}}";
    Map<String, Object> payload = GatewayCodec.decode(raw);
    assertEquals("utt-1", payload.get("utteranceId"));
}
```

Run: `./gradlew :gateway:test --tests '*GatewayCodecTest'`
Expected: FAIL（解码被白名单拦截或字段不存在）

- [ ] **Step 2: 白名单加字段（实现）**

`GatewayCodec.java:42` 改为：

```java
Map.entry("audio_start", Set.of("sessionId", "sampleRate", "channels", "encoding", "segmentId", "utteranceId")),
```

`:50` 改为：

```java
Map.entry("tts_request", Set.of("text", "segmentId", "utteranceId")),
```

- [ ] **Step 3: 写失败测试（RaceArbiterTest：决策事件 utteranceId 用传入值而非 sessionId）**

`RaceArbiterTest` 现有用例的 `decide(...)` 调用全部加一个 `"utt-42"` 实参；并加断言（现有用例中 sink 捕获的 DecisionEntry，若断言了 utteranceId 则改为 `"utt-42"`；若没有则加一条）：

```java
@Test
void decisionEntryUsesPassedUtteranceId() {
    RaceArbiter arbiter = new RaceArbiter(2000, 500, scheduler, sink);
    arbiter.decide(CompletableFuture.completedFuture(null),
            CompletableFuture.completedFuture(Reply.ofText("hi")), ctx, "utt-42").join();
    assertEquals("utt-42", capturedEntries.get(0).utteranceId());
}
```

（`capturedEntries`/`scheduler`/`ctx` 复用测试现有 fixtures；若现有测试类结构不同，按该类现有方式建 fixtures。）

Run: `./gradlew :arbitration:test`
Expected: FAIL（编译失败：decide 无 4 参重载）

- [ ] **Step 4: RaceArbiter 改签名 + entry 填真实值**

`RaceArbiter.java`：

```java
public CompletableFuture<ArbiterDecision> decide(CompletableFuture<OfflineCommandHit> offline,
                                                 CompletableFuture<Reply> llm,
                                                 SessionContext ctx, String utteranceId) {
    // 方法体内 3 处 sink.log(entry(ctx, ROUTE_..., "...")) 改为 sink.log(entry(ctx, utteranceId, ROUTE_..., "..."))
    ...
}

private static DecisionEntry entry(SessionContext ctx, String utteranceId, String route, String reason) {
    return new DecisionEntry(ARBITER_CLOUD, route, reason, utteranceId, System.currentTimeMillis());
}
```

旧单路入口同步改：

```java
public CompletableFuture<Reply> decide(String text, LlmProvider llm, SessionContext ctx, String utteranceId) {
    CompletableFuture<OfflineCommandHit> offline = CompletableFuture.completedFuture(null);
    return decide(offline, llm.chat(text, ctx), ctx, utteranceId).thenApply(ArbiterDecision::reply);
}
```

- [ ] **Step 5: SegmentPipeline 调用处改 4 参**

`SegmentPipeline.java:96`：

```java
ArbiterDecision decision = arbiter
        .decide(offlineF.thenApply(o -> o.orElse(null)), llmF, ctx, utteranceId)
        .join();
```

（方法体其他 entry 相关调用如 `waitOfflineFallback` 里的 `sink.log(new DecisionEntry(ARBITER_CLOUD, ROUTE_NLU_TRADITIONAL, REASON_OFFLINE_WON, ...` 也把第 4 参从 `ctx.sessionId()` 改为 `utteranceId`——全文搜 `ctx.sessionId()` 在 DecisionEntry 构造处的替换。）

- [ ] **Step 6: handler 优先采纳端侧 utteranceId（实现）**

`VoiceGatewayHandler.onAudioStart`（:256）改为：

```java
String clientUtteranceId = payload.get("utteranceId") != null
        ? String.valueOf(payload.get("utteranceId")) : null;
st.utteranceId = clientUtteranceId != null && !clientUtteranceId.isBlank()
        ? clientUtteranceId
        : "u-" + ++st.segmentSeq; // 兼容旧客户端：无 utteranceId 时回退自增
```

- [ ] **Step 7: handler 测试加"端侧 utteranceId 贯通"**

`VoiceGatewayHandlerTest` 加用例：带 `utteranceId=utt-custom-1` 的 audio_start → 流程后下行的 decision 事件 payload 中 `utteranceId` 为 `utt-custom-1`（按该测试类现有断言 decision 下行的方式写；若无现有断言方式，断言该轮未收到 error 且 ready 正常即可，服务端插桩断言在 Task 9 E2E 覆盖）。

- [ ] **Step 8: 全量测试保绿 + 提交**

Run: `cd AutoVoiceServer && ./gradlew test`
Expected: BUILD SUCCESSFUL

```bash
git add AutoVoiceServer/
git commit -m "feat(gateway): client utteranceId adoption + arbiter truthful id (telemetry)"
```

---

### Task 3: telemetry 模块——存储 / Controller / SSE / 音频 / 清理

**Files:**
- Modify: `AutoVoiceServer/settings.gradle.kts`（include `":telemetry"`）
- Modify: `AutoVoiceServer/gradle/libs.versions.toml`（加 sqlite-jdbc）
- Create: `AutoVoiceServer/telemetry/build.gradle.kts`
- Create: `AutoVoiceServer/telemetry/src/main/java/com/autovoice/server/telemetry/TelemetryProperties.java`
- Create: `AutoVoiceServer/telemetry/src/main/java/com/autovoice/server/telemetry/SqliteTelemetryStore.java`
- Create: `AutoVoiceServer/telemetry/src/main/java/com/autovoice/server/telemetry/TelemetryService.java`
- Create: `AutoVoiceServer/telemetry/src/main/java/com/autovoice/server/telemetry/TelemetryController.java`
- Create: `AutoVoiceServer/telemetry/src/main/java/com/autovoice/server/telemetry/TelemetryConfig.java`
- Modify: `AutoVoiceServer/app/build.gradle.kts`（`implementation(project(":telemetry"))`）
- Test: `AutoVoiceServer/telemetry/src/test/java/com/autovoice/server/telemetry/TelemetryServiceTest.java`、`TelemetryControllerTest.java`

**Interfaces:**
- Consumes: Task 1（TelemetryRecorder/TelemetryEvent/TelemetryStages/Noop）
- Produces:
  - `TelemetryProperties`：record `(boolean enabled, String dbPath, String audioDir, int retentionDays)`，prefix `autovoice.telemetry`；compact constructor：`dbPath` 空 → `./telemetry.db`、`audioDir` 空 → `./telemetry-audio`、`retentionDays < 1` → 7
  - `TelemetryService implements TelemetryRecorder`：`record(String utteranceId, TelemetryEvent)`（幂等：event 首现建 round 骨架）、`recordDeviceRound(DeviceRoundPayload)`（端侧包，含 round 聚合字段）、`saveAudio(String utteranceId, byte[] pcm)`（写 WAV）、`List<RoundSummary> queryRounds(String device, long fromMs, long toMs)`、`RoundDetail queryRound(String utteranceId)`、`cleanupOld()`（删超期 rounds+events+音频）、`void addListener(Consumer<RoundSummary>)`（SSE 推送回调）
  - `TelemetryController` 端点：`POST /api/telemetry/round`、`POST /api/telemetry/audio`（multipart `utteranceId`+`file`）、`GET /api/telemetry/rounds`、`GET /api/telemetry/rounds/{utteranceId}`、`GET /api/telemetry/stream`（SSE）、`GET /api/telemetry/audio/{file}`

- [ ] **Step 1: Gradle 接线**

`gradle/libs.versions.toml` `[libraries]` 加：

```toml
sqlite-jdbc = { module = "org.xerial:sqlite-jdbc", version = "3.45.3.0" }
```

`settings.gradle.kts` include 行加 `":telemetry"`。

`telemetry/build.gradle.kts`（仿 tts-server）：

```kotlin
plugins {
    `java`
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

dependencies {
    implementation(project(":contracts"))
    implementation(libs.spring.boot.starter.web)
    implementation(libs.okhttp)
    implementation(libs.sqlite.jdbc)
    testImplementation(libs.spring.boot.starter.test)
}

tasks.test {
    useJUnitPlatform()
}
```

`app/build.gradle.kts` dependencies 加 `implementation(project(":telemetry"))`（app 是 Spring Boot 应用，telemetry 的 @Configuration 被 `com.autovoice.server` 包扫描覆盖）。

Run: `./gradlew :telemetry:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: 写失败测试（TelemetryServiceTest：入库/查询/清理/WAV）**

```java
package com.autovoice.server.telemetry;

import com.autovoice.server.contracts.telemetry.TelemetryEvent;
import com.autovoice.server.contracts.telemetry.TelemetryRecorder;
import com.autovoice.server.contracts.telemetry.TelemetryStages;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TelemetryServiceTest {

    @TempDir
    Path tmp;

    private TelemetryService newService() {
        return new TelemetryService(new TelemetryProperties(true,
                tmp.resolve("t.db").toString(), tmp.resolve("audio").toString(), 7));
    }

    @Test
    void recordsEventsAndQueriesRound() {
        TelemetryService svc = newService();
        svc.record("utt-1", TelemetryStages.UTTERANCE_START, "info",
                Map.of("source", "button"));
        svc.record("utt-1", TelemetryStages.CLOUD_ASR, "info", Map.of("text", "空调调到二十四度"));
        svc.recordDeviceRound(new TelemetryService.DeviceRoundPayload("utt-1", "s1", "demo-1",
                "button", 1000L, 5000L, List.of()));
        var round = svc.queryRound("utt-1");
        assertNotNull(round);
        assertEquals("demo-1", round.deviceId());
        assertEquals(2, round.events().size());
        assertEquals("cloud", round.cloudDecision().route()); // 见下方 Step 3 定义
    }

    @Test
    void savesWavFile() throws Exception {
        TelemetryService svc = newService();
        byte[] pcm = new byte[3200];
        svc.saveAudio("utt-2", pcm);
        byte[] wav = java.nio.file.Files.readAllBytes(tmp.resolve("audio/utt-2.wav"));
        assertEquals(44 + 3200, wav.length);
        assertEquals('R', wav[0]);
        assertEquals('W', wav[1]);
        assertEquals('A', wav[2]);
        assertEquals('V', wav[3]);
    }

    @Test
    void cleanupDeletesOldRoundsAndAudio() throws Exception {
        TelemetryService svc = newService();
        svc.record("utt-old", TelemetryStages.UTTERANCE_START, "info", Map.of());
        svc.saveAudio("utt-old", new byte[1600]);
        // 手工插一条 8 天前的 round（retention 7）——通过私有 JDBC 不可行，
        // 改为：直接调 cleanupOld() 用 fake clock——见 Step 3 实现说明
        svc.cleanupOld();
        assertNull(svc.queryRound("utt-old"));
        assertFalse(java.nio.file.Files.exists(tmp.resolve("audio/utt-old.wav")));
    }
}
```

Run: `./gradlew :telemetry:test --tests '*TelemetryServiceTest'`
Expected: FAIL（编译失败：TelemetryService 不存在）

- [ ] **Step 3: 实现 TelemetryProperties + SqliteTelemetryStore + TelemetryService**

`TelemetryProperties`（@ConfigurationProperties，仿 AppConfig 的 record 风格）：

```java
package com.autovoice.server.telemetry;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "autovoice.telemetry")
public record TelemetryProperties(boolean enabled, String dbPath, String audioDir, int retentionDays) {

    public TelemetryProperties {
        if (dbPath == null || dbPath.isBlank()) dbPath = "./telemetry.db";
        if (audioDir == null || audioDir.isBlank()) audioDir = "./telemetry-audio";
        if (retentionDays < 1) retentionDays = 7;
    }
}
```

`SqliteTelemetryStore`：JDBC（`org.sqlite.JDBC`）封装。要点：
- `connect()` 每操作短连接（sqlite-jdbc `DriverManager.getConnection("jdbc:sqlite:" + dbPath)`），打开时 `PRAGMA busy_timeout=5000`；单写线程由 service 保证，读可直连。
- 建表（`CREATE TABLE IF NOT EXISTS`）：

```sql
CREATE TABLE IF NOT EXISTS rounds (
  utterance_id TEXT PRIMARY KEY,
  session_id TEXT, device_id TEXT, source TEXT,
  start_ms INTEGER, end_ms INTEGER,
  local_decision TEXT, cloud_decision TEXT, final_decision TEXT,
  asr_local TEXT, asr_cloud TEXT, llm_reply TEXT,
  execute_result TEXT, tts_text TEXT, tts_cache_hit INTEGER,
  playback_result TEXT, audio_path TEXT,
  created_ms INTEGER
);
CREATE TABLE IF NOT EXISTS events (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  utterance_id TEXT NOT NULL,
  stage TEXT NOT NULL, ts_ms INTEGER NOT NULL,
  level TEXT NOT NULL, payload_json TEXT
);
CREATE INDEX IF NOT EXISTS idx_events_utt ON events(utterance_id);
CREATE INDEX IF NOT EXISTS idx_rounds_created ON rounds(created_ms);
```

- 方法：`upsertRound(String utteranceId, Map<String,Object> fields)`（INSERT ... ON CONFLICT(utterance_id) DO UPDATE）、`insertEvent(String utteranceId, TelemetryEvent)`、`roundExists(String)`、`queryRound(String) → RoundDetail`、`queryRounds(String device, long fromMs, long toMs) → List<RoundSummary>`（JOIN 聚合：COUNT(events)、每 stage 最晚一条的 ts/payload 摘要）、`deleteOlderThan(long cutoffMs) → Set<String> deletedUtteranceIds`（先查 rounds.created_ms < cutoff 的 utterance_id，删 rounds+events 行，返回 ID 供音频清理）、`closeAll()`（单例 Connection 或全部短连接，用短连接则无 closeAll）。

`TelemetryService implements TelemetryRecorder`：
- 构造器：`TelemetryService(TelemetryProperties props)`；内部：`ExecutorService writer = Executors.newSingleThreadExecutor(...daemon)`；`CopyOnWriteArrayList<Consumer<RoundSummary>> listeners`；`Set<String> knownRounds = ConcurrentHashMap.newKeySet()`（record 首见建骨架：upsert `created_ms=now` + `start_ms=ts`）。
- `record(utt, event)`：`writer.execute(() -> { if (knownRounds.add(utt)) upsertRound(utt, Map.of("created_ms", now, "start_ms", event.tsMs())); insertEvent(utt, event); })`。
- `recordDeviceRound(DeviceRoundPayload p)`：`writer.execute(() -> upsertRound(p.utteranceId(), Map.of("session_id",...,"device_id",...,"source",...,"start_ms",...,"end_ms",...,"local_decision",...,"cloud_decision",...,"final_decision",...,"asr_local",...,"asr_cloud",...,"llm_reply",...,"execute_result",...,"tts_text",...,"tts_cache_hit",...,"playback_result",...))`；随后对 `p.events()` 逐条 insertEvent；`knownRounds.add(...)`。
- `saveAudio(utt, pcm)`：先建 audioDir（`Files.createDirectories`），写 44 字节 WAV 头 + pcm：

```java
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
```

  落盘 `{audioDir}/{utt}.wav`（路径经 `Path.of(fileName).getFileName()` 防穿越），成功后 upsertRound audio_path 字段。
- `queryRounds`/`queryRound` 走 store 同步查。
- `cleanupOld()`：`writer.execute(() -> { cutoff = now - retentionDays*86400000L; store.deleteOlderThan(cutoff).forEach(id -> delete audio file); })`。**保留 7 天测试**：构造器加包可见 `@VisibleForTesting void setNowForTest(long)` 或重载构造 `TelemetryService(props, long nowMs)`——计划采用：第二个构造器 `TelemetryService(TelemetryProperties props, java.util.function.LongSupplier clock)`，默认构造委托 `System::currentTimeMillis`。测试 `cleanupDeletesOldRoundsAndAudio` 用 `clock = () -> START + 8*86400000L`（START 为插入时的真实 now）。
- `addListener(Consumer<RoundSummary>)`：SSE 注册。
- 事件 → RoundSummary 通知：`recordDeviceRound` 完成后 `listeners.forEach(l -> l.accept(summary))`（summary 从 upsert 后的行构造，或直接构造 `new RoundSummary(p.utteranceId(), p.deviceId(), p.source(), p.startMs(), p.endMs(), localDecision, cloudDecision, finalDecision, ttsCacheHit, playbackResult, audioPath)`）。SSE 只推摘要字段。
- 数据类（telemetry 模块内 public record）：`RoundSummary(String utteranceId, String deviceId, String source, long startMs, long endMs, String localDecision, String cloudDecision, String finalDecision, Boolean ttsCacheHit, String playbackResult, String audioPath)`、`RoundDetail extends 摘要 + List<TelemetryEvent> events`（record 组合：`RoundDetail(RoundSummary summary, List<TelemetryEvent> events)`）、`DeviceRoundPayload(String utteranceId, String sessionId, String deviceId, String source, Long startMs, Long endMs, List<TelemetryEvent> events)`（Jackson 反序列化——端侧 POST body；聚合字段 local_decision 等由 service 从 events 推导：`lastEventOf(stage)` 的 payload 摘要，如 `device_arbiter` 事件的 route/reason → local_decision/final_decision 等）。

- [ ] **Step 4: 跑 TelemetryServiceTest**

Run: `./gradlew :telemetry:test --tests '*TelemetryServiceTest'`
Expected: PASS

- [ ] **Step 5: 写失败测试（TelemetryControllerTest：round/audio/rounds/stream/audio 文件）**

```java
package com.autovoice.server.telemetry;

import com.autovoice.server.contracts.telemetry.TelemetryEvent;
import com.autovoice.server.contracts.telemetry.TelemetryStages;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(TelemetryController.class)
class TelemetryControllerTest {

    @Autowired
    MockMvc mvc;

    @MockBean
    TelemetryService svc;

    @Test
    void acceptsRoundPost() throws Exception {
        String body = "{\"utteranceId\":\"utt-1\",\"sessionId\":\"s1\",\"deviceId\":\"demo-1\","
                + "\"source\":\"button\",\"startMs\":1000,\"endMs\":5000,"
                + "\"events\":[{\"stage\":\"local_asr\",\"tsMs\":2000,\"level\":\"info\",\"payload\":{\"text\":\"打开空调\"}}]}";
        mvc.perform(post("/api/telemetry/round")
                        .contentType("application/json").content(body))
                .andExpect(status().isOk());
        verify(svc).recordDeviceRound(any());
    }

    @Test
    void acceptsAudioUpload() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "utt-1.pcm", "application/octet-stream",
                new byte[1600]);
        mvc.perform(multipart("/api/telemetry/audio")
                        .file(file).param("utteranceId", "utt-1"))
                .andExpect(status().isOk());
        verify(svc).saveAudio(eq("utt-1"), any(byte[].class));
    }

    @Test
    void queriesRoundsAndRound() throws Exception {
        when(svc.queryRounds("demo-1", 0, 1)).thenReturn(List.of());
        mvc.perform(get("/api/telemetry/rounds").param("device", "demo-1"))
                .andExpect(status().isOk());
        when(svc.queryRound("utt-1")).thenReturn(null);
        mvc.perform(get("/api/telemetry/rounds/utt-1"))
                .andExpect(status().isOk());
    }

    @Test
    void streamsSseOnNewRound() throws Exception {
        mvc.perform(get("/api/telemetry/stream")).andExpect(status().isOk());
        // SSE 推送内容由 service listener 回调驱动——冒烟只验证端点可连与注册 listener
        verify(svc).addListener(any());
    }
}
```

Run: `./gradlew :telemetry:test --tests '*TelemetryControllerTest'`
Expected: FAIL（Controller 不存在）

- [ ] **Step 6: 实现 TelemetryController + TelemetryConfig**

`TelemetryController`（@RestController + @RequestMapping("/api/telemetry")，注入 TelemetryService）：
- `@PostMapping("/round")`：body `DeviceRoundPayload` → `service.recordDeviceRound(p)`；返回 200。JSON 解析错误 → 400（`@ExceptionHandler(MethodArgumentNotValidException/HttpMessageNotReadableException)` → `ResponseStatusException(BAD_REQUEST)`，仿 TtsController 的显式异常风格）。
- `@PostMapping(value = "/audio", consumes = MULTIPART_FORM_DATA_VALUE)`：`@RequestParam String utteranceId` + `@RequestParam("file") MultipartFile file` → `service.saveAudio(utteranceId, file.getBytes())`；200。缺参 → 400。
- `@GetMapping("/rounds")`：`@RequestParam(required=false) String device`、`@RequestParam(required=false) Long from`、`to` → `service.queryRounds(device, from==null?0:from, to==null?Long.MAX_VALUE:to)`；返回 `List<RoundSummary>`。
- `@GetMapping("/rounds/{utteranceId}")`：`service.queryRound(id)`；null → `ResponseStatusException(NOT_FOUND)`。
- `@GetMapping("/stream")`（produces TEXT_EVENT_STREAM_VALUE）：

```java
@GetMapping("/stream")
public SseEmitter stream() {
    SseEmitter emitter = new SseEmitter(0L); // 不超时
    service.addListener(summary -> {
        try {
            emitter.send(SseEmitter.event().name("round").data(summary));
        } catch (IOException ignored) {
            // 面板断开：忽略，下一轮再推
        }
    });
    emitter.onCompletion(() -> {});
    return emitter;
}
```

- `@GetMapping("/audio/{file}")`：`service.readAudio(file)` → `ResponseEntity<byte[]>`（Content-Type `audio/wav`）或 404（不存在）。`readAudio` 需加进 TelemetryService：`Optional<byte[]> readAudio(String fileName)`（路径防穿越 + Files.exists/readAllBytes）。
- 注：`/stream` 的 `{utteranceId}` 路径与 `/audio/{file}` 顺序无冲突（不同前缀）。

`TelemetryConfig`：

```java
@Configuration
@EnableConfigurationProperties(TelemetryProperties.class)
public class TelemetryConfig {

    @Bean
    public TelemetryRecorder telemetryRecorder(TelemetryProperties props) {
        if (!props.enabled()) return NoopTelemetryRecorder.INSTANCE;
        return new TelemetryService(props);
    }

    @Bean
    public TelemetryService telemetryService(TelemetryProperties props) {
        return props.enabled() ? new TelemetryService(props) : null;
    }
}
```

（`telemetryService` 返回 null 时 Controller 依赖注入会失败——改为：enabled 时提供 bean、禁用时 Controller 不装配。简化：`TelemetryController` 只在 enabled 时注册：`@Bean TelemetryController telemetryController(TelemetryService svc)` 由 Config 条件装配——直接用 `@ConditionalOnProperty(prefix="autovoice.telemetry", name="enabled", havingValue="true", matchIfMissing=true)` 注解在 `TelemetryController`/`TelemetryService` 类上，禁用时整个模块不装配、recorder=Noop，最干净。）

- [ ] **Step 7: 跑 telemetry 全量测试**

Run: `./gradlew :telemetry:test`
Expected: BUILD SUCCESSFUL（两测试类全过）

- [ ] **Step 8: 全量保绿 + 提交**

Run: `cd AutoVoiceServer && ./gradlew test`
Expected: BUILD SUCCESSFUL

```bash
git add AutoVoiceServer/
git commit -m "feat(telemetry): sqlite store, round/audio/query API, SSE stream (telemetry)"
```

---

### Task 4: 服务端插桩（ASR / 离线池 / LLM / 云端仲裁）

**Files:**
- Modify: `AutoVoiceServer/gateway/src/main/java/com/autovoice/server/gateway/SegmentPipeline.java`（构造器 + transcribe + handleSegment 尾部）
- Modify: `AutoVoiceServer/gateway/src/main/java/com/autovoice/server/gateway/VoiceGatewayHandler.java:445-446`（构造 pipeline 传 recorder）
- Modify: `AutoVoiceServer/offline-command/src/main/java/com/autovoice/server/offlinecommand/OfflineEnginePool.java`（构造器 + recognize）
- Modify: `AutoVoiceServer/llm/src/main/java/com/autovoice/server/llm/DeepSeekLlmProvider.java`（构造器 + chat）
- Modify: `AutoVoiceServer/app/src/main/java/com/autovoice/server/app/AppConfig.java`（`telemetryRecorder` bean 注入 handler）
- Test: `AutoVoiceServer/gateway/src/test/java/com/autovoice/server/gateway/SegmentPipelineTest.java`、`AutoVoiceServer/offline-command/src/test/java/.../OfflineEnginePoolTest.java`、`AutoVoiceServer/llm/src/test/java/.../DeepSeekLlmProviderTest.java`（若有；无则新建）

**Interfaces:**
- Consumes: Task 1 recorder、Task 2 utteranceId
- Produces: 插桩后各构造器新签名：
  - `SegmentPipeline(AsrProvider, RaceArbiter, LlmProvider, OfflineCommandService, long asrFailWaitMs, DecisionSink, TelemetryRecorder)`
  - `OfflineEnginePool(List<OfflineCommandProvider>, TelemetryRecorder)`
  - `DeepSeekLlmProvider(OkHttpClient, String apiKey, String endpoint, TelemetryRecorder)`

- [ ] **Step 1: 写失败测试（SegmentPipelineTest：cloud_asr 与 cloud_arbiter 事件）**

`SegmentPipelineTest` 按现有 fixtures 扩展（asr/llm/offline mock + sink mock + recorder mock）：

```java
@Test
void recordsCloudAsrAndArbiterEvents() {
    when(asr.transcribe(any(), any())).thenReturn("空调调到二十四度");
    when(offline.recognize(any(), any()))
            .thenReturn(CompletableFuture.completedFuture(Optional.empty()));
    when(llm.chat(any(), any())).thenReturn(CompletableFuture.completedFuture(Reply.ofText("好的")));
    pipeline = new SegmentPipeline(asr, arbiter, llm, offline, 2000, sink, recorder);
    pipeline.handleSegment(PCM, ctx, "utt-9");
    verify(recorder).record(eq("utt-9"), eq(TelemetryStages.CLOUD_ASR), eq("info"),
            argThat(p -> "空调调到二十四度".equals(p.get("text"))));
    verify(recorder).record(eq("utt-9"), eq(TelemetryStages.CLOUD_ARBITER), eq("info"),
            argThat(p -> "llm_reply".equals(p.get("reason"))));
}
```

（pipeline 现有测试若用 `new SegmentPipeline(...)` 6 参构造，先全部加第 7 参 `NoopTelemetryRecorder.INSTANCE` 保证编译。）

Run: `./gradlew :gateway:test --tests '*SegmentPipelineTest'`
Expected: FAIL（编译失败：7 参构造不存在 / recorder 无交互）

- [ ] **Step 2: SegmentPipeline 插桩实现**

- 构造器加 `TelemetryRecorder recorder` 字段（最后一位参数）。
- `transcribe(...)` 三处（ASR start/ok/blank/failed 日志处）追加事件：

```java
recorder.record(utteranceId, TelemetryStages.CLOUD_ASR, "info",
        Map.of("text", text, "durationMs", Math.max(1, System.currentTimeMillis() - start)));
```

（start 在 transcribe 入口记 `long start = System.currentTimeMillis()`；blank 与异常路径 level=warn，payload 带 `error` 描述。）

- `handleSegment` 尾部（toResult 之后/之前，decision 已知处）追加：

```java
recorder.record(utteranceId, TelemetryStages.CLOUD_ARBITER, "info",
        Map.of("route", decision.route(), "reason", decision.reason()));
```

- `waitOfflineFallback` 命中路径（offline 胜出）追加同类 cloud_arbiter 事件（route=nlu-traditional、reason=offline_won）。
- imports 加 `com.autovoice.server.contracts.telemetry.*` + `java.util.Map`。

- [ ] **Step 3: OfflineEnginePool + DeepSeekLlmProvider 插桩**

`OfflineEnginePool`：构造器加 `TelemetryRecorder recorder`；recognize 三处日志（busy skip / routing failed / worker failed）各追加：

```java
recorder.record(ctx.sessionId(), TelemetryStages.OFFLINE_POOL, "warn",
        Map.of("reason", "busy", "poolSize", workers.size()));
```

（busy 路径；routing/worker failed 的 reason 相应改。注意此阶段 utteranceId 不在池接口内——池事件用 sessionId 关联，Task 9 E2E 由 round 汇聚；后续如需精确可在 OfflineCommandProvider 接口加 utteranceId（二期），本计划不做接口破坏。）

`DeepSeekLlmProvider`：构造器加 `TelemetryRecorder recorder`；`callAndParse` 入口记 start、完成/异常两处：

```java
// 完成
recorder.record(ctx.sessionId(), TelemetryStages.LLM, "info",
        Map.of("text", text, "reply", replyKind + ":" + brief, "durationMs", elapsed));
// 失败
recorder.record(ctx.sessionId(), TelemetryStages.LLM, "error",
        Map.of("text", text, "error", String.valueOf(e.getMessage()), "durationMs", elapsed));
```

（brief = reply 是 action 时 intent 摘要，text 时截断 80 字符；ctx.sessionId() 关联同池事件。）

- [ ] **Step 4: AppConfig + handler 装配 recorder**

`AppConfig`：
- `voiceGatewayHandler(...)` 参数加 `TelemetryRecorder recorder`（Spring 自动注入 Task 3 的 bean；未启用时是 Noop）
- 传给 handler 构造器（handler 构造器加第 12 参 `TelemetryRecorder recorder`）
- `VoiceGatewayHandler` 内部 :445-446 改为：

```java
final SegmentPipeline pipeline = new SegmentPipeline(asr, arbiter, llm, offline, asrFailWaitMs, sink, recorder);
```

- [ ] **Step 5: 跑各模块测试 + 全量保绿**

Run: `cd AutoVoiceServer && ./gradlew test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 提交**

```bash
git add AutoVoiceServer/
git commit -m "feat(gateway): server-side stage instrumentation (cloud_asr/offline_pool/llm/cloud_arbiter)"
```

---

### Task 5: tts-server 插桩 + 事件转发

**Files:**
- Modify: `AutoVoiceServer/tts-gateway/src/main/java/com/autovoice/server/ttsgateway/RemoteTtsProvider.java`（body 加 utteranceId）
- Modify: `AutoVoiceServer/tts-gateway/src/main/java/com/autovoice/server/ttsgateway/CachedTtsProvider.java`（构造器 + synthesize 插桩）
- Modify: `AutoVoiceServer/tts-gateway/src/main/java/com/autovoice/server/ttsgateway/AliyunTtsProvider.java`（构造器 + synthesize 插桩）
- Modify: `AutoVoiceServer/tts-server/src/main/java/com/autovoice/server/ttsserver/TtsController.java`（TtsRequest 加 utteranceId + tts_request 事件）
- Create: `AutoVoiceServer/tts-server/src/main/java/com/autovoice/server/ttsserver/TtsTelemetryForwarder.java`
- Modify: `AutoVoiceServer/tts-server/src/main/java/com/autovoice/server/ttsserver/TtsAppConfig.java`（telemetry.url 配置 + recorder bean）
- Modify: `AutoVoiceServer/tts-server/src/main/resources/application.yml`（`autovoice.telemetry.url: ${AUTOVOICE_TELEMETRY_URL:}`）
- Test: `AutoVoiceServer/tts-gateway/src/test/java/com/autovoice/server/ttsgateway/RemoteTtsProviderTest.java`、`AutoVoiceServer/tts-server/src/test/java/com/autovoice/server/ttsserver/TtsControllerTest.java`

**Interfaces:**
- Consumes: Task 1 recorder（tts-server 依赖 :contracts，可直接用）
- Produces:
  - `TtsTelemetryForwarder implements TelemetryRecorder`：`TtsTelemetryForwarder(OkHttpClient, String gatewayTelemetryUrl)`；`record` 把事件异步 POST 到 `{gatewayTelemetryUrl}/events`（body `{utteranceId, events:[{stage,tsMs,level,payload}]}`，OkHttp enqueue，失败 Log.w 静默）
  - 网关侧 `TelemetryController` 加 `POST /api/telemetry/events`：body `{utteranceId, events[]}` → 逐条 `service.record(...)`（202 或 200）

- [ ] **Step 1: 写失败测试（RemoteTtsProviderTest：utteranceId 透传）**

`RemoteTtsProviderTest` 现有用例改造：`when(svc.synthesize("你好", ctx))` 的请求 body 断言加 `"utteranceId":"utt-5"`：

```java
@Test
void forwardsUtteranceId() throws Exception {
    MockResponse ok = new MockResponse().setResponseCode(200)
            .setBody("{\"mime\":\"audio/wav\",\"dataBase64\":\"AQID\"}");
    server.enqueue(ok);
    provider.synthesize("你好", ctx, "utt-5");
    RecordedRequest req = server.takeRequest();
    JsonNode body = new ObjectMapper().readTree(req.getBody().readUtf8());
    assertEquals("utt-5", body.get("utteranceId").asText());
}
```

（TtsProvider 接口加 3 参重载？——`synthesize(String text, SessionContext ctx)` 现有签名。为不破坏接口：`TtsProvider` 接口加 default 方法 `default String synthesize(String text, SessionContext ctx) { return synthesize(text, ctx, ""); }`？返回类型是什么——TtsProvider.synthesize 返回？探索时没确认。查 contracts/TtsProvider.java：`public interface TtsProvider`。CachedTtsProvider.synthesize(text, ctx) 返回 byte[]？从 CachedTtsProvider 日志"TTS ok: ... -> {} bytes"看返回 byte[]。TtsController 用 provider 拿 byte[] 再 base64。计划按 `byte[] synthesize(String text, SessionContext ctx)` 现签名处理，3 参版 `synthesize(String text, SessionContext ctx, String utteranceId)` 走 default 转发（ctx 不变时 utteranceId=""）。RemoteTtsProvider 重写 3 参版。实现前先 grep 确认接口签名。）

Run: `./gradlew :tts-gateway:test --tests '*RemoteTtsProviderTest'`
Expected: FAIL

- [ ] **Step 2: 实现 utteranceId 贯通 tts 链路**

- `TtsProvider`（contracts）加 default：

```java
default byte[] synthesize(String text, SessionContext ctx, String utteranceId) {
    return synthesize(text, ctx);
}
```

- `RemoteTtsProvider.synthesize` 重写 3 参版，body 加 `"utteranceId"`（null 时省略）。
- `VoiceGatewayHandler.onTtsRequest`（:369 起）：从 payload 读 utteranceId（`payload.get("utteranceId")`，可空），调 `tts.synthesize(text, ctx, utteranceId)`。payload 的 utteranceId 已在 Task 2 加白名单。
- `TtsController.TtsRequest` record 加 `String utteranceId`（`@JsonProperty` 可空）：

```java
public record TtsRequest(String text, String sessionId, String utteranceId) {
}
```

- [ ] **Step 3: 写失败测试（TtsControllerTest：tts_request 事件转发）**

`TtsControllerTest`（@WebMvcTest + @MockBean TtsProvider）加：

```java
@MockBean
TelemetryRecorder recorder;

@Test
void recordsTtsRequestEvent() throws Exception {
    when(provider.synthesize(anyString(), any(), anyString())).thenReturn(new byte[]{1, 2, 3});
    mvc.perform(post("/tts").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"text\":\"好的\",\"sessionId\":\"s1\",\"utteranceId\":\"utt-5\"}"))
            .andExpect(status().isOk());
    verify(recorder).record(eq("utt-5"), eq(TelemetryStages.TTS_REQUEST), eq("info"), any());
}
```

（TtsController 构造器需注入 recorder 才编译——先写测试会编译失败，符合 TDD。Controller 内 tts_request 事件：`recorder.record(utteranceId, TTS_REQUEST, "info", Map.of("text", text))`。）

- [ ] **Step 4: 实现 tts-server 插桩 + 转发**

- `TtsController`：构造器加 `TelemetryRecorder recorder`；`synthesize` 里：text 校验后 `record(TTS_REQUEST)`；成功路径把 `(text, utteranceId)` 传 provider；失败路径 `record(TTS_REQUEST, "error", ...)` 已有 warn 日志处。
- `CachedTtsProvider`：构造器加 `TelemetryRecorder recorder`；`synthesize` 3 参版（或保留 2 参 + 转发）：HIT 处 `record(TTS_CACHE, "info", Map.of("text", text, "hit", true, "bytes", cached.length))`；MISS 合成成功处 `record(TTS_CACHE, "info", Map.of("text", text, "hit", false, "bytes", data.length))`；失败处 level=error。2 参版转发 3 参（utteranceId=""）。
- `AliyunTtsProvider`：构造器加 recorder；3 参版 synthesize 成功/失败处 `record(TTS_SYNTH, ...)`（payload：bytes/durationMs/error）。
- `TtsAppConfig`：加 `TtsProperties` 字段 `telemetryUrl`（`autovoice.telemetry.url`，默认空）；装配：

```java
@Bean
public TelemetryRecorder ttsTelemetryRecorder(OkHttpClient client, TtsProperties props) {
    if (props.telemetryUrl() == null || props.telemetryUrl().isBlank()) {
        return NoopTelemetryRecorder.INSTANCE;
    }
    return new TtsTelemetryForwarder(client, props.telemetryUrl());
}
```

  并注入 TtsController 与两个 provider 的构造。
- `TtsTelemetryForwarder`：`record` 里把事件缓冲成 `{utteranceId, events[]}` 批量 POST（每 500ms 或每 N 条 flush——简化：每事件一条 POST，事件量小；post body 单事件 `{utteranceId, events:[event]}`）。网关 `POST /api/telemetry/events` 端点（Task 3 的 TelemetryController 加）：

```java
@PostMapping("/events")
public void recordEvents(@RequestBody Map<String, Object> body) {
    String utt = String.valueOf(body.get("utteranceId"));
    List<?> events = (List<?>) body.get("events");
    for (Object ev : events) {
        TelemetryEvent e = MAPPER.convertValue(ev, TelemetryEvent.class);
        service.record(utt, e);
    }
}
```

（Task 3 的 service.record 已有。此端点加进 Task 3 的 Controller——为避免任务间悬空依赖，在 Task 3 Step 6 直接实现 `/events`，此处仅引用。）

- [ ] **Step 5: 全量测试保绿 + 提交**

Run: `cd AutoVoiceServer && ./gradlew test`
Expected: BUILD SUCCESSFUL

```bash
git add AutoVoiceServer/
git commit -m "feat(tts): stage events + gateway forward (tts_request/tts_cache/tts_synth)"
```

---

### Task 6: 端侧 utteranceId + TelemetryClient

**Files:**
- Modify: `AutoVoice/voice-core/src/main/kotlin/com/autovoice/voicecore/Config.kt`（CloudConfig 加 telemetry 段）
- Modify: `AutoVoice/gateway-client/src/main/kotlin/com/autovoice/gatewayclient/GatewayClient.kt:131-143`（sendAudioStart 加 utteranceId）、`:172-178`（sendTtsRequest 加 utteranceId）
- Modify: `AutoVoice/app/src/main/kotlin/com/autovoice/app/VoiceEngine.kt`（utteranceId 生成/传递/装配）
- Create: `AutoVoice/app/src/main/kotlin/com/autovoice/app/telemetry/TelemetryClient.kt`
- Modify: `AutoVoice/app/src/main/assets/demo-full.json`（telemetry 段）
- Test: `AutoVoice/gateway-client/src/test/kotlin/.../GatewayClientTest.kt`、`AutoVoice/voice-core/src/test/.../ConfigTest.kt`（若有）、新建 `AutoVoice/app/src/test/.../telemetry/TelemetryClientTest.kt`

**Interfaces:**
- Consumes: Task 2 的服务端兼容（utteranceId 可选）
- Produces:
  - `CloudConfig` 加 `val telemetry: TelemetryConfig? = null`；`class TelemetryConfig(val enabled: Boolean = true, val url: String? = null)`
  - `GatewayClient.sendAudioStart(sessionId, segmentId?, utteranceId?)` / `sendTtsRequest(text, segmentId?, utteranceId?)`
  - `TelemetryClient(okHttp, baseUrl, deviceId, scope, enabled)`：`begin(utteranceId)`、`record(stage, level, payload)`（挂当前 utterance）、`end(utteranceId)`（POST round）、`uploadAudio(utteranceId, pcm)`（POST multipart）

- [ ] **Step 1: 写失败测试（GatewayClientTest：audio_start/tts_request 带 utteranceId）**

`GatewayClientTest` 加：

```kotlin
@Test
fun audioStartCarriesUtteranceId() = runTest {
    connectReady()
    client.sendAudioStart("s1", "seg-1", "utt-1")
    val frame = client.messages.first { it.type == "audio_start" }
    assertEquals("utt-1", frame.payload["utteranceId"]?.asString)
}
```

（按现有测试的 mock server 形态；若 `sendAudioStart` 直接发帧不经过 messages 流，改为对 mock server 的 RecordedRequest 断言。现有 round trip 测试的调用已用旧签名——加参数默认值 `= null` 兼容。）

- [ ] **Step 2: 实现 GatewayClient + Config**

`GatewayClient.sendAudioStart`：

```kotlin
fun sendAudioStart(sessionId: String, segmentId: String? = null, utteranceId: String? = null) {
    val payload = linkedMapOf<String, Any>(
        "sessionId" to sessionId,
        "sampleRate" to sampleRate,
        "channels" to channels,
        "encoding" to encoding,
    )
    if (segmentId != null) payload["segmentId"] = segmentId
    if (utteranceId != null) payload["utteranceId"] = utteranceId
    sendFrame(mapOf("type" to "audio_start", "payload" to payload))
    pcmBytesInSegment = 0
}
```

`sendTtsRequest` 同样加 `utteranceId: String? = null`。

`Config.kt` CloudConfig 加：

```kotlin
val telemetry: TelemetryConfig? = null,
```

fromJson 宽松读取 `cloud.optJSONObject("telemetry")`：

```kotlin
val telemetryJson = cloud.optJSONObject("telemetry")
telemetry = telemetryJson?.let { t ->
    TelemetryConfig(
        enabled = t.optBoolean("enabled", true),
        url = t.optString("url").ifBlank { null },
    )
}
```

```kotlin
class TelemetryConfig(val enabled: Boolean = true, val url: String? = null)
```

- [ ] **Step 3: 实现 TelemetryClient**

```kotlin
package com.autovoice.app.telemetry

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * 链路数据上报客户端（数据平台一期）：事件包按"轮"聚合——[begin] 开启一轮，
 * [record] 追加事件，[end] 批量 POST /api/telemetry/round；[uploadAudio] 单独
 * POST VAD 后 PCM。失败静默（Log.w，绝不抛）；enabled=false 时全部 no-op。
 */
class TelemetryClient(
    private val okHttp: OkHttpClient,
    private val baseUrl: String,
    private val deviceId: String?,
    private val scope: CoroutineScope,
    private val enabled: Boolean = true,
) {
    private var current: CurrentRound? = null
    private var sessionId: String = ""

    @Synchronized
    fun onSessionId(id: String) {
        sessionId = id
    }

    @Synchronized
    fun begin(utteranceId: String) {
        current = CurrentRound(utteranceId, System.currentTimeMillis(), mutableListOf())
    }

    @Synchronized
    fun record(stage: String, level: String, payload: Map<String, Any?>) {
        val round = current ?: return
        round.events.add(
            JSONObject()
                .put("stage", stage)
                .put("tsMs", System.currentTimeMillis())
                .put("level", level)
                .put("payload", JSONObject(payload))
        )
    }

    @Synchronized
    fun end(utteranceId: String) {
        val round = current ?: return
        if (round.utteranceId != utteranceId) return
        current = null
        val body = JSONObject()
            .put("utteranceId", round.utteranceId)
            .put("sessionId", sessionId)
            .put("deviceId", deviceId ?: "")
            .put("source", "button")
            .put("startMs", round.startMs)
            .put("endMs", System.currentTimeMillis())
            .put("events", JSONArray(round.events))
        scope.launch(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url("$baseUrl/api/telemetry/round")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                okHttp.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "telemetry round upload failed: http ${resp.code}")
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "telemetry round upload failed", t)
            }
        }
    }

    @Synchronized
    fun uploadAudio(utteranceId: String, pcm: ByteArray) {
        if (!enabled) return
        scope.launch(Dispatchers.IO) {
            try {
                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("utteranceId", utteranceId)
                    .addFormDataPart("file", "$utteranceId.pcm",
                        pcm.toRequestBody("application/octet-stream".toMediaType()))
                    .build()
                val req = Request.Builder()
                    .url("$baseUrl/api/telemetry/audio")
                    .post(body)
                    .build()
                okHttp.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "telemetry audio upload failed: http ${resp.code}")
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "telemetry audio upload failed", t)
            }
        }
    }

    private class CurrentRound(val utteranceId: String, val startMs: Long, val events: MutableList<JSONObject>)

    companion object {
        private const val TAG = "TelemetryClient"
    }
}
```

（`baseUrl` 推导：VoiceEngine 装配处——`cfg.telemetry?.url ?: cfg.gatewayUrl.replaceFirst("ws://", "http://")`；ws 已是 http 前缀时不变。`enabled = cfg.telemetry?.enabled ?: false`——demo-full 配置了才开。）

- [ ] **Step 4: VoiceEngine 装配与 utteranceId 生成**

`VoiceEngine`：
- 构造器（`create` 工厂）加 `telemetry: TelemetryClient`（装配处：`TelemetryClient(cfg 推导)`；null cfg.telemetry → `TelemetryClient(...enabled=false)` 或 null 安全检查——用 enabled=false 的实例，避免处处判空）。
- 成员 `private var currentUtteranceId = ""`。
- `onListeningStart()`：

```kotlin
fun onListeningStart() {
    currentUtteranceId = UUID.randomUUID().toString()
    telemetry.begin(currentUtteranceId)
    telemetry.record(TelemetryStages.UTTERANCE_START, "info", mapOf("source" to "button"))
    if (networkAvailable()) session.onCloudAvailable() else session.onCloudUnavailable()
    session.onListeningStart()
}
```

- `onTurnResult(winner)` 尾部：`telemetry.end(currentUtteranceId)`（每轮结束收包）。
- `GatewayCloudRunner.run`：`client.sendAudioStart(sessionId, segmentId, currentUtteranceId)`；`request()`：`client.sendTtsRequest(text, ttsId, currentUtteranceId)`。
- `onTurnSegment(segment)`：`telemetry.record(VAD, "info", mapOf("bytes" to segment.size, "durationMs" to segment.size * 1000 / (2 * 16000)))` + `telemetry.uploadAudio(currentUtteranceId, segment)`（enabled 时）。
- 端侧 DecisionSink（VoiceEngine 装配的 sink lambda）在 `onDecision(entry)` 处追加：

```kotlin
telemetry.record(TelemetryStages.DEVICE_ARBITER, "info",
    mapOf("route" to entry.route, "reason" to entry.reason))
```

（云端 decision 事件经 sink 透传时 stage 仍记 device_arbiter？不——sink 收到的是端云两端的事件。区分：`entry.arbiter` 值为 `"on-device"` → device_arbiter；`"cloud"` → cloud_arbiter。两分支各记。）

- [ ] **Step 5: TelemetryClientTest（JVM 单测：包结构）**

```kotlin
package com.autovoice.app.telemetry

// 用 MockWebServer（app 模块 testImplementation 已有 okhttp mockwebserver？确认 app/build.gradle.kts；
// 没有则加 testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")）
class TelemetryClientTest {
    @Test
    fun endPostsRoundWithCollectedEvents() { ... }
}
```

（断言：POST body 的 utteranceId/sessionId/deviceId/events 数组含 begin 后 record 的阶段；失败时静默不抛——enqueue 500 后无异常。）

- [ ] **Step 6: demo-full.json 加 telemetry 段**

```json
"telemetry": { "enabled": true, "url": "" }
```

- [ ] **Step 7: 端侧测试保绿 + 提交**

Run: `cd AutoVoice && ./gradlew :gateway-client:test :voice-core:test :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL

```bash
git add AutoVoice/
git commit -m "feat(client): utteranceId on wire + TelemetryClient (round upload)"
```

---

### Task 7: 端侧插桩（7 阶段事件 + 仲裁填真实值）

**Files:**
- Modify: `AutoVoice/voice-core/src/main/kotlin/com/autovoice/voicecore/arbiter/OnDeviceRaceArbiter.kt`（utteranceId provider + 真实填值）
- Modify: `AutoVoice/voice-core/src/main/kotlin/com/autovoice/voicecore/session/VoiceSession.kt`（currentUtteranceId 传递）
- Modify: `AutoVoice/app/src/main/kotlin/com/autovoice/app/VoiceEngine.kt`（local_asr / execute / tts_request / tts_play 事件）
- Modify: `AutoVoice/app/src/main/kotlin/com/autovoice/app/audio/TtsPlayer.kt`（播放事件回调）
- Test: `AutoVoice/voice-core/src/test/.../OnDeviceRaceArbiterTest.kt`（utteranceId 断言）

**Interfaces:**
- Consumes: Task 6（TelemetryClient、utteranceId）
- Produces:
  - `OnDeviceRaceArbiter(cloudWaitMs, localFallbackMs, clock, sink, utteranceId: () -> String)`（默认 `{ "" }` 保兼容）
  - `VoiceSession` 成员 `var currentUtteranceId: String = ""`（VoiceEngine.onListeningStart 设置；构造时注入 arbiter 的 provider 为 `{ currentUtteranceId }`）

- [ ] **Step 1: 写失败测试（OnDeviceRaceArbiterTest：决策 utteranceId 用 provider 值）**

现有测试类加：

```kotlin
@Test
fun decisionCarriesUtteranceIdFromProvider() = runTest {
    val entries = mutableListOf<DecisionEntry>()
    val arbiter = OnDeviceRaceArbiter(
        cloudWaitMs = 50, localFallbackMs = 100,
        clock = { 1L },
        sink = { entries.add(it) },
        utteranceId = { "utt-provided" },
    )
    val winner = arbiter.race(
        cloud = async { Reply.ofText("好的") },
        local = async { Intent.unknown("vehicle") },
    )
    assertEquals("utt-provided", entries.first().utteranceId)
}
```

（按现有测试类的 runTest/async 形态调整。）

Run: `cd AutoVoice && ./gradlew :voice-core:test`
Expected: FAIL（构造器无 5 参）

- [ ] **Step 2: 实现 OnDeviceRaceArbiter + VoiceSession**

`OnDeviceRaceArbiter` 构造器加 `private val utteranceId: () -> String = { "" }`；`decision()` 的 utteranceId 字段改 `utteranceId()`。类注释更新（不再"无上游参数可取"）。

`VoiceSession`：加 `var currentUtteranceId: String = ""`；构造 arbiter 处（VoiceSession 构造器参数 `arbiter: OnDeviceRaceArbiter`——由装配方构造，因此 **VoiceSession 不构造 arbiter**，provider 需在装配处注入。装配在 VoiceEngine.create——那里构造 arbiter 时传 `utteranceId = { session.currentUtteranceId }`？session 在 arbiter 之后构造。调整：`create` 先建 session，再建 arbiter 引用 session。查看 VoiceEngine.create 现有顺序——计划：`session` 创建后，`OnDeviceRaceArbiter(..., utteranceId = { session.currentUtteranceId })`。VoiceEngine 测试/生产装配处改。）

- [ ] **Step 3: VoiceEngine 插桩补全**

- local_asr：`buildLocalChain` 的 LocalChainRunner lambda（:339/:342 日志处）追加：

```kotlin
telemetry.record(TelemetryStages.LOCAL_ASR, "info",
    mapOf("text" to (command ?: ""), "intent" to "${intent.domain}/${intent.intent}", "durationMs" to elapsed))
```

- execute：`onTurnResult` 三个分支各追加：

```kotlin
// Cloud → routeCloudReply 内 applyAndNotify 处：execute = intent 摘要 + 成功
telemetry.record(TelemetryStages.EXECUTE, "info", mapOf("intent" to "...", "result" to "applied"))
// Local → vehicle.apply(winner.intent) 成功/空 处
// Failed → FALLBACK_PHRASE 处：result=failed
```

  具体：Local 分支 `vehicle.apply(winner.intent)?.let { ... }` 改造成在 apply 后 record（含 speakText）；Cloud 分支 applyAndNotify 内 record；Failed 分支 record（result=fallback）。
- tts_request：`speakViaTts` 内 `tts.request(text)` 前：

```kotlin
telemetry.record(TelemetryStages.TTS_REQUEST, "info", mapOf("text" to text))
```

- tts_play：`speakViaTts` 的播放结果（network 路径 player.play 成功/失败、system 路径 speaker.speak 结果）——TtsPlayer 内部异步，用 Task 6 已建的回调不够；计划：**TtsPlayer 构造器加 `onPlayEvent: (String, Map<String, Any?>) -> Unit = { _, _ -> }`**（start/completed/failed/interrupted 处回调），VoiceEngine 装配传 `{ stage, payload -> telemetry.record(TelemetryStages.TTS_PLAY, stage, payload) }`（level 参数：start/completed=info、failed=error、interrupted=warn）；SystemTtsFallback.speak 调用处包 onResult：

```kotlin
speaker.speak(text) { ok ->
    telemetry.record(TelemetryStages.TTS_PLAY, if (ok) "info" else "error",
        mapOf("source" to "system", "result" to if (ok) "ok" else "failed"))
}
```

  `speakViaTts` 里 network 路径的 play 结果（player 回调）+ system 兜底路径分开标 source=network/system。
  TtsPlayer 内部回调点：:139 play start、:143 completed、:149 failed、:166 interrupted 四处。

- [ ] **Step 4: 端侧全量测试保绿 + 提交**

Run: `cd AutoVoice && ./gradlew :gateway-client:test :voice-core:test :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL

```bash
git add AutoVoice/
git commit -m "feat(app): device-side instrumentation (vad/local_asr/execute/tts_play) + truthful arbiter id"
```

---

### Task 8: telemetry-web 实时面板

**Files:**
- Create: `AutoVoiceServer/telemetry-web/package.json`
- Create: `AutoVoiceServer/telemetry-web/vite.config.ts`
- Create: `AutoVoiceServer/telemetry-web/index.html`
- Create: `AutoVoiceServer/telemetry-web/src/main.tsx`、`src/App.tsx`、`src/types.ts`、`src/api.ts`、`src/useSse.ts`
- Create: `AutoVoiceServer/telemetry-web/src/components/RoundList.tsx`、`RoundDetail.tsx`、`Timeline.tsx`、`Stats.tsx`、`AudioPlayer.tsx`

**Interfaces:**
- Consumes: Task 3 的 API（`/api/telemetry/rounds`、`/rounds/{id}`、`/stream`、`/audio/{file}`）
- Produces: 构建产物 `AutoVoiceServer/telemetry/src/main/resources/static/telemetry/`（vite outDir 直出，npm run build 即可，无 Gradle 集成）

- [ ] **Step 1: 脚手架**

`package.json`：

```json
{
  "name": "telemetry-web",
  "private": true,
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "tsc && vite build"
  },
  "dependencies": {
    "react": "^18.3.1",
    "react-dom": "^18.3.1"
  },
  "devDependencies": {
    "@types/react": "^18.3.3",
    "@types/react-dom": "^18.3.0",
    "@vitejs/plugin-react": "^4.3.1",
    "typescript": "^5.5.4",
    "vite": "^5.4.0"
  }
}
```

`vite.config.ts`：

```typescript
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  server: { proxy: { "/api": "http://127.0.0.1:8080" } },
  build: {
    outDir: "../telemetry/src/main/resources/static/telemetry",
    emptyOutDir: true,
  },
});
```

`index.html`：

```html
<!doctype html>
<html lang="zh-CN">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>AutoVoice 链路数据平台</title>
  </head>
  <body>
    <div id="root"></div>
    <script type="module" src="/src/main.tsx"></script>
  </body>
</html>
```

`tsconfig.json`（react-jsx、strict、`"types": ["vite/client"]`）。

- [ ] **Step 2: types + api + SSE hook**

`src/types.ts`：

```typescript
export interface RoundSummary {
  utteranceId: string;
  deviceId: string;
  source: string;
  startMs: number;
  endMs: number;
  localDecision: string | null;
  cloudDecision: string | null;
  finalDecision: string | null;
  ttsCacheHit: boolean | null;
  playbackResult: string | null;
  audioPath: string | null;
}

export interface TelemetryEvent {
  stage: string;
  tsMs: number;
  level: string;
  payload: Record<string, unknown>;
}

export interface RoundDetail {
  summary: RoundSummary;
  events: TelemetryEvent[];
}
```

`src/api.ts`：

```typescript
import type { RoundDetail, RoundSummary } from "./types";

export async function fetchRounds(device?: string): Promise<RoundSummary[]> {
  const q = device ? `?device=${encodeURIComponent(device)}` : "";
  const res = await fetch(`/api/telemetry/rounds${q}`);
  if (!res.ok) throw new Error(`http ${res.status}`);
  return res.json();
}

export async function fetchRound(id: string): Promise<RoundDetail | null> {
  const res = await fetch(`/api/telemetry/rounds/${encodeURIComponent(id)}`);
  if (res.status === 404) return null;
  if (!res.ok) throw new Error(`http ${res.status}`);
  return res.json();
}
```

`src/useSse.ts`（EventSource 订阅 `/api/telemetry/stream`，onmessage 追加到列表，自动重连由浏览器处理）：

```typescript
import { useEffect, useState } from "react";
import type { RoundSummary } from "./types";

export function useRounds(initial: RoundSummary[]) {
  const [rounds, setRounds] = useState<RoundSummary[]>(initial);
  useEffect(() => {
    const es = new EventSource("/api/telemetry/stream");
    es.addEventListener("round", (e) => {
      try {
        const s = JSON.parse((e as MessageEvent).data) as RoundSummary;
        setRounds((prev) => [s, ...prev.filter((r) => r.utteranceId !== s.utteranceId)]);
      } catch {
        /* 忽略坏帧 */
      }
    });
    return () => es.close();
  }, []);
  return rounds;
}
```

- [ ] **Step 3: 组件**

- `App.tsx`：顶部设备筛选 + 统计条（总轮次/失败轮/缓存命中率），左列 `RoundList`（SSE 实时），点选后右侧 `RoundDetail`。
- `RoundList.tsx`：每行 utteranceId + 时间（`new Date(startMs).toLocaleTimeString()`）+ 最终决策（finalDecision）+ 失败标红（`finalDecision` 含 `both_failed`/`failed` 或任事件 level=error 时 `style={{color:"red"}}`）+ 缓存命中绿标（`ttsCacheHit === true`）。
- `RoundDetail.tsx`：`fetchRound(id)` 渲染 `Timeline` + 音频回放（`audioPath` 非空时 `<audio src={"/api/telemetry/audio/" + audioPath} controls />`——**注意 audioPath 是相对文件名**，直接拼到 `/api/telemetry/audio/`）。
- `Timeline.tsx`：事件按 `tsMs` 升序渲染条带：阶段名（stage 映射中文标签：utterance_start→话语开始、vad→VAD 分段、local_asr→本地识别、cloud_asr→云端识别、llm→大模型、offline_pool→离线池、cloud_arbiter→云端仲裁、device_arbiter→端云仲裁、execute→执行、tts_request→TTS 请求、tts_cache→TTS 缓存、tts_synth→TTS 合成、tts_play→播放）+ 相对首事件耗时（`tsMs - first.tsMs` ms）+ payload 关键字段（text/reason/result/hit 等，取前 3 个键值）。level=error 红、warn 黄。
- `Stats.tsx`：props 轮次列表，计算：轮次总数、失败轮数（finalDecision 含 failed 或 error 轮）、平均端到端耗时（endMs-startMs）、TTS 缓存命中率（ttsCacheHit=true 轮次 / 有 tts 轮次）。
- `AudioPlayer.tsx`：封装 `<audio controls src>` + 下载链接。
- `main.tsx`：`createRoot(document.getElementById("root")!).render(<StrictMode><App /></StrictMode>)`。

- [ ] **Step 4: 构建验证**

Run: `cd AutoVoiceServer/telemetry-web && npm install && npm run build`
Expected: 产物出现在 `telemetry/src/main/resources/static/telemetry/`（index.html + assets/）

- [ ] **Step 5: 提交**

```bash
git add AutoVoiceServer/telemetry-web AutoVoiceServer/telemetry/src/main/resources/static/telemetry
git commit -m "feat(telemetry-web): SSE realtime panel (list/timeline/audio/stats)"
```

---

### Task 9: E2E 扩展——端云事件按 utteranceId 汇合 + 回归

**Files:**
- Modify: `AutoVoiceServer/app/src/test/java/com/autovoice/server/app/MultiDeviceGatewayTest.java`

**Interfaces:**
- Consumes: Task 3（telemetry API）、Task 4（服务端插桩）、Task 6-7（端侧包形态）
- Produces: E2E 断言——双连接说话后，端侧事件包（模拟 POST）+ 服务端插桩事件在 `GET /api/telemetry/rounds/{utt}` 汇合

- [ ] **Step 1: 容器启用 telemetry（临时 SQLite）**

`MultiDeviceGatewayTest` 加类注解（现有 @SpringBootTest 基础加 properties）：

```java
@SpringBootTest(webEnvironment = RANDOM_PORT,
        properties = {
                "autovoice.telemetry.enabled=true",
                "autovoice.telemetry.db-path=${java.io.tmpdir}/autovoice-e2e-${random.uuid}.db",
                "autovoice.telemetry.audio-dir=${java.io.tmpdir}/autovoice-e2e-audio"
        })
```

（若与现有注解重复，合并进现有 @SpringBootTest。注意：`${random.uuid}` Spring 占位符每次容器启动新库。）

- [ ] **Step 2: 写失败测试（双连接 → 端云事件汇合）**

在 `MultiDeviceGatewayTest` 加：

```java
@Test
void telemetryRoundMergesDeviceAndServerEvents() throws Exception {
    // 同现有用例：双连接各说一句（audio_start 带 utteranceId=utt-e2e-a / utt-e2e-b）
    // ...（复用 twoConnectionsProcessInParallelWithSegmentIdMatching 的 speak 流程）
    // 端侧事件包（模拟设备端插桩）：POST /api/telemetry/round
    String deviceRound = """
        {"utteranceId":"utt-e2e-a","sessionId":"s1","deviceId":"demo-1","source":"button",
         "startMs":1000,"endMs":5000,
         "events":[{"stage":"utterance_start","tsMs":1000,"level":"info","payload":{"source":"button"}},
                   {"stage":"local_asr","tsMs":1500,"level":"info","payload":{"text":"打开空调"}},
                   {"stage":"device_arbiter","tsMs":3000,"level":"info","payload":{"route":"cloud","reason":"cloud_won"}},
                   {"stage":"execute","tsMs":4000,"level":"info","payload":{"intent":"climate/set_temperature","result":"applied"}},
                   {"stage":"tts_request","tsMs":4500,"level":"info","payload":{"text":"空调调到二十四度"}},
                   {"stage":"tts_play","tsMs":4900,"level":"info","payload":{"source":"network","result":"ok"}}]}
        """;
    postJson("/api/telemetry/round", deviceRound);

    // 服务端插桩事件（audio_start 带 utteranceId=utt-e2e-a 触发的 ASR/LLM/仲裁）
    // 查询汇合：GET /api/telemetry/rounds/utt-e2e-a
    Map<String, Object> round = getJson("/api/telemetry/rounds/utt-e2e-a");
    assertNotNull(round, "round 应存在");
    List<?> events = (List<?>) ((Map<?, ?>) round.get("summary") != null
            ? ((Map<?, ?>) round.get("summary")) : round).get("events");
    // 断言端侧事件 + 服务端事件都汇合在 utt-e2e-a 下
    Set<String> stages = events.stream()
            .map(e -> String.valueOf(((Map<?, ?>) e).get("stage")))
            .collect(Collectors.toSet());
    assertTrue(stages.containsAll(Set.of("utterance_start", "local_asr", "device_arbiter",
            "execute", "tts_request", "tts_play")), "端侧事件应汇合");
    assertTrue(stages.containsAll(Set.of("cloud_asr", "llm", "cloud_arbiter")), "服务端插桩事件应汇合");
}
```

（`postJson`/`getJson` 用现有 okhttp client 或 TestRestTemplate——按该类现有形态；RoundDetail JSON 形状以 Task 3 实现为准：`{summary:{...}, events:[...]}`。）

Run: `./gradlew :app:test --tests '*MultiDeviceGatewayTest'`
Expected: FAIL（汇合断言失败——端侧包先 POST 后查询只有端侧事件；服务端插桩事件的 utteranceId 为 u-N 自增，未与 utt-e2e-a 对齐）

- [ ] **Step 3: 修正 audio_start 携带端侧 utteranceId（贯通依赖检查）**

若 Step 2 失败原因是"服务端插桩事件的 utteranceId 是 u-N"：检查 Task 2 的 handler 采纳逻辑是否生效——E2E 的 audio_start 须带 `utteranceId=utt-e2e-a`（现有 `speak()` 的 send audio_start 加该字段）。修改 `DeviceSession.speak()` 的 audio_start payload 加 `"utteranceId", utteranceId`（DeviceSession 加字段，open 时传入）。

Run: `./gradlew :app:test --tests '*MultiDeviceGatewayTest'`
Expected: PASS（两端事件在 utt-e2e-a 汇合）

- [ ] **Step 4: 全量回归（两端）**

Run: `cd AutoVoiceServer && ./gradlew test`；`cd AutoVoice && ./gradlew :gateway-client:test :voice-core:test :app:testDebugUnitTest`
Expected: 全绿

- [ ] **Step 5: 提交**

```bash
git add AutoVoiceServer/
git commit -m "test(gateway): e2e merge assertion — device + server events by utteranceId (telemetry)"
```

---

### Task 10: 契约与部署文档

**Files:**
- Modify: `docs/runbook.md`（§1.7 链路数据平台部署节）
- Modify: `shared/protocol.md`（audio_start/tts_request 加 utteranceId 可选字段；§5 telemetry 说明）
- Modify: `shared/contracts/gateway-messages.schema.json`（audio_start/tts_request properties 加可选 utteranceId）
- Modify: `AutoVoiceServer/tts-server/src/main/resources/application.yml`（已在 Task 5 改，此处仅核对）

- [ ] **Step 1: protocol.md + schema**

`gateway-messages.schema.json` audio_start properties 加：

```json
"utteranceId": { "type": "string", "description": "链路追踪：端侧每轮生成的 UUID，随 audio_start 上传，服务端插桩复用（可选，向后兼容）" }
```

tts_request properties 加同字段（description：随 tts_request 上传，tts-server 插桩用）。

`shared/protocol.md`：
- §3.2 audio_start 字段表加 utteranceId（可选：链路追踪 ID，端侧每轮 UUID；服务端决策/插桩事件回带该值；缺省时服务端回退 u-N 自增）。
- §3.4 tts_request 字段表加 utteranceId（可选：透传到 TTS 服务的链路追踪 ID）。
- 新小节「链路追踪（telemetry）」：端侧事件（utterance_start/vad/local_asr/device_arbiter/execute/tts_request/tts_play）经 `POST /api/telemetry/round` 上报（HTTP，非 WS 协议消息）；服务端插桩（cloud_asr/offline_pool/llm/cloud_arbiter/tts_request/tts_cache/tts_synth）落同库；查询 API 与面板地址（`/telemetry/`）。注明：上报为尽力而为，失败/禁用不影响业务。

- [ ] **Step 2: runbook §1.7**

`docs/runbook.md` 加：

```markdown
### 1.7 链路数据平台（telemetry）

数据平台与网关同进程同端口（8080）：端侧每轮事件经 HTTP 上报，服务端插桩落同库，
面板 `http://47.94.4.204:8080/telemetry/`（SSE 实时列表 / 单轮时间线 / 音频回放 / 统计）。

| 环境变量 | 默认 | 说明 |
| --- | --- | --- |
| `AUTOVOICE_TELEMETRY_ENABLED` | `true` | 数据平台开关（关 → 全部 Noop，零影响） |
| `AUTOVOICE_TELEMETRY_DB` | `./telemetry.db` | SQLite 路径（服务器建议 `/opt/autovoice/telemetry/telemetry.db`） |
| `AUTOVOICE_TELEMETRY_AUDIO_DIR` | `./telemetry-audio` | VAD 后语音 WAV 目录 |
| `AUTOVOICE_TELEMETRY_RETENTION_DAYS` | `7` | 保留天数，超期自动清理（含音频） |
| `AUTOVOICE_TELEMETRY_URL`（tts-server） | 空 | tts-server 事件转发网关地址（`http://127.0.0.1:8080`）；空 → 不转发 |

端侧 `demo-full.json` `telemetry.enabled=true` 时启用上报（baseUrl 从 gatewayUrl 推导，可 `url` 覆盖）。
```

- [ ] **Step 3: 提交**

```bash
git add docs/runbook.md shared/
git commit -m "docs: telemetry protocol fields + runbook deployment section"
```

---

## 自审记录

- **Spec 覆盖**：utteranceId 打通（Task 2/6/7）、telemetry 模块存储/API/SSE/清理/音频（Task 3）、服务端插桩（Task 4）、tts-server 插桩与转发（Task 5）、端侧插桩（Task 6/7）、面板（Task 8）、E2E 汇合断言（Task 9）、部署文档（Task 10）——spec 全部 6 节实施顺序一一对应。VAD 统计细节（maxProb 等）保留 logcat（spec 事件模型只要求段统计字节/时长）——与 spec 的 vad payload（段数/总时长）一致。
- **占位符**：全部步骤含具体代码/命令；无 TBD。Task 4/5 中 `ctx.sessionId()` 关联的池/LLM 事件是 spec 已声明的取舍（utteranceId 不在 provider 接口内，RoundDetail 按 round 聚合不丢失关联）。
- **类型一致性**：`TelemetryRecorder.record(utt, stage, level, payload)` 全链路一致；`TelemetryEvent(stage, tsMs, level, payload)` 服务端/端侧 JSON 形状一致（`stage/tsMs/level/payload`）；`DeviceRoundPayload` 字段与端侧 TelemetryClient 的 POST body 键一一对应（utteranceId/sessionId/deviceId/source/startMs/endMs/events）；`RoundDetail{summary, events}` 与面板 types.ts 一致。
- **风险提示**：Task 5 的 `TtsProvider.synthesize` 现签名需实现前 grep 确认（计划 Step 1 已标注）；Task 3 的 `@ConditionalOnProperty` 确保禁用时 Controller 不装配（避免 null bean 注入失败）。
