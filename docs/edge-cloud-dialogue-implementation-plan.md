# AutoVoice 端云对话管理设计与落地规划

日期：2026-09-30。状态：端云业务 DM 方案待实施；客户端基础模块拆分已分期落地。本文面向客户端、服务端和测试开发，定义端云 DM 的职责、协议、状态归属及逐步改造计划。本 PR 只增加设计文档，不修改运行时代码。

原始方案核对基线为分支 `codex/voice-engine-business-ui-2026-09-29`、提交 `049e9a2`；本 PR 从 `dev` 的 `840bd56` 整理。其间 `voice-business`、`voice-engine` 和部分 UI 已进入 `dev`，详见[语音引擎与业务拆分设计](voice-engine-business-split-design.md)第 17 节。下文“目标模块”与“当前实现”描述保留原方案语境；实施前须再核对最新源码，不能将已落地的基础拆分或仍未落地的端云业务 DM 混为一谈。

本文采用云端业务 DM 与端侧交互 DM 分工：云端维护在线业务槽位、查询信源、决定补充什么信息；端侧负责选择、确认、交互生命周期和本地执行。导航优先使用当前位置和就近规则确定具体 POI，不因为检索返回多个地点就自动发起选择。

## 1 设计范围与已确认决策

1. 在线信源位于云端，在线业务槽位的最终状态由云端维护。端侧无需解析各信源原始响应，也不重复实现业务补槽规则。
2. 补充信息型追问由云端决定，例如机票出发日期和出发地；端侧负责呈现、收音和回传。云端定义问题，不直接操作端侧麦克风。
3. 候选选择和执行确认由端侧完成交互。业务要求是否确认、确认什么对象，由对应业务策略提供；端侧不得跳过必要确认。
4. “导航到机场”在有效定位下按当前位置范围内的就近策略解析并执行，不询问城市，也不默认展示机场候选让用户二选一。
5. “帮我导航”没有目的地，支持云端补充目的地；导航主流程不强制经过补槽。明确指定外地地点、机场或航站楼时，遵从用户约束，不用附近地点替换。
6. 保留端云识别仲裁及当前轮采用检查。NLU 输出、云端回复下发和端侧实际采用是不同事件。
7. 沿用当前断线语义：结束未完成在线任务，不重放 PCM，不自动恢复待选任务，不补执行。已发生的地图拉起不会因断线而撤销。
8. 首期一个活动在线业务任务，不做任务栈、跨设备接续、长期记忆或通用工作流平台。
9. 机票作为第二类多槽位业务验证方案，首期使用确定性测试信源，仅验证补槽、选择和确认；真实出票、支付和供应商对接另行实施。

以上是本项目的设计选择，不宣称为行业统一架构。文中的新增类、消息、能力名和配置均为实施建议，尚未进入代码或正式协议。

## 2 当前代码与目标方案的差异

下表是基于当前实现的核对结果。源码链接位于文末；方法名用于定位行为，避免仅依据历史文档判断现状。

| 当前落点 | 已有行为 | 需要调整的地方 |
| --- | --- | --- |
| `VoiceEngine.onTurnResult` | 端云仲裁后确认话语、校验当前轮，再交 `ResponseDispatcher` | 新业务回复仍经过该采用边界；随后按关联设计将业务部分迁入 `voice-business` |
| `ConversationController` 与 `DialogueStateMachine` | 管理当前轮及五种交互阶段 | 保留，不能再创建一套相同交互状态机 |
| `TaskDialogueCoordinator` | `offer` 在端侧生成任务 ID 和 revision，`claim` 防止重复选择，锁内转换、锁外 FIFO 效果 | 扩展为通用交互期待管理；云端业务身份与本地任务身份分开 |
| `AppDialogueManager` | 管导航待选任务、聆听策略、上下文发布及闲聊模式 | 增加通用补充信息、选择、确认入口；逐步取消导航专属耦合 |
| `NavigationSession` | 内部组合任务协调器，维护候选及地图交接记录 | 候选迁为通用交互快照；保留导航行程和地图交接事实 |
| `NavigationDialoguePolicy` | 校验候选 ID、名称、坐标与本地列表一致 | 作为选择执行校验复用，不承担云端信源解析 |
| `NavigationDialogService` | pending/active 候选缓存，`prepare → commit → adoptExact`；现代路径只读解析选择 | 不是完整通用云端 DM；新增在线业务任务和补槽服务，旧路径独立兼容 |
| `NavigationToolFacade` | 聚合高德 MCP 工具；单地点输出仍含“展示候选，等待下一轮确认”指示 | 改为结构化解析结果，由业务策略决定直接执行、选择或失败 |
| `NavigationCandidateReplies.from` | 单目的地返回 `choose_destination`，多目的地选每站首项并打开路线预览 | 新路径单目的地改为确定性就近决策，旧路径暂留 |
| `NavigationToolFacade.rankAirports` | 先按国际机场主体、其他机场主体等分档，同档按球面距离排序 | 与“最近机场”并不完全一致，必须调整排序而非仅取现有首项 |
| `ClassicOnlineSpeechProvider` | ASR 后执行退出控制、`navigationDialog.complete`、业务 LLM | 抽取两种后端共享的业务 DM 入口 |
| `HybridBusinessChatSpeechProvider` | 普通业务同样走业务 LLM，显式闲聊走独立 Qwen 通道 | 普通业务接同一 DM；不把闲聊流当作业务补槽状态机 |
| `Reply`、`SegmentResult` 和客户端 `Reply` | 主要传递 text/action/audio 与 Intent，没有通用补槽指令 | 增加类型化业务对话结果并贯穿转换链 |
| `ResponseDispatcher` | 已采用 ActionReply 直接交业务执行，再播报 | 补槽、选择、确认必须进入交互 DM，不能误用为立即执行动作 |
| `ActionExecutionGateway` | 按 turnId 做进程内执行去重 | 新跨轮动作增加独立执行身份；不把 actionId 当作已有持久化幂等保障 |
| `GatewayNavigationContextChannel` | 精确发布/关闭导航候选引用，接收缺失反馈 | 新增通用业务对话通道，复用现有 WebSocket |

