# 架构评估与修改步骤（2026-09-10 修订版）

## 评估范围与结论

本次核查最初基于 `main` 的 `591a35c`；六步实施已推进到第四批合并提交 `e28e428`。
下文路径相对于仓库根目录，以符号名定位代码，避免行号过时。

本报告依据源代码、[端云轮次设计](cross-tier-turn-admission.md)和
[覆盖率基线](test-coverage.md)修订。已证实的代码问题、潜在风险和改进建议分别标注。
静态代码检查不能证明某个问题已经导致历史上的某一次导航失败或断连。

架构分层总体可继续沿用。近期优先处理协议一致性、会话与仲裁职责边界、流式任务生命周期
和配置生效问题；随后移动导航业务逻辑、拆分网关和收敛重复代码，不进行整体重写。

第一至第五步已合并；第六步拆分的四个独立 PR 也已全部合并，依次完成网关职责、共享业务
装配、死代码/依赖清理和资源生命周期治理。
此前已经完成的 CI 和覆盖率工作见文末。

## 一、必须保留的设计约束

1. 音频并发进入端侧、云端候选；云端内部也允许离线命令词与在线模型并行。
   不改成“未命中才上传”或“未命中才给模型”。
2. 两级仲裁保留业务优先级：端侧车窗命令优先，否则等云端；云端空调离线命令优先，
   否则等模型。现有宽限期和失败兜底需明确记录、测试，不能悄悄改成先到先得。
3. 仲裁器按 `turnId` 记录该轮是否已输出语义。未输出时按规则仲裁，已输出时拦截同轮其他
   语义；它不决定哪个 ID 是当前轮，也不因另一轮出现而拒绝本轮结果。
4. 当前轮由会话状态机管理；会话协调、输出准入层判断结果是否仍可用于当前轮。
   仲裁落败或轮次替代仅拦截输出，不取消候选计算。任务自身超时、连接资源销毁属于
   生命周期事件，应单独定义和记录。
5. VAD 只影响采集分段，不新增 `SPEECH_CANDIDATE` 对话状态，不直接建立业务轮或停止播报。
   新轮以 ASR 话语成立或有效 NLU 语义为依据；ASR 回声误识别由识别能力解决。
6. ASR 文本独立更新识别框，不等待语义仲裁；获准输出的 NLU 若带识别文本，再刷新识别框。
7. TTS 模块统一播放文本合成和直接输入音频。业务域走 ASR/LLM；显式闲聊走 Qwen
   S2S/Realtime，保留闲聊锁域和独立 Skill/system prompt 配置。
8. 导航候选属于逻辑会话，不等同于 WebSocket 连接。重连恢复必须保留已有会话身份校验，
   并校验有效期和 `selectionId`。

## 二、当前架构和问题核查

### 2.1 当前链路

Android 由 app 装配 voice-core、gateway-client 和厂商/本地适配器。
`ConversationController` 管理当前轮，`ResponseDispatcher` 分发已接受的结果，
`SpeechOutputService` 负责语音输出。这些分工已经存在，不再建立并列控制器。

服务端 app 按 Classic/Omni 构建变体装配能力。Classic 走 ASR 和业务 LLM；Omni 业务域
同样调用 DeepSeek，显式闲聊走 Qwen。`audio_start` 可启动流式 ASR，`audio_end` 后
复用该在线候选或进入批式路径，同时启动云端离线候选。ASR 文本旁路发送，语义与回答音频
经过仲裁和输出准入。内部主要通过调用和 Future 协作；外部还存在 ASR、模型、MCP、
配置服务、TTS 等网络通信，不能说“唯一远程调用是 TTS”。

### 2.2 已核实的问题

