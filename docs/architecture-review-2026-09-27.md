# 项目架构复评：模块边界、运行时一致性与生产准备

> 实施更新：本报告下述“尚未修复”描述记录的是评审时的原始基线。
> `codex/architecture-hardening-2026-09-27` 已开始按 §5 落地；实时进度与剩余限制见文末“实施记录”，当前结构入口见 [current-architecture.md](current-architecture.md)。

日期：2026-09-27。

## 1. 评审范围与结论

基线为本地 `codex/local-exit-conversation` 的 `c79b2f4`，包含导航任务 DM 与端云退出修改。
评审时远端 `dev=440e4f2`、`main=55ac33f`；PR #123 和 #124 均 OPEN，#124 依赖 #123。
因此本报告描述的是最新开发代码，不能直接当作 main 或线上运行状态的说明。

结论：模块划分方向合理，适合继续演进为单实例的生产语音系统。通信、音频前端、识别、
仲裁、交互状态、任务状态和业务执行已有可以保留的边界。目前主要风险在模块之间的真实接线、
异步事件身份和资源收尾；增加更多模块或全面重写不会自动解决这些问题。

本次发现两个应先修复的 P1：持续闲聊的播放身份与 TTS 准入不兼容；云端仲裁的队列消费者
可能并发执行。均用当前已编译类的本地探针复现。修复前不建议将此开发基线直接推进生产。

方法：静态阅读生产装配、模块依赖、相关测试及历史文档；复用上一轮同一提交的验证记录；
查询当前 GitHub CI；运行两个纯本地探针。按用户要求未做真机测试，未访问或修改服务器，
未调用真实模型，未修改生产代码。

## 2. 已建立且应保留的边界

| 部分 | 当前实现 | 评价 |
| --- | --- | --- |
| 音频输入 | `audio-frontend` 封装信号处理/VAD；`RecordingCoordinator` 组织采集与音频分流 | 合理。VAD 是音频事实，不应变成会话状态或直接确认新轮 |
| 识别 | 本地 ASR/NLU 契约分开；云端 ASR/NLU 按消息类型独立监听 | 合理。当前端侧 2C SDK 仍返回文本与语义的组合结果，本地 ASR 空实现不代表已有独立 PGS 能力 |
| 传输 | `GatewayClient` 管连接、心跳、重连、原始帧；业务消息由 `GatewayProtocolSender` 编码 | 已落实职责分离，不应把 `sendAudioStart` 放回通道层 |
| 消息分发 | 类型注册、多监听者的 `MessageDispatcher` | 合理；已有隔离监听器异常的基础 |
| 端侧仲裁 | 常驻消息队列、规则准入、按 turn 防重复输出 | 设计方向正确；不认识状态机当前轮，胜出后再检查会话有效性 |
| 对话 | `ConversationController` + `DialogueStateMachine`；独立 `TaskDialogueCoordinator` | 普通交互与多轮业务任务分开合理，但应用层协调入口还未收齐 |
| TTS | 独立 `tts` 模块，合成、缓存、播放身份在内部 | 模块边界正确；普通轮次与 Realtime 的输出身份需要显式区分 |
| 业务 | `VoiceEngine` 经 `BusinessHandler` 下发；导航执行/模拟车控在 app | 已消除引擎直接依赖导航、车辆状态；业务路由仍集中在具体 handler |
| 云端 | `agent-loop`、`navigation-domain`、模型适配器独立 | 无需更换整个 Agent 核心；应优先完善已有运行时与装配测试 |
| 动作语义 | `ActionExecutionGateway` 为内存单轮闸门；旧 actionId 为兼容关联字段 | 符合当前“断线结束、不重放、不补执行”原则；无需恢复持久化动作账本 |

项目目前为 9 个 Android Gradle 模块、18 个服务端 Gradle 模块。模块数不是完成度指标。
本次也没有证据要求引入微服务拆分、跨实例会话恢复或更复杂的通用编排框架。

## 3. 具体发现

### F1 / P1：Realtime 回复使用空身份，被生产 TTS 准入拦截（已复现）

调用链：

1. `GatewayAdapter.kt:805` 为闲聊流建立空 `utteranceId`，通过 `onChatReply` 下发。
2. `ResponseDispatcher.kt:76` 调用 `output.playStream("", reply)`。
3. `VoiceEngineFactory.kt:162` 将 TTS 的有效性检查绑定为 `conversation.isCurrentTurn(turnId)`。
4. `TtsOutput.kt:93` 在检查失败时立即返回。普通状态机的 turnId 为 null 或非空业务 ID，
   不会等于空串，因此闲聊流不会进入 driver，也不会执行完成回调。