检索本次阅读的客户端和服务端业务源码，未发现完整机票补槽、航班候选及下单领域实现。机票流程不能作为现成模块直接接线。

已有文档中，[导航领域状态](navigation-domain-state.md) 的部分恢复描述早于当前任务设计；以代码和[当前架构](current-architecture.md)的“断线结束、不恢复待选任务”为迁移约束。[既有任务 DM 设计](dialogue-manager-task-architecture.md)中“客户端拥有导航任务”是旧路径事实，不能直接改写成“现在已经由云端拥有业务槽位”。

## 3 模块边界与数据所有权

### 3.1 目标模块

| 模块 | 目标职责 | 与当前工程的关系 |
| --- | --- | --- |
| 服务端 `contracts` | BusinessDialogue 接口、类型化提议、任务引用和事件 | 现有模块扩展 |
| 服务端 `business-dialogue` | 业务任务、槽位更新、版本、补槽策略、提议采用和任务终态 | 建议新增纯 Java 模块，依赖 contracts |
| 服务端 `navigation-domain` | 导航槽位与选择策略、信源结果校验、就近规则 | 在现有模块扩展，通过 contracts 接口接入 DM |
| 服务端 `skill-mcp` | 信源适配、结构化 POI 结果、调用预算 | 复用；不决定端侧追问时机 |
| 服务端 `speech-classic` 与 `speech-qwen-omni` | 语音后端适配，将普通业务交给同一 BusinessDialogue 端口 | 不在两个后端复制补槽逻辑 |
| 服务端 `gateway` | 消息校验、连接所有权、输入与输出资格、协议接线 | 新增独立业务对话 endpoint，不继续扩大主 handler 的业务分支 |
| 客户端 `voice-business` | AppDialogueManager、任务交互、领域执行与回复编排 | Gradle 模块和部分业务已落地；通用补槽、选择、确认仍是本方案待实现范围 |
| 客户端 `voice-engine` | ASR、NLU、仲裁、结果交付 | 目标模块，DM 不进入其内部 |
| 客户端 `gateway-client` | 连接和原始帧传输 | 不保存业务槽位 |
| 客户端 `tts` | 播放输出及真实播放生命周期 | 不决定任务完成或执行动作 |

编译期依赖由 contracts 中的端口隔离。`business-dialogue` 不直接依赖某个具体导航实现，`navigation-domain` 实现领域端口，`app` 组合根注入；避免 `agent-loop → navigation-domain → business-dialogue → agent-loop` 环。

### 3.2 状态归属

| 数据 | 权威拥有者 | 另一侧保存什么 |
| --- | --- | --- |
| 在线业务意图、原始槽位、解析实体、已选候选 | 云端业务 DM | 显示和执行需要的不可变快照 |
| 当前要补什么信息、缺槽规则 | 云端领域策略 | 当前问题及回答类型 |
| 信源候选集合与候选 ID | 云端 | 当前展示集合的快照，顺序与云端保持一致 |
| 用户点击或语音确认事实 | 端侧交互 DM | 云端接收事件，校验后更新业务状态 |
| 当前话语及是否采用识别结果 | 端侧 ConversationController | 云端保留输入与输出许可，不替代端侧采用判断 |
| 播报、收音、超时、界面与本地交互结束 | 端侧 | 必要的 task close/result 事件 |
| 导航应用拉起结果 | 端侧执行器 | 云端只记录收到的结果；没有回执时不得推断成功 |
| 离线车控任务 | 本地业务处理器 | 不要求断网时同步云端 |

“云端拥有槽位”不意味着云端可以越过客户端任务取消和当前轮门禁执行本地动作。端侧仍有最终的本地执行许可。

### 3.3 身份定义

保留已有 `sessionId`、`segmentId`、`utteranceId/turnId`、端侧 `interactionId` 和本地 `TaskIdentity`。新增字段使用业务前缀，避免改变旧 `task_dialog_v1` 字段的含义。

| 身份 | 生成方与用途 |
| --- | --- |
| `businessTaskId` | 云端生成，跨补槽轮次稳定；不复用旧端侧 taskId |
| `businessRevision` | 云端递增，代表已采用的业务状态版本 |
| `proposalId` | 云端生成，一次尚未被客户端采用的状态与输出提议 |
| `expectationId` | 云端为一次问题、选择或确认生成；用户回答必须绑定它 |
| `eventId` | 端侧生成，用于业务事件去重，不等于语音轮次 |
| `candidateSetId` / `candidateId` | 云端生成，绑定不可变候选及顺序；可由旧 selectionId 适配 |
| `commandId` | 云端为一个确定的本地动作生成；跨确认轮、重复回包保持稳定 |
| 本地 task revision | 端侧交互窗口版本；仅防止旧 UI 和 timer 操作新窗口 |

连接代次继续由传输维护。云端任务索引必须包含认证 owner、逻辑 session、连接生命周期；重连即便复用 sessionId，也不能重新开放已中止任务。

## 4 云端业务状态与采用机制

### 4.1 业务状态

建议业务状态为 `COLLECTING`、`RESOLVING`、`WAITING_SELECTION`、`WAITING_CONFIRMATION`、`READY_TO_EXECUTE`、`AWAITING_RESULT` 及终态。终态包括 `COMPLETED`、`FAILED`、`CANCELLED`、`EXPIRED`、`ABORTED`。这些状态不加入客户端五阶段交互枚举。

云端槽位除值以外，保存来源和解析阶段，例如：

```text
destination.query = 机场             来源：用户
destination.resolvedPoi = POI A      来源：信源与就近策略
departureDate = 2026-10-01          来源：用户“明天”加请求日期与时区
```

