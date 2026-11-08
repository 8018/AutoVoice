# 端侧开放语音渠道 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在保留讯飞渠道行为和两渠道共用讯飞唤醒的条件下，为 8295 Android 增加可编译选择的 WebRTC + FireRedASR2 本地渠道。

**Architecture:** 公共音频契约、具体前端、本地 ASR 和 App 渠道组合根分离；业务、DM、仲裁、云端和 TTS 策略共用。Open 使用同一个 AudioRecord，原始 PCM 供既有唤醒，处理后 PCM 供 VAD、本地识别和既有云端候选；平台播放提供 AEC render 参考。

**Tech Stack:** Kotlin 2.0.20、Java 21、AGP 8.5.2、Android API 26/34、WebRTC GN/JNI、sherpa-onnx Android JNI、FireRedASR2 CTC INT8。

**Spec:** `docs/superpowers/specs/2026-11-08-open-speech-channel-design.md`；构建规约 `docs/webrtc-firered-android-build.md`。

## Global Constraints

- 旧讯飞渠道的识别、唤醒、音频处理、业务与失败行为保持；禁止真实 SDK 不可用时暗中切换 fake。
- 两渠道复用 `IflytekWakeWordObserver` 和同一进程 AIKit runtime；Open 不初始化讯飞命令识别。
- 目标 `arm64-v8a`，minSdk 26、compileSdk 34；不顺带升级 Gradle/AGP，不改变 dev/prod 环境规则。
- 首版 FireRedASR2 CTC INT8 + sherpa-onnx，CPU 分段 final；不把模拟流式或 QNN 作为已支持能力。
- 一份采集、每帧只经过一份 APM；VAD、续听、打断门控状态独立，VAD 不直接建立业务轮或停播。
- 未收口输入执行前复核输入许可；已正常收口的输入凭原请求和 deadline 继续，当前开关不统一过滤其执行/输出；DM 决定是否采用迟到结果。
- 云端同请求一次音频上传，保留现有 ASR/NLU 竞速；禁止将 FireRed final 再作为显式文本请求重复上传。
- PCM、render 和推理队列有界，不静默丢中间音频；参考异常撤销 AEC 健康状态。
- 模型独立资源包预装并校验，缺失明确不可用；不把大型模型、厂商 AAR、密钥、生成的 native 库提交到 Git。
- 不修改原工作区的未提交 `docs/edge-cloud-dialogue-implementation-plan.md`；不推送、合并、发布或部署。
- 用户指定一个执行子 agent 跨阶段复用，主 agent 审核通过才发下一阶段。执行者不得派代理，也不得自行进入下一 Task。
- native 缺少构建主机/制品或真机时，记录具体未验证项，继续独立可实施工作；不得用桩实现宣称真实能力完成。

## Review Focus

- 模式切换重新构造前端时不得回到写死的 Silero/RNNoise，也不得泄漏 native 资源。
- 关闭识别时排队中已收口请求和未收口请求采取不同规则，关闭实例后两者均不能重启资源。
- 同一32ms块跨越多个10ms帧时样本、VAD事件和预卷坐标一致，尾部补零不制造真实静音事件。
- 播放 stop/flush、迟到 render 和路由变化不能把旧参考交给新输出或误报告可打断。
- Open 包只保留 sherpa 配套 ORT；缺模型、缺 JNI、错误 ABI 和旧 native 入口在启动期可诊断，不伪造识别。

---

### Task 1: 提取 legacy 音频实现并建立可替换前端契约

**Files:** Modify `AutoVoice/settings.gradle.kts`、`audio-frontend/build.gradle.kts`、`audio-frontend/src/main/kotlin/com/autovoice/audiofrontend/**`、App `audio/AudioRecorder.kt`；Create `audio-frontend-legacy/**`；迁移对应算法/模型/native 和测试，保持 license。

**Interfaces:** 保持当前 `AudioFrontendEngine` 的 App 调用兼容；公共 `CaptureEffects`/`VadEngine` 保留。新增注入的 frontend/vad/effects factory，现有 recorder 默认从 legacy 工厂得到同等行为，后续渠道可覆盖。公共模块不能依赖具体算法或 ORT。

- [ ] 先验证“注入不同前端后，模式重配和普通/续听/打断的创建路径均使用注入者；旧输入输出样本数、尾帧和分段不变”的行为测试，记录预期失败证据。
- [ ] 按职责迁移 RNNoise C/C++/Silero/模型及 Android 系统 effects；修复生产与测试 imports，不复制算法实现。
- [ ] 测试保留 VAD 不可用时旧失败行为、close 幂等、切换关闭旧实例、legacy帧网格；配置迁移只改变装配不改变旧值。
- [ ] 运行 `./gradlew test lint :app:assembleDebug -PuseIflytekStub=true`，验证公共模块运行依赖图无 ORT/native。
- [ ] 自审并提交本阶段，写报告，等待主 agent 审核。