| 编号 | 核查结果 | 代码依据 | 影响与处理方向 |
| --- | --- | --- | --- |
| A | 已修复协议约束漂移：source 保持可选，Android 使用明确的未知来源值；共同 fixture 约束正反例。 | shared/contracts、shared/fixtures、GatewayCodec、GatewayClient | 第一步已合并；后续新增协议字段仍需同步契约测试。 |
| B | 已拆除云端仲裁器中的单一 activeTurn 和 voidTurn；会话层改用逐轮输出许可证处理 cancel/superseded。 | RaceArbiter、TurnOutputPermit、VoiceGatewayHandler、SegmentPipeline | 第二步已合并；旧候选不取消且可继续仲裁，连接 worker 只停止等待其输出。 |
| C | 已为默认桥接、讯飞及 Classic/Hybrid 直接流式 finish 增加统一截止时间和异常释放。 | StreamingAsrProvider/Session、IflytekIatAsrProvider、Classic/Hybrid provider、AsrTurnTrace | 第三步已合并；截止时间从 finish 开始，另记录模式、降级、首字/终字延迟和超时。 |
| D | 已确认并修复配置与执行脱节：VAD 参数现进入主分段门，ECNR 可选 RNNoise/旁路，provider 矩阵启动期校验；mock.executor 明确为未实现保留项。 | DemoConfig、AudioRecorder、ConfiguredVadGate、VoiceEngineFactory、android-config-capabilities.md | 第四步已合并；打断/延时聆听 VAD 保留独立门限，避免混用检测目的。 |
| E | 已将导航候选存储、匹配和过期处理移出 contracts。 | NavigationDialog、navigation-domain、Classic/Omni 导航 Bean | contracts 仅保留端口；领域模块按逻辑 sessionId 保存候选，保留 120 秒 TTL 和 selectionId，并增加确定性容量淘汰。第五步已合并。 |
| F | 已分四批收敛网关职责、装配重复、死代码和资源生命周期。 | VoiceGatewayHandler、GatewayDownlink、RealtimeChatBridge、BusinessBackendConfig、AgentExecutionRuntime | 四批均已合并并通过 CI。 |
| G | 已确认覆盖率差异。 | docs/test-coverage.md | Android app 和讯飞适配器较低；重点补生命周期、协议与输出行为测试，设备验收仍独立进行。 |

### 2.3 原草稿纠正记录

| 原判断 | 修正后的判断 |
| --- | --- |
| 删除离线/在线双轨，减少音频重复 | 双候选并行是既定设计。只优化同一候选的重复请求、缓冲复制和生命周期。SegmentPipeline 已能复用 streamingOnline。 |
| 离线 30 秒超时必然拖住整轮 | 模型先完成且离线未完成时，RaceArbiter 默认等待 1500ms 宽限期，并有 safety 兜底。候选耗时不等于用户等待时间。 |
| 默认 join 证明讯飞断网后永久堵塞 | 默认接口存在隐患，但讯飞 transcribe 已使用有超时的 get，并在异常时取消会话；直接流式路径需独立验证。 |
| 导航没有 TTL，应改为每连接实例 | 已有 120 秒 TTL。优化逻辑会话存储和淘汰，不让正常重连自动丢失有效候选。 |
| llm 依赖 agent-loop 必然方向错误 | 通用 AgentLoop 通过 Adapter 调模型和工具，厂商实现使用它可以合理。需要核查具体业务策略归属。 |
| Omni 业务循环在 Qwen，Classic 在 DeepSeek | 当前 Omni 业务同样走 DeepSeek，Qwen 负责闲聊。 |
| 未装配 TranscriptEnrichedSpeechProvider 说明不能提前上屏 | Classic/Hybrid 与网关已有独立 ASR 回调和 asr_partial 下行；确认生产、反射和运行时均无引用后，第三批已删除早期旁路类。 |
| 没有端云 fixture 编解码测试 | 已有部分 fixture 被 Java/Kotlin 测试消费；问题是覆盖不完整，缺少可选字段等一致性用例。 |
| 所有流式降级都没有日志 | 网关捕获流式启动异常时已有回退日志。能力不支持和运行失败的观测仍可统一。 |
| Qwen 具体类未实现 RealtimeChatProvider 就是错误 | Hybrid 对外实现接口、内部组合具体 Qwen 实现是可用结构。多实现注入需求出现时再引入更小接口。 |
| TTS 音频永远整段返回 | 传统 TTS 合成接口整段返回，S2S/Realtime 已有增量音频。传统 TTS 是否改流式由延迟数据决定。 |
| scope 缺失回退 llm 已造成错误域注入 | 这是现有旧数据兼容行为。未知 scope 校验值得补充，尚无证据证明缺字段已导致跨域注入。 |
| SQLite 使网关只能单实例 | 主要限制是遥测汇总、会话归属和跨实例恢复，不等于无法多实例运行。 |
| 无源码 import 就能判定虚假依赖 | 还需检查装配、运行时和测试。tts-server 实际使用 tts-gateway 中的 AliyunTtsProvider，不能直接删依赖。 |
| 集成测试跨模块依赖都应改假实现 | 聚焦单测可用假实现，同时保留必要的跨模块协议与装配测试。 |
| P1 零风险，两三天完成 | 缺少工作量依据；轮次和导航生命周期改动有行为风险，需拆 PR 和验收，不承诺零风险。 |