槽位更新允许 SET、CLEAR、REPLACE。修改上游槽位时，领域策略使依赖数据失效：机票改日期后清除旧航班集合和确认；导航改目的地后清除旧 POI 和待执行命令。NLU 只生成更新提议，不能自行写任务库。

### 4.2 为什么需要提议与采用分离

当前已有端侧仲裁。云端生成回复时，端侧可能已采用本地指令或结束当前轮。因此不能在 LLM 返回时直接把“下一步追问”写成有效业务状态，也不能仅凭服务端允许下发就认为用户已看到该问题。

复用现有 `prepare/commit/adoptExact` 的原则，将新路径分成：

1. 云端在固定的业务快照上计算更新、查询只读信源，生成 `DialogueProposal`。它携带 base/next revision、输出指令及拟提交槽位，不修改已采用状态。
2. 服务端输出许可通过后，登记有期限、有容量上限的 pending proposal，再发送回复。落败或过期的输出不能覆盖活动任务。
3. 端侧在既有仲裁及当前轮检查之后，将回复交 AppDialogueManager；校验连接、任务引用和版本，接受该提议。
4. 端侧发送 `ADOPT_PROPOSAL`。云端串行检查 owner、任务版本和 proposal，原子提交业务状态，回传采用结果。
5. 同一 proposal 的重复采用返回同一结果；不同 proposal 不能从同一 base revision 重复提交。取消或关闭对任务的后续采用具有否决作用。

首轮任务尚无已采用状态，baseRevision 为 0；首次采用后建立 revision 1。后续每个业务状态转换更新 revision。内部查询进度不必每一步同步为新的公开 revision。

对于补槽，端侧可以在发送采用后立即展示和播报；但下一次回答进入云端业务处理前必须确认对应提议已采用。同一 WebSocket 上先处理采用事件，再登记后续 `audio_start` 的固定上下文。若采用仍在异步处理中，网关只能有界等待明确结果，超时返回 `ADOPTION_PENDING` 并结束该次请求，不回退旧上下文、不重放 PCM。客户端收到拒绝时关闭对应期待。

对于已有完整参数的本地导航动作，端侧不必等待云端采用 ACK 再拉起地图。执行回执须包含 proposalId 和 commandId，服务端能够幂等处理“采用并记录执行结果”；ACK 丢失不能导致第二次本地执行。若任务已关闭，迟到执行回执只能记录已发生的事实，不能再提交提议或恢复活动状态。此优化仅适用于本地动作，不能用于出票等远端写操作。

### 4.3 不在推测阶段执行远端写操作

当前 `AgentLoop` 和工具声明控制调用方式，不等价于具有完整的订单提交协议。新业务 DM 的补槽及候选准备阶段只允许已明确声明的只读工具。新增订单类操作必须由采用后的业务执行入口触发，并具备供应商幂等键和结果查询；本期测试机票不执行真实远端写入。

## 5 端侧交互管理

端侧复用 `ConversationController`、`DialogueListeningController` 和 `TaskDialogueCoordinator` 的时序边界，不创建第二套麦克风或播放状态。通用交互上下文至少包含业务引用、expectationId、问题/候选快照、允许的回答类型、完成去向。

| 交互类型 | 端侧行为 | 完成后 |
| --- | --- | --- |
| ASK_INFO | 播报云端问题，记录待回答的槽位提示 | 自然语言答案随固定业务引用回云端 |
| SELECT | 展示候选、翻页，处理点击或明确序号 | LOCAL_EXECUTE 或 RETURN_TO_CLOUD |
| CONFIRM | 展示明确对象，处理确认、拒绝和超时 | LOCAL_EXECUTE 或 RETURN_TO_CLOUD |
| EXECUTE | 校验完整命令并调用本地业务处理器 | 回传执行结果 |
| RESULT | 展示普通业务结果 | 按既有续听策略收口 |
| ERROR | 呈现失败及明确的后续操作 | 结束或按云端指令保留当前期待 |

ASK_INFO、SELECT、CONFIRM 不能通过现有 `ActionReply → execute` 路径假装成成功执行。`ResponseDispatcher` 先将类型化对话回复交 DM；只有 EXECUTE 或有效本地确认后的命令才调用 BusinessHandler。

`TaskDialogueCoordinator.offer` 目前每次创建新任务身份。新增通用业务交互可以继续更换本地窗口身份，但必须在 context 中保留稳定 businessTaskId；若复用同一窗口，则新增显式 update 操作推进本地 revision。不可用旧 offer 隐式生成新云端业务任务。增加 `AWAITING_CLOUD` 或等价的受控阶段，区分“已回传选择，等待云端”与“本地动作正在执行”。

语音序号和“确认/取消”先经过识别形成语义，再由 DM 采用。建议新增纯规则 `InteractionAnswerInterpreter`，仅使用输入开始时的只读期待快照，输出 SELECT/CONFIRM/REJECT 等提议，通过既有仲裁链交 DM；不在 ASR partial 回调中直接执行。没有本地可用转写时仍使用云端理解，这不改变端侧拥有选择和确认交互的事实。

“第二个”映射到当前 candidateId；“不要第二个，换成明天下午的”不能被简单序号规则截断，交云端更新槽位。首期不改变车窗和退出对话的即时本地优先级。

播放完成、失败和无输出都要驱动续听收口，沿用已有监听窗口和绝对交互上限。补槽允许独立配置业务总期限，但不能在 DM 以外重置现有交互期限；超时后首期不支持自动恢复业务任务。

## 6 导航与机票业务规则

### 6.1 导航主流程

```text
导航到机场
  → 云端理解目的地描述
  → 取本轮有效定位
  → 信源召回并过滤不符合“机场”语义的地点
  → 按距离选择最近的有效机场主体
  → 云端准备 POI、坐标系和导航命令
  → 端侧采用
  → 直接调用导航，或按配置完成端侧执行确认
  → 端侧回传地图交接结果
```

