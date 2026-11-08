# 语音组件初始化结果与可用性上报设计

日期：2026-11-08。基线：渠道改造 Task2，44ce525。
状态：用户已于本会话批准按此实施；尚未实施。用户已选择 App 内可见＋结构化日志，本期不增加服务端上传。

## 目标与现状

每个选中渠道的组件都明确报告：是否开始初始化、是否成功、失败在哪里、影响什么功能，以及能否恢复。正常组件继续工作，界面能解释不可用的功能。原讯飞识别与唤醒算法、授权方式和业务策略不因本改动改变。

当前已有三种分散信号：MainViewModel/RecordingCoordinator 的 wakeError；frontend 的 unavailabilityReason/vadAvailable；讯飞命令识别初始化异常仅 Log.w。SpeechChannelCapabilities 是装配期已知缺失信息，不能代表运行时模型/授权就绪。统一报告需覆盖同步异常、异步回调、超时和依赖阻断，不能只包一层 runCatching。

已有 TelemetryClient 按 utteranceId 归集对话事件，record 在未 begin 时丢弃事件。因此启动初始化结果不走该通道，不伪造业务轮次；本期使用独立本地状态/日志出口。未来如远程上报，单独设计设备生命周期事件接口。

## 方案比较与选择

1. 各组件继续增加错误字符串：改动少，但错误、恢复和界面状态会继续分散，不能解释依赖链；不采用。
2. 统一状态中心＋组件报告端口＋功能可用性聚合：适配器保持独立，App 能汇总依赖、降级与诊断；推荐并按此设计。
3. 统一状态中心＋服务器设备健康上报：适合远程排障，增加服务端协议、存储和离线队列；用户本期选择不包含上传。

## 分层与职责

- voice-core 增加纯 Kotlin 状态数据、稳定错误码和 ComponentStatusSink 端口，不引用 Android、UI 或具体 SDK。
- 每个适配器只报告自己真正拥有的初始化过程。状态中心不能替 SDK 完成授权、创建第二份 recognizer 或重复加载模型。
- App 层 ComponentStatusRegistry 合并线程安全、不可变的快照，作为唯一状态源；在加载配置和构造 adapter 之前创建，避免最早的配置/JNI失败无人记录。
- SpeechChannel 只注册选中 flavor 的组件、依赖和功能策略。Open 不初始化讯飞命令识别；另一渠道未选择不算失败。
- CapabilityEvaluator 将组件快照和当前模式转换为功能状态。它不执行识别、不建立 turn、不提交候选、不切换业务状态机。
- MainViewModel 订阅 StateFlow，向界面投影；日志出口订阅状态变化。录音/识别入口仍按实际就绪性做最后检查，不能只靠按钮置灰防竞态。

## 组件清单与依赖

| 稳定 ID | 组件与就绪判据 | 依赖/说明 |
| --- | --- | --- |
| config | 所选渠道配置解析、provider 和参数校验完成 | 配置失败阻断该配置的装配；诊断仍可展示 |
| capture | 麦克风权限、实际设备打开/启动结果 | 权限未请求为等待条件，不当作 SDK 失败；按实际首次打开报告 |
| iflytek.runtime | 共享 AIKit 授权成功 | 进程级单例，两渠道均供唤醒使用，只报告一份授权根因 |
| wake | 唤醒资源加载与能力初始化完成 | 依赖 runtime；是否正在监听另用运行态，暂停不等于初始化失败 |
| frontend | 选中前端可创建并处理规定格式音频 | Open 为 WebRTC；iflytek 沿用原 legacy |
| vad | 选中 VAD 创建与参数配置成功 | Open 为 WebRTC VAD；iflytek 为 Silero |
| render_reference | 播放驱动能提供时序、格式正确的参考音频 | 无播放时为 idle；非失败，不要求启动时已播一段音频 |
| aec | AEC 初始化状态及参考链路健康 | APM 创建成功不等于 AEC 实际可用；健康随播放变化 |
| local.asr | 讯飞命令能力初始化完成，或 FireRed runtime/模型成功加载 | iflytek 依赖 AIKit；Open 依赖 sherpa/JNI 与经校验的模型，不依赖讯飞授权 |
| local.nlu | 所选规则引擎配置就绪 | 规则引擎本身不依赖 ASR 模型；本地命令功能需二者都可用 |
| cloud | 当前模式启用时的网关连接/握手状态 | 关闭为 disabled；连接可用性独立于初始化状态 |
| playback | 所选播放驱动创建状态 | 某次合成/播放请求失败另报请求错误，不冒充组件初始化失败 |