## 三、修改步骤

按 1 → 2 → 3 → 4 → 5 → 6 推进，每步独立提交、评审并通过 CI 后进入下一步。
步骤 6 可拆多个 PR。每个 PR 记录实际行为变化、兼容影响和验证结果；失败时先修复或
回退该步提交，不继续叠加后续重构。以下记录实施计划和验收标准，标题标注当前进度。

### 第一步：统一协议与兼容性测试（已完成）

**修改范围：** shared/contracts、shared/fixtures、GatewayCodec、GatewayClient 和相关测试。

1. 列出各方向消息的必填/可选字段、类型、默认值与旧版行为，重点核查 source、
   turnId/utteranceId/segmentId、asrText 和音频元信息。
2. source 优先保留现有 Schema 的可选兼容语义；核查路由、仲裁和遥测对它的依赖后，
   确定明确的未知来源表示，不伪造胜出来源，不放宽必需的轮次身份约束。
3. 补合法缺字段、错误类型、未知消息和旧版消息 fixture；Java/Kotlin 实际编解码器
   消费适用于各自方向的共同样本，Schema 同时校验。统一内嵌与独立 intent schema。
4. 增加必要字段往返保留检查。先以测试约束手写映射，维护成本确有需要时再引入 codegen。

**验收：** 缺 source 的合法 action 能进入正常业务分发；非法必填字段仍被拒绝；
现有消息兼容；不能只通过 JS Schema 就算完成。

**示例：** 服务端导航 action 没带来源描述，客户端仍可处理导航，不会把整条 action 解析成 null。

**实施结果：** `source` 缺失时映射为 `protocol.unspecified`，错误类型仍拒绝；reply 内嵌 intent
改为引用统一 Schema；slot 的 `unit` 在 Android 模型中保留；新增合法缺字段、文本 reply 和
错误字段类型 fixture。所有 gateway fixture 由 Java codec 解码，消息类型枚举与 Schema 对拍，
Kotlin 客户端直接消费相同的正反例。

### 第二步：收敛会话、仲裁和输出准入边界（已完成）

**修改范围：** 端侧仲裁/状态机对照测试，服务端 RaceArbiter、ConnectionTurnCoordinator、
VoiceGatewayHandler 的结果接收和下行准入。

1. 固定边界用例：VAD 不换轮；ASR/有效语义才换轮；仲裁仅按每轮记录去重；
   当前轮判断由会话/输出层负责。
2. 拆开单个活动轮与 superseded 作废职责，使用按 turnId 隔离的仲裁状态。
   新轮注册不覆盖旧轮；旧轮候选正常完成后，仍可形成该轮首次仲裁结果。
3. 会话协调层撤销旧轮输出资格、释放对旧结果的等待并推进任务槽；为自然完成的旧候选
   提供有界在途登记及过载策略，避免任务无限积累。仲裁落败和新轮成立不得取消候选。
4. 活动仲裁记录不能提前淘汰；完成记录的保留覆盖合法迟到窗口。每轮语义只声明一次，
   该语义的后续音频块由同一输出许可连续放行，不能当作多次语义输出拦截。
5. pending、回复、语音和缓存响应统一执行身份校验，ASR 保持独立上屏。
   保留现有优先级、宽限期和兜底行为，遥测区分仲裁落败、会话过期、请求自身超时。

**验收：** A 晚于 B 返回时，A 若未输出过语义仍可完成仲裁，但当前会话输出层丢弃 A；
A 不能覆盖 B 的 pending、语音或缓存；同轮第二份语义被拦截；B 不取消 A 的模型请求；
语音流不会因为已有语义输出而只播第一块。

**示例：** A 导航尚未完成，用户用有效语音建立 B 天气请求，A 晚到的导航不能拉起地图。
若只是 VAD 检测到噪声，A 仍应正常输出。

**实施结果：** `RaceArbiter` 不再保存连接级当前轮，也不处理 cancel/superseded；每次
`decide` 的赢家状态只属于该次调用。网关为每个语音段创建 `TurnOutputPermit`，有效新轮或
显式 cancel 只撤销旧许可证，并用撤销信号让串行 worker 停止等待；ASR、离线 NLU、LLM、
工具与仲裁 Future 均不因此取消。pending、ASR、流式文本/音频、最终回复、决策和缓存重放
统一检查同一许可证；撤销记录在 safety 窗口后有界清理。新增并发旧轮晚到、撤销幂等、
不取消候选和缓冲音频不泄漏测试；Classic/Omni 服务端全量测试与 bootJar 已通过。