探针使用真实 `createTtsOutput` 和 `DialogueStateMachine`，播放 driver 只计数，使用 Unconfined
调度排除等待时间影响。同一条已完成流：

```text
realtime blank identity: plays=0, completions=0
business current identity: plays=1, completions=1
```

影响：进入闲聊后仍可能发送音频，但收到的回复无法播放；附在该完成回调中的 `exit_chat`
也无法经此路径执行。这是开发基线上的装配缺陷，尚未据此推断线上设备的实际表现。

建议：为输出定义明确的所有者，例如 `BusinessTurn(turnId)` 与
`RealtimeResponse(chatSessionId, generation, responseId)`。普通输出检查当前轮；闲聊输出检查
当前闲聊会话及响应代次。不要仅把空串改成无条件放行，否则退出或重连后的旧流也会通过。
补一条贯穿真实装配的“进入闲聊 → 收流 → 播放 → exit_chat → 回唤醒”回归。

### F2 / P1：云端仲裁消费者所有权释放存在竞争（已复现）

位置：`AutoVoiceServer/arbitration/.../RaceArbiter.java:187–205`。

`drainMessages` 在循环内 `draining.set(false)`，又在 `finally` 无条件设置一次 false。
两次释放之间，新消息可以成功获取标志并启动消费者 B；旧消费者 A 的 finally 随后清掉 B
持有的标志，第三次提交便可启动消费者 C。B/C 会同时处理队列。

探针调用真实私有 drain 方法，通过可控队列在第一次释放后的 `isEmpty()` 暂停 A，
让 B 开始执行且保持阻塞，再恢复 A 并提交 C。未更改生产实现：

```text
B executing, draining flag=false
maximum concurrent arbiter callbacks=2
```

这是确定性的受控线程交错，不是压测得出的发生频率。探针确认了串行前提失效，尚未直接
复现同轮双胜出。但 `turn.winner` 的检查和赋值依赖单消费者，已不能证明单轮只胜出一次。

建议：采用经过验证的串行执行器，或明确消费者所有权的排空协议，避免旧消费者释放新消费者
的所有权。保留现有业务优先级及队列上限，不将当前轮判断迁入仲裁器。新增受控交错回归，
再验证同轮单输出、跨轮推进、观察者异常和过载。

### F3 / P2：应用级对话协调仍分散在 ViewModel、Factory 和业务类（静态确认）

导航已有通用任务身份、revision、原子 claim 和效果队列。上次的 F1–F6 修复有对应代码，
不应继续列成“尚未实现”。但统一应用级 DM 入口仍未形成：

- `MainViewModel.kt:177–200` 维护导航上下文发布与关闭协议。
- `MainViewModel.kt:225–246` 直接构造语义并调用导航执行器；语音经 ResponseDispatcher/BusinessHandler。
- ViewModel 负责交互状态转发、后台与延时聆听结束；Factory 负责断线/重连结束导航任务。
- 普通状态由 ConversationController 管理，闲聊模式同时散落在 RecordingCoordinator 的
  `chatLocked`、GatewayCloudRunner 的 `realtimeChatDesired/Ready` 等字段中。

UI 与语音已有共享 claim，不能说完全没有统一保护。问题是增加一个新任务域时，生命周期规则
仍需同时补多个入口；也容易产生 F1 这样的跨模式接线遗漏。

建议：新增薄的应用级 DialogueManager，组合交互控制器、任务协调器与明确的模式控制器。
统一消费点击、语义、连接变化、后台、超时、业务完成事件，产出监听/上下文发布/业务执行效果。
ViewModel 负责发送用户事件和投影状态。保留交互状态、任务状态和音频资源状态的独立性；
仲裁和识别继续在 DM 外。

`ConversationController.kt:198–202` 的效果回调异常会中止排空，已排队的后续效果要等下一次
mutate 才有机会继续。`TaskDialogueCoordinator` 已采用继续排空后报告失败的机制；未来统一
协调入口时，应明确并统一这类异常策略，避免状态已更新但监听或播放效果没有跟上。

### F4 / P2：诊断事件的轮次归属与收尾仍不完整（静态确认）

