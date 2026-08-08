# AutoVoice 语音引擎框架设计 —— 周末 Demo 里程碑

- 日期：2026-08-08
- 状态：已评审
- 关联文档：`实现方式.md`（语音链路形态与裁剪规则）

## 1. 背景与目标

车企语音项目的工程化需求：多车型、多硬件平台（8155/8295/8397）、多国家销售；ECNR/ASR/NLU/LLM 等环节采购不同供应商；工程化（框架、编排、仲裁、音频链路）自研。架构必须可扩展——`实现方式.md` 中的链路形态（端侧传统语音 / 云端传统语音 / 云端大模型 / MA / S2S / VLA）必须能被同一套框架承载。

**本里程碑目标**：架构先行，但用周末 demo 在第一个真实环境（Android 手机模拟车机 + 阿里云）端到端验证。demo 不是玩具——它验证的正是架构最不确定的部分：**竞速仲裁策略 + 端云统一网关 + 供应商可插拔**。

**Demo 成功标准**：三条链路全通 + 双仲裁工作 + 决策日志可见：

1. 端侧传统语音：讯飞离线 ASR + 讯飞离线 NLU
2. 云端传统语音：阿里云 Paraformer → 讯飞传统 NLU（供应商 API，适配器可插拔）
3. 云端大模型：阿里云 Paraformer → DeepSeek 对话

外加端侧仲裁（云端优先，2000ms 兜底本地）与云端仲裁（传统优先，LLM 兜底）都真实生效，验收 = 四个演示剧本跑通（见 §9）。

## 2. 范围

**本次 spec 范围内（周末 demo）**：

- 端侧 Android 工程：语音采集、ECNR、VAD、讯飞离线链路、端侧仲裁、mock 执行器、播报
- 云端 Java 工程：统一网关、云端仲裁、传统 NLU（讯飞语义理解 API，可插拔）、LLM（DeepSeek）、Paraformer/CosyVoice 代理
- 端云契约（shared）与配置矩阵的最小形态
- 单轮对话；中文（zh-CN）

**明确不做（接口留位，见 §7）**：多轮对话、barge-in 打断、唤醒词、完整车控领域模型、8155 车载音频 HAL、多市场（语言/法规/供应商）、生产部署与高可用。

## 3. 总体架构

### 3.1 端云统一网关（模式 B）

端侧只连接自研 AutoVoiceServer 一个口，由云端中间件代理全部供应商（ASR/TTS/LLM）。理由：

- 云端仲裁需要一手 ASR 文本做"传统 vs LLM"收敛，直连供应商等于绕路
- API key、配额、重试收在云端，车机零暴露，换供应商端侧无感
- 数据路径可控，满足合规审计（将来敏感词过滤、数据本地化都在网关做）
- 代价：多一跳延迟（几十 ms），1500ms 仲裁预算内可承受；TTS 音频流生产期可优化为"云端下发 URL 端侧直下"

### 3.2 端侧分层（Android 手机 = 车机占位）

```
应用层    语音 Demo App（会话 UI + 决策日志展示）
引擎内核  会话状态机 · 端侧仲裁器 · 执行分发器（自研，供应商无关）
SPI 层    Stage 接口：Ecnr/Vad/Asr/Nlu/Tts/Executor
适配器层  讯飞离线 ASR·NLU | RNNoise | Silero | 云端网关客户端 | mock 执行器
设备层    麦克风采集 · 音频播放 · 音频焦点（自研 HAL，8155 上替换为车载音频 HAL）
```

### 3.3 云端结构（AutoVoiceServer，Java/Spring Boot）

```
gateway/        车辆长连接网关（WebSocket，每车会话，音频流上下行）
arbitration/    云端仲裁（竞速收敛）
nlu-traditional/ 传统 NLU（讯飞语义理解 API，NluProvider 可插拔，归一化到 Canonical Intent）
llm/            LLM 对话（DeepSeek）
asr-gateway/    Paraformer 流式代理
tts-gateway/    CosyVoice 代理
session/        会话状态
```

## 4. 核心消息模型与 Stage SPI

### 4.1 统一消息模型

```
AudioStream    PCM 音频流（16k 单声道 S16LE 标准化，seq + isLast）
SpeechEvent    VAD 语音段边界（start/end）
TextResult     识别文本（partial/final + 置信度 + 语言）
Intent         Canonical 意图（domain/intent/slots/confidence/source）
Reply          Agent 回复，sealed 联合类型：
               ├─ TextReply   （纯文本）
               ├─ AudioReply  （TTS 音频 / 未来 S2S 直接输出音频）
               └─ ActionReply （车控动作指令 → 执行器）
ActionResult   执行器反馈（成功/失败 + 新状态）
```