### 第三步：补齐流式 ASR 生命周期和观测（已完成）

**修改范围：** StreamingAsrProvider/Session、讯飞实现、Classic/Hybrid 桥接、网关清理逻辑。

1. 分别梳理默认转批式、讯飞转批式和直接流式路径的启动、append、finish、成功、失败和关闭。
   保留现有讯飞超时保护，不能只改默认方法便宣称全部路径修复。
2. 默认桥接增加有界等待或要求实现明确提供超时；直接流式 finish 使用请求级截止时间。
   明确计时起点，避免正常长输入被误算成响应超时。
3. 超时、异常、连接销毁时幂等释放 socket、Future、监听器和缓冲；成功后正常关闭资源。
   与仲裁落败、轮次过期只拦截输出的规则分开。
4. 记录实际 ASR 模式、回退原因、首个识别结果延迟、最终结果延迟与超时；
   区分能力不支持、启动失败、识别失败，检查同一段音频是否重复发起同一候选。

**验收：** 可控假 WebSocket 模拟无 final、异常关闭、final 后迟到回调和断线；
等待有上限、资源释放、下一轮可继续；批式提供者仍可使用，partial 独立上屏。

**示例：** ASR 只给 partial 就断开，该请求明确失败或进入既定兜底；恢复后下一轮识别
不需要杀进程。真实 SDK 和网络故障另做设备验证。

**实施结果：** `StreamingAsrSession.finishWithin` 提供统一的最终结果截止时间，计时从
`finish` 开始；超时、源 Future 异常和调用方取消均幂等释放会话。默认批式桥接和
Classic/Omni 业务流统一使用 provider 声明的截止时间。讯飞会话重复 `finish` 只发送一个
status=2 尾帧，失败立即清空待发缓冲，cancel 幂等。网关按请求记录 streaming/
batch_fallback 模式、降级原因、首个识别结果延迟、最终结果延迟、finish 后延迟和超时，
ASR 错误仍旁路语义仲裁。相关 contracts、讯飞、Classic、Omni 和 gateway 测试已通过。

### 第四步：让配置与实际能力一致（已完成）

**修改范围：** 配置 Schema、DemoConfig、VoiceEngineFactory、AudioRecorder、VAD 分段器。

1. 建立“字段 → 默认值 → 装配点 → 最终消费者”清单，逐项核查 vad/ecnr/mock/local。
2. 将支持的 VAD 参数接入实际分段器，统一单位、默认值及合法范围；不同检测用途分别标注，
   不把一套参数盲目套到所有音频检测器。
3. 未实现或保留字段给出明确文档与运行提示；未知 provider 名称在装配时明确报错。
   生产 SDK 未配置按能力不可用处理，保留显式 fake-cmd 的 Demo 用法。
4. 参数驱动测试验证分段效果，另验证状态机的轮次成立条件不受这些参数直接改变。

**验收：** 静音参数确实改变音频结束判定；非法值能定位；VAD start 仍不停止 TTS 或建立
新业务轮；默认配置行为兼容。

**实施结果：** 新增字段到默认值、装配点和消费者的完整清单。主录音的 threshold、
minSpeechMs、minSilenceMs 进入实际 Silero 门控，模式切换先暂停共享麦克风再安全替换配置；
打断和延时聆听仍使用独立门限。ECNR 支持 `rnnoise` 和显式 `none` 旁路；省略字段保持
RNNoise 兼容行为。ASR/NLU/ECNR 未知 provider、非法 VAD、无效云端参数及未实现的
`mock.executor=true` 在装配前给出字段级错误。测试音频也改由同一 DemoConfig 解析，不再
固定旁路读取 demo-full。生产讯飞能力不可用继续按未命中处理，只有显式 fake-cmd 才产生
Demo 命令。Android 全量单测、lint、Debug 构建和共享 Schema fixture 已通过；既有
ConversationController 测试继续约束 VAD 不建立业务轮。

### 第五步：迁移导航领域逻辑，保留逻辑会话恢复（已完成）

**修改范围：** NavigationDialog/NavigationDialogService、服务端 session、Classic/Hybrid 业务路由及装配。

