# AutoVoice 周末 Demo 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 周末 demo——在 Android 手机（模拟车机）+ 阿里云环境上，用同一套可插拔框架跑通三条语音链路（讯飞离线 / 讯飞语义API / DeepSeek LLM）+ 端云竞速仲裁，四剧本验收。

**Architecture:** 分层流水线引擎（方案 1）：Stage SPI + Canonical Intent 归一化 + 竞速仲裁（端侧云端优先 2000ms 兜底本地；云端传统优先 1500ms 兜底 LLM）+ 统一网关（端侧只连 AutoVoiceServer）。供应商差异锁死在适配器内。

**Tech Stack:** 端侧 Kotlin 2.0（Gradle 8.9 / AGP 8.5.2，minSdk 26，Compose）；云端 Java 21 + Spring Boot 3.3；Gson（端）/ Jackson（云）；OkHttp + MockWebServer；JUnit 5；onnxruntime-android（Silero VAD）；RNNoise（JNI/CMake）。

## Global Constraints

- 契约以 `shared/` 为准：Canonical Intent Schema、网关消息、配置 schema；两端测试资源直接引用 `shared/fixtures/`（相对路径 `srcDir`），禁止复制粘贴后漂移
- 单赢家原则：一轮话语只有一个结果过执行器，输家丢弃并记决策日志
- 拒识 = Canonical `Intent(domain="unknown", intent="unknown")`
- 音频统一 16k 单声道 S16LE；Android 采集端负责重采样到 16k（48k 输入用 AudioRecord 采样率 16000 直接采，不做重采样——手机麦克风直接按 16k 采）
- 超时/优先级全部配置化：`cloudWaitMs=2000`、`nluGraceMs=1500`、`safetyTimeoutMs=6500`
- 密钥一律走环境变量（`XFYUN_APPID/XFYUN_API_KEY/DEEPSEEK_API_KEY/ALIYUN_AK/ALIYUN_SK/ALIYUN_NLS_APPKEY/DASHSCOPE_API_KEY`），禁止提交 git；`local.properties`、`.env` 已 gitignore
- 提交信息遵循 Conventional Commits（feat:/fix:/test:/docs:）

### Demo 简化声明（有意的范围裁剪，接口不变）

1. **阿里云 ASR 用一句话识别**（每 VAD 段一次 HTTP 调用）代替流式 WebSocket——网关接口（Android 只传段音频）不变，流式是后续升级
2. **TTS 用 DashScope sambert-zhichu-v1（HTTP 同步）**代替 CosyVoice 流式——同理由
3. **本地链路播报用 Android 系统 TTS**（离线可用），云端播报用阿里云 TTS
4. **讯飞离线命令词 SDK 用体验版**（3 装机量 / 35 天，开放平台控制台自助申请开通）；若申请/下载受阻 → 同一 SPI 下的 `FakeCommandAsrProvider`（模拟命令词识别）兜底，demo 仍可跑通，验收剧本 1 不阻塞。讯飞无个人可用的离线语义 SDK → 端侧语义由自研 `RuleNluProvider`（命令词→Canonical Intent 规则映射）承担
5. 配置系统：demo 用单文件 JSON（`demo-full.json` / `demo-offline.json`），三层继承引擎不做（spec §9.1 已排除）
6. 验收剧本 3 的"讯飞拒识"依赖讯飞语义 API 对测试句的返回：若讯飞对"明天上海天气怎么样"返回了非 unknown 结果，runbook 已给出替代测试句（"讲个笑话"）

## File Structure

```
AutoVoice/                          ← 仓库根（现有：README/License/docs/specs/实现方式.md/.gitignore）
├── shared/
│   ├── contracts/
│   │   ├── intent.schema.json      Canonical Intent JSON Schema
│   │   ├── gateway-messages.schema.json   端云网关消息 JSON Schema
│   │   └── config.schema.json      端侧 demo 配置 JSON Schema
│   ├── fixtures/
│   │   ├── iflytek-semantic-*.json     讯飞语义 API 返回样本（归一化测试用）
│   │   ├── aliyun-asr-*.json           阿里云一句话识别返回样本
│   │   ├── aliyun-tts-*.json           DashScope TTS 返回样本
│   │   ├── deepseek-llm-*.json         DeepSeek chat 返回样本
│   │   └── gateway-*.json              网关消息样本（契约测试用）
│   └── protocol.md                 端云网关协议文档（消息定义+时序）
├── AutoVoice/                      ← 端侧 Android 项目
│   ├── settings.gradle.kts / build.gradle.kts / gradle/libs.versions.toml
│   ├── voice-core/                 纯 JVM Kotlin 库：消息模型 + Stage SPI + 端侧仲裁 + 状态机 + 配置加载
│   ├── gateway-client/             AutoVoiceServer WS 客户端（OkHttp，可测）
│   ├── adapter-local/              RNNoise(JNI) + SileroVAD(onnxruntime) + 能量VAD兜底
│   ├── adapter-iflytek/            讯飞离线命令词 ASR 适配器 + RuleNluProvider（规则语义）+ FakeCommandAsrProvider 兜底
│   └── app/                        Compose UI：状态/决策日志/模拟车控/TTS播放/录音
└── AutoVoiceServer/                ← 云端 Java 项目（Gradle multi-module）
    ├── settings.gradle.kts / build.gradle.kts / gradle/libs.versions.toml
    ├── contracts/                  Java 消息/意图类 + Provider SPI（NluProvider/LlmProvider/AsrProvider/TtsProvider）
    ├── arbitration/                云端竞速仲裁（RaceArbiter）+ DecisionSink
    ├── session/                    会话状态 SessionContext
    ├── nlu-traditional/            IflytekNluProvider（讯飞语义 API + 归一化）
    ├── llm/                        DeepSeekLlmProvider（chat/completions）
    ├── asr-gateway/                AliyunAsrProvider（一句话识别）
    ├── tts-gateway/                AliyunTtsProvider（sambert HTTP）
    ├── gateway/                    WS 网关：协议编解码 + 会话装配（asr→arbiter→tts 流水线）
    └── app/                        Spring Boot 入口 + 配置 + Bean 装配
```

## 各阶段与任务一览

- **Phase A 脚手架与契约**：Task 1-3
- **Phase B 云端**：Task 4-11（先核心后供应商，全程单测）
- **Phase C 端侧核心**：Task 12-15
- **Phase D 端侧适配器**：Task 16-17
- **Phase E App 与集成**：Task 18-22（含四剧本验收）

---

# Phase A：脚手架与共享契约

### Task 1: 仓库脚手架（双项目 Gradle 根 + 模块骨架）

**Files:**
- Modify: `AutoVoice/.gitignore`（追加 Gradle/Android/IDE 忽略）
- Create: `AutoVoice/settings.gradle.kts`、`AutoVoice/build.gradle.kts`、`AutoVoice/gradle/libs.versions.toml`、`AutoVoice/gradle/wrapper/*`
- Create: `AutoVoiceServer/settings.gradle.kts`、`AutoVoiceServer/build.gradle.kts`、`AutoVoiceServer/gradle/libs.versions.toml`、`AutoVoiceServer/gradle/wrapper/*`
- Create: 各模块空骨架 `build.gradle.kts` + `src/main/kotlin|java/com/autovoice/.../` 占位（每模块一个空类 `ModuleInfo`）

**Interfaces:**
- Consumes: 无
- Produces: 两个可 `./gradlew build` 通过的项目骨架；模块名与包名约定：端侧 `com.autovoice.<module>`，云端 `com.autovoice.server.<module>`

- [ ] **Step 1: 根 .gitignore 追加**

```gitignore
# Gradle
.gradle/
build/
# Android
local.properties
*.apk
*.aab
captures/
# IDE
.idea/
*.iml
# 密钥
.env
*.jks
```

- [ ] **Step 2: 端侧 `AutoVoice/settings.gradle.kts` + 根 build 文件**

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "AutoVoice"
include(":voice-core", ":gateway-client", ":adapter-local", ":adapter-iflytek", ":app")
```

```toml
# gradle/libs.versions.toml
[versions]
agp = "8.5.2"
kotlin = "2.0.20"
coroutines = "1.9.0"
gson = "2.11.0"
okhttp = "4.12.0"
junit = "5.10.2"
composeBom = "2024.09.00"
activityCompose = "1.9.2"
onnxruntime = "1.19.0"
[libraries]
gson = { module = "com.google.code.gson:gson", version.ref = "gson" }
coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }
coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }
mockwebserver = { module = "com.squareup.okhttp3:mockwebserver", version.ref = "okhttp" }
junit = { module = "org.junit.jupiter:junit-jupiter", version.ref = "junit" }
onnxruntime-android = { module = "com.microsoft.onnxruntime:onnxruntime-android", version.ref = "onnxruntime" }
[plugins]
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
android-application = { id = "com.android.application", version.ref = "agp" }
android-library = { id = "com.android.library", version.ref = "agp" }
compose-compiler = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
```

- [ ] **Step 3: 云端 `AutoVoiceServer/settings.gradle.kts` + 根 build 文件**

```kotlin
// settings.gradle.kts
pluginManagement { repositories { mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { mavenCentral() } }
rootProject.name = "AutoVoiceServer"
include(":contracts", ":arbitration", ":session", ":nlu-traditional", ":llm", ":asr-gateway", ":tts-gateway", ":gateway", ":app")
```

```toml
# gradle/libs.versions.toml
[versions]
springBoot = "3.3.4"
jackson = "2.17.2"
okhttp = "4.12.0"
junit = "5.10.2"
[plugins]
spring-boot = { id = "org.springframework.boot", version.ref = "springBoot" }
spring-dependency-management = { id = "io.spring.dependency-management", version.ref = "springBoot" }
[libraries]
spring-boot-starter-websocket = { module = "org.springframework.boot:spring-boot-starter-websocket" }
spring-boot-starter-json = { module = "org.springframework.boot:spring-boot-starter-json" }
okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }
mockwebserver = { module = "com.squareup.okhttp3:mockwebserver", version.ref = "okhttp" }
junit = { module = "org.junit.jupiter:junit-jupiter", version.ref = "junit" }
jackson-databind = { module = "com.fasterxml.jackson.core:jackson-databind", version.ref = "jackson" }
jackson-datatype-jsr310 = { module = "com.fasterxml.jackson.datatype:jackson-datatype-jsr310", version.ref = "jackson" }
[plugins]
```

- [ ] **Step 4: 模块骨架**——每个模块建 `build.gradle.kts`（kotlin("jvm") 或 Android 插件，版本号全部走 version catalog）与 `src/main/...` 包目录。端侧 `voice-core` 用 `kotlin("jvm")`（纯 JVM，无 Android 依赖，host 单测）；`gateway-client`、`adapter-iflytek` 用 `kotlin("jvm")` + OkHttp；`adapter-local`、`app` 用 Android 插件。云端 9 个模块全部 `java-library`，`app` 用 `org.springframework.boot` 插件。

- [ ] **Step 5: 生成 wrapper 并验证**

Run: `cd AutoVoice && gradle wrapper --gradle-version 8.9 && ./gradlew :voice-core:build`（无模块时的空 build）
Run: `cd AutoVoiceServer && gradle wrapper --gradle-version 8.9 && ./gradlew build`
Expected: 两个项目 BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "chore: scaffold AutoVoice (android) and AutoVoiceServer (cloud) gradle projects"
```

