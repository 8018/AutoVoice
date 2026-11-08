# 组件初始化上报 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 两渠道的选中组件均能在 App 内显示真实初始化与健康结果，日志可定位根因和功能影响。

**Architecture:** voice-core 定义无 Android 依赖的数据/报告端口，App 状态中心聚合并投影功能状态。适配器报告真实生命周期；同一 AIKit 授权只报告一份；报告不参与 DM/仲裁。新 native 组件随原渠道计划 Task4–6 接入。

**Tech Stack:** 现有 Kotlin/JVM、StateFlow、Compose、JUnit；结构化 Logcat，不新增服务端依赖。

**Spec:** docs/superpowers/specs/2026-11-08-component-initialization-reporting-design.md（用户已批准）；原渠道计划 docs/superpowers/plans/2026-11-08-open-speech-channel.md。

## Global Constraints

- 本期仅 App 内可见＋结构化日志，不上传服务器，不伪造 utteranceId。
- 旧讯飞算法/参数/SDK所有权保持；只允许为真实报告修复过期回调覆盖等生命周期错误，不能重复授权或重复加载模型。
- 文字独立于音频组件，但可用性仍取决于既有云端文字路由与模式；当前离线返回NO_ROUTE，不新增离线文字NLU/业务路由。
- 生命周期与健康独立；懒加载/配置关闭/依赖阻断不能当作成功或本组件故障。
- 同一进程 AIKit runtime 一份根因，Open FireRed 不依赖讯飞授权；Open 不装配讯飞命令识别。
- generation/attempt/sequence 拒绝迟到事件，释放后不可改回 READY；native 未安全结束前不并发重试。
- 无原始音频、转录、凭据、token或完整认证URL进入新增日志；队列/历史有界，音频线程不做磁盘写入。
- 继续一个实现子 agent，主 agent逐阶段审核，不另派实现者；不改原工作区、不部署。

## Review Focus

- 配置解析/JNI构造最早失败仍有可显示快照，App不得在UI建立前无诊断崩溃（Task1）。
- runtime进程存活而ViewModel重建时不能丢状态、重复授权或持有旧Activity（Task1）。
- 超时/释放/重装后的迟到SDK回调不可改写当前状态或重新启用能力（Task1、原Task5/6）。
- 无播放时无render不能误报故障；播放中参考中断必须撤销有效AEC能力（原Task4/6）。
- FireRed失败时可用前端/云端不受错误连带阻断；组件故障日志中不能出现SDK密钥（原Task5/6、Task1）。

---

### Task 1: 统一报告基础与现有渠道可见结果（主计划 Task3 之后、Task4 之前）

**Files:**
- Create voice-core/src/main/kotlin/com/autovoice/voicecore/health/{ComponentStatus,ComponentStatusSink,ComponentReporter}.kt 及对应测试。
- Create app/src/main/kotlin/com/autovoice/app/health/{ComponentStatusRegistry,CapabilityEvaluator,ComponentLogSink,ComponentHealthPanel}.kt 及对应单元测试。
- Modify app main 的 channel/SpeechChannel.kt、channel/WakeWordFactory.kt、MainViewModel.kt、VoiceEngineFactory.kt、MainActivity.kt、audio/AudioRecorder.kt、RecordingCoordinator.kt。
- Modify 两 flavor 的 SelectedSpeechChannel.kt、adapter-iflytek 的 IflytekAiKitRuntime.kt、IflytekWakeWordObserver.kt/命令初始化接线，以及 legacy frontend 工厂/适配器。
- Create/modify 必要回归测试，测试位置随当前 main/test/iflytek 源集约定。

**Interfaces:**
- ComponentStatusSink.report(event: ComponentStatusEvent) 是公共纯 Kotlin 写端口；ComponentStatusEvent 为不可变数据，含组件身份/provider/channel/scope、generation、attemptId、sequence、更新类型和错误。
- ComponentReporter.start(stage: String): ComponentToken 分配尝试；report(token: ComponentToken, update: ComponentUpdate) 上报 ready/fail/health；close() 报告释放，close(token) 仅释放匹配尝试以隔离同代旧实例。报告者绑定组件/代次；不允许旧token获得新attempt身份。
- ComponentStatusRegistry.beginGeneration(channel: String): Long；reporter(descriptor: ComponentDescriptor, generation: Long, dependencies: Set<String>): ComponentReporter；snapshot: StateFlow<ComponentHealthSnapshot>。scope 为 PROCESS 或 ASSEMBLY。依赖引用稳定componentId，进程runtime不会被一次装配释放。
- AudioFrontendEngine.attachReporting(frontendReporter, vadReporter) 默认no-op；只更换诊断绑定，复用状态标记reuse_existing，不重建APM/VAD、不重置算法。实际既有辅助检测独立使用vad.aux，不增加实例。
- Lifecycle = NOT_STARTED/INITIALIZING/READY/FAILED/BLOCKED/DISABLED/RELEASED；Health = UNKNOWN/HEALTHY/DEGRADED/UNAVAILABLE；CapabilityAvailability = AVAILABLE/LIMITED/UNAVAILABLE/PENDING/DISABLED。
- CapabilityEvaluator 只投影数据，不操作AudioRecord/SDK/DM。新增结果通过 MainViewModel.UiState 投影；现有 wakeError/vadUnavailable 等如暂保留，必须派生而非成为第二份事实源。