1. 候选匹配与导航规则移到导航领域包/模块，contracts 保留必要接口和值对象。
   以消费者和依赖方向确定位置，不立即大拆所有 contracts。
2. 分离无状态选择规则与按逻辑会话保存的候选存储，保留 120 秒 TTL、selectionId、
   candidateId 和一次选择消费语义；旧客户端兼容路径明确测试。
3. 定义容量、过期清理、确定性淘汰和逻辑会话失效处理；正常重连恢复同一有效会话时
   保留候选，新会话不得继承其他会话候选。
4. 共享导航 resolve → 业务 LLM → remember 流程，保持业务与闲聊隔离；
   核查存储候选与实际获准显示的列表一致，防止过期结果污染新列表。

**验收：** 第几个、精确地点名、相互包含名称、取消、超时、新列表替代旧列表、
同会话重连、会话间隔离和并发消费均有回归测试；选择成功仅执行一次导航。

**示例：** 显示成都机场列表后短暂断网，恢复同一有效会话并说“天府机场”，仍选中当前列表
对应项；超过 TTL 明确提示重搜，不使用过期坐标。

**实施结果：** contracts 删除具体的 `NavigationDialogState`，只保留 `NavigationDialog` 端口和
共享的 resolve → model → remember 流程。新 `navigation-domain` 模块将无状态文本匹配、
带 TTL/容量策略的逻辑会话存储、Reply/Intent 适配拆开；容量满时按过期时间、创建时间、
sessionId 确定性淘汰。Classic 与 Omni 业务路由只依赖端口，应用装配注入领域实现；默认
两参数构造使用显式空实现，不再让业务适配器暗中创建状态。测试覆盖序号、名称/地址、包含关系、
取消、过期、新旧列表标识、同逻辑会话重连、跨会话隔离、并发一次消费和确定性淘汰。

### 第六步：按职责拆分网关、收敛重复（已完成）

**修改范围：** 网关、后端装配、共享业务流程、未使用代码与依赖。

1. 在前五步行为测试保护下，先提取下行发送和 Realtime 闲聊处理组件，复用现有任务协调类，
   Handler 主要保留连接接入与协议分派。
2. 提取共享 ASR/业务 LLM 工厂及步骤五的业务流程；通用 AgentLoop 保留，
   厂商协议转换留在对应适配器。
3. 删除代码前确认生产装配、测试、反射和运行时引用；清理依赖后验证两个构建变体
   的装配和产物。保留必要集成测试，不只依赖假实现。
4. 明确线程池、缓存、监听器的拥有者和关闭时机，避免形成无边界的 common 模块。

**验收：** 协议、优先级、识别上屏、闲聊锁域、音频连续播放、导航和 TTS 缓存行为一致；
Classic/Omni 均可装配；不以减少行数作为成功标准。

**第一批实施结果：** `GatewayDownlink` 统一负责协议编码、文本/二进制串行发送、reply/error/
pending 形态和策略关闭；`RealtimeChatBridge` 独立拥有每连接的 Realtime 建连、音频转发、
事件映射与关闭。Handler 只分派 chat_start/chat_finish/二进制输入，不再保存 Realtime 的
opening/requested/response 序号等内部状态。连接关闭和 Bean 销毁共用同一幂等释放路径；建连
尚未完成时收到 chat_finish，迟到建立的上游会话会立即关闭且不发送 chat_ready。新增正常结束、
建连中结束、不支持能力和既有连续音频回归测试。

**第二批实施结果：** 新增 `BusinessBackendConfig`，Classic 与 Omni 共享 Clock、阿里令牌、
讯飞/阿里 ASR 选择、DeepSeek 业务 LLM、业务 MCP 工具合并及 NavigationDialog 装配。
Classic 变体只负责选择 Classic speech；Omni 变体只增加隔离的 Qwen 闲聊工具、prompt、HTTP/
Realtime provider 和 Hybrid 路由。未知 ASR/LLM 仍在同一个装配边界快速失败，并增加 provider
矩阵测试。

**第三批实施结果：** 全仓生产、测试、配置和构建引用核查后，删除从未进入生产装配的
`TranscriptEnrichedSpeechProvider` 早期旁路及其自测；当前 ASR 提前上屏和导航二轮能力继续由
Classic/Hybrid、NavigationDialog 和网关回归约束。删除 Java 侧未消费的 `GatewayMessage` 及
零引用 `ModuleInfo` 占位类。`speech-classic` 运行时依赖收敛为 contracts，不再错误携带
asr-gateway、llm、slf4j；gateway 删除未使用的 tts-gateway 边，app 将两个变体都需要的
asr-gateway/llm 改为公共依赖并删除重复声明。Gradle 运行时依赖图和两个构建变体已通过验证。