这里“最近”首期定义为合法候选间的地理距离，不声称是驾车距离或最快路线。地点适配先过滤机场停车场、酒店等非目标实体，主体去重后再按距离排序；距离相同用稳定字段打破平局。当前“国际机场先于其他机场”的优先级不能继续压过距离。

只需确定一个目标时，信源召回仍需足够候选，不能将 limit 简单改为 1 再宣称获得最近结果。应在结构化解析层区分召回数量和最终输出数量；最近结论限于实际检索覆盖范围。无结果按受控搜索范围策略处理，最终失败时给出检索失败，不把缺城市当作默认追问。

明确名称、外地城市、航站楼、途经点优先满足用户约束。首期多目的地继续按既有策略打开路线规划预览，保持站点顺序，不改为逐站选择或自动开始引导。

特殊情况：

- “帮我导航”：云端 ASK_INFO 询问目的地，回答后再查信源。
- 没有有效定位且请求依赖附近：返回 `LOCATION_REQUIRED`，由端侧提示开启定位或重新发起；不编造位置，也不默认追问城市。当前定位提示文案需同步调整。
- 用户明确要求“列出附近机场让我选”：可以进入 SELECT；多个检索结果本身不是选择触发条件。
- 用户自行补充明确城市可以使用，但不改变本方案默认不追问城市的规则。
- 地图 URI 打开成功仅记为 HANDOFF_ACCEPTED，不等于已进入持续导航、到达或可远程取消行程。

### 6.2 多槽位机票验证流程

新增仅用于验证的 `FlightSearchPolicy` 和 `FakeFlightSearchProvider`，以出发地、目的地、出发日期为首期查询必需槽位。不会默认用当前位置推断出发地，不要求乘机人、证件或支付信息。

```text
帮我订到上海的机票
  → 保存 destinationCity=上海 的待采用提议
  → ASK_INFO：哪天出发
明天
  → 在请求日期和时区下归一化日期
  → ASK_INFO：从哪里出发
北京
  → 槽位齐全，查询测试信源
  → SELECT：端侧展示航班
第二个
  → 端侧回传 candidateId
  → 云端更新已选航班并准备确认对象
  → CONFIRM：端侧确认所选行程
确认
  → RETURN_TO_CLOUD
  → RESULT：已选定该测试行程，未出票
```

这条链路验证的是飞行查询与意向确认，不能把最终结果称为已订票。一次说齐日期和出发地时不重复追问；修改日期后旧列表和旧确认失效。NLU 需要可靠识别“帮我导航”等不完整意图，不能仅依靠要求完整坐标的 navigate 工具 schema。

## 7 端云协议草案

### 7.1 版本与承载

新增可协商能力 `business_dialogue_v1`。当前协议允许接收未知扩展字段，GatewayCodec 的发送白名单则会过滤未声明字段；不要把发送白名单误解为接收端拒绝全部扩展字段。建议在 hello 增加可选 `capabilities`，新服务端在 ready 返回双方支持的交集，并同步修改 schema、发送白名单和客户端解析。旧服务端忽略 hello 扩展且不返回能力，新客户端据此保持旧路径；旧客户端未声明能力，新服务端不发送新 kind 或新消息。新消息类型只有协商成功后才能使用，不能因为扩展字段可兼容就直接发送未知消息类型。

新下行使用新增的 `reply.kind=dialogue`，内部携带类型化 directive；保留 `segmentId` 与 `asrText`，让普通语音结果继续通过当前云端候选和仲裁链。新增字段必须贯穿 Java Reply、OnlineSpeechResult、SegmentResult、GatewayDownlink、客户端 GatewayPayloadParser、Kotlin Reply、仲裁和 ResponseDispatcher，不允许在中间降成 TextReply 丢失任务引用。

用于结构化事件的上行建议 `dialogue_event`，控制回执为 `dialogue_event_result`。事件响应产生新业务输出时继续使用 dialogue reply，但以 `inReplyToEventId` 关联，进入端侧 DM 的待处理事件检查，不伪造音频 segment，也不重复进入已完成的语音仲裁。

### 7.2 ASK_INFO 下行示例

以下 JSON 是目标协议样例，当前代码不能直接接收。

```json
{
  "type": "reply",
  "payload": {
    "kind": "dialogue",
    "segmentId": "seg-101",
    "asrText": "帮我订到上海的机票",
    "dialogue": {
      "businessTaskId": "flight-1",
      "baseRevision": 0,
      "nextRevision": 1,
      "proposalId": "proposal-1",
      "expectationId": "expectation-date-1",
      "domain": "flight_search",
      "directive": {
        "type": "ASK_INFO",
        "slot": "departureDate",
        "prompt": "你计划哪天出发？",
        "answerType": "DATE",
        "completion": "RETURN_TO_CLOUD"
      }
    }
  }
}
```

实际契约还要定义提议过期策略、大小限制和任务关闭原因。客户端不接收或回写完整内部槽位库；下行仅含当前交互及执行所需数据。

### 7.3 上行事件与回执

| 事件 | 必要关联信息 | 服务端处理 |
| --- | --- | --- |
| ADOPT_PROPOSAL | eventId、proposalId、businessTaskId、baseRevision | 原子提交提议并返回 committedRevision |
| SELECT | eventId、businessTaskId、baseRevision、expectationId、candidateSetId、candidateId | 校验集合和版本，更新已选实体 |
| CONFIRM / REJECT | eventId、任务版本、expectationId、被确认对象 ID | 完成或拒绝对应确认，不匹配新对象 |
| CLOSE | eventId、任务及已知版本、关闭原因 | 终止同一任务及它的 pending 提议，不操作替换任务 |
| EXECUTION_RESULT | eventId、proposalId、commandId、结果 | 记录执行事实；不下发第二条相同命令 |

自然语言补充信息仍走 `audio_start → PCM → audio_end`。新增通用 businessContext 引用在音频开始时固定，含 businessTaskId、businessRevision、expectationId；不是在模型完成后读取全局“最新任务”。

