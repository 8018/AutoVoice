# 当前架构复评：导航任务 DM 与端云上下文

- 日期：2026-09-22
- 评审基线：`087fbbe`，分支 `codex/dialogue-manager-navigation`，PR [#123](https://github.com/8018/AutoVoice/pull/123)，目标 `dev`。
- 方法：模块依赖与生产接线静态审查，使用当前已编译的业务类执行三个本地复现探针；未连接生产、未调用付费模型、未启动地图。
- 结论：本报告首次评审识别的 F1–F6 已在同一 PR 后续提交中修复并补充自动化回归；新路径达到代码合并条件。仍需按 §5 做真机组合验收，且兼容旧协议期间不能把混合版本宣称为完整任务闭环。

## 0. 修复复验（2026-09-22）

首次评审的复现结论保留为变更依据；当前状态如下：

| 问题 | 修复与验收 |
|---|---|
| F1 旧 close 删除新列表 | 领域层增加 `adoptExact/closeExact`；关闭携带原 selectionId，覆盖 close(A)/publish(B) 两种顺序 |
| F2 效果发布乱序 | 任务协调器改为锁内入 FIFO 效果队列、单消费者锁外分发；覆盖旧过期与新 offer、回调重入和回调异常 |
| F3 新导航被旧列表拒绝 | 服务端可信入口签发 `select/cancel/start_new`，客户端以完整任务身份条件采用；模型 source 不作为授权依据 |
| F4 空闲断线不收口 | 独立观察 READY 下降沿，无需在途 reply；只终止 WAITING_INPUT，不撤销 EXECUTING，不重放 |
| F5 生命周期与监听分散 | 回答窗口及 60 秒上限迁入 `DialogueListeningController`；VAD capture 不取消时钟，ASR 准入才改变交互状态；旧定时器比较完整快照 |
| F6 协议缺少回执 | 新增 `navigation_context_result` 与 `CONTEXT_MISSING`；客户端按精确身份结束任务并提示，迟到回执不清新任务 |

自动化复验：Android 全量 `test lint :app:assembleDebug androidUnitTestCoverage`、服务端 classic/omni 测试与打包、schema fixtures、覆盖率门禁均通过。该结论不代替真机地图拉起、真实断网和真实云模型回归。

## 1. 对此前完成度结论的更正

此前把新增类、增加协议字段和已有测试通过，等同于目标架构完成，是不准确的。实际进度如下。

| 阶段 | 已实现 | 尚未完成或有缺陷 |
|---|---|---|
| P1 服务端不提前消费 | `task_dialog_v1` 下 select/cancel/fresh-search 不再立即删除已采用列表；旧协议保持原行为 | 精确关闭只做到网关身份校验，领域层仍清空 session 下全部候选，破坏列表替换 |
| P2 客户端任务 DM | 通用任务数据与协调器、原子 claim、导航策略、候选 UI 接线 | 没有统一 DialogueManager 入口；锁外回调并不保证 FIFO；UI 和语音入口仍分流；结果未回传完整任务身份 |
| P3 任务收口与监听策略 | ViewModel 的 120 秒定时器迁出；录音层接收通用时长和 revision | 录音层仍决定回答窗口及 60 秒期限；任务 TTL、思考超时、后台、断线分散处理；思考超时不能同步结束任务 |
| P4 端云上下文 | 上行包含四项身份，网关校验；现代路径禁止退回最近列表 | 缺少结构化 CONTEXT_MISSING、上下文采用结果、完整下行身份及可靠的连接断开事件接入 |
| P5 验收与兼容清理 | 现有测试、lint、构建及 schema 校验有通过记录 | 关键事件交错未覆盖；真机未验收；旧协议仍保留 |

## 2. 值得保留的架构基础

1. `audio-frontend` 把信号处理和 VAD 放在同一音频前端，业务读取处理后的音频与观察事件；ASR/NLU 契约继续分开。
2. `gateway-client` 保持通道职责，上行业务编码在 `GatewayProtocolSender`；下行通过类型注册的 `MessageDispatcher` 多播。
3. `tts` 提供统一输出接口，内部管理合成、缓存及播放身份。无需让导航任务持有播放器。
4. `OnDeviceRaceArbiter` 使用常驻消息队列，按 turn 防重复输出；当前轮校验位于下游 `ConversationController`，符合已确定的关注点分离。
5. `VoiceEngine` 通过 `BusinessHandler` 输出业务命令；服务端有独立 `navigation-domain` 和 `agent-loop`。应继续沿这些边界改进，而非整体重写。

当前主要缺口是交互状态、任务状态、业务执行和上下文发布之间的协调。增加一个任务类不会自动把这些入口统一起来。

## 3. 首次评审发现（现均已修复，保留问题证据）

### F1 · P1：关闭旧任务会删除已经准备好的新列表（已复现）

证据：

- `TaskDialogueCoordinator.offer` 先发旧任务结束事件，再发新任务事件：`AutoVoice/voice-core/src/main/kotlin/com/autovoice/voicecore/dialog/TaskDialogueCoordinator.kt:79`。
- ViewModel 将两个事件转成旧上下文关闭和新上下文发布：`AutoVoice/app/src/main/kotlin/com/autovoice/app/MainViewModel.kt:193`。
- 网关通过旧任务身份检查后调用 `navigationDialog.adopt(st.ctx, "")`：`AutoVoiceServer/gateway/src/main/java/com/autovoice/server/gateway/VoiceGatewayHandler.java:678`。
- `adopt("")` 同时清理 pendingStore 和 activeStore，未按 selectionId 限定：`AutoVoiceServer/navigation-domain/src/main/java/com/autovoice/server/navigation/NavigationDialogService.java:129`。

场景：机场列表 A 正显示，用户改搜车站，服务端已提交新列表 B；客户端替换任务，发送 close(A) → publish(B)。close(A) 将 pending B 一起删除，publish(B) 无法采用。界面有 B，用户说“第一个”却得到“地点选择已失效”。这不是只在网络乱序时发生；正常有序消息也会触发。

本地探针顺序：`remember(A) → prepare(B) → commit(B) → adopt("") → adopt(B.id) → resolve(B.id, "第一个")`。

实测：`replacement: kind=text, text=地点选择已失效，请重新搜索`。

修改方向：领域接口增加按确切 selectionId 关闭及明确的采用结果，关闭 A 不能删除 B；客户端关闭携带被关闭的真实列表身份。网关只在领域层采用成功后登记上下文。增加“close(A) 在 publish(B) 前”和相反次序的回归。

### F2 · P1：任务状态更新和效果发布没有保证同一顺序（已复现）

证据：`TaskDialogueCoordinator.kt:66` 在锁内更新状态，`:79`、`:102`、`:124` 直接锁外回调，没有效果队列。注释中的“remain FIFO”不成立。

生产可达路径：`NavigationSession.kt:138` 的过期协程直接调用协调器，而正常 offer/claim 从 `NavigationSession` 的同步方法进入；旧过期回调可能在等待 session 锁时被新任务状态更新超越。`onTaskChanged` 不检查事件序号，最终可能清空新任务投影并取消新定时器。

本地探针以 latch 控制：旧任务过期已更新状态但暂停发布 → 新任务 offer 并发布 → 放行旧过期回调。

实测：`effect ordering: active=new, projection=null`。不依赖 sleep 或概率性竞争。

修改方向：复用 `ConversationController` 的“锁内入效果队列、单一消费者锁外按序分发”机制，或统一 actor；发布事件带递增状态版本。计时器也必须进入同一入口。不能仅在普通方法加锁，再让计时器绕过。

### F3 · P1：已有现代候选列表时，新的直接导航结果被当成旧列表选择拒绝（已复现）

证据：`AutoVoice/app/src/main/kotlin/com/autovoice/app/NavigationExecutor.kt:68` 只要 `pending.selectionId != null` 就进入必须携带旧 selectionId/candidateId 的选择分支；后面的 fresh-request 替换分支对现代列表不可达。

场景：显示机场列表后，用户说“改去这个地址的酒店”，云端返回带明确坐标的全新 navigate；因为它没有旧候选身份，被拒绝，旧机场弹窗仍保留。新的多地点路线结果也可能被同一条件拦截。

实测：`fresh resolved search: accepted=false, launches=0, remainingSelection=airport-list`。

修改方向：语义契约明确区分 SelectCandidate 和 StartNewNavigation；选择严格检查旧任务身份，新检索经当前轮准入后显式替换旧任务。不能简单放开所有无 ID 的坐标动作，否则会削弱候选归属检查。

### F4 · P1：空闲待选期间的连接断开没有直接结束任务（静态证据）

证据：`GatewayAdapter.kt:363`、`:459` 的请求异常 catch 才触发 `onCloudUnavailable`；`GatewayBridge.handleNluError`（`:863`）仅结束现存 reply 槽和音频流。Factory 在 `:254`、`:271` 通过请求失败或下一次 ready 清理任务，没有直接订阅连接状态下降沿。

场景：播报结束、候选弹窗等待选择，没有在途 reply，WebSocket 此时断开。没有等待该 reply 的协程触发 catch，列表可能继续显示；用户点击仍能从本地列表拉起导航。这不符合“断线结束待选任务”的既定规则。下一次握手再清理只是部分补救。

修改方向：由传输适配层向 DM 发布带连接代次的 ConnectionLost 事实；DM 终止该连接关联的待选任务。已交给地图的执行结果另行记录，不补执行、不伪装撤回。连接恢复不重放旧任务。

### F5 · P2：交互期限和任务生命周期仍是两套控制路径（静态证据）

证据：

- `RecordingCoordinator.kt:473` 决定回答窗口与 60 秒剩余期限，仍承担 DM 策略。
- `NavigationSession.kt:136` 独立使用 120 秒 TTL。
- `VoiceEngine.kt:398` 的思考超时仅调用 `conversation.onThinkingExpired`。
- `MainViewModel.kt:311` 在交互变成 DORMANT 时只转发录音状态，没有同步结束导航任务。

场景：选择第二个时云端长时间未响应，交互思考超时回到空闲，候选任务却仍 WAITING_INPUT，列表仍可被点击，直到另一个定时器收口。另一方面，VAD 开始 capture 会先取消回答计时（`RecordingCoordinator.kt:465`），并非由 ASR/NLU 准入统一决定。

修改方向：DM 拥有交互/回答期限和任务结束规则，定时器发带身份及代次的事件；录音层执行监听启停。保留现有“不由 VAD 推进交互状态”的约束，并明确短噪声 capture 与业务回答计时之间的恢复规则。增加思考超时、同交互换任务、无输出、空合成和误报 capture 测试。

### F6 · P2：任务身份与失败语义尚未形成端到端契约（静态证据）

上行已带 taskId/revision/interactionId，网关校验是进步。但 `NavigationDialogService.select/cancel`（`:190`、`:204`）只在结果中返回 selectionId/candidateId；客户端 `claimSelection` 读取当前任务身份再 claim，并未验证结果附带的完整任务身份。现有列表和坐标校验仍有效，不能据此说“完全没有保护”，但方案中的精确上下文闭环尚未完成。

上下文缺失只返回普通 TextReply（`:155`），没有结构化 CONTEXT_MISSING；客户端 `ResponseDispatcher` 将它作为正常文本播放，无法据此结束残留任务。`navigation_selection_start` 没有成功/失败确认，网关甚至先更新身份字段再调用返回 void 的 adopt。

修改方向：领域解析输出类型化导航提议及任务引用；明确 ContextAccepted/ContextMissing 语义。无需为了命名另造全部消息，允许扩展既有消息；但不得只加字段而缺少行为与对应测试。

## 4. 职责边界仍需收拢

当前 `DialogueManager` 门面没有落地。实际链路是：语音 → ConversationController → ResponseDispatcher → AppBusinessHandler → NavigationExecutor → NavigationSession → TaskDialogueCoordinator；UI 点击从 MainViewModel 直接进入 NavigationExecutor。两者只共享末端 claim，并未共享完整事件与效果入口。

MainViewModel 仍构造任务协议、维护 publishedNavigationContext、触发上下文关闭和任务超时；NavigationSession 仍包含候选任务、trip、handoff、TTL；RecordingCoordinator 仍决定交互期限。`NavigationDialoguePolicy` 已抽成纯类，但仍在 app 并依赖 NavigationExecutor 中定义的候选类型；尚未成为独立纯导航对话模块。

建议先建立应用级的统一 DM 协调入口，内部组合现有 ConversationController 与 TaskDialogueCoordinator，注册导航策略。业务执行器只执行已采用命令并按身份返回结果。UI 接收只读快照、发送明确身份的点击/关闭事件。最后按真实依赖需要迁移模块，不以新增目录数量判断解耦程度。

## 5. 建议实施顺序及验收

1. 修 F1：领域层精确关闭/采用返回值，补正常替换链路测试。这直接影响改搜后的二轮选择。
2. 修 F2：统一状态与效果顺序，补可控并发和回调重入测试，再接其他生命周期事件。
3. 修 F3：类型化“旧候选选择/新导航任务”，同时验证拒绝伪造旧候选和允许合法改搜。
4. 收拢 F4/F5：连接事件、交互超时、任务过期、后台、进入闲聊统一进入 DM；录音层执行监听指令。
5. 补 F6：结果带任务引用、明确上下文缺失与采用结果，保留兼容窗口。
6. 自动化组合回归已补；合并后仍需真机执行“机场→换车站→第二个”“机场列表→改去唯一酒店”“空闲待选断线→点击”“思考超时后旧列表”“点击与语音同到”。未经真机验证不建议直接发布生产。

以上均不需要引入持久化动作账本、自动重试、跨重启任务恢复，也不需要让仲裁器认识当前轮。

## 6. 验证证据与局限

- 上一轮记录了端侧/服务端测试、lint、构建、schema 通过；本次未将这些结果当作任务闭环正确性的证明。
- 本次额外使用 `087fbbe` 的已编译类做 F1/F2/F3 本地探针，三项问题全部复现；并发探针使用 latch 排序。
- 现有 `TaskDialogueCoordinatorTest` 只有两个测试，其中“click and voice”实际为连续调用 claim，并未覆盖并发事件发布顺序。
- 网关旧关闭测试只覆盖“新上下文已经发布后，旧关闭迟到”，未覆盖真实替换顺序中的“旧关闭先到，新列表已 pending”。
- F4/F5/F6 为生产接线静态结论；尚未对真机、网络故障、云端真实模型延迟进行现场验证。
- 本报告评估模块边界及导航多轮一致性，未重新进行容量压测、密钥审计或部署恢复演练；不替代生产发布验收。