**第四批实施结果：** 新增应用级 `AgentExecutionRuntime`，由 Spring 单例统一拥有 Agent Loop
截止任务和并行只读工具的两个有界线程池；DeepSeek 与 Qwen 只借用该资源，请求级
`AgentLoop`/`RequestToolExecutor` 不再隐藏静态线程。Hybrid 路由器拥有并关闭自己的 ASR
线程池及组合的 Qwen HTTP provider；Qwen 关闭时取消在途请求并停止私有 worker。模型、路由、
工具执行线程数和队列均有明确上限，过载时请求明确失败，不再无限排队或扩线程。

Telemetry Service 在 Spring 关闭时先停止接收、排空已排队写入、清除 SSE listener，再停止
单写线程；其写队列有界，满载时仅丢弃 best-effort 遥测并记录告警。OfflineCommandService
级联关闭引擎池和各 Native worker 的 Java 串行线程；讯飞 native 引擎是进程级单例，单个
worker 不擅自卸载共享能力，随进程退出释放。闲聊域缓存从“容量满后全量 clear”改为按准入序号
确定性淘汰最旧逻辑会话，避免一个新会话让其余 1000 个会话同时退域。

资源关闭顺序由依赖方向决定：Gateway/Omni Router 停止产生新任务 → LLM/Qwen 私有请求池
停止 → AgentExecutionRuntime 共享池停止；TelemetryController 先停止 SSE sender，随后
TelemetryService 排空写队列；OfflineCommandService 关闭池内 worker。仲裁落败和新轮成立仍然
只拦截输出，不触发上述关闭；这些关闭只发生在 Bean/连接/请求自身生命周期结束时。

## 四、验证与后续项

每步运行相关行为测试和协议 fixture，提交前通过项目 CI 要求的测试、lint、构建和覆盖率门禁。
合并、部署、设备安装按对应任务执行；代码测试通过不等于设备验收完成。

涉及步骤二至五的版本，设备验收至少包括：播报中 VAD 噪声、有效语音打断、连续多轮问答、
导航选择与退出、前后台切换、网络中断恢复、闲聊锁域与退出。保留轮次 ID、候选产生、
仲裁决定、输出接受/丢弃和播放事件，以定位责任层。

以下按实际需求安排，不插入本轮核心改造：

- 传统 TTS 流式合成：先测文本完成到首音的延迟，确认瓶颈再设计增量合成、缓存与失败恢复。
- 遥测独立服务：需要多实例数据汇总或容量增长时，再设计集中存储与会话路由。
- codegen、wire/domain 完整拆分、多厂商适配接口：由实际维护和接入需求驱动。
- Demo 凭据和明文通信治理：用户此前接受 Demo 静态凭据；按真实权限、暴露面与上线计划评估。
  正式环境另行处理 TLS 和凭据管理；移出文件不等于撤销历史已暴露凭据。

## 五、已完成工作与统计口径

d3fc686 已补齐 Android app/adapter 单测覆盖率报告、两个 Web 交互测试和各端覆盖率门禁。
该提交的 GitHub CI 曾验证六项全部通过，不代表未来提交的检查状态。

| 范围 | 已记录行覆盖率 | CI 最低值 |
| --- | ---: | ---: |
| 服务端 JVM 加权合计 | 82.08% | 75% |
| Android voice-core + gateway-client | 89.29% | 70% |
| Android app | 28.64% | 25% |
| Android adapter-local | 76.22% | 70% |
| Android adapter-iflytek | 24.68% | 20% |
| Telemetry Web | 93.28% | 80% |
| Skill Manager Web | 63.29% | 60% |

第四批在 Classic 全量测试后用 `shared/verify-jacoco-coverage.mjs` 聚合 17 份 JaCoCo 报告，得到
4041/4923 行（82.08%）；Omni 也按 CI 命令完成全模块测试与 bootJar。原草稿的总行数、测试数量
和混合 JVM 覆盖率缺少可复现的范围说明，不再作为本报告结论依据。后续统计应继续记录提交、
文件范围、SDK/生成代码排除规则、测试变体和报告来源；设备测试与 JVM 单测分别列示。