控制回执返回 `ACCEPTED`、`DUPLICATE`、`STALE_REVISION`、`CONTEXT_MISSING`、`TASK_CLOSED` 等明确状态。重复 eventId 返回原回执及原 proposal 引用，不新建提议；相同 eventId 携带不同内容直接拒绝。

首期每个在线任务只允许一个等待云端响应的业务事件。用户取消或采用新任务时撤销旧等待，旧回包只能记录迟到事实，不能恢复界面。CLOSE 按精确 businessTaskId 终止当前连接上的该任务，不因云端已推进一个 revision 而漏取消；不能关闭另一个新任务。

### 7.4 选择后是否需要往返

| 情况 | 是否必须等云端响应 |
| --- | --- |
| 导航已经返回完整合法命令，端侧确认 | 否；本地执行后同步采用与结果 |
| 候选已含完整本地导航命令，明确允许 LOCAL_EXECUTE | 否；在有效期待中原子选取并执行 |
| 机票选择，需要重新校验航班或继续收集信息 | 是；RETURN_TO_CLOUD |
| 机票确认，后续为云端业务动作 | 是；不能由端侧确认直接推断云端成功 |

完整本地命令必须来自已关联的云端提议，不接受 UI 或模型随意拼接的坐标。坐标系应在契约中显式声明，并与高德 URI 的处理保持一致。当前 VehiclePosition 仅保存经纬度而未声明坐标系，多途经点 URI 则明确使用 `dev=0`；实施前必须核对设备定位来源与地图信源的坐标系，在适配边界统一后再计算距离，禁止默认两者可以直接混用。

## 8 与语音引擎拆分设计的关系

关联文档 [TTS 与语音引擎及语音业务拆分](voice-engine-business-split-design.md)已规划 `voice-engine-api`、`voice-engine` 和 `voice-business`，但这些目录不在当前 settings 中。

本方案采用它的边界：DM 和业务在 voice-business，识别引擎仅交付结果，TTS 独立。不能一边在 VoiceEngine 增加云端 DM 状态，一边再实施引擎拆分。

落地顺序采用以下约定：

1. 服务端业务 DM 和共享协议可以先独立实现，功能默认关闭。
2. 客户端实现前，先完成关联设计中 DM、ResponseDispatcher 和业务入口的边界迁移；本方案引用现有文件作为迁移起点，而不是要求再复制一份。
3. 统一文本输入、动态唤醒词和完整引擎开关不是本方案先决条件；UI 选择、确认使用结构化业务事件即可，不需要等待 `startAsrByText`。
4. 若迁移尚未完成，新逻辑先置于独立业务包、通过端口接入 AppDialogueManager，随后整体迁入 voice-business；不将它嵌入音频引擎或 GatewayClient。

## 9 分阶段实施规划

每阶段建议独立可评审变更，服务端先提供兼容能力，客户端再启用。以下均为待办，不表示已经完成。

### 第一步 固定产品规则和基线回归

**改动位置**：本文件、现有导航测试与共享场景 fixture。

1. 将单目的地策略定义为 NEAREST；可选端侧确认独立为 confirmation 配置，不用“是否多候选”隐式决定。
2. 固定就近距离定义、定位缺失结果和显式目的地优先级；当前有效定位门槛沿用 LocationQuality，不在本次同时调整。
3. 将已有“机场必出候选”的测试标记为 legacy 场景，新策略新建用例，兼容期不直接删除旧用例。
4. 记录引擎拆分与本方案的共同改动文件，确定只由一次迁移创建 voice-business。

**验收**：有效位置的“导航到机场”只有一个确定目标；“帮我导航”需要目的地；无位置不给出伪造最近机场；明确外地机场不被就近覆盖。

### 第二步 定义双端契约和协商

**改动位置**：`shared/protocol.md`、`shared/contracts/gateway-messages.schema.json`、`shared/fixtures`；两端 Reply；服务端 GatewayCodec、GatewayDownlink、SegmentPipeline；客户端 GatewayPayloadParser、GatewayProtocolSender。

1. 新建 `shared/contracts/business-dialogue.schema.json` 并引用，定义六种 directive、事件、业务引用和完成去向。
2. 在 Java contracts 增加 `BusinessDialogue`、`DialogueProposal`、`BusinessTaskRef`、`DialogueDirective`、`DialogueEvent`；客户端增加对应类型化契约。
3. 保留 Intent 作为已解析动作数据，不把业务 revision、问题、候选再次塞入字符串 slots。
4. 实现 hello/ready 的 capabilities 交集协商，贯穿消息类型、发送字段白名单和必要字段校验；保留未知扩展字段接收兼容。
5. 新增合法与非法 fixture：缺身份、错版本、空候选、非法坐标、错误 directive/completion 组合、超限 payload。
6. 为语音响应与 event 响应定义不同关联字段；控制 ACK 不推进语音状态机。

**验收**：双端序列化往返不丢 directive 或身份；旧 fixture 仍通过；未协商新能力时不输出新 kind；旧服务端不被新客户端扩展字段破坏。

### 第三步 建立云端业务任务与采用服务

**改动位置**：新增 `AutoVoiceServer/business-dialogue`，扩展 server settings、contracts 和 app 组合根。

1. 新增 `BusinessDialogueService`、`BusinessTaskStore`、`PendingProposalStore`、`DomainDialoguePolicy` 端口及提议采用服务。
2. 任务以 owner/session/连接生命周期隔离；使用有界内存存储、可控时钟和 TTL，首期不做数据库恢复。
3. 实现 SET/CLEAR/REPLACE 与依赖失效；模型只提供结构化提议，领域策略校验必需槽位与日期等值。
4. 实现 propose、stage、adopt、handleEvent、close，按任务串行/CAS 更新；信源调用在状态锁外完成，回调重新核验版本。
5. 去重事件和提议，限制每任务活动请求数，满载明确返回 BUSY；取消、关闭事件不被普通队列挤掉。
6. 实现执行结果记录，区分“已准备命令”“等待执行结果”“交接成功”“执行失败”和“结果未知”。