FireRed 内部校验子阶段：native_load、resource_validate、model_create、可选 warmup。对外仍是一份 local.asr 状态，详情保留失败子阶段、runtime/model 版本与原始厂商错误码。不能仅因 System.loadLibrary 成功就报告模型就绪。

## 状态模型

生命周期状态：NOT_STARTED、INITIALIZING、READY、FAILED、BLOCKED、DISABLED、RELEASED。

- NOT_STARTED：尚未触发，允许懒加载；不显示为成功或故障。
- INITIALIZING：正在授权/加载/配置，显示阶段和已耗时。
- READY：规定的初始化操作已真实完成。
- FAILED：本组件初始化尝试失败，保存本组件根因。
- BLOCKED：依赖未满足，保存 blockedBy/rootCauseId；不重复制造授权失败等根因。
- DISABLED：配置关闭、测试提供者显式启用或当前渠道不选择该能力时按原因展示；不算故障。显式 fake 应标明测试模式，不能展示真实 ASR 就绪。
- RELEASED：该实例已关闭，旧回调不得改回 READY。

生命周期之外，独立记录运行健康：UNKNOWN、HEALTHY、DEGRADED、UNAVAILABLE。初始化 READY 可保留，但后续设备断开、模型执行器故障或 AEC 参考丢失会改变健康。没有播放参考且当前没有播放时不算 AEC 故障；播放时参考失配才撤销有效能力。

功能聚合状态：AVAILABLE、LIMITED、UNAVAILABLE、PENDING、DISABLED。聚合依据当前模式和有效组件，不以全部组件是否 READY 来决定 App 能否使用。

每份 ComponentStatus 至少包含：componentId、provider、channel、generation、attemptId、sequence、lifecycle、health、stage、startedAt、updatedAt、durationMs、error、blockedBy、版本信息。duration 用单调时钟计算；墙钟仅用于展示/日志。error 包含稳定 code、脱敏 message、vendorCode、retryable、建议操作；异常对象不进入 UI 状态。

## 错误码与故障归因

| 错误码示例 | 结果与建议 |
| --- | --- |
| CONFIG_INVALID / PROVIDER_MISMATCH | 阻断当前配置装配；显示配置问题，不切 fake |
| MIC_PERMISSION_DENIED / CAPTURE_OPEN_FAILED | 语音输入不可用；允许文本，按原因引导授权/重试 |
| NATIVE_LIBRARY_MISSING / NATIVE_ABI_MISMATCH / NATIVE_SYMBOL_MISMATCH | 对应组件不可用；显示缺少或不兼容的运行库，不反复自动重试 |
| MODEL_MISSING / MODEL_HASH_MISMATCH / TOKENS_MISMATCH / MODEL_LOAD_FAILED | FireRed 不可用；保留其他链路，不静默下载、切讯飞或切 fake |
| SDK_CREDENTIALS_MISSING / SDK_AUTH_FAILED / INIT_TIMEOUT | runtime 根因；wake 和讯飞命令能力显示被它阻断 |
| RENDER_REFERENCE_STALE / RENDER_QUEUE_OVERFLOW | AEC 运行健康降级；撤销普通话术打断能力，记录参考原因 |
| COMPONENT_RUNTIME_FAILED / RESOURCE_EXHAUSTED | 区分初始化/运行阶段，关闭受影响能力并给出恢复条件 |