Reply 必须是 sealed 联合类型：S2S 链路输出音频、LLM 输出文本/动作，统一承载才不破坏流水线——这是 MA/S2S/VLA 的扩展点。

### 4.2 Stage 接口

```kotlin
interface Stage<IN, OUT> {
    val name: String                    // 如 "asr.iflytek.offline"
    fun configure(config: StageConfig)  // 从配置矩阵注入参数
    fun Flow<IN>.transform(): Flow<OUT> // 流式变换，兼容流式 ASR 部分结果
    suspend fun start() / stop()
}
```

- 加供应商 = 实现一个 Stage + 配置矩阵加一行，内核零改动
- 链路裁剪 = 换配置不换代码
- 每个 Stage 可独立 mock、独立测试

### 4.3 语义归一化（Canonical Intent）

**原则：内部只认一套意图格式（OEM 自研），供应商格式只存在于适配器内部。**

```
讯飞离线NLU ─→ 讯飞语义JSON ─┐
讯飞语义API ─→ 讯飞语义JSON ─┤
                            ├─→ 适配器内归一化 →→ Canonical Intent
其他供应商  ─→ 各家语义格式 ─┘     (domain/intent/slots/confidence)
```

```json
{
  "schemaVersion": "1.0",
  "domain": "climate",
  "intent": "set_temperature",
  "slots": {
    "temperature": {"type": "number", "value": 24},
    "unit": {"type": "enum", "value": "celsius"},
    "zone": {"type": "enum", "value": "driver"}
  },
  "confidence": 0.92,
  "source": "asr.iflytek.offline",
  "rawSemantic": "{...}"
}
```

归一化规则：

1. 槽位类型化——供应商字符串槽位由适配器转 typed value（number/enum）
2. 枚举标准化——等价表述（"全车"=all）在适配器映射
3. 无法映射 → `Intent(unknown)` + 携带原文，交给仲裁器兜底
4. schema 版本化——`schemaVersion` 贯穿端云，新增领域只加版本

此原则推广到全链路：TTS 播报文本规范（数字/多音字/单位）同理在适配器内统一。Canonical Intent Schema 是自研领域模型资产（车控领域建模与供应商无关）。

## 5. 仲裁设计（竞速仲裁）

仲裁器 = **并发编排 + 收敛策略**，超时和优先级均为配置参数。每次仲裁产生决策日志（route + reason），供 debug 与 demo 展示。

### 5.1 端侧仲裁（云端优先，2000ms 兜底本地）

```
话语边界 → 并发启动：云端链路 + 端侧链路
云端结果先到 → 立即采用
本地结果先到 → 等待云端最多 cloudWaitMs（=2000，配置）
  ├─ 云端到达 → 用云端（本地丢弃）
  └─ 超时 → 用本地
唯一结果 → 执行｜播报
```

路由可达性检查：断网不启动云端链路；本地离线未授权不启动本地链路。

### 5.2 云端仲裁（传统优先，1500ms 兜底 LLM）

```
收到文本 → 并发启动：传统 NLU（讯飞语义理解 API）+ LLM 对话（DeepSeek）
传统先出结果（非拒识）→ 直接用传统
LLM 先出结果 → 等待传统最多 nluGraceMs（=1500，配置）
  ├─ 传统 1500ms 内到达 → 用传统（LLM 丢弃）
  ├─ 传统到达但拒识（Intent(unknown)）→ 用 LLM
  └─ 超时 → 用 LLM
唯一结果 → 下发端侧
```

### 5.3 通用规则

- **单赢家原则**：一轮话语只有一个结果能过执行器，输家丢弃并记日志（"cloud_timeout_use_local"、"llm_first_wait_nlu_arrived"、"nlu_rejected_use_llm"）
- **拒识**由 Canonical `Intent(unknown)` 显式表达
- 仲裁只在话语边界（VAD end）触发，不打断流式处理
- `ArbiterContext` 统一携带：文本、网络状态、本地可用性、市场配置、多轮状态（预留）、车辆状态快照
- 端云仲裁是同一套收敛策略模型的两个实例，将来策略可下放

## 6. 配置矩阵与裁剪规则

**裁剪规则 = 配置约束，不是代码。**

- 维度：平台（8155/8295/8397 → 算力档）、市场（中国/欧洲/… → 语言/法规）、档位（标配/高配）、特殊（出口法规款）
- 三层继承：`base.yaml` → `market/cn.yaml` → `platform/sa8155.yaml` → `model/xxx.yaml`，层叠覆盖
- 配置加载时校验（构建期报错，不在运行时炸）：