**测试**：新增 BusinessDialogueServiceTest、BusinessTaskStoreTest、ProposalAdoptionTest。覆盖落败提议不污染槽位、重复采用、版本竞争、跨设备隔离、过期、取消后回调、执行结果先于 ACK。

**验收**：未被端侧采用的回复只留 pending，不改变已采用状态；关闭后旧事件不能复活任务；不存在锁内调用外部信源。

### 第四步 接入两种语音后端和网关

**改动位置**：ClassicOnlineSpeechProvider、HybridBusinessChatSpeechProvider、BusinessBackendConfig、VoiceGatewayHandler、SegmentPipeline、TurnOutputPermit；新增 `GatewayDialogueEndpoint`。

1. 将普通业务从 `navigationDialog.complete` 接到通用 BusinessDialogue 端口，legacy 导航通过适配器继续处理。
2. 保留退出对话等确定性控制入口；显式闲聊仍用独立通道，不把 realtime 输出写进在线任务。
3. 网关仅在输出许可有效时 stage proposal；客户端采用回执触发正式提交。`turn_commit` 仍只表示有效话语，不等于 ADOPT_PROPOSAL。
4. 在 audio_start 固定通用业务引用，与旧 navigationTask 字段按能力选路，不能同一请求两套身份同时生效。
5. Endpoint 处理结构化选择/确认/关闭与回执，使用与普通任务一致的预算和生命周期；异步回包也检查连接和业务引用。
6. business_dialogue_v1 普通业务先使用结构化最终回复及独立 TTS。保持闲聊原有流式播放，不让含未确认业务状态的模型音频绕过采用边界。

**测试**：扩展 ClassicOnlineSpeechProviderTest、HybridBusinessChatSpeechProviderTest、VoiceGatewayHandlerTest、SegmentPipelineTest；新增 GatewayDialogueEndpointTest。

**验收**：classic 与 omni 普通业务使用同一 DM；ASR 仍独立输出；本地胜出、旧轮迟到和连接断开不会提交云端槽位；事件响应不伪造音频轮次。

### 第五步 建立端侧通用交互入口

**改动位置**：AppDialogueManager、TaskDialogueCoordinator、ResponseDispatcher、MainViewModel、GatewayBridge、GatewayProtocolSender；新增 `GatewayBusinessDialogueChannel` 和类型化交互 context。目标归属 voice-business。

1. 完成与关联设计共用的业务边界迁移，保留 ConversationController 和现有 TTS 生命周期。
2. 将 ASK_INFO、SELECT、CONFIRM 转成通用期待，保存云端业务引用与本地窗口 revision。
3. 新增采用、事件发送和回执关联；采用失败只关闭对应期待，不能清除替换任务。
4. MainViewModel 只转发选择、确认、关闭事件及投影快照；计时与业务推进留 DM。
5. 当前 UI 点击直接调用 navigationExecutor 的路径改走同一 DM claim 入口，再由效果执行器调用领域执行。
6. 提取有状态限制的规则回答解释器，语音答案仍形成候选经仲裁采用；点击不伪造 ASR。
7. 完成无输出、TTS 失败、播放被打断、旧 timer 和前后台切换的收口。

**测试**：扩展 AppDialogueManagerTest、TaskDialogueCoordinatorTest、ResponseDispatcherTest、GatewayBridgeTest；新增 BusinessDialogueChannelTest、InteractionAnswerInterpreterTest。

**验收**：点击与语音选择竞争时最多接受一个；复杂修改不被序号规则截断；新 directive 不能误执行；DM 等待云端时不会被当成本地执行成功。

### 第六步 改造导航解析与执行

**改动位置**：NavigationToolFacade、NavigationCandidateReplies、NavigationDialogService、NavigationDialoguePolicy、NavigationSession、NavigationExecutor、ActionExecutionGateway、AppBusinessHandler、VehicleContextProvider。

1. 将信源响应解析成类型化 NavigationResolution，分开召回、过滤、排序和最终决策；不依靠工具返回的自然语言 instruction 驱动流程。
2. 在 navigation-domain 新增导航业务策略与地理距离选择器；泛机场过滤主体后按距离选取，明确航站楼保留粒度。
3. 新路径替换 NavigationCandidateReplies 单目的地强制 choose_destination 的转换；旧路径暂保留原行为。
4. 增加缺目的地 ASK_INFO；定位缺失返回 LOCATION_REQUIRED，同步修改当前提示“说明城市”的默认文案，避免产品规则冲突。
5. 云端在目标任务中保存已解析实体及命令，端侧仅接收必要快照。旧 navigation_selection_start 不用于写新云端槽位。
6. NavigationSession 保留 trip/handoff；通用选择/确认交互不再与它重复保存可变状态。
7. 新动作按 commandId 在进程内 claim，旧普通动作继续按 turnId。不要直接将 commandId 填进 turnId 参数冒充轮次；增加明确方法或 ExecutionKey。
8. 手势、确认和语音最终都进入同一执行网关；同一 command 的确认后执行与重复回复共享去重。去重容量不足时拒绝新任务，不因淘汰仍有效记录而允许旧命令再执行。

**测试**：扩展 NavigationToolFacadeTest、NavigationCandidateRepliesTest、NavigationDialogServiceTest、NavigationSessionTest、AppBusinessHandlerTest、ActionExecutionGatewayTest 和 LocationQualityTest。

**验收**：较近的普通机场不会被较远的国际机场无条件压过；默认不弹列表；定位有效性与坐标系正确；重复确认只拉起一次；拉起成功仅报告 HANDOFF_ACCEPTED。

### 第七步 实现第二个多槽位业务

**改动位置**：新增服务端 FlightSearchPolicy、FakeFlightSearchProvider 和域测试；客户端复用通用 SELECT/CONFIRM，不新增机票专用交互状态机。