失败消息不直接拼接任意异常文本到 UI。日志保留受控的异常类型、阶段和厂商码；过滤凭据、token、完整认证 URL、模型个人路径和音频/转录内容。启动诊断不新增原始音频保存。

## 降级与功能策略

| 失败项 | App 行为 |
| --- | --- |
| 唤醒或 AIKit 授权失败 | 关闭语音唤醒；前端和 ASR 可用时保留按键录音。Open 的 FireRed 不因 AIKit 失败停用 |
| FireRed/本地 ASR 失败 | 关闭本地识别候选；云端已配置、连接可用且前端满足时保留云端语音。离线模式明确显示语音识别不可用，可用文本 |
| 前端不可用 | 按渠道安全策略阻止处理后的语音输入，不透传 PCM 假装处理完成；文本独立可用；原始唤醒监测是否允许保持既有策略 |
| VAD 失败 | 报告分段/自动结束/续听等受影响能力；iflytek 保留原降级行为。Open 仅保留经验证不依赖 VAD 的按键整段能力，否则阻断相关入口；不自动套旧 VAD |
| AEC 或参考异常 | 关闭普通话术打断/相关开放麦克风能力，保留原有唤醒和按键策略；不把“APM 已创建”当作 AEC 健康证明 |
| 云端断开 | 本地链可用则保留本地；离线配置中的云端 disabled 不是错误 |
| 播放驱动失败 | 展示回复文字；不能宣称已经播报完成；不改变 DM 的既有副作用采用规则 |

本设计只让失败显式可见并约束已经存在的能力边界，不新增暗中切换 provider、重试识别、重复上传或自动改变用户所选模式。

## 初始化、并发、超时与恢复

1. 创建 registry，分配 App 会话和装配 generation，注册该渠道组件；然后读取配置。配置失败也进入快照并保留诊断界面。
2. 各 adapter 初始化前报告 INITIALIZING；同步返回、异步 SDK 授权、模型加载最终结果都带 generation＋attemptId。完成时原子检查身份，只接受当前有效尝试。
3. 已共享初始化的 runtime 只运行一次；命令与唤醒订阅同一个结果。各子能力另报资源初始化结果，避免把 SDK 授权和唤醒资源成功混为一谈。
4. 懒加载保持现状；用户首次触发时显示准备中，不能发送给未就绪的本地引擎。UI 出现 READY 的时刻必须对应实际操作完成。
5. 沿用厂商既有超时（AIKit 当前 20 秒）；模型阶段超时按可配置预算及实机测量确定。超时报告 FAILED/INIT_TIMEOUT，禁止迟到结果改写状态。
6. 协程取消不等于 native 调用已停止。无法中断的模型加载/推理仍由原线程收尾，安全释放后才允许下一尝试；不因超时并发创建第二个大模型。取消作为取消处理，不捕获成普通 SDK 失败；致命 VM 错误不靠通用 catch 吞掉。
7. 配置重装/模式切换递增 generation，释放旧实例。旧实例关闭、授权回调或推理完成不能覆盖新状态、重新开麦克风或重载模型。共享进程 runtime 按自身生命周期去重，不因模式切换反复授权。
8. 仅组件明确支持时允许重试。权限变化、资源安装完成、网络恢复可触发相应检查；坏包、错误 ABI、坏摘要不无限重试。AIKit 进程级 FAILED 暂按现有实现要求重启，不增加未经验证的 singleton 重置。

## App 展示与日志

主界面提示“语音功能受限：本地识别暂不可用”，按实际可用功能控制入口。详情页显示组件名、状态、影响、简短原因、建议操作和耗时；正常项可折叠。没有故障时只显示简要就绪状态，不持续弹窗；故障提示去重，恢复后更新而不是留下过期横幅。