`OnDeviceArbiterEvent` 的 Received/Won/Lost/Pending 只有 route/reason，没有 turnId。
Factory 在 `VoiceEngineFactory.kt:190–211` 调用 `telemetry.record(...)`；该方法在
`TelemetryClient.kt:64–67` 读取可变的 `activeUtteranceId`。

场景：A 轮已胜出、B 轮开始，A 的迟到候选再被仲裁拦截。A 的 Lost 过程事件会被记入 B，
或者 B 已结束时被丢弃。最终 DecisionEntry 自带 utteranceId，归属是正确的，问题主要在过程事件。
数据平台由此可能呈现不一致的证据，影响排查“下一轮不识别”等问题。

另外，TelemetryClient 的 rounds 为无上限 Map，begin 添加，end 才移除；VoiceEngine 的 end
仅在胜者回调中调用（`:388/:402`）。取消过短 capture、无候选输出及超时复位没有对应收尾，
在遥测开启时会留下未收包记录。状态机回到 DORMANT 不会自动清理这张 Map。

建议：事件创建时携带固定 turnId，异步路径统一 recordFor；由会话/采集所有者发出明确的
结束原因并关闭诊断记录，另加容量保护。结束诊断记录无需结束仲裁流水线，也无需恢复动作账本。

### F5 / P2：业务完成依赖播报回调，无输出分支缺少统一终态（静态确认）

VoiceEngine 收到胜者先进入 RESPONDING；思考定时器只覆盖 THINKING/SEMANTIC_PROCESSING。
随后主要靠 TTS 播放结束进入延时聆听。已有空文本、合成失败和流完成异常的处理，但没有覆盖
所有“根本未请求输出”的业务结果。

例如 `ResponseDispatcher.kt:62–68` 对本地 FAILED/REJECTED/DUPLICATE 或成功但 speakText=null
直接返回；云端 action 的部分结果也直接返回（`:56`）。若业务没有自行 reset、也没有原播放
正在完成，就缺少推进 RESPONDING 的事件。结构上会影响未来接入无声业务；本地缺槽语义被采用
但执行失败也是需要覆盖的异常路径。本次未复现一条真实 SDK 能稳定触发的用户话术。

建议：业务完成与音频完成分别建模，例如 `BusinessCompleted(result)`、
`OutputCompleted/Failed/Skipped`。DM 根据结果明确决定延时聆听、结束或等待任务输入。
TTS 只报告播放事实，不负责替所有业务兜底生命周期。保留现有新 ASR/NLU 才确认新轮的规则。

### F6 / P2：业务通信聚合与文档治理还需收敛（静态确认）

底层通道已解耦，但 `GatewayAdapter.kt` 907 行仍聚合普通音频上传、ASR/NLU 回复槽、TTS 请求、
持续闲聊重连、回复字幕、导航上下文。服务端 `VoiceGatewayHandler.java` 1167 行同样承载握手、
配额、音频轮次、导航上下文、TTS 与闲聊接线。行数仅作为定位线索，真正的问题是这些职责具有
不同的状态和失败处理，却由同一聚合类维护。

建议按行为边界提取 BusinessSpeechChannel、RealtimeChatChannel、TtsTransport、
NavigationContextChannel，共享连接及有类型的消息分发；避免每个模块各建 WebSocket。
消息身份应在适配边界转换为类型化对象，减少在业务代码中散落字符串键和空 ID 约定。

历史文档还存在相互冲突：`docs/recovery-objectives.md` 保留持久化动作账本、重连保留候选的
旧规则，当前代码已改为内存执行闸门，并在连接变化时终止待选任务。
`docs/architecture-review-2026-09-22.md` 顶部写已修，后文保留首次发现与旧结论，容易被误读。
应给历史设计标明被哪份决策替代，并维护一份与当前基线一致的架构入口文档。

## 4. 测试与生产准备评价

PR #124 在本次评审时六项 CI 全部通过：schema、classic、omni、Android、两个 Web。
同一基线上一轮本地覆盖率记录：服务端 83.70%，voice-core + gateway-client 汇总 88.06%，
app 36.65%，audio-frontend 73.08%，adapter-iflytek 28.71%。这些是对应报告范围的行覆盖率，
不是全产品或真实硬件覆盖率。

当前测试对纯状态规则、协议解析、导航身份校验已有较好基础。主要缺口：