1. 定义目的地、日期、出发地三类槽位、校验与追问顺序；支持一次输入补多个槽位。
2. 注入 Clock 和时区，确定性解析相对日期；无效日期保留有效旧槽位并重问对应信息。
3. 接入固定测试航班集合，所有 UI 和最终回复明确测试数据、未出票。
4. 选择回云更新槽位，确认绑定当前行程；改日期或出发地后失效旧列表与确认。
5. 任务切换首期取消旧任务，不挂起恢复；回答期间车控插话按已采用新业务替换规则处理。

**验收**：“上海→明天→北京→第二个→确认”完整闭环；一次说齐不重复问；旧列表无法用于新日期；没有真实订单或支付操作。

### 第八步 完成异常生命周期与可观测性

**改动位置**：SessionRecovery、ConnectionLossObserver、AppDialogueManager、服务端 SessionRegistry/网关关闭入口、两端 telemetry 契约。

1. 统一用户取消、退出对话、后台、进入闲聊、断线、下电、超时的终止入口；本地先撤销执行资格，网络通知尽力发送。
2. 云端连接关闭结束该连接生命周期的在线任务，清理 pending proposal；重连不恢复旧期待。
3. 执行结果迟到只记录事实，不更新新任务 UI；回执丢失记为云端结果未知，不自动重发动作。
4. 增加 proposal_created/adopted/rejected、slot_updated、expectation_opened/closed、event_duplicate、command_claimed、handoff_result 等事件，关联业务和语音身份。
5. 统计各阶段耗时、额外消息数、采用失败和迟到丢弃；不把未经实测的 500ms 或 1s 作为完成标准。

**验收**：断线、取消、旧定时器和重复回包组合场景均无任务复活、重复执行或跨用户状态串用。

### 第九步 集成验收与分阶段启用

**改动位置**：共享 fixtures、CI、当前架构文档、运行手册和能力开关。

1. 先部署兼容新契约但默认关闭的服务端；客户端仅在协商成功并启用对应业务开关时走新路径。
2. 依次启用“导航直接解析执行”“导航缺目的地补槽”“端侧确认”“测试机票多槽位”。每个新任务固定协议版本，中途不从 v2 状态切回 legacy。
3. 运行下一节的自动化和真机矩阵，保存 classic/omni、客户端版本、配置、trace 和实际地图交接结果。
4. 更新 current-architecture.md 和旧导航文档中的状态归属及恢复描述；不能将本设计提前标为已实现。
5. 回滚时停止建立新协议任务，终止当前等待任务后再切旧行为。执行中的动作只观察结果，不借回滚再次执行。
6. 按现有开发流程 feature PR 到 dev，验证后再进入发布流程；本次文档工作不创建 PR、不部署。

**验收**：有旧客户端/新服务端和新客户端/旧服务端的明确兼容结果；不支持新能力时不会退化成无上下文补槽；新增任务不同时经过两套 DM 业务状态路径。

## 10 测试矩阵与执行命令

### 10.1 必须覆盖的行为

| 场景 | 预期 |
| --- | --- |
| 导航到机场且定位有效 | 就近得到合法 POI，不追问城市，不默认候选选择 |
| 明确远处机场或指定航站楼 | 遵从明确目标，不被附近机场替代 |
| 导航缺目的地 | 云端 ASK_INFO，回答合并进同一业务任务 |
| 定位缺失或过旧 | 明确 LOCATION_REQUIRED，不复用上轮位置 |
| 机票缺日期和出发地 | 云端依次补槽，端侧不复制规则 |
| 一次提供多个槽位 | 一次更新，无重复追问 |
| 改日期后点击旧航班 | 旧期待或版本被拒绝 |
| 点击与语音选择同时到达 | 只有一个 claim，后续不得重复执行 |
| 云端回复发出后本地指令胜出 | 未采用云端提议，不污染活动槽位 |
| ADOPT 与下一轮 audio_start 紧邻 | 使用已确认的新版本，或明确失败，不读旧快照 |
| 事件回包晚于任务切换 | 无新播报、无旧弹窗、无动作 |
| 确认后云端 ACK 丢失 | 已发生本地动作不重复；云端状态允许未知 |
| 取消与执行效果入队竞争 | 实际执行边界再次检查许可；已执行动作不声称撤销 |
| 同 eventId 不同内容 | 明确拒绝，不覆盖原事件 |
| 断线重连或进程重启 | 不恢复待选任务、不补执行、不承诺跨崩溃 exactly-once |
| classic 与 omni 普通业务 | 状态和提议采用规则一致，闲聊保持独立 |
| 同设备多连接或多设备 | owner/session/连接隔离，不能相互读取或采用任务 |
| 无 TTS、TTS 失败、旧播放完成事件 | 正确收口，不能重开已经取消的交互 |

时序测试采用受控时钟和显式完成信号，不能用固定 sleep 作为完成证明。只读检索允许沿用现有预算内策略；动作不会因为超时自动重试。

### 10.2 当前工程可用的回归入口

以下是实施时运行的命令，本次文档生成未运行。依赖环境使用项目约定的 Java 21、Android SDK 和 Node；新模块落地后将其测试纳入同一 CI。

共享协议，在 `shared` 目录：

```sh
npm test
```

服务端受影响模块，在 `AutoVoiceServer` 目录：

```sh
./gradlew :contracts:test :navigation-domain:test :skill-mcp:test :agent-loop:test :gateway:test :speech-classic:test :speech-qwen-omni:test
```

服务端集成，沿用当前 CI 的两个构建变体：

```sh
./gradlew test :app:bootJar -PvoiceBackend=classic
./gradlew test -x :app:test :app:bootJar -PvoiceBackend=omni
```

当前 CI 对 omni 排除了 `:app:test`，因此新增 BusinessBackendConfig 和 DM 装配测试时，应补齐可在 omni 下运行的集成用例或独立任务，不能把上述命令通过写成已验证 omni App 装配。

客户端受影响模块，在 `AutoVoice` 目录：