### Task 2: 建立双 flavor 装配和共享唤醒

**Files:** Modify App `build.gradle.kts`、`VoiceEngineFactory.kt`、`MainViewModel.kt`、`voice-core/Config.kt`、shared schema/fixtures；Create App `channel/SpeechChannel.kt`、`WakeWordFactory.kt`、`src/iflytek/.../SelectedSpeechChannel.kt`、`src/open/.../SelectedSpeechChannel.kt` 和渠道 assets；创建 Open adapter 的最小模块边界。

**Interfaces:** `SpeechChannel` 提供前端创建、本地 ASR/NLU 装配、采集策略和播放 driver 创建端口，厂商类型不进入公共契约。同名 `SelectedSpeechChannel` 由 source set 选择。主录音、配置重建和原始PCM唤醒使用 Task1 端口。Open尚缺后续能力时显式 unavailable，不产生fake final。

- [ ] 先写配置/provider矩阵、两flavor组合根及“Open不初始化讯飞命令、两个渠道都初始化独立唤醒”的测试，并记录失败。
- [ ] 保留旧资产映射，新增 Open 对应 frontend/VAD/ASR 参数；通用字段检查与所选渠道支持校验分开，未知provider仍拒绝。
- [ ] 把当前厂商命令选择迁入 iflytek source set，唤醒构造迁入公共 WakeWordFactory；保持授权资源校验和一个SDK runtime。
- [ ] 运行两套 App 单测、lint、assemble以及 shared schema测试；核对旧manifest/环境与API行为未改变。
- [ ] 提交、报告哪些Open能力尚待Task4–6，等待审核。

### Task 3: 实现可复现 native 构建与制品校验工具

**Files:** Create `AutoVoice/tools/native/{build-webrtc-android.sh,build-firered-android.sh,stage-native-artifacts.sh,native-lock.json}`、工具测试和制品目录忽略规则；Modify build guide。

**Interfaces:** 读取明确 revision/ABI/API/工具链及 artifact hash，按白名单将已校验库装入各 adapter 的 jniLibs；不接收未经验证的任意解压路径，不复制到公共app。native实现源码由Task4提供。

- [ ] 用本地微型归档先验证缺文件、错误hash、错误ABI元数据、路径穿越和重复ORT明确失败；成功路径只安装约定库，记录失败证据。
- [ ] 实现WebRTC Linux checkout/GN补丁入口和sherpa固定提交构建，遵守构建规约；平台不支持时说明原因，不尝试安装整套系统工具。
- [ ] 实现制品锁/校验和 staging，不虚构未生成制品的hash；未知hash的版本不得走已认证发行路径。
- [ ] 运行工具测试、shell语法检查；检查是否有可用Linux构建机和Android设备，实际可执行的构建才执行，完整记录环境限制。
- [ ] 提交与报告；真实WebRTC编译需Linux资源时留待主agent协调，继续阶段4的源码实现需主agent明确放行。

### Task 4: WebRTC 前端和声学时间轴

**Files:** Create `adapter-webrtc/native/{BUILD.gn,frontend_jni.cc,frontend_session.cc,frontend_session.h}`、`WebRtcNative.kt`、`WebRtcAudioFrontend.kt` 与测试；Modify公共前端的batch/observations/样本格式类型及legacy兼容包装。

**Interfaces:** 公共 `FrontendBatch` 提供带有效样本索引的PCM块和有序VAD观测，不能只返回一个可空事件。`RenderReferenceSink` 接收播放代次/格式/样本位置/时间的PCM并撤销旧参考。JNI具备create/processCapture/processRender/resetVad/release，不跨库暴露C++对象。

- [ ] 先验证跨32ms→10ms块样本守恒、多事件顺序、flush截尾、预卷、不足帧不伪造静音、设备关闭后不再处理和AEC跨turn保留，记录失败。
- [ ] 实现单APM流水线、classic WebRTC二值VAD、独立门控状态及bounded render/capture；legacy包装维持原512-sample概率/事件结果。
- [ ] 实现GN可达目标、`java_targets=[]`、手写JNI入口/keep规则及实际APM/VAD调用；能力初始化失败明确报告。
- [ ] 跑pure/runtime边界测试和两flavor回归；有Linux条件时执行GN check/native build，无条件则保存可重现命令与未验证边界。
- [ ] 提交、报告并等待审核，不把fake native backend测试说成真实DSP验收。