| 实现方式.md 规则 | 校验 |
|---|---|
| 云端大模型必须和云端语音组合 | `cloud.llm.enabled=true` → 强制 `cloud.asr.enabled=true` |
| 云端 MA/大模型/S2S 两两互斥 | 三者 enabled 之和 ≤ 1 |
| 端侧两个只能二选一 | 端侧传统语音与端侧大模型类互斥 |
| 法规只有端侧 | `market.regulation.cloudAllowed=false` → 云端全裁剪 |

- 叶子配置解析为：流水线 DAG（装配器实例化 Stage）+ 仲裁参数（超时/优先级/可达性门槛）

demo 两张配置：`demo-full.yaml`（三链路全开 + 竞速仲裁）、`demo-offline.yaml`（法规场景云端全裁剪，只跑讯飞离线）。

## 7. 会话状态机与错误处理

### 7.1 状态机

```
IDLE → LISTENING(录音+VAD) → UNDERSTANDING(竞速仲裁) → EXECUTING｜SPEAKING → IDLE
                               └──── 超时/全败 → 兜底话术 → IDLE
```

预留（本次不实现）：barge-in（SPEAKING 中 VAD start → 停止播报重听）；多轮上下文（ArbiterContext 携带上一轮 Intent/路由）。

### 7.2 错误处理（分类 + 每类一个降级动作，全部落决策日志）

| 失败场景 | 分类 | 降级动作 |
|---|---|---|
| 云端 ASR 断连/超时 | transient | 端侧仲裁感知 → 本地兜底（本地链路一直在跑） |
| 云端认证失效/配额耗尽 | permanent | 云端不可达 → 本轮直接本地 + 配额告警 |
| 云端中间件 5xx | transient | 重试 1 次 → 仍失败走本地兜底 |
| TTS 失败 | transient | 降级为屏幕显示文本（"tts_failed_show_text"） |
| VAD 误检（过短/噪声） | — | 过滤：最小段时长 + 能量门槛（配置） |
| 段超长无 VAD end | — | 强制切分上云 |
| 讯飞离线未授权/初始化失败 | permanent | 端侧仲裁可达性检查 → 不启动本地路由 |
| LLM 限流 | transient | 云端仲裁回退传统 NLU；全不可用 → 兜底话术 |

兜底话术"网络开小差了，请稍后再试"本身是一条 TTS 链路，不算特例。

## 8. 项目结构与技术选型

```
AutoVoice/            ← git 仓库根（monorepo）
├── AutoVoice/        端侧 Android 项目（Kotlin，Gradle multi-module）
│   ├── app/          UI + 决策日志展示
│   ├── voice-core/   纯逻辑库：SPI/消息模型/内核/端侧仲裁（可单测）
│   ├── adapter-iflytek/  讯飞离线（AAR 依赖）
│   ├── adapter-local/    RNNoise + Silero（so/JNI）
│   └── gateway-client/   AutoVoiceServer 客户端（WS/HTTP）
├── AutoVoiceServer/  云端 Java 项目（Spring Boot，Gradle multi-module）
│   ├── gateway/      车辆长连接网关（WebSocket，每车会话）
│   ├── arbitration/  云端仲裁（竞速收敛）
│   ├── nlu-traditional/  传统 NLU（讯飞语义 API，NluProvider 可插拔）
│   ├── llm/          LLM 对话（DeepSeek）
│   ├── asr-gateway/  Paraformer 流式代理
│   ├── tts-gateway/  CosyVoice 代理
│   └── session/      会话状态
├── shared/           端云契约：Intent Schema/消息格式/配置 schema（JSON Schema + 文档）
├── docs/             specs
└── README.md
```

- 端云各自实现仲裁（位置不同），收敛策略靠 shared 规则定义对齐，不强行共享代码
- shared 用 JSON Schema + 文档，两端各自生成/手写类；demo 阶段加契约测试（两端 mock 消息互认）
- monorepo 单仓库；将来拆库则 shared 升级为版本化发布

**供应商选型（demo）**：讯飞（端侧离线 SDK 走传统链路 + 云端语义理解 API 走云端传统链路，开放平台申请；两处语义结果统一在适配器内归一化）；阿里云 Paraformer/CosyVoice（用户已有阿里云服务）；DeepSeek API（仅云端大模型链路：LLM 对话，OpenAI 兼容）；RNNoise（ECNR 降噪）；Silero VAD。

**传统 NLU 可插拔**：`nlu-traditional` 模块内部定义 `NluProvider` 接口（输入文本 → 归一化 Intent），讯飞语义 API 只是第一个实现；换思必驰/其他供应商 = 新增一个 Provider 实现 + 配置切换，云端仲裁与下游零改动。