```sh
./gradlew :voice-core:test :gateway-client:test :app:testDebugUnitTest -PuseIflytekStub=true
```

客户端集成门禁：

```sh
./gradlew test lint :app:assembleDebug androidUnitTestCoverage -PuseIflytekStub=true
```

现有覆盖率脚本、发布 manifest 检查和 CI 其他任务继续保留。新建 business-dialogue、voice-business 后补充模块覆盖率和依赖边界检查，不降低现有门槛。

真机另验：真实定位、真实机场检索、明确外地地点、实际高德 URI、重复确认、播放后收音、弱网断网、退后台和地图返回。测试替身通过不代表真机链路完成。

## 11 实施完成标准

- [ ] 在线业务槽位只有云端一个写入源；端侧只记录交互事实和执行快照。
- [ ] 未采用云端提议不改变活动业务状态，turn_commit 不等于业务采用。
- [ ] 新业务结果完整经过协议、仲裁及端侧当前轮检查，不走旁路执行。
- [ ] 云端补充信息、端侧选择确认在导航和测试机票两类业务中均有用例验证。
- [ ] 默认导航按当前位置就近解析，现有“国际机场优先”和“单目的地必出列表”不再影响新路径。
- [ ] 选择、确认及动作具有稳定身份，过期和重复事件不能重复执行。
- [ ] 端侧 DM 与识别引擎、TTS 的边界与三模块拆分一致。
- [ ] 断线、下电、后台、取消及任务替换收口一致，无自动恢复旧动作。
- [ ] classic/omni 和新旧端组合有明确验证记录，真机结果与自动化结果分别记录。
- [ ] 历史文档在代码完成后更新，当前设计仍保留实际未完成事项。

## 12 代码与关联资料索引

### 客户端

- [VoiceEngine](../AutoVoice/app/src/main/kotlin/com/autovoice/app/VoiceEngine.kt)
- [AppDialogueManager](../AutoVoice/app/src/main/kotlin/com/autovoice/app/AppDialogueManager.kt)
- [TaskDialogueCoordinator](../AutoVoice/voice-core/src/main/kotlin/com/autovoice/voicecore/dialog/TaskDialogueCoordinator.kt)
- [ConversationController](../AutoVoice/voice-core/src/main/kotlin/com/autovoice/voicecore/dialog/ConversationController.kt)
- [DialogueStateMachine](../AutoVoice/voice-core/src/main/kotlin/com/autovoice/voicecore/dialog/DialogueStateMachine.kt)
- [NavigationSession](../AutoVoice/app/src/main/kotlin/com/autovoice/app/NavigationSession.kt)
- [NavigationDialoguePolicy](../AutoVoice/app/src/main/kotlin/com/autovoice/app/NavigationDialoguePolicy.kt)
- [NavigationExecutor](../AutoVoice/app/src/main/kotlin/com/autovoice/app/NavigationExecutor.kt)
- [ResponseDispatcher](../AutoVoice/app/src/main/kotlin/com/autovoice/app/ResponseDispatcher.kt)
- [ActionExecutionGateway](../AutoVoice/app/src/main/kotlin/com/autovoice/app/action/ActionExecutionGateway.kt)
- [AppBusinessHandler](../AutoVoice/app/src/main/kotlin/com/autovoice/app/business/AppBusinessHandler.kt)
- [GatewayNavigationContextChannel](../AutoVoice/app/src/main/kotlin/com/autovoice/app/GatewayNavigationContextChannel.kt)
- [GatewayPayloadParser](../AutoVoice/gateway-client/src/main/kotlin/com/autovoice/gatewayclient/GatewayPayloadParser.kt)
- [VehicleContextProvider](../AutoVoice/app/src/main/kotlin/com/autovoice/app/VehicleContextProvider.kt)

### 服务端

- [NavigationDialog 契约](../AutoVoiceServer/contracts/src/main/java/com/autovoice/server/contracts/NavigationDialog.java)
- [NavigationDialogService](../AutoVoiceServer/navigation-domain/src/main/java/com/autovoice/server/navigation/NavigationDialogService.java)
- [NavigationToolFacade](../AutoVoiceServer/skill-mcp/src/main/java/com/autovoice/server/skillmcp/NavigationToolFacade.java)
- [NavigationCandidateReplies](../AutoVoiceServer/agent-loop/src/main/java/com/autovoice/server/agentloop/NavigationCandidateReplies.java)
- [ClassicOnlineSpeechProvider](../AutoVoiceServer/speech-classic/src/main/java/com/autovoice/server/speechclassic/ClassicOnlineSpeechProvider.java)
- [HybridBusinessChatSpeechProvider](../AutoVoiceServer/speech-qwen-omni/src/main/java/com/autovoice/server/speechqwenomni/HybridBusinessChatSpeechProvider.java)
- [BusinessBackendConfig](../AutoVoiceServer/app/src/main/java/com/autovoice/server/app/BusinessBackendConfig.java)
- [VoiceGatewayHandler](../AutoVoiceServer/gateway/src/main/java/com/autovoice/server/gateway/VoiceGatewayHandler.java)
- [SegmentPipeline](../AutoVoiceServer/gateway/src/main/java/com/autovoice/server/gateway/SegmentPipeline.java)
- [GatewayCodec](../AutoVoiceServer/gateway/src/main/java/com/autovoice/server/gateway/GatewayCodec.java)

### 协议与设计

- [端云协议](../shared/protocol.md)
- [消息 schema](../shared/contracts/gateway-messages.schema.json)
- [CI 门禁](../.github/workflows/ci.yml)
- [当前客户端架构](current-architecture.md)
- [语音引擎与业务拆分设计](voice-engine-business-split-design.md)
- [已有任务 DM 设计](dialogue-manager-task-architecture.md)
- [定位与检索质量](location-search-quality.md)
- [工具执行策略](tool-execution-policy.md)
- [开发与发布流程](development-workflow.md)