### Task 5: FireRed 模型加载和本地文本语义

**Files:** Create `adapter-firered` 的模型manifest/loader、`FireRedAsrEngine`、专用有界推理执行器及测试；Create `voice-engine/local/{TextRuleNluEngine,LocalCommandRules}.kt`；Modify原讯飞规则入口为兼容委托。

**Interfaces:** 同版本sherpa Kotlin API/JNI/ORT通过Task3制品入口；`AsrEngine`/`AsrSink`保持现有接口，输入16k PCM16小端、转换float，输出真实final/话语成立。TextRuleNluEngine消费该final，仅语义进入仲裁。

- [ ] 先验证PCM端序/归一化、缺模型/错误摘要/tokens混版、模型只加载一次、stream释放、空final不确立话语、unknown文本、队列溢出和关闭竞态，记录失败。
- [ ] 集成固定版本API与运行时，模型独立预置；禁止重复识别PCM或把final作为显式文本重复提交云端。
- [ ] 明确正常收口请求在关闭新输入后仍可执行；未收口许可失效不得启动native，release后不再启动任何任务；测试两者受控时序。
- [ ] 跑adapter、voice-engine、legacy规则及App回归；可用时下载校验实际模型/运行时做真实推理，不能用网络失败或无设备冒充已通过。
- [ ] 提交并报告模型/native实测证据与限制，等待审核。

### Task 6: 接通 Open 采集与播放参考

**Files:** Modify App `AudioRecorder.kt`、`RecordingCoordinator.kt`、`VoiceEngineFactory.kt`、`CandidateCoordinator.kt` 和 Open组合根；Create `OpenPcmPlaybackDriver.kt`、通用播放driver端口和必要解码/重采样适配；保留讯飞原driver。

**Interfaces:** 使用Task4 batch与RenderReferenceSink、Task5 ASR/NLU；共用现有DM/输出身份。Open输出PCM可观测；原始PCM仍给讯飞唤醒。CapturePolicy决定音源/系统effects，软件AEC不默认叠加系统AEC。

- [ ] 先验证模式重配、一个AudioRecord/APM、普通/续听/打断使用Open provider、一个云端上传和一个本地语义出口，记录失败。
- [ ] 完成Open配置到实际组件接线；分段调用FireRed时保持原capture/request关联，不因VAD直接停播。
- [ ] 文件解码/流式AudioTrack都提供render；测试stop/flush、输出代次迟到、24k→16k、音量、部分write和路由变化；参考失效撤销AEC健康。
- [ ] 运行两套App单测、lint、APK和相关公共模块测试；检查旧讯飞路径/文本/闲聊/导航回归。真机具备时验收真实播放/双讲，否则具体列出尚未覆盖的硬件事实。
- [ ] 提交并报告，等待审核。

### Task 7: CI 制品隔离与最终交付

**Files:** Modify `.github/workflows/ci.yml`、引用原APK任务/路径的部署脚本、相关README和本设计的实施状态；Create native/model安装验收说明及依赖检查。

**Interfaces:** 默认发行入口仍为iflytek，Open构建独立选择；两渠道共用讯飞AAR、Open只有一份sherpa ORT且无legacy模型/native；模型不进入APK。

- [ ] 为artifact内容规则先建立能抓到两份ORT/错误渠道库的检查，再接入实际APK，记录失败/成功证据。
- [ ] 更新CI双flavor、manifest、coverage及路径；保留当前server/web/shared检查，不自动触发远程部署。
- [ ] 运行完整受影响Android回归、两flavorlint/build与schema/native工具检查；只在代码变动或新问题时重复昂贵测试。
- [ ] 对照spec逐项记录代码/测试/实际native构建/8295验收四种状态，不将外部未验事项标为完成。
- [ ] 提交并报告，等待主agent全分支审核；所有关键问题处理后交付本地工作区，不推送/合并/发布。

## 执行和审核约定

一个执行者每次只接收对应Task brief，完成后停止。报告写入本计划专属SDD目录，包含改动、RED/GREEN证据、命令/结果、提交SHA、依赖/硬件限制和自审；主agent以阶段基线到HEAD的完整diff审核规范符合性和实现质量，必要时要求修复同阶段，再允许下一阶段。

当前主机是Mac arm64，没有已发现的Docker；WebRTC实际构建需要Linux主机或已验证制品。该限制不阻止可在当前环境完成的源码、脚本和Android构建，但阻止宣称全部native/实机验收完成。