- [x] **Step 1: 状态与依赖的 RED。** 新测试至少断言：未开始不就绪、READY后健康可以降级、旧generation/attempt/sequence被拒绝、FAILED与BLOCKED根因不同、DISABLED不是失败、RELEASED后迟到READY无效、共享runtime失败只产生一份根因且不阻断Open本地ASR。
- [x] **Step 2: 运行RED。** 从 AutoVoice 运行 :voice-core:test --tests '*Component*Test'（-PuseIflytekStub=true --offline）；保存缺失API或行为失败，不能用测试编译错误中的偶然拼写错误代替RED。
- [x] **Step 3: 实现公共报告契约和App registry。** 注册/归并/依赖变化线程安全；使用单调耗时；快照不可变；进程级registry不持Activity或ViewModel，模式重装仅替换ASSEMBLY组件，PROCESS结果保留。最近事件最多128条，状态/日志队列有界，重复健康事件合并，恢复保留。
- [x] **Step 4: 现有适配器/早期失败的RED。** 用可控SDK/时钟/回调验证：配置加载失败可见且不启麦、缺JNI可见、授权成功/失败/20秒超时有稳定码；超时后迟到AUTH成功无效；两渠道共用一次runtime；讯飞命令初始化异常不只写Log.w。测试记录必须能断言provider与受影响能力，不伪造真实授权。
- [x] **Step 5: 接入真实初始化边界。** 先创建/取得registry，再读取配置/构造适配器；通过注入报告端口记录同步和异步初始化。AIKit原有20秒预算及单例调用所有权保持，过期AUTH只做安全收尾，不允许终态回滚。CancellationException不报普通初始化失败，致命VM错误不吞掉；UnsatisfiedLinkError等可诊断装配错误映射为稳定码。
- [x] **Step 6: UI与日志RED。** 验证本地ASR失败只显示本地识别影响、离线云端disabled不红报、初始化中显示准备中、健康恢复清除过期提示；序列化的诊断JSON不包含注入测试密钥/token/转录。配置错误状态下诊断入口仍可使用。
- [x] **Step 7: 接入UI和本地结构化日志。** 主页面保留简短可用性提示，设置区“组件状态”显示每项状态/影响/建议/耗时，并可复制脱敏JSON；正常项可折叠。日志schemaVersion=1；独立appSessionId，不使用对话utteranceId；不做长久磁盘日志或上传。当前Open缺实现仍准确报不可用，不能为了状态示例创建假模型成功。
- [x] **Step 8: GREEN与回归。** 运行 :voice-core:test、:adapter-iflytek:test、:audio-frontend:test、:audio-frontend-legacy:test、:app:testIflytekDebugUnitTest、:app:testOpenDebugUnitTest，及两个flavor lint/Debug assemble（JDK21、stub明确、offline）。必要时分批过滤聚焦测试；最终测试必须对应最终代码。运行git diff --check。
- [x] **Step 9: 提交并报告。** 报告初始化时序/代次/依赖/日志/UI证据、SDK/native实测限制，停止等待主agent审核，不自行进入原Task4。

### Task 2: 随原渠道 Task4–7 完成新组件和运行健康接线

**Files:** 原计划Task4 adapter-webrtc/native+Kotlin；Task5 adapter-firered/model loader/推理执行器；Task6 OpenPcmPlaybackDriver/AudioRecorder/RecordingCoordinator/网关/TTS接线；Task7 CI与验收文档。

**Interfaces:** 消费本计划Task1的ComponentReporter/Token/Error/Snapshot；不得另建第二套全局初始化布尔值。模型的子阶段作为stage，组件仍是local.asr；AEC健康不能只依据APM handle存在。

- [ ] **原Task4 RED/GREEN补充：** JNI缺失/符号不匹配/参数失败分别报告；frontend与VAD分别有真实结果；render idle不误报，播放中参考丢失/队列溢出改变AEC健康。保留原legacy行为，接口初始未就绪不产生假PCM。
- [ ] **原Task5 RED/GREEN补充：** native_load/resource_validate/model_create阶段、模型缺失/坏摘要/tokens混版、初始化超时、关闭竞态及资源耗尽有稳定错误；坏模型不禁用前端/有效云端；模型只加载一次，旧attempt不能成功复活。
- [ ] **原Task6 RED/GREEN补充：** 麦克风权限和实际打开失败、播放驱动创建和网络握手结果接入；权限未请求为等待条件；请求级ASR/TTS失败不冒充初始化失败。按有效能力门控对应入口，回调仍经过既有DM/请求身份校验。
- [ ] **原Task7验收补充：** 逐项核对两渠道组件状态、依赖根因、功能影响与安装/故障说明；执行对应报告/日志测试，设备事实单列。更新设计状态，不以单元测试替代授权、native或8295验收。

## 自审与执行约定

Task1交付可用的统一状态机制及现有组件/UI；新native的真实结果由原Task4/5补齐，实时采集/连接/播放由原Task6完成，最终原Task7审核。这样每条spec要求有唯一实现任务，公共类型在首次实现任务确定，后续消费同一端口。

用户已批准设计并要求实施，执行方式沿用此前明确指定的一个子agent＋主agent逐阶段审核，不再次询问执行方式。计划细化由主agent按批准设计审核，worker完成后仍须等待主agent放行。