**云端语言**：Java（Spring Boot）。负载特征为每车一路长连接流式音频 + 低延迟仲裁，Java 21 虚拟线程 + Spring Boot 生态可满足；与端侧语言统一心智。

## 9. 周末 Demo 范围与测试

### 9.1 真做 vs 简化

| 组件 | 状态 |
|---|---|
| 录音、播放（Android） | 真做 |
| ECNR（RNNoise）/ VAD（Silero） | 真做 |
| 讯飞离线 ASR+NLU | 真做（试用授权） |
| AutoVoiceServer（网关/仲裁/NLU/LLM；传统 NLU=讯飞语义 API，LLM=DeepSeek） | 真做；demo 期跑在开发机，手机经局域网访问；阿里云部署是量产子项目 |
| Paraformer / CosyVoice 代理 | 真做（经云端网关） |
| 竞速仲裁（端云） | 真做，参数从配置读 |
| mock 执行器 | 简化：模拟空调/车窗状态，UI 显示 |
| 多轮 / 打断 / 唤醒词 / 完整配置矩阵 | 不做，接口留位 |

### 9.2 测试策略

1. **归一化层单测**——讯飞语义 JSON fixture（离线 SDK 与云端语义 API 两种格式）→ Canonical Intent 映射表测试；unknown 拒识映射测试
2. **仲裁收敛单测**——假 Stage + 可控延迟：云端先到即用、LLM 先到等 1500ms、2000ms 超时用本地、拒识走 LLM；时间可注入，不依赖真网络
3. **配置校验测试**——裁剪规则违反 → 构建期报错
4. **契约测试**——端云 mock 消息互认
5. **集成验证**——`demo-offline.yaml` 全本地可测；云端链路手动剧本验收

### 9.3 验收剧本（四个，每个结果确定、与 §5 竞速规则一致）

1. **断网本地兜底**：飞行模式（云端链路不可达）→ "打开空调" → 端侧仲裁云端不可达 → 讯飞离线链路 → mock 执行 + 播报。**验证端侧仲裁 + 端侧传统语音链路**
2. **在线车控走云端传统 NLU**：在线 → "空调调到24度" → 端侧竞速云端先到 → 云端仲裁 → 讯飞语义 API → mock 执行 + TTS 播报。**验证云端仲裁传统分支 + 云端传统语音链路**
3. **闲聊拒识走 LLM**：在线 → "明天上海天气怎么样" → 端侧上云 → 云端仲裁：讯飞语义拒识（无匹配意图/置信度低）→ DeepSeek LLM 兜底回答。**验证云端仲裁 LLM 兜底 + 云端大模型链路**
4. **云端超时用本地**：弱网（限速）→ "打开车窗" → 端侧仲裁本地先到 → 等云端 2000ms 超时 → 用本地讯飞离线结果。**验证 2000ms 超时收敛**

（演示 1500ms 窗口需控制"LLM 先于传统到达"，时序不可控，不作为必验项；由 §9.2 仲裁收敛单测覆盖。）

每场验收同时确认决策日志在屏幕可见（哪条链路赢、为什么）。

## 10. 演进路线

每个子项目 = 一次 spec → plan → implement 周期：

| 阶段 | 子项目 | 内容 | 触发 |
|---|---|---|---|
| ② | 8155 平台移植 | 车载音频 HAL（AEC 回采、48k→16k、音频焦点）、Android Automotive 集成、唤醒词 | demo 验证通过 |
| ② | 车控领域模型 | Canonical Intent Schema 全量化（空调/车窗/媒体/导航/座椅…）+ 归一化映射表 | 与 8155 并行 |
| ② | 多轮对话 | ArbiterContext 上一轮意图消费：指代消解、域内上下文 | 单轮稳定后 |
| ③ | 多市场扩展 | 语言矩阵、TTS 音色、欧洲/中东供应商替换、数据本地化 | 出海立项 |
| ③ | AutoVoiceServer 生产化 | 阿里云部署、高可用、监控告警、配额限流、审计日志 | 实车验证后 |
| ③ | 新链路形态 | 端侧 LLM（8295/8397）、MA、S2S、VLA——Reply sealed + Stage SPI + 配置矩阵已预留 | 平台升级/立项 |

**顺序逻辑**：先验证仲裁策略与契约（最大不确定性）→ 8155 解决音频链路（第二不确定性）→ 领域模型/多轮实车补 → 多市场与 S2S 纯增量。

本次 spec 边界：仅里程碑 ①（周末 demo）。②③ 各自独立走设计流程，`shared/` 契约保持向后兼容（schema 版本化）。