1. 使用生产准入函数的完整装配测试。F1 的收包与 TTS 分别测试成功，并不能证明串接后能播放。
2. 受控并发交错。F2 的两次释放窗口不适合仅靠 sleep 或普通并发调用覆盖。
3. 无输出、拒识、超时、断线的诊断收尾及长期运行上限。
4. 普通业务与持续闲聊模式切换的全链路状态测试。

建议将上述回归设为合并门禁，再增加少量依赖边界检查：VoiceEngine 不引用导航/车辆实现，
GatewayClient 不依赖业务意图，仲裁不引用会话状态机。无需先追求全仓统一提高覆盖率百分比。

生产可用性还依赖部署资源、真实 SDK、网络及容量验收；本次未重新验证 D16 环境事项。
尤其 Linux FSA、Android 厂商 SDK、运行配置是独立产物，jar/APK 的 CI 成功不能证明这些资源已更新。

## 5. 建议实施顺序

1. 修 F1/F2，并把本次探针转成长期回归。完成前暂停推进该基线到生产。
2. 修 F4/F5：固定事件身份、会话/采集终止记录、业务无输出终态。用拒识、过短录音、超时和交错轮次验证。
3. 建立 F3 的薄 DM 协调入口，迁移 ViewModel/Factory 中的跨模块生命周期策略；每次迁移保持现有导航身份校验。
4. 按 F6 拆分业务通信适配，整理类型和历史文档；再考虑把导航策略从 app 移入独立领域模块。
5. 按用户安排补真机和 dev 环境回归，再判断生产发布条件。

首次评估阶段只产出报告、未改生产代码；后续实施状态见下一节。

## 6. 实施记录（2026-09-27 开发分支）

- F1：增加 Realtime 专用播放 token 与 generation 准入；退出或流故障使已分发的旧代次输出失效。TTS 模块回归已增加。协议未给闲聊回复携带代次，跨重连后才抵达的旧回复不能仅靠本地代次完全辨别；尚未真机验证完整锁域链路。
- F2：修正消费者所有权重复释放；新增可控线程交错测试验证不会出现第二消费者。
- F3：增加薄 `AppDialogueManager`，将导航任务的 UI 选择、上下文发布/撤销、监听 revision 与生命周期入口从 ViewModel 收入一处；普通交互状态机仍独立。闲聊模式跨录音/传输的完整收拢不在本次小步改动内。
- F4：端侧仲裁过程事件显式带固定 turnId；重置、退出、超时及短录音收尾诊断轮次，并为异常开放轮数加上限。增加跨轮归属/收尾回归。
- F5：业务分发显式返回“请求播放/无播放/过期”，无播放由对话状态机转入延时聆听；增加无输出测试。
- F6：提取 TTS 请求传输与下行消息桥，保持共享连接及协议不变；标记冲突历史文档并建立当前架构入口。普通语音/闲聊上行控制及服务端聚合类尚待分步拆分；不把文件行数当作发布阻断条件。

以上是开发分支状态，不代表 PR 已合并或已部署。生产准入仍需完整 CI、真机与 dev 环境验收。

## 7. 后续状态（2026-09-27）

- #123–#126 已依次合入 `dev`（最终提交 `cb307a3`）；最终 dev CI 成功，自动部署日志确认同一 SHA 已部署到 dev。此事实不代表生产部署或真机验收。
- #126 已为 Realtime `chat_start`、下行事件和 `chat_finish` 增加可选 `chatId`，服务端丢弃已关闭会话回调，客户端拒绝带旧 ID 的消息。§6 F1 中“协议未给闲聊回复携带代次”只适用于 #125 基线；混用旧服务端时无 ID 的兼容消息仍不能彻底隔离。
- F3/F6 的剩余收敛在 `codex/chat-mode-lifecycle-2026-09-27` 分支继续实施，当前结构见 [current-architecture.md](current-architecture.md)。
- #127 已合入 `dev`，将闲聊业务模式归于应用 DM，并提取闲聊/导航网关通道；后续普通业务语音通道在独立分支抽取并增加 WebSocket 协议回归。服务端聚合类仍待单独处理。
- #128 普通业务语音通道在 CI；服务端 TTS 请求处理在下一独立分支从 `VoiceGatewayHandler` 提取，沿用原有长度限制、有界队列和下行预算，协议不变。服务端握手及普通音频轮次仍在 Handler，真机和容量验收未完成。