### Task 2: shared 契约——Schema、fixtures、协议文档

**Files:**
- Create: `shared/contracts/intent.schema.json`
- Create: `shared/contracts/gateway-messages.schema.json`
- Create: `shared/contracts/config.schema.json`
- Create: `shared/fixtures/gateway-hello.json`、`gateway-reply-action.json`、`gateway-reply-audio.json`、`iflytek-semantic-ac.json`、`aliyun-asr-result.json`、`deepseek-llm-reply.json`
- Create: `shared/protocol.md`

**Interfaces:**
- Consumes: 无
- Produces: 契约文件——两端全部类定义与测试以这里为准

- [ ] **Step 1: Canonical Intent Schema**

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://autovoice/shared/intent.schema.json",
  "title": "CanonicalIntent",
  "type": "object",
  "required": ["schemaVersion", "domain", "intent", "slots", "confidence"],
  "properties": {
    "schemaVersion": { "type": "string", "const": "1.0" },
    "domain": { "type": "string" },
    "intent": { "type": "string" },
    "slots": {
      "type": "object",
      "additionalProperties": {
        "type": "object",
        "required": ["type", "value"],
        "properties": {
          "type": { "enum": ["number", "enum", "string", "boolean"] },
          "value": {},
          "unit": { "type": "string" }
        }
      }
    },
    "confidence": { "type": "number", "minimum": 0, "maximum": 1 },
    "source": { "type": "string" },
    "rawSemantic": { "type": "string" }
  }
}
```

- [ ] **Step 2: 网关消息 Schema**（`gateway-messages.schema.json`）——消息统一形状 `{"type": "...", "payload": {...}}`，`oneOf` 列出：`hello` / `audio_start` / `audio_end` / `ready` / `decision` / `asr_partial` / `reply` / `error` / `bye`。reply 的 payload 含 `{"kind": "text"|"audio"|"action"}`，audio 带 `mime`+`dataBase64`，action 带 `intent`（引用 intent schema 形状）+ `speakText`。字段含义以 `shared/protocol.md` 为准（Task 3 详述）。

- [ ] **Step 3: Fixtures**（真实返回样本，归一化/解析测试用）

```json
// shared/fixtures/iflytek-semantic-ac.json —— 讯飞语义 API 返回样本（空调）
{
  "code": "0",
  "data": {
    "result": {
      "intent": {
        "service": "空调",
        "intent": "调节温度",
        "answer": "好的，已为您设置",
        "slots": [
          { "name": "温度", "value": "24", "normValue": "24" },
          { "name": "对象", "value": "主驾" }
        ]
      }
    }
  }
}
```

```json
// shared/fixtures/aliyun-asr-result.json —— 阿里云一句话识别返回样本
{ "status": 20000000, "result": "空调调到二十四度", "sentenceId": "abc123" }
```

```json
// shared/fixtures/deepseek-llm-reply.json —— DeepSeek chat 返回样本
{
  "id": "chatcmpl-1", "object": "chat.completion", "model": "deepseek-chat",
  "choices": [{ "index": 0, "message": { "role": "assistant", "content": "上海明天多云，25到31度。" }, "finish_reason": "stop" }],
  "usage": { "prompt_tokens": 20, "completion_tokens": 15, "total_tokens": 35 }
}
```

```json
// shared/fixtures/gateway-reply-action.json —— 网关下行 action 消息
{
  "type": "reply",
  "payload": {
    "kind": "action",
    "intent": {
      "schemaVersion": "1.0", "domain": "climate", "intent": "set_temperature",
      "slots": { "temperature": { "type": "number", "value": 24 }, "zone": { "type": "enum", "value": "driver" } },
      "confidence": 0.95, "source": "nlu.iflytek.api"
    },
    "speakText": "已为您把空调调到24度"
  }
}
```

- [ ] **Step 4: Commit**

```bash
git add shared/
git commit -m "docs: add shared contracts, fixtures, protocol"
```

### Task 3: 端云网关协议文档 + 契约测试接线

**Files:**
- Create: `shared/protocol.md`
- Modify: `AutoVoice/voice-core/build.gradle.kts`、`AutoVoiceServer/contracts/build.gradle.kts`（测试资源指向 shared/fixtures）

**Interfaces:**
- Consumes: Task 1 模块骨架、Task 2 fixtures
- Produces: `voice-core`/`contracts` 测试可直接读 `shared/fixtures/*.json`

- [ ] **Step 1: 写 `shared/protocol.md`**——包含：传输（WS，文本帧 JSON + 二进制帧 PCM）、客户端→服务端消息（`hello`/`audio_start`/`audio_end`）、服务端→客户端消息（`ready`/`decision`/`asr_partial`/`reply`/`error`/`bye`）、每类消息的 JSON 示例、时序（连接→录音段→结果）、决策日志事件（`arbiter` 字段 `on-device`/`cloud`，`route` 字段 `local`/`cloud`/`nlu-traditional`/`llm`）。

- [ ] **Step 2: 测试资源接线**——两个模块 build.gradle.kts 各加：

```kotlin
// AutoVoice/voice-core/build.gradle.kts 与 AutoVoiceServer/contracts/build.gradle.kts 的 test 配置：
sourceSets.test { resources.srcDir("../../shared/fixtures") }
```

- [ ] **Step 3: 冒烟测试**——`voice-core` 写一个测试读取 fixture：

```kotlin
// AutoVoice/voice-core/src/test/kotlin/com/autovoice/voicecore/ContractSmokeTest.kt
class ContractSmokeTest {
    @Test
    fun `shared fixtures readable`() {
        val path = javaClass.classLoader.getResource("gateway-reply-action.json")
        assertNotNull(path, "shared/fixtures 未接线")
        val text = path!!.readText()
        assertTrue(text.contains("\"type\": \"reply\""))
    }
}
```

- [ ] **Step 4: 验证**

Run: `cd AutoVoice && ./gradlew :voice-core:test`
Run: `cd AutoVoiceServer && ./gradlew :contracts:test`
Expected: 两处 PASS

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "test: wire shared fixtures into both projects contract tests"
```

---

# Phase B：AutoVoiceServer（云端）

### Task 4: contracts 模块——Java 消息/意图类 + Provider SPI

**Files:**
- Create: `AutoVoiceServer/contracts/src/main/java/com/autovoice/server/contracts/*.java`（`Intent.java`、`SlotValue.java`、`Reply.java`、`GatewayMessage.java`、`DecisionEntry.java`、`SessionContext.java`、`NluProvider.java`、`LlmProvider.java`、`AsrProvider.java`、`TtsProvider.java`）
- Test: `AutoVoiceServer/contracts/src/test/java/com/autovoice/server/contracts/IntentSerializationTest.java`

**Interfaces:**
- Consumes: Task 3 fixtures
- Produces（后续任务依赖的精确签名）:
  - `Intent`（不可变，Jackson 注解，与 shared schema 一致）：`schemaVersion/domain/intent/slots(Map<String,SlotValue>)/confidence/source/rawSemantic`；`static Intent unknown(String source)`；`boolean isUnknown()`（domain=="unknown"）
  - `Reply`（sealed 风格：`static Reply ofText(String)`、`ofAudio(String mime, byte[] data)`、`ofAction(Intent intent, String speakText)`）+ `kind()` 三值
  - `SessionContext`：`String sessionId; String language; Map<String,Object> attrs;`
  - `NluProvider`：`CompletableFuture<Intent> understand(String text, SessionContext ctx)`
  - `LlmProvider`：`CompletableFuture<Reply> chat(String text, SessionContext ctx)`
  - `AsrProvider`：`String transcribe(byte[] pcm16k, SessionContext ctx)`（同步，抛 `AsrException`）
  - `TtsProvider`：`Reply synthesize(String text, SessionContext ctx)`
  - `DecisionEntry`：`String arbiter/route/reason/utteranceId; long timestampMs`

- [ ] **Step 1: 写失败测试**（Jackson 序列化 round-trip 与 fixture 校验）

```java
// contracts/src/test/java/com/autovoice/server/contracts/IntentSerializationTest.java
class IntentSerializationTest {
    static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void roundTripsCanonicalIntent() throws Exception {
        Intent i = Intent.of("1.0", "climate", "set_temperature",
            Map.of("temperature", SlotValue.number(24), "zone", SlotValue.enumValue("driver")),
            0.95, "nlu.iflytek.api", null);
        String json = MAPPER.writeValueAsString(i);
        Intent back = MAPPER.readValue(json, Intent.class);
        assertEquals("climate", back.domain());
        assertEquals(24, back.slots().get("temperature").value());
        assertEquals("driver", back.slots().get("zone").value());
        assertFalse(back.isUnknown());
    }

    @Test
    void unknownIntentRoundTrip() throws Exception {
        Intent u = Intent.unknown("nlu.iflytek.api");
        Intent back = MAPPER.readValue(MAPPER.writeValueAsString(u), Intent.class);
        assertTrue(back.isUnknown());
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd AutoVoiceServer && ./gradlew :contracts:test`
Expected: FAIL——类不存在

- [ ] **Step 3: 实现类**——`Intent`（record 风格 + Jackson），`SlotValue`（`type/value/unit`，工厂 `number(double)`/`enumValue(String)`/`stringValue(String)`/`bool(boolean)`），`Reply`（kind + 三个静态工厂），`SessionContext`（构造 + `withAttr`），`DecisionEntry`（record），四个 Provider 接口 + `AsrException extends RuntimeException`。`Intent.unknown()` 返回 `Intent.of("1.0","unknown","unknown",Map.of(),0.0,source,null)`。

- [ ] **Step 4: 运行确认通过**

Run: `cd AutoVoiceServer && ./gradlew :contracts:test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add AutoVoiceServer/contracts
git commit -m "feat: server contracts module with canonical intent and provider SPIs"
```

### Task 5: arbitration 模块——云端竞速仲裁 RaceArbiter

**Files:**
- Create: `AutoVoiceServer/arbitration/src/main/java/com/autovoice/server/arbitration/RaceArbiter.java`、`DecisionSink.java`
- Test: `AutoVoiceServer/arbitration/src/test/java/com/autovoice/server/arbitration/RaceArbiterTest.java`

**Interfaces:**
- Consumes: Task 4（`Intent`/`Reply`/`NluProvider`/`LlmProvider`/`SessionContext`/`DecisionEntry`）
- Produces:
  - `DecisionSink`：`void log(DecisionEntry e)`
  - `RaceArbiter`：构造 `(long nluGraceMs, long safetyTimeoutMs, ScheduledExecutorService scheduler, DecisionSink sink)`；`CompletableFuture<Reply> decide(String text, NluProvider nlu, LlmProvider llm, SessionContext ctx)`
  - 收敛规则（spec §5.2）：nlu 先出非拒识 → 立即用它；llm 先出 → 等 nlu 最多 nluGraceMs，nlu 到达且非拒识 → 用 nlu，nlu 拒识 → 用 llm，超时 → 用 llm；两者都不出 → safetyTimeout 后返回兜底 TextReply("网络开小差了，请稍后再试")
  - 决策日志 reason 取值：`nlu_first` / `llm_first_wait_nlu_arrived` / `nlu_rejected_use_llm` / `llm_first_wait_timeout` / `safety_timeout`

- [ ] **Step 1: 写失败测试**（可控延迟，验证全部收敛分支）

```java
// arbitration/src/test/java/com/autovoice/server/arbitration/RaceArbiterTest.java
class RaceArbiterTest {
    static final long GRACE = 100, SAFETY = 1000;
    final List<DecisionEntry> log = new ArrayList<>();
    final DecisionSink sink = log::add;
    final ScheduledExecutorService sched = Executors.newScheduledThreadPool(2);
    final RaceArbiter arbiter = new RaceArbiter(GRACE, SAFETY, sched, sink);
    final SessionContext ctx = new SessionContext("s1", "zh-CN", Map.of());

    NluProvider nlu(String text, long delayMs, boolean unknown) {
        return (t, c) -> CompletableFuture.supplyAsync(() -> {
            sleep(delayMs);
            return unknown ? Intent.unknown("test") : Intent.of("1.0", "climate", "set_temperature", Map.of(), 0.9, "test", null);
        }, sched);
    }
    LlmProvider llm(String text, long delayMs) {
        return (t, c) -> CompletableFuture.supplyAsync(() -> { sleep(delayMs); return Reply.ofText("LLM回答"); }, sched);
    }
    static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { throw new RuntimeException(e); } }

    @Test void nluFirstWins() {
        Reply r = arbiter.decide("x", nlu("x", 10, false), llm("x", 300), ctx).join();
        assertEquals("action", r.kind()); // nlu 非拒识 → ofAction
        assertEquals("nlu_first", log.get(log.size()-1).reason());
    }
    @Test void llmFirstWaitsForNluWithinGrace() {
        Reply r = arbiter.decide("x", nlu("x", 60, false), llm("x", 5), ctx).join();
        assertEquals("nlu_first", log.get(log.size()-1).reason()); // nlu 60ms < GRACE 100ms
    }
    @Test void llmFirstNluRejectedThenLlm() {
        Reply r = arbiter.decide("x", nlu("x", 60, true), llm("x", 5), ctx).join();
        assertEquals("LLM回答", r.text());
        assertEquals("nlu_rejected_use_llm", log.get(log.size()-1).reason());
    }
    @Test void llmFirstNluTimeoutThenLlm() {
        Reply r = arbiter.decide("x", nlu("x", 500, false), llm("x", 5), ctx).join();
        assertEquals("LLM回答", r.text());
        assertEquals("llm_first_wait_timeout", log.get(log.size()-1).reason());
    }
    @Test void bothSlowSafetyFallback() {
        Reply r = arbiter.decide("x", nlu("x", 5000, false), llm("x", 5000), ctx).join();
        assertTrue(r.text().contains("网络开小差"));
        assertEquals("safety_timeout", log.get(log.size()-1).reason());
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd AutoVoiceServer && ./gradlew :arbitration:test`
Expected: FAIL——RaceArbiter 不存在

- [ ] **Step 3: 实现 RaceArbiter**——按 spec §5.2 收敛规则；用 `CompletableFuture` + `AtomicBoolean` 单赢家守卫 + `scheduler.schedule` 实现 grace/safety 计时（伪码见下方，需通过全部 5 个测试；注意 llmFirstWaitsForNluWithinGrace 里 nlu 的 thenAccept 直接 complete，delayed 任务再查 `nluF.isDone()` 防双写）：

```java
public CompletableFuture<Reply> decide(String text, NluProvider nlu, LlmProvider llm, SessionContext ctx) {
    CompletableFuture<Reply> out = new CompletableFuture<>();
    AtomicBoolean settled = new AtomicBoolean(false);
    CompletableFuture<Intent> nluF = nlu.understand(text, ctx);
    CompletableFuture<Reply> llmF = llm.chat(text, ctx);
    nluF.whenComplete((intent, err) -> {
        if (err != null || intent == null || intent.isUnknown()) return; // 拒识留给 LLM
        if (settled.compareAndSet(false, true)) {
            sink.log(new DecisionEntry("cloud", "nlu-traditional", "nlu_first", ctx.sessionId(), System.currentTimeMillis()));
            out.complete(Reply.ofAction(intent, intentToSpeak(intent))); // 车控动作回复；text 意图场景由调用方转换为文本
        }
    });
    llmF.whenComplete((reply, err) -> {
        if (err != null || settled.get()) return;
        if (nluF.isDone()) { // nlu 已有结论
            try { Intent i = nluF.get();
                if (i == null || i.isUnknown()) {
                    if (settled.compareAndSet(false, true)) { sink.log(entry("nlu_rejected_use_llm")); out.complete(reply); }
                } // 非拒识：nlu 分支已 settle 或即将 settle，忽略
            } catch (Exception e) { if (settled.compareAndSet(false, true)) out.complete(reply); }
            return;
        }
        // llm 先到，给 nlu 宽限期
        scheduler.schedule(() -> {
            if (settled.get()) return;
            if (nluF.isDone()) {
                try { Intent i = nluF.get();
                    if (i == null || i.isUnknown()) { settled.set(true); sink.log(entry("nlu_rejected_use_llm")); out.complete(reply); }
                    else { settled.set(true); sink.log(entry("llm_first_wait_nlu_arrived")); out.complete(Reply.ofAction(i, intentToSpeak(i))); }
                } catch (Exception e) { settled.set(true); out.complete(reply); }
            } else { settled.set(true); sink.log(entry("llm_first_wait_timeout")); out.complete(reply); }
        }, nluGraceMs, TimeUnit.MILLISECONDS);
    });
    scheduler.schedule(() -> { if (settled.compareAndSet(false, true)) { sink.log(entry("safety_timeout")); out.complete(Reply.ofText("网络开小差了，请稍后再试")); } }, safetyTimeoutMs, TimeUnit.MILLISECONDS);
    return out;
}
```

（`intentToSpeak`：把车控 Intent 转成一句播报文本，如 set_temperature → "已为您把空调调到24度"，先按 domain/intent 写简单模板映射，后续 Task 11 中由网关按 ActionReply 的 speakText 统一处理——**Task 5 中把 `Reply.ofAction(intent, speakText)` 的 speakText 用模板生成**，模板映射表 `Map<String,String> DOMAIN_SPEECH` 放本类，未知 domain 返回 "已为您执行"。）

- [ ] **Step 4: 运行确认通过**

Run: `cd AutoVoiceServer && ./gradlew :arbitration:test`
Expected: 5 个测试全 PASS

- [ ] **Step 5: Commit**

```bash
git add AutoVoiceServer/arbitration
git commit -m "feat: cloud race arbiter with grace-period convergence and decision log"
```

### Task 6: session 模块——会话状态

**Files:**
- Create: `AutoVoiceServer/session/src/main/java/com/autovoice/server/session/SessionRegistry.java`
- Test: `AutoVoiceServer/session/src/test/java/com/autovoice/server/session/SessionRegistryTest.java`

**Interfaces:**
- Consumes: Task 4 `SessionContext`
- Produces: `SessionRegistry`：`SessionContext create(String language)` / `SessionContext get(String sessionId)` / `void remove(String sessionId)`——ConcurrentHashMap，最大容量 1000 自动淘汰最旧（LRU 简化：容量满时移除第一个插入项）

- [ ] **Step 1: 写失败测试**——create/get/remove/容量淘汰（插入 1002 个后最旧被移除，总数 1000）
- [ ] **Step 2: 运行确认失败**
- [ ] **Step 3: 实现**——`ConcurrentHashMap` + `LinkedHashMap` 容量守卫（或 `ConcurrentHashMap` + 计数 + 超限时迭代移除最早项；demo 规模足够）
- [ ] **Step 4: 运行确认通过**
- [ ] **Step 5: Commit**

```bash
git add AutoVoiceServer/session
git commit -m "feat: session registry with LRU eviction"
```

### Task 7: nlu-traditional 模块——IflytekNluProvider（讯飞语义 API + 归一化）

**Files:**
- Create: `AutoVoiceServer/nlu-traditional/src/main/java/com/autovoice/server/nlutraditional/IflytekNluProvider.java`、`IflytekSemanticNormalizer.java`
- Test: `AutoVoiceServer/nlu-traditional/src/test/java/com/autovoice/server/nlutraditional/IflytekSemanticNormalizerTest.java`、`IflytekNluProviderTest.java`

**Interfaces:**
- Consumes: Task 4（`Intent`/`NluProvider`/`SessionContext`）、`shared/fixtures/iflytek-semantic-ac.json`、环境变量 `XFYUN_APPID`/`XFYUN_API_KEY`
- Produces:
  - `IflytekSemanticNormalizer`：`Intent normalize(String vendorJson, String source)`——纯函数，供应商语义 → Canonical Intent；拒识（无 service/intent 或 code!=0）→ `Intent.unknown(source)`
  - `IflytekNluProvider implements NluProvider`：构造 `(OkHttpClient client, String appid, String apiKey, String endpoint)`；`understand()` 发 HTTP 后调 normalizer

**归一化映射表（demo 版，fixture 驱动）**：service="空调" → domain=climate；intent 映射：{"调节温度"→set_temperature, "开启"→power_on, "关闭"→power_off}；槽位：{温度→temperature(number), 对象→zone(enum: 主驾→driver/副驾→passenger/全车→all)}；未知 service/intent → unknown。**映射表集中在一个 `Map<String, DomainMapping>` 常量里，新增意图只加表项。**

- [ ] **Step 1: 写失败测试（normalizer，用 shared fixture）**

```java
// nlu-traditional/src/test/java/.../IflytekSemanticNormalizerTest.java
class IflytekSemanticNormalizerTest {
    final IflytekSemanticNormalizer n = new IflytekSemanticNormalizer();

    @Test void mapsClimateIntent() throws Exception {
        String vendor = new String(javaClass.classLoader.getResourceAsStream("iflytek-semantic-ac.json").readAllBytes(), UTF_8);
        Intent i = n.normalize(vendor, "nlu.iflytek.api");
        assertEquals("climate", i.domain());
        assertEquals("set_temperature", i.intent());
        assertEquals(24.0, (double) i.slots().get("temperature").value(), 0.001);
        assertEquals("driver", i.slots().get("zone").value());
        assertEquals("nlu.iflytek.api", i.source());
    }

    @Test void unknownWhenNoService() {
        Intent i = n.normalize("{\"code\":\"0\",\"data\":{\"result\":{\"intent\":{\"answer\":\"抱歉\"}}}}", "nlu.iflytek.api");
        assertTrue(i.isUnknown());
    }

    @Test void unknownWhenErrorCode() {
        Intent i = n.normalize("{\"code\":\"10110\"}", "nlu.iflytek.api");
        assertTrue(i.isUnknown());
    }
}
```

- [ ] **Step 2: 运行确认失败**
- [ ] **Step 3: 实现 normalizer**——解析 `data.result.intent.{service,intent,slots[]}`（用 Jackson `JsonNode`），按映射表转 Canonical；解析失败/异常一律返回 `Intent.unknown(source)`（归一化永不抛异常——这是适配器边界的铁律）
- [ ] **Step 4: 运行确认通过**
- [ ] **Step 5: Provider 测试（MockWebServer 模拟讯飞接口 + 签名校验）**——`IflytekNluProviderTest`：MockWebServer 返回 fixture 响应，断言请求头含 `x-appid` 与签名头、请求体为 JSON、`understand()` 返回 canonical Intent；错误码响应 → unknown
- [ ] **Step 6: 实现 Provider**——HTTP 请求按讯飞开放平台"语义理解"webapi 文档（POST + `x-appid`/`x-ca-key`/`x-ca-signature-headers` 头 + HMAC-SHA256 或文档指定签名算法；**端点与签名字段以实施时讯飞控制台文档为准，provider 内常量集中定义便于修改**）；`endpoint` 默认 `https://api.xfyun.cn/v1/aiui/v1/intent`（实施时核对）
- [ ] **Step 7: 运行确认通过**（MockWebServer 测试不需真实密钥）
- [ ] **Step 8: Commit**

```bash
git add AutoVoiceServer/nlu-traditional
git commit -m "feat: iflytek semantic nlu provider with canonical normalization"
```

### Task 8: llm 模块——DeepSeekLlmProvider

**Files:**
- Create: `AutoVoiceServer/llm/src/main/java/com/autovoice/server/llm/DeepSeekLlmProvider.java`
- Test: `AutoVoiceServer/llm/src/test/java/com/autovoice/server/llm/DeepSeekLlmProviderTest.java`

**Interfaces:**
- Consumes: Task 4（`Reply`/`LlmProvider`/`SessionContext`）、`shared/fixtures/deepseek-llm-reply.json`、环境变量 `DEEPSEEK_API_KEY`
- Produces: `DeepSeekLlmProvider implements LlmProvider`：构造 `(OkHttpClient, String apiKey, String endpoint)`，`chat()` → POST `https://api.deepseek.com/chat/completions`，system prompt："你是车载语音助手，回答简短口语化，不超过两句话。"，user=文本；解析 `choices[0].message.content` → `Reply.ofText(...)`

- [ ] **Step 1: 写失败测试**——MockWebServer 返回 fixture，断言请求体（model=deepseek-chat、Authorization Bearer、system+user 消息）与返回值
- [ ] **Step 2: 运行确认失败**
- [ ] **Step 3: 实现**（OkHttp 同步 + 超时 10s；HTTP 非 2xx → 抛 LlmException 由仲裁 safety 兜底）
- [ ] **Step 4: 运行确认通过**
- [ ] **Step 5: Commit**

```bash
git add AutoVoiceServer/llm
git commit -m "feat: deepseek llm provider (openai-compatible chat)"
```

### Task 9: asr-gateway + tts-gateway——阿里云一句话识别 + sambert TTS

**Files:**
- Create: `AutoVoiceServer/asr-gateway/src/main/java/com/autovoice/server/asrgateway/AliyunAsrProvider.java`、`AliyunTokenClient.java`
- Create: `AutoVoiceServer/tts-gateway/src/main/java/com/autovoice/server/ttsgateway/AliyunTtsProvider.java`
- Test: `AutoVoiceServer/asr-gateway/src/test/java/com/autovoice/server/asrgateway/AliyunAsrProviderTest.java`、`AutoVoiceServer/tts-gateway/src/test/java/com/autovoice/server/ttsgateway/AliyunTtsProviderTest.java`

**Interfaces:**
- Consumes: Task 4（`Reply`/`AsrProvider`/`TtsProvider`）、fixtures、环境变量 `ALIYUN_AK`/`ALIYUN_SK`/`ALIYUN_NLS_APPKEY`/`DASHSCOPE_API_KEY`
- Produces:
  - `AliyunTokenClient`：`String token()`——GET `https://nls-meta-cn-shanghai.aliyuncs.com/api/v1/ws/token`（AK/SK 签名，实施时按阿里云文档签名规则；token 缓存到 ExpireTime-60s）
  - `AliyunAsrProvider implements AsrProvider`：`transcribe(byte[] pcm16k, ctx)` → POST `https://nls-gateway-cn-shanghai.aliyuncs.com/stream/v1/asr`，query：`appkey/format=pcm/sample_rate=16000/enable_punctuation_prediction=true`，header：`X-NLS-Token` + `Content-Type: audio/L16;rate=16000;channels=1`，body=PCM；返回 JSON 取 `result` 字段
  - `AliyunTtsProvider implements TtsProvider`：`synthesize(text, ctx)` → POST `https://dashscope.aliyuncs.com/api/v1/services/aigc/text2audio/sambert-zhichu-v1`，header `Authorization: Bearer <DASHSCOPE_API_KEY>`，body `{"text":...,"format":"wav","sample_rate":16000}` → `Reply.ofAudio("audio/wav", bytes)`；非 200 → 抛异常

- [ ] **Step 1: 写失败测试（两个 provider，MockWebServer）**

```java
// asr-gateway/.../AliyunAsrProviderTest.java
class AliyunAsrProviderTest {
    @Test void transcribes() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("""
                {"status":20000000,"result":"空调调到二十四度"}""").setHeader("Content-Type","application/json"));
            AliyunAsrProvider p = new AliyunAsrProvider(new OkHttpClient(), "APPKEY", server.url("/").toString(), () -> "TOKEN");
            String text = p.transcribe(new byte[]{0,0,0,0}, new SessionContext("s","zh-CN",Map.of()));
            assertEquals("空调调到二十四度", text);
        }
    }
}
```

（`AliyunTtsProviderTest` 同理：MockWebServer 返回 wav 字节 + 200，断言 `Reply.kind()=="audio"` 且 mime=audio/wav。）

- [ ] **Step 2: 运行确认失败**
- [ ] **Step 3: 实现两个 provider + token client**——token 签名逻辑独立成 `AliyunTokenClient` 并允许注入（测试传 lambda），真实签名实现按阿里云文档（HMAC-SHA1 的 `Signature=...` 算法）写，实施时与文档核对；ASR/TTS 端点与参数如上，均为 demo 简化声明（Global Constraints 1/2）
- [ ] **Step 4: 运行确认通过**
- [ ] **Step 5: Commit**

```bash
git add AutoVoiceServer/asr-gateway AutoVoiceServer/tts-gateway
git commit -m "feat: aliyun one-shot asr and sambert tts providers"
```

### Task 10: gateway 模块——WS 协议编解码 + 流水线装配

**Files:**
- Create: `AutoVoiceServer/gateway/src/main/java/com/autovoice/server/gateway/GatewayCodec.java`、`VoiceGatewayHandler.java`、`SegmentPipeline.java`
- Test: `AutoVoiceServer/gateway/src/test/java/com/autovoice/server/gateway/GatewayCodecTest.java`

**Interfaces:**
- Consumes: Task 4-9（消息类、RaceArbiter、SessionRegistry、四个 Provider）
- Produces:
  - `GatewayCodec`：`Map<String,Object> decode(String json)` 校验 type 字段；`String encode(String type, Object payload)`
  - `SegmentPipeline`：构造 `(AsrProvider asr, RaceArbiter arbiter, NluProvider nlu, LlmProvider llm, TtsProvider tts, DecisionSink sink)`；`SegmentResult handleSegment(byte[] pcm, SessionContext ctx, String utteranceId)`——`SegmentResult` 为 record `(byte[] wavAudio, String speakText, Intent intent)`：`text=asr.transcribe(pcm)` → `arbiter.decide(text,nlu,llm,ctx)` → 依 reply kind 取播报文本（action 取 `reply.speakText()`，text 取 `reply.text()`）→ `tts.synthesize(speakText)` 合成音频；**网关下行恒为 kind=audio 的 reply 消息**，payload 携带 `mime`/`dataBase64`/`speakText`/`intent`（intent 为 null 时省略字段）；音频超 64KB base64 一次消息下发
  - `VoiceGatewayHandler implements WebSocketHandler`：维护每连接会话；`hello` → 回 `ready`；`audio_start` → 记录 utteranceId 并开始累积；二进制帧 → 累积 PCM；`audio_end` → 同步执行 `SegmentPipeline.handleSegment` → 回 `reply` + 逐条下发 arbiter 的 `decision` 事件

- [ ] **Step 1: 写失败测试（codec 与 pipeline 用 fake providers）**——codec：合法/非法消息解析；pipeline：fake AsrProvider 返回固定文本 + fake NluProvider（非拒识）+ fake TtsProvider（返回固定 wav）→ 断言返回 AudioReply 且含 intent；arbiter 日志通过注入的 DecisionSink 收集断言
- [ ] **Step 2: 运行确认失败**
- [ ] **Step 3: 实现**——Codec 用 Jackson `ObjectNode` 轻量校验；Handler 每连接一个 `SegmentPipeline` 实例（demo 单线程同步处理段，吞吐不是目标）
- [ ] **Step 4: 运行确认通过**
- [ ] **Step 5: Commit**

```bash
git add AutoVoiceServer/gateway
git commit -m "feat: ws gateway codec and segment pipeline (asr -> arbiter -> tts)"
```

### Task 11: app 模块——Spring Boot 装配 + 配置 + 端到端测试

**Files:**
- Create: `AutoVoiceServer/app/src/main/java/com/autovoice/server/app/AutoVoiceServerApplication.java`、`AppConfig.java`（@ConfigurationProperties + Bean 装配）
- Create: `AutoVoiceServer/app/src/main/resources/application.yml`、`application-demo-full.yml`
- Test: `AutoVoiceServer/app/src/test/java/com/autovoice/server/app/EndToEndGatewayTest.java`

**Interfaces:**
- Consumes: Task 4-10
- Produces: 可运行服务 `./gradlew :app:bootRun --args='--spring.profiles.active=demo-full'`；端口 8080，WS 端点 `/ws`（离线裁剪由端侧配置控制——Task 21 的 `demo-offline.json` 只关端侧云端链路，云端服务本身不需要离线 profile）

**配置（application-demo-full.yml）：**

```yaml
server:
  port: 8080
autovoice:
  arbitration:
    nlu-grace-ms: 1500
    safety-timeout-ms: 6500
  providers:
    nlu: iflytek        # iflytek | fake
    llm: deepseek
    asr: aliyun
    tts: aliyun
  secrets:
    xfyun-appid: ${XFYUN_APPID:}
    xfyun-api-key: ${XFYUN_API_KEY:}
    deepseek-api-key: ${DEEPSEEK_API_KEY:}
    aliyun-ak: ${ALIYUN_AK:}
    aliyun-sk: ${ALIYUN_SK:}
    aliyun-nls-appkey: ${ALIYUN_NLS_APPKEY:}
    dashscope-api-key: ${DASHSCOPE_API_KEY:}
```

- [ ] **Step 1: 写端到端失败测试**——启动完整 Spring 上下文（`@SpringBootTest`），但 provider 用 `@MockBean`/测试配置替换为 fake（asr→固定文本"空调调到二十四度"、nlu→固定 canonical 意图、llm→固定文本、tts→固定 wav 字节）；用 OkHttp WebSocket 客户端连 `ws://localhost:${port}/ws`：发 hello→收 ready；发 audio_start→发 PCM 二进制→发 audio_end→断言收到 `reply`（kind=audio + speakText 字段）+ 至少一条 `decision` 事件；断言 `reply.payload.intent.domain=="climate"`
- [ ] **Step 2: 运行确认失败**
- [ ] **Step 3: 实现装配**——`AppConfig` 按 `providers.*` 选择实现：iflytek → 从 secrets 装配 `IflytekNluProvider`；`fake` → `FakeNluProvider`（本类内嵌：文本含"空调"→climate/set_temperature，否则 unknown——**fake 只在配置指定时装配，默认 iflytek**）；DeepSeek/阿里云同理
- [ ] **Step 4: 运行确认通过**
- [ ] **Step 5: Commit**

```bash
git add AutoVoiceServer/app
git commit -m "feat: spring boot app wiring with profile configs and e2e gateway test"
```

---

# Phase C：端侧核心

### Task 12: voice-core——消息模型 + Stage SPI + 装配器

**Files:**
- Create: `AutoVoice/voice-core/src/main/kotlin/com/autovoice/voicecore/*.kt`（`Messages.kt`、`Stages.kt`、`PipelineFactory.kt`、`Config.kt`）
- Test: `AutoVoice/voice-core/src/test/kotlin/com/autovoice/voicecore/MessagesTest.kt`、`ConfigTest.kt`

**Interfaces:**
- Consumes: Task 2/3（shared schema、fixtures）
- Produces（后续任务依赖的精确签名）:
  - `data class Intent(schemaVersion, domain, intent, slots: Map<String, SlotValue>, confidence, source, rawSemantic: String?)` + `isUnknown()` + Gson 序列化与 shared schema 对齐
  - `sealed class Reply`：`TextReply(text)` / `AudioReply(mime, data: ByteArray, speakText: String = "", intent: Intent? = null)` / `ActionReply(intent, speakText)` + `kind: String`——网关下发的音频回复统一 `AudioReply`（云端 TTS 后 kind 恒为 audio，intent 存在时附带，供执行器消费）
  - `data class DecisionEntry(arbiter, route, reason, utteranceId, timestampMs)`
  - `data class GatewayMessage(type, payload: JsonObject)`
  - `data class DemoConfig(mode, vad, ecnr, local, cloud, mock)`（Gson 解析 `assets/demo-full.json`；`cloud.waitMs: Long`、`cloud.enabled: Boolean`）
  - `interface Stage<IN, OUT> { val name: String; fun configure(config: JsonObject); fun Flow<IN>.transform(): Flow<OUT>; suspend fun start(); suspend fun stop() }`
  - `object PipelineFactory`：`fun buildLocalChain(cfg): List<Stage<*,*>>`——demo 直接装配固定拓扑（不实现通用 DAG 引擎，见 Global Constraints 5）

- [ ] **Step 1: 写失败测试（消息模型 + 配置解析）**

```kotlin
// voice-core/src/test/kotlin/com/autovoice/voicecore/MessagesTest.kt
class MessagesTest {
    private val gson = Gson()
    @Test fun `intent gson round trip matches shared schema`() {
        val i = Intent("1.0", "climate", "set_temperature",
            mapOf("temperature" to SlotValue.Number(24.0), "zone" to SlotValue.EnumValue("driver")), 0.95, "nlu.iflytek.api", null)
        val json = gson.toJson(i)
        // shared fixture 形状校验：字段名一致
        val shared = javaClass.classLoader.getResource("gateway-reply-action.json")!!.readText()
        assertTrue(shared.contains("\"domain\": \"climate\""))
        assertTrue(json.contains("\"schemaVersion\":\"1.0\""))
        assertTrue(json.contains("\"intent\":\"set_temperature\""))
    }
    @Test fun `unknown intent flag`() {
        assertTrue(Intent.unknown("test").isUnknown())
    }
    @Test fun `reply kinds`() {
        assertEquals("text", TextReply("hi").kind)
        assertEquals("audio", AudioReply("audio/wav", byteArrayOf(1)).kind)
        assertEquals("action", ActionReply(Intent.unknown("t"), "好的").kind)
    }
}

// ConfigTest.kt
class ConfigTest {
    @Test fun `parses demo full config`() {
        val json = """
            {"mode":"full","vad":{"threshold":0.5,"minSpeechMs":64,"minSilenceMs":960},
             "ecnr":"rnnoise","local":{"asr":"iflytek.offline-cmd","nlu":"rule"},
             "cloud":{"enabled":true,"gatewayUrl":"ws://192.168.1.1:8080/ws","waitMs":2000},
             "mock":{"executor":true}}""".trimIndent()
        val cfg = DemoConfig.fromJson(json)
        assertTrue(cfg.cloud.enabled)
        assertEquals(2000, cfg.cloud.waitMs)
    }
    @Test fun `parses demo offline config`() {
        val json = """{"mode":"offline","vad":{"threshold":0.5,"minSpeechMs":64,"minSilenceMs":960},
             "ecnr":"rnnoise","local":{"asr":"iflytek.offline-cmd","nlu":"rule"},
             "cloud":{"enabled":false,"gatewayUrl":"","waitMs":2000},
             "mock":{"executor":true}}""".trimIndent()
        val cfg = DemoConfig.fromJson(json)
        assertFalse(cfg.cloud.enabled)
    }
}
```

- [ ] **Step 2: 运行确认失败**
- [ ] **Step 3: 实现**——数据类 + Gson 自定义序列化（`SlotValue` sealed：`Number(v: Double)/EnumValue(v: String)/StringValue(v: String)/Bool(v: Boolean)`，序列化输出 `{"type":...,"value":...}` 与 shared schema 对齐）；`Stage` 接口如 Interfaces 所示；`PipelineFactory.buildLocalChain` 返回占位空列表（Task 16/17 填实现）
- [ ] **Step 4: 运行确认通过**
- [ ] **Step 5: Commit**

```bash
git add AutoVoice/voice-core
git commit -m "feat: voice-core message model, stage spi, config parsing"
```

### Task 13: voice-core——端侧竞速仲裁 OnDeviceRaceArbiter

**Files:**
- Create: `AutoVoice/voice-core/src/main/kotlin/com/autovoice/voicecore/arbiter/OnDeviceRaceArbiter.kt`、`DecisionSink.kt`
- Test: `AutoVoice/voice-core/src/test/kotlin/com/autovoice/voicecore/arbiter/OnDeviceRaceArbiterTest.kt`

**Interfaces:**
- Consumes: Task 12（`Intent`/`Reply`/`DecisionEntry`）
- Produces:
  - `fun interface DecisionSink { fun onDecision(entry: DecisionEntry) }`
  - `sealed class RaceWinner`：`Local(intent: Intent)` / `Cloud(reply: Reply)` / `Failed`
  - `class OnDeviceRaceArbiter(cloudWaitMs: Long = 2000, localFallbackMs: Long = 10_000, clock: () -> Long = System::currentTimeMillis, sink: DecisionSink)`：`suspend fun race(cloud: Deferred<Reply>, local: Deferred<Intent>): RaceWinner`
  - 规则（spec §5.1）：`withTimeoutOrNull(cloudWaitMs) { cloud.await() }` 非空 → `Cloud`（reason=`cloud_won`）；超时 → `withTimeoutOrNull(localFallbackMs) { local.await() }` 非空 → `Local`（reason=`cloud_timeout_use_local`）；两者皆空 → `Failed`（reason=`both_failed`）

- [ ] **Step 1: 写失败测试**

```kotlin
// voice-core/src/test/kotlin/com/autovoice/voicecore/arbiter/OnDeviceRaceArbiterTest.kt
class OnDeviceRaceArbiterTest {
    private val entries = mutableListOf<DecisionEntry>()
    private val sink = DecisionSink { entries.add(it) }

    @Test fun `cloud first wins`() = runBlocking {
        val arbiter = OnDeviceRaceArbiter(cloudWaitMs = 2000, sink = sink)
        val cloud = CompletableDeferred<Reply>().also { it.complete(TextReply("hi")) }
        val local = CompletableDeferred<Intent>().also { it.complete(Intent.unknown("t")) }
        val w = arbiter.race(cloud, local)
        assertTrue(w is RaceWinner.Cloud)
        assertEquals("cloud_won", entries.last().reason)
    }

    @Test fun `cloud timeout falls back to local`() = runBlocking {
        val arbiter = OnDeviceRaceArbiter(cloudWaitMs = 100, sink = sink)
        val cloud = CompletableDeferred<Reply>() // 永不完成
        val local = CompletableDeferred<Intent>().also { it.complete(Intent.unknown("t")) }
        val w = arbiter.race(cloud, local)
        assertTrue(w is RaceWinner.Local)
        assertEquals("cloud_timeout_use_local", entries.last().reason)
    }

    @Test fun `cloud arrives within window even if late local`() = runBlocking {
        val arbiter = OnDeviceRaceArbiter(cloudWaitMs = 500, sink = sink)
        val cloud = async { delay(50); TextReply("hi") }
        val local = CompletableDeferred<Intent>()
        val w = arbiter.race(cloud, local)
        assertTrue(w is RaceWinner.Cloud)
    }

    @Test fun `local never completes but cloud times out`() = runBlocking {
        val arbiter = OnDeviceRaceArbiter(cloudWaitMs = 100, localFallbackMs = 200, sink = sink)
        val cloud = CompletableDeferred<Reply>()
        val local = CompletableDeferred<Intent>()
        val w = arbiter.race(cloud, local)
        assertTrue(w is RaceWinner.Failed)
        assertEquals("both_failed", entries.last().reason)
    }
}
```

- [ ] **Step 2: 运行确认失败**
- [ ] **Step 3: 实现**——按 Interfaces 规则；本地 10s 兜底用 `withTimeoutOrNull(10_000) { local.await() } ?: throw TimeoutCancellationException(...)`（或返回 `RaceWinner.Failed`——**选 Failed：`sealed class RaceWinner { Local; Cloud; Failed }`**，测试 4 改为断言 `RaceWinner.Failed`，避免抛异常路径污染），决策日志：cloud 赢 → `DecisionEntry("on-device","cloud","cloud_won",...)`；本地赢 → `("on-device","local","cloud_timeout_use_local",...)`；Failed → `("on-device","local","both_failed",...)`
- [ ] **Step 4: 运行确认通过**
- [ ] **Step 5: Commit**

```bash
git add AutoVoice/voice-core
git commit -m "feat: on-device race arbiter with cloud-first timeout fallback"
```

### Task 14: voice-core——会话状态机 + 语音控制器 VoiceSession

**Files:**
- Create: `AutoVoice/voice-core/src/main/kotlin/com/autovoice/voicecore/session/VoiceSession.kt`、`SessionState.kt`
- Test: `AutoVoice/voice-core/src/test/kotlin/com/autovoice/voicecore/session/VoiceSessionTest.kt`

**Interfaces:**
- Consumes: Task 12/13（`Stage`、`RaceWinner`、`OnDeviceRaceArbiter`、`DemoConfig`）
- Produces:
  - `enum class SessionState { IDLE, LISTENING, UNDERSTANDING, EXECUTING, SPEAKING }`
  - `class VoiceSession(cfg: DemoConfig, arbiter: OnDeviceRaceArbiter, sink: DecisionSink)`：`fun onVadSegment(segment: ByteArray)`（LISTENING → UNDERSTANDING；启动本地链 + 云端链并发；收敛后 → EXECUTING/SPEAKING → IDLE）；`fun onCloudUnavailable()`（spec §5.1 可达性：`cloud.enabled=false` 或断网 → 只跑本地链，日志 `cloud_unreachable`）；`state: StateFlow<SessionState>`；`onState(listener)`
  - 路由注入：`fun interface LocalChainRunner { suspend fun run(segment: ByteArray): Intent }`、`fun interface CloudRunner { suspend fun run(segment: ByteArray): Reply }`——`VoiceSession` 不直接依赖适配器（Task 16/17/20 注入实现）

- [ ] **Step 1: 写失败测试**——fake Stage（本地链直接返回固定 Intent 的假 `Stage<*,*>` + 注入假 Deferred）：cloud 可达 → 收敛走 Cloud；`onCloudUnavailable()` 后 → 只跑本地；状态流转断言（LISTENING→UNDERSTANDING→EXECUTING→IDLE 的事件序列）
- [ ] **Step 2: 运行确认失败**
- [ ] **Step 3: 实现**——状态机按 spec §7.1；本地链/云端链的启动由注入的 `LocalChainRunner` / `CloudRunner` 函数接口提供（`VoiceSession` 不直接依赖适配器，Task 16/17 注入实现）
- [ ] **Step 4: 运行确认通过**
- [ ] **Step 5: Commit**

```bash
git add AutoVoice/voice-core
git commit -m "feat: voice session state machine with local/cloud route orchestration"
```

### Task 15: gateway-client——AutoVoiceServer WS 客户端

**Files:**
- Create: `AutoVoice/gateway-client/src/main/kotlin/com/autovoice/gatewayclient/GatewayClient.kt`、`GatewayListener.kt`
- Test: `AutoVoice/gateway-client/src/test/kotlin/com/autovoice/gatewayclient/GatewayClientTest.kt`

**Interfaces:**
- Consumes: Task 12（`GatewayMessage`/`Reply`/`DecisionEntry`）
- Produces:
  - `class GatewayClient(url: String, okHttp: OkHttpClient)`：`suspend fun connect()` / `disconnect()`；`fun sendAudioStart(segmentId: String)`（文本帧）；`fun sendAudioChunk(pcm: ByteArray)`（二进制帧）；`fun sendAudioEnd(segmentId: String)`；`val messages: SharedFlow<GatewayMessage>`（ready/decision/asr_partial/reply/error/bye 事件流）
  - `fun parseReply(payload: JsonObject): Reply?`——kind=audio → `AudioReply(mime, base64解码, speakText, intent)`；kind=text → `TextReply`；kind=action → `ActionReply`
  - 断线重连：`connect()` 内自动重试（指数退避 1s/2s/4s，上限 3 次，仍失败抛 `GatewayException`）

- [ ] **Step 1: 写失败测试**——MockWebServer 扮演网关：接受 hello 回 ready；收 audio_start/二进制/audio_end 后回 reply(action)；断言 `messages` 流事件顺序与 `parseReply` 解析正确（含 base64 音频往返）
- [ ] **Step 2: 运行确认失败**
- [ ] **Step 3: 实现**——OkHttp WebSocket + coroutine Channel/Flow 桥接；二进制帧直接 `send(ByteString)`；`parseReply` 用 Gson 解析 payload
- [ ] **Step 4: 运行确认通过**
- [ ] **Step 5: Commit**

```bash
git add AutoVoice/gateway-client
git commit -m "feat: gateway ws client with message flow and reconnection"
```

---

# Phase D：端侧适配器

### Task 16: adapter-local——VAD（Silero）+ ECNR（RNNoise）+ 能量兜底

**Files:**
- Create: `AutoVoice/adapter-local/src/main/java|kotlin/.../vad/VoiceActivityGate.kt`（纯 Kotlin，可测）、`SileroVad.kt`（onnxruntime）、`ecnr/RnnoiseProcessor.kt` + `src/main/cpp/rnnoise.*` + `CMakeLists.txt` + JNI
- Test: `AutoVoice/adapter-local/src/test/.../VoiceActivityGateTest.kt`、`RnnoiseChunkingTest.kt`

**Interfaces:**
- Consumes: Task 12 `Stage` 接口
- Produces:
  - `class VoiceActivityGate(threshold=0.5, minSpeechMs=64, minSilenceMs=960, sampleRate=16000)`：`fun feed(probability: Float): VadEvent?`（`SpeechStart`/`SpeechEnd`/`null`）；语音态机：连续 ≥2 帧（32ms/帧）概率>阈值 → SpeechStart；之后连续 <阈值 累计 ≥minSilenceMs → SpeechEnd；**纯状态机，输入注入概率，host 可测**
  - `class SileroVad(context, modelAsset)`：`fun feed(pcm16k: ByteArray): Float`——onnxruntime 推理输出概率；模型 `silero_vad.onnx` 放 `app/src/main/assets/`（从 snakers4/silero-vad 仓库下载，约 2MB）
  - `class RnnoiseProcessor`：`fun process(frame480: ShortArray): ShortArray`——JNI 包装 xiph/rnnoise（`rnnoise_create`/`process_frame`/`rnnoise_destroy`，16k 480 帧）；`fun chunk(pcm: ShortArray): List<ShortArray>`（纯 Kotlin，可测）

- [ ] **Step 1: 写失败测试（状态机 + 分块，纯 JVM）**

```kotlin
// adapter-local/src/test/kotlin/.../VoiceActivityGateTest.kt
class VoiceActivityGateTest {
    @Test fun `speech start after two hot frames`() {
        val g = VoiceActivityGate(threshold = 0.5f, minSpeechMs = 64, minSilenceMs = 960)
        assertEquals(null, g.feed(0.9f))
        assertEquals(VadEvent.SpeechStart, g.feed(0.9f))
    }
    @Test fun `speech end after min silence`() {
        val g = VoiceActivityGate(threshold = 0.5f, minSpeechMs = 64, minSilenceMs = 960)
        g.feed(0.9f); g.feed(0.9f) // SpeechStart
        repeat(29) { assertNull(g.feed(0.1f)) }   // 29*32ms=928ms < 960ms
        assertEquals(VadEvent.SpeechEnd, g.feed(0.1f)) // 30*32ms=960ms
    }
    @Test fun `no speech for quiet frames`() {
        val g = VoiceActivityGate(threshold = 0.5f, minSpeechMs = 64, minSilenceMs = 960)
        repeat(100) { assertNull(g.feed(0.1f)) }
    }
}
```

- [ ] **Step 2: 运行确认失败**
- [ ] **Step 3: 实现状态机 + 分块逻辑**
- [ ] **Step 4: 运行确认通过**
- [ ] **Step 5: RNNoise JNI**——复制 xiph/rnnoise 源码（`rnnoise/` 目录，含 `rnnoise_data.c`）到 `src/main/cpp/`，写 `CMakeLists.txt`（`externalNativeBuild`）+ JNI 桥 `com_autovoice_adapterlocal_ecnr_RnnoiseNative.cpp`；`RnnoiseProcessor` 调 JNI；`RnnoiseChunkingTest` 验证 480 帧分块（不足 480 尾帧丢弃——demo 可接受，段尾丢 <30ms）
- [ ] **Step 6: 构建验证**

Run: `cd AutoVoice && ./gradlew :adapter-local:assembleDebug`
Expected: BUILD SUCCESSFUL（JNI so 编译通过）

- [ ] **Step 7: Commit**

```bash
git add AutoVoice/adapter-local
git commit -m "feat: local vad (silero+gate), rnnoise ecnr with jni"
```

### Task 17: adapter-iflytek——离线命令词 ASR 接入 + 规则语义映射（RuleNluProvider）

**Files:**
- Create: `AutoVoice/adapter-iflytek/src/main/kotlin/com/autovoice/adapteriflytek/RuleNluProvider.kt`（纯 Kotlin：命令词→Canonical Intent 规则映射）、`IflytekOfflineCommandAsrStage.kt`（讯飞离线命令词识别）、`FakeCommandAsrProvider.kt`
- Test: `AutoVoice/adapter-iflytek/src/test/kotlin/.../RuleNluProviderTest.kt`、`FakeCommandAsrProviderTest.kt`
- Create: `AutoVoice/adapter-iflytek/libs/`（讯飞 MSC 离线命令词 SDK：jar/aar + so + 授权文件；**SDK 文件不提交 git，加入 .gitignore，实施时从讯飞开放平台下载**）

**Interfaces:**
- Consumes: Task 12（`Intent`/`Stage`）、讯飞开放平台账号（appid + 离线命令词体验版授权）
- Produces:
  - `object RuleNluProvider`：`fun understand(command: String): Intent`——规则映射表（常量 `Map`）：含"空调"→climate，含"车窗"→window；意图规则："打开"+空调→`power_on`、"关闭"+空调→`power_off`、含"调到/调至"→`set_temperature`（正则提取数字→`temperature` 槽位）；未命中 → `Intent.unknown("rule.nlu")`；**映射表集中定义，新增命令只加表项**
  - `FakeCommandAsrProvider`：`fun recognize(pcm: ByteArray): String`——模拟命令词识别：返回固定文本"打开空调"（或按内置微型能量检测返回 null）；**与真实 ASR 同一 Stage 边界，配置 `local.asr=iflytek.fake-cmd` 时使用**
  - `IflytekOfflineCommandAsrStage : Stage<AudioStream, TextResult>`——讯飞离线命令词识别引擎（词表 = 车控命令集合：打开空调/关闭空调/空调调到X度/打开车窗/关闭车窗…）；**若 SDK/授权未就绪，抛出明确错误"讯飞离线命令词 SDK 未配置，请切换 local.asr=iflytek.fake-cmd"**

- [ ] **Step 1: 写失败测试（RuleNluProvider + fake，host 可测）**

```kotlin
class RuleNluProviderTest {
    @Test fun `ac on command maps to climate power on`() {
        val i = RuleNluProvider.understand("打开空调")
        assertEquals("climate", i.domain)
        assertEquals("power_on", i.intent)
    }
    @Test fun `temperature command extracts slot`() {
        val i = RuleNluProvider.understand("空调调到24度")
        assertEquals("set_temperature", i.intent)
        assertEquals(24.0, (i.slots["temperature"] as SlotValue.Number).v, 0.001)
    }
    @Test fun `window command maps to window`() {
        assertEquals("window", RuleNluProvider.understand("打开车窗").domain)
    }
    @Test fun `unknown for out-of-scope`() {
        assertTrue(RuleNluProvider.understand("讲个笑话").isUnknown())
    }
}
```

- [ ] **Step 2: 运行确认失败**
- [ ] **Step 3: 实现 RuleNluProvider + FakeCommandAsrProvider**（规则表 + 正则提取；fake 返回固定文本）
- [ ] **Step 4: 运行确认通过**
- [ ] **Step 5: 真实讯飞离线命令词 SDK 接入**——开放平台控制台开通"离线命令词识别"体验版（3 装机量/35 天）→ 下载 MSC SDK + 授权 → `SpeechUtility.createUtility(context, "appid=...")` + 离线命令词引擎（`SpeechRecognizer`，`ENGINE_TYPE=local` 命令词模式，词表见 Interfaces）→ 输出命令词文本 → `RuleNluProvider.understand(command)` 映射为 Intent；**此步为"凭据就绪才做"的任务**：若周末拿不到体验版，`app/src/main/assets/demo-full.json` 的 `local.asr` 配 `iflytek.fake-cmd`，本步跳过并在 commit message 注明
- [ ] **Step 6: Commit**

```bash
git add AutoVoice/adapter-iflytek
git commit -m "feat: iflytek offline command-word asr with rule-based nlu and fake fallback"
```

---

# Phase E：App 与集成

### Task 18: app——录音与音频通道（AudioRecorder + TTS 播放）

**Files:**
- Create: `AutoVoice/app/src/main/kotlin/com/autovoice/app/audio/AudioRecorder.kt`、`TtsPlayer.kt`、`SystemTtsFallback.kt`
- Test: `AutoVoice/app/src/test/kotlin/.../AudioRecorderFormatTest.kt`（帧格式头解析等纯逻辑）

**Interfaces:**
- Consumes: Task 12/16（VAD gate、RNNoise）
- Produces:
  - `class AudioRecorder`：`start(sampleRate=16000)` / `stop()`；`Flow<ByteArray>` 输出 PCM 块（1024 字节/块）；权限 `RECORD_AUDIO` 由 Activity 处理；**VAD 装配在录音流上**：每 3200 字节（100ms）→ RNNoise 处理 → Silero 概率 → VoiceActivityGate → SpeechStart/SpeechEnd 事件
  - `TtsPlayer`：播放 `AudioReply`（MediaPlayer + 临时 wav 文件）；`SystemTtsFallback`：Android `TextToSpeech`（本地链路播报，离线可用）

- [ ] **Step 1: 写失败测试**（纯逻辑：PCM 块切分/格式断言）
- [ ] **Step 2: 运行确认失败**
- [ ] **Step 3: 实现**——`AudioRecorder` 用 `AudioRecord`（SOURCE_MIC, 16k, MONO, PCM_16BIT）；VAD 事件流经 `MutableSharedFlow`
- [ ] **Step 4: 构建验证**

Run: `cd AutoVoice && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add AutoVoice/app
git commit -m "feat: audio recorder with vad segmentation and tts players"
```

### Task 19: app——Compose UI（状态/决策日志/模拟车控/录音按钮）

**Files:**
- Create: `AutoVoice/app/src/main/kotlin/com/autovoice/app/MainActivity.kt`、`ui/VoiceScreen.kt`、`ui/DecisionLog.kt`、`ui/VehiclePanel.kt`
- Test: `AutoVoice/app/src/test/kotlin/.../MockVehicleStateTest.kt`（执行器状态机纯逻辑可测）

**Interfaces:**
- Consumes: Task 12-18
- Produces:
  - `MainViewModel`：持有 `VoiceSession` + `GatewayClient` + 录音器；`state: StateFlow<UiState>`（sessionState + 决策日志列表 + 车辆状态 + 录音按钮态 + 弱网模拟开关）
  - `MockVehicleState`：空调温度/开关 + 车窗开闭，`apply(intent)` 返回播报文本（"已为您把空调调到24度"）；`apply` 未知 intent → 保持现状
  - 决策日志 `LazyColumn`；车辆状态 `AssistChip` 面板；底部录音按钮（按住说话 + VAD 自动截止）；设置区：`demo-full/demo-offline` 切换 + "模拟弱网（云端延迟3s）"开关（debug hook，Task 20 接线）

- [ ] **Step 1: 写失败测试**（`MockVehicleStateTest`：apply set_temperature 24 → 温度=24 + 播报文本断言；apply unknown → 状态不变）
- [ ] **Step 2: 运行确认失败**
- [ ] **Step 3: 实现 MockVehicleState + ViewModel + UI**（Compose Material3；录音按钮长按录音、松开停止；VAD 自动结束后 UI 状态回到 IDLE）
- [ ] **Step 4: 构建验证**：`./gradlew :app:assembleDebug` BUILD SUCCESSFUL
- [ ] **Step 5: Commit**

```bash
git add AutoVoice/app
git commit -m "feat: compose voice demo ui with decision log and mock vehicle panel"
```

### Task 20: app——装配与接线（双链路竞速 + 播报 + 执行）

**Files:**
- Modify: `AutoVoice/app/src/main/kotlin/com/autovoice/app/MainViewModel.kt`、`VoiceEngine.kt`（新建：端侧全局装配点）
- Test: `AutoVoice/app/src/test/kotlin/.../VoiceEngineTest.kt`

**Interfaces:**
- Consumes: Task 12-19
- Produces: `VoiceEngine(cfg, context, networkAvailable: () -> Boolean)`：`onUtterance(pcm: ByteArray)` ——
  1. 网络检查：不可用或 `cfg.cloud.enabled=false` → 只跑本地链（reason `cloud_unreachable`）
  2. 本地链：VAD 段音频 → 讯飞离线命令词 ASR（或 fake-cmd）→ `RuleNluProvider.understand(command)` → Intent
  3. 云端链：`gateway.sendAudioStart → 分块发送 → sendAudioEnd` → 收 reply
  4. `OnDeviceRaceArbiter.race(cloud, local)` → Winner：Cloud → `TtsPlayer.play(audio)` + `MockVehicleState.apply(action.intent)`；Local → `SystemTtsFallback.speak(speakText)` + `apply(intent)`
  5. "模拟弱网"开关 → gateway-client 响应前人为 delay 3000ms（调试 hook，仅 debug 构建）

- [ ] **Step 1: 写失败测试**——注入 fake 本地链 + fake gateway（`VoiceEngine` 依赖接口注入，不直接 new GatewayClient）：cloud 先赢 → 断言播放/执行序列（AudioReply 携带 intent → MockVehicleState.apply）；`networkAvailable=false` → 只本地（reason `cloud_unreachable`）；弱网开关 → 本地赢（reason `cloud_timeout_use_local`）
- [ ] **Step 2: 运行确认失败**
- [ ] **Step 3: 实现 VoiceEngine 装配**——本地链赢：`MockVehicleState.apply(intent)` 返回播报文本 → `SystemTtsFallback.speak(...)`；云端链赢：`TtsPlayer.play(audio)` + 若 `AudioReply.intent != null` → `MockVehicleState.apply(intent)`
- [ ] **Step 4: 运行确认通过**
- [ ] **Step 5: 真机冒烟**——`adb install`，连开发机网关地址，说"空调调到二十四度"，观察决策日志 + 车辆面板变化（云端链路冒烟；本地链路在 Task 21 验证）
- [ ] **Step 6: Commit**

```bash
git add AutoVoice/app
git commit -m "feat: wire dual-route race engine with playback and execution"
```

### Task 21: 双配置模式 + 离线剧本验证

**Files:**
- Create: `AutoVoice/app/src/main/assets/demo-full.json`、`demo-offline.json`（按 Task 12 `DemoConfig` 形状）
- Modify: `AutoVoice/app/src/main/kotlin/com/autovoice/app/MainViewModel.kt`（配置切换）

**Interfaces:**
- Consumes: Task 12/20
- Produces: 两种可运行模式；`demo-offline.json`：`cloud.enabled=false`

- [ ] **Step 1: 写两份配置 asset 文件**（字段与 `DemoConfig` 一一对应；`local.nlu` 恒为 `rule`；`local.asr` 视讯飞 SDK 状态填 `iflytek.offline-cmd` 或 `iflytek.fake-cmd`）
- [ ] **Step 2: 真机验证剧本 1（断网本地兜底）**——手机开飞行模式 → 切 `demo-offline` 配置 → 长按说话"打开空调" → 期望：决策日志显示本地链路 reason、模拟车控"空调开启"、系统 TTS 播报
- [ ] **Step 3: 真机验证剧本 4（云端超时用本地）**——`demo-full` + 弱网开关 → "打开车窗" → 期望：决策日志 `cloud_timeout_use_local`、本地结果执行
- [ ] **Step 4: Commit**

```bash
git add AutoVoice/app/src/main/assets
git commit -m "feat: demo-full and demo-offline config assets"
```

### Task 22: 集成验收（四剧本）+ README + 最终提交

**Files:**
- Create: `AutoVoice/README.md`（或更新现有）、`docs/runbook.md`

**Interfaces:**
- Consumes: 全部
- Produces: 验收记录

- [ ] **Step 1: 写 runbook**（`docs/runbook.md`）——前置条件（密钥环境变量、讯飞账号、手机与开发机同一局域网、网关地址改配置）、启动步骤（云端 `./gradlew :app:bootRun --args='--spring.profiles.active=demo-full'`；端侧安装 app）、四个剧本的操作与期望（含替代测试句说明，见 Global Constraints 6）
- [ ] **Step 2: 剧本 2 验收**——在线 + `demo-full` + 语音"空调调到二十四度" → 期望：端侧决策日志 `cloud_won`、云端决策日志 `nlu_first`（或 `llm_first_wait_*`）、模拟车控温度 24、阿里云 TTS 播报
- [ ] **Step 3: 剧本 3 验收**——在线 + "明天上海天气怎么样"（或"讲个笑话"）→ 期望：云端决策日志 `nlu_rejected_use_llm`（或 LLM 分支）、TTS 播报 LLM 回答文本
- [ ] **Step 4: 剧本 1/4 复验**（Task 21 已过，此处记录结果）
- [ ] **Step 5: 更新 README**——项目结构、运行方式、验收结果表（四剧本 × 通过/未通过 + 备注）
- [ ] **Step 6: 最终提交**

```bash
git add -A
git commit -m "docs: acceptance runbook and demo results"
```

---

## Self-Review 记录（实施前检查项）

- [ ] Spec §3 统一网关 → Task 3/10/15/20
- [ ] Spec §4 消息模型/Stage SPI/归一化 → Task 4/7/12/17（云端讯飞语义归一化 fixture 测试 + 端侧规则映射测试，均收敛到 Canonical Intent）
- [ ] Spec §5 竞速仲裁（2000/1500/拒识/单赢家/决策日志）→ Task 5/13
- [ ] Spec §6 配置与裁剪（demo-full/demo-offline + 校验）→ Task 12/21（三层继承不在 demo 范围，Global Constraints 5）
- [ ] Spec §7 状态机 + 错误处理 → Task 14/20（兜底话术在 Task 5 safety timeout）
- [ ] Spec §8 双项目结构 + shared → Task 1/2
- [ ] Spec §9 范围与测试（归一化单测/仲裁收敛单测/契约测试/集成验证/四剧本）→ Task 5/7/13/17/22
- [ ] Spec §10 演进 → 本计划只覆盖里程碑 ①，其余为后续计划（spec 边界一致）