示例：
- 本地识别：失败；模型文件缺失；离线语音不可用；请安装对应模型资源。
- 语音唤醒：可用。
- 音频处理/VAD：可用。
- 云端识别：未启用（当前离线模式）。

结构化事件：component_init_started、component_init_succeeded、component_init_failed、component_blocked、component_health_changed、component_released。包含 schemaVersion、eventId、appSessionId、generation、attemptId、componentId/provider/channel、状态、阶段、耗时、错误码、阻断根因和版本，不带 utteranceId。每个状态变化记录一次；重复健康报警合并计数，恢复仍记录一次。音频线程只投递轻量事件，磁盘写入异步有界；队列溢出计数可见，不阻塞采集。

日志首版沿用 Android 结构化 Logcat；registry 保留有限的最近状态变化和当前完整快照，详情页支持复制脱敏 JSON。暂不增加长期磁盘日志、上传或跨启动持久化故障，避免过期状态被当作本次结果；如需导出文件，另作明确入口。

## 修改范围与任务衔接

| 文件/模块 | 修改内容 |
| --- | --- |
| voice-core/.../health/ComponentStatus.kt、ComponentStatusSink.kt | 新增纯数据、错误码、报告端口 |
| app/.../health/ComponentStatusRegistry.kt、CapabilityEvaluator.kt、ComponentLogSink.kt | 状态归并、依赖/功能投影、结构化本地日志 |
| channel/SpeechChannel.kt 与两个 SelectedSpeechChannel | 注入报告端口，声明选中组件/依赖；装配期能力描述与运行时状态分开 |
| WakeWordFactory、IflytekAiKitRuntime、IflytekWakeWordObserver | 共享授权根因和唤醒资源结果，保留原 SDK 调用所有权 |
| adapter-iflytek 命令 ASR 初始化 | 由仅日志改为状态＋日志，支持真实初始化成功/失败/超时 |
| audio-frontend 与 legacy/WebRTC adapter | 前端/VAD 初始化报告；Open 参考与 AEC 健康变化；保留旧降级 |
| adapter-firered | runtime、资源校验、模型创建阶段与错误映射，模型失败只影响本地 ASR |
| AudioRecorder / RecordingCoordinator | 权限、采集设备和运行错误报告，保留单一 AudioRecord 和实际入口检查 |
| MainViewModel / VoiceEngineFactory / MainActivity | 在最早装配前创建状态中心，接线状态；诊断入口和主页面简要提示 |
| TTS 播放驱动与网关接线 | 报告驱动/连接状态；请求级错误仍留在原请求通道 |

不影响当前 Task3 构建工具。原 Task4/5/6 中组件结果接线在本设计批准后纳入实施，或单独新增阶段；主 agent 应先修订任务 brief，再放行实现。每阶段仍由同一个实现子 agent 完成、主 agent 审核。

## 验收

- 缺 JNI、错误 ABI、缺模型/坏摘要/tokens 不匹配、SDK 授权失败/超时、麦克风无权限各有稳定码与可见结果，不能导致无诊断崩溃。
- 共享 AIKit 失败只有一个根因；Open FireRed 可以继续就绪，wake 变为 blocked；iflytek 命令也明确受阻。
- 尚未触发、模式关闭、初始化中分别可辨，不能当作失败或成功。
- FireRed 失败不关闭工作中的前端；云端断开不关闭可用本地；云端禁用不出现红色故障。
- 旧 generation/attempt 的迟到成功不能覆盖失败/释放/新实例；native 超时不产生双重模型初始化。
- 播放参考丢失及时撤销相关 AEC 能力，恢复按真实健康判据更新；未播放不误报故障。
- 状态和错误在两渠道可见，符合实际有效能力，日志字段稳定、脱敏、事件去重；初始化事件不进入对话轮、不触发业务副作用。
- 本地单元测试覆盖状态机/依赖/竞态；Android 组件测试覆盖 UI 和真实回调边界；真实授权、native 故障和 8295 测试单独列出，不以 stub 结果代替。
