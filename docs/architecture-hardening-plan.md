# AutoVoice 架构审查与整改计划

更新时间：2026-08-16

## 1. 目标与边界

本文记录 AutoVoice 当前架构审查中发现的问题、风险、修改策略、验证方式和仍需外部条件的事项。

本轮按 Demo 场景实施，原则如下：

- 优先消除可导致内存、线程、磁盘耗尽或消息竞态的问题。
- 保持现有 Android 与服务端协议兼容，不无故中断当前 Demo。
- 所有密钥仍优先通过环境变量或 GitHub Secrets 提供；Demo 中已有的静态设备 token 暂不作为发布阻塞。
- 本地完成代码、测试和 GitHub Actions 工作流；不自动部署，不自动修改远程仓库保护规则或 Secrets。

## 2. 当前架构概览

系统由四部分组成：

1. Android 客户端：VAD、RNNoise、本地命令识别、端侧仲裁、车控/导航执行、TTS 播放。
2. 网关服务：WebSocket 接入、音频分段、ASR、离线命令与 LLM 竞速仲裁。
3. 独立能力：TTS 服务、Skill Manager、MCP 工具接入。
4. 可观测平台：端侧和服务端 telemetry、SQLite、SSE Web 面板。

模块边界整体合理：Android 使用 `voice-core + adapters + app`，服务端使用 `contracts + providers + gateway/app`。主要问题集中在接入边界、并发模型、资源生命周期和工程化验证。

## 3. 问题与修改策略

### 3.1 Telemetry 接口缺少生产鉴权和请求限额

问题：

- Telemetry 默认开启，读写、SSE 和音频上传原先都没有鉴权。
- Multipart 音频直接整体读入内存。
- 单次事件数量、音频大小和 SSE 发送资源没有明确边界。

风险：伪造链路数据、读取语音与识别文本、大文件占满堆或磁盘、慢 SSE 客户端耗尽线程。

策略：

- 增加可选 `AUTOVOICE_TELEMETRY_ACCESS_TOKEN`，配置后要求请求头 `X-Telemetry-Token`。
- Demo 默认空 token 保持兼容；公网部署清单要求显式配置。
- 单次音频限制为 1,920,000 字节（60 秒 16kHz/16bit/mono PCM）。
- 单次事件包限制为 512 条。
- Spring Multipart 同时限制文件和请求总大小。
- SSE 改为有界线程池和有界队列，过载时主动结束连接。

验证：鉴权开关、401、上传 413、事件 413、正常 SSE 与线程池关闭测试。

### 3.2 WebSocket 单段音频可无限累计

问题：单帧虽有限制，但 `audio_start` 到 `audio_end` 之间的累计 PCM 原先没有上限。

风险：单连接持续推送即可耗尽服务端堆内存。

策略：

- 增加 `AUTOVOICE_GATEWAY_MAX_AUDIO_BYTES`，默认 1,920,000 字节。
- 累计前检查当前段大小；超限后清空缓冲、停止本段并返回 `AUDIO_TOO_LARGE`。
- 后续补齐采样率、声道、编码、持续时间和值类型校验。

验证：边界内正常处理、恰好达到上限、超限清空、不进入 ASR。

### 3.3 WebSocket 并发发送与同步 TTS

问题：`reply/decision`、`pending`、`tts_response/error` 可能来自不同线程；原始 WebSocket session 不应被假定支持并发发送。TTS 原先还在 WebSocket 收包线程同步执行。

风险：并发写异常、消息顺序不稳定、TTS 慢调用阻塞连接消息处理。

策略：

- 所有下行经过同一个连接级串行出口；第一阶段以 session 锁保证原子发送，后续可独立为 `OutboundChannel`。
- TTS 放入共享有界执行器；队列满快速返回 `TTS_BUSY`。
- Handler 销毁时关闭 TTS、仲裁和连接执行器。

验证：并发 pending/reply/TTS 不发生重叠发送；慢 TTS 不阻塞 WebSocket 收包；队列过载快速失败。

### 3.4 超时未传播到底层任务

问题：云端仲裁 safety timeout 只完成兜底结果，不会自动终止 ASR、LLM、MCP 或离线任务。LLM 阻塞 HTTP 还运行于公共 `ForkJoinPool`。

风险：用户已收到超时回复，后台仍继续推理或调用工具；高负载下公共线程池被阻塞任务占满。

策略：

- 为每轮创建统一 deadline，并传入 ASR/LLM/MCP。
- LLM 使用专用有界线程池，不使用 common pool。
- 仲裁收敛后取消输家 future；OkHttp Call 与 MCP 调用响应取消/中断。
- 对具有副作用的工具，在执行前再次检查 deadline 和轮次是否仍有效。
- 统一 `safetyTimeoutMs`、LLM tool budget 和 Android pending wait 的配置关系并做启动校验。

验证：safety 后底层 Call 被取消、不会继续下一轮工具调用、线程池容量和拒绝路径可控。

### 3.5 ASR PCM 诊断文件不受保留策略管理

问题：ASR 成功与失败原先都会直接写 `/tmp/asr-*.pcm`，绕过 telemetry retention。

风险：磁盘持续增长、语音隐私数据残留、客户端输入参与文件名。

策略：

- 移除主链路中的无条件 PCM 落盘。
- 需要诊断时统一走 telemetry 音频存储、文件名消毒、容量与保留策略。

验证：正常及失败 ASR 不再生成额外 `/tmp/asr-*` 文件。

### 3.6 MCP 工具重名

问题：多个 Skill 可声明同名工具；注入列表含重复项，而调用路由选择第一个匹配 session。外部 Skill 也可能与内置 `car_control`、`navigate` 重名。

风险：LLM 工具定义歧义，调用被路由到错误服务，具有副作用的工具尤其危险。

策略：

- Registry 刷新时构建唯一 `toolName -> session` 路由快照。
- 发现跨 Skill 重名时拒绝新快照并保留上次成功版本，日志列出冲突名称。
- 明确禁止外部工具使用内置终局工具名称。
- 后续如需允许重名，改用 `skillId__toolName` 命名空间。

验证：同名冲突、内置名冲突、刷新失败保留旧快照、正常工具 O(1) 路由。

### 3.7 连接上限检查存在竞态

问题：`connections.size() >= maxConnections` 与登记不是原子操作。

风险：并发握手可能突破配置上限；被拒连接还可能被消息处理路径重新登记。

策略：

- 使用原子活动连接计数执行准入。
- 只有 `afterConnectionEstablished` 可以登记状态；未知/未准入 session 的消息直接关闭。
- 连接清理时只减一次计数。

验证：并发连接压力测试、重复 close 幂等、被拒连接无法重新进入状态表。

### 3.8 GatewayHandler 和 AppConfig 职责过重

问题：GatewayHandler 同时负责准入、协议状态、音频缓存、任务并发、TTS、仲裁装配和消息发送；AppConfig 同时装配几乎所有 provider。

风险：修改一个链路容易破坏其他链路，并发状态难以局部验证。

策略：

- 提取 `AudioAccumulator`：音频状态和容量边界。
- 提取 `OutboundChannel`：串行发送与关闭语义。
- 提取 `TtsRequestHandler`：有界异步 TTS。
- 提取 `SegmentCoordinator`：段处理任务与取消。
- 将配置拆为 Gateway、ASR、LLM、Offline、Skill 等配置类。
- 每次只做保持行为的提取，并用现有回归测试锁定协议。

### 3.9 文档和实际实现漂移

问题：README、runbook 和 ACCEPTANCE 仍包含已删除模块、旧 reply 形态、旧超时和旧测试数量。

策略：

- 以当前协议 1.1、LLM function calling、独立 TTS、当前配置为准更新文档。
- 验收记录区分“当前自动化结果”和“历史运行记录”。
- CI 自动产出测试报告，不再手工维护固定测试总数。

## 4. 测试与 CI 策略

GitHub Actions 已实现：

1. `server-test`：Java 21，执行 AutoVoiceServer 全量测试和 JaCoCo。
2. `android-test`：Java 21 + Android SDK/NDK，执行 JVM 测试、lint 和 debug 构建。
3. `telemetry-web`：Node 22，执行 ESLint、Vitest、覆盖率和 Vite build。
4. `skill-manager-web`：同上。
5. `schema-fixtures`：用 AJV 校验共享 fixtures，并校验协议 fixture 能被端云两侧读取。
6. 上传 JUnit、JaCoCo、Web 覆盖率和 Android lint 报告。

当前情况：

- 两个 Web 项目已有 ESLint、Vitest、V8 coverage 和 build 脚本；当前测试集中在 API 与阶段映射，整体覆盖率仍低，暂未设置硬门槛。
- 两个 Gradle 工程已启用 JVM JaCoCo XML/HTML 报告；Android instrumentation/真机覆盖率尚未纳入。
- 仓库级工作流位于 `.github/workflows/ci.yml`，Pull Request、main push 和手工触发均运行。
- 共享 AJV 校验会覆盖 gateway fixtures、Demo 配置以及 action fixture 中的 canonical intent。
- Android 真机 `androidTest` 不属于普通 JVM 测试。
- `AIKit.aar` 被忽略；GitHub Runner 用 compile-only SDK stub 编译、测试和 lint，设备/发布构建仍必须使用真实 AAR。

厂商 SDK 策略：普通 Pull Request CI 使用 fake/stub variant，不依赖真实 AAR；受保护的发布流程再从私有制品或 Secrets 配置的下载位置取得真实 SDK。

## 5. 实施顺序与状态

| 阶段 | 内容 | 状态 |
| --- | --- | --- |
| 1 | 网关/Telemetry 资源边界、连接准入、停止无条件 PCM 落盘 | 已完成 |
| 2 | 串行下行、异步 TTS、执行器生命周期 | 已完成（串行出口当前采用连接锁） |
| 3 | deadline、取消传播、有界 LLM 执行器 | 部分完成：取消传播与有界池已完成，统一 deadline 待后续协议化 |
| 4 | MCP 工具冲突保护 | 已完成 |
| 5 | GitHub Actions、Web 测试/lint、覆盖率、Schema 校验 | 已完成基础闭环 |
| 6 | 拆分职责、同步 README/runbook/验收文档 | 部分完成：本整改文档已完成，类级拆分与旧文档清理待独立重构 |
| 7 | 全量回归与剩余风险报告 | 已完成本地自动化回归；外部集成验收待真实环境 |

## 6. 本轮验证结果

- `AutoVoiceServer ./gradlew test`：通过，并生成各 JVM 模块 JaCoCo 报告。
- `AutoVoice ./gradlew test`：通过；使用 `-PuseIflytekStub=true` 的 `lint` 与 `:app:assembleDebug` 通过。
- Telemetry Web：lint、4 个 Vitest、coverage、build 通过。
- Skill Manager Web：lint、2 个 Vitest、coverage、build 通过。
- Schema fixtures：AJV 校验通过。
- GitHub Actions 文件已完成本地 YAML 解析检查；真正的 Runner 结果需要推送后确认。

剩余风险：Web UI 组件测试和覆盖率门槛尚未建立；Android 真机、真实音频、真实厂商/LLM/MCP 服务无法由普通 PR CI 覆盖；MCP SDK 在关闭一个不可达连接时仍可能输出异步告警，但不影响测试收敛。

## 7. 外部阻塞与审批边界

本地实现和测试无需额外业务批准。以下事项不在本地代码中自动完成：

- 向 GitHub 推送分支、创建 PR、修改 Branch Protection。
- 配置 GitHub Actions Secrets 或上传厂商 AAR。
- 部署服务、修改生产环境变量和轮换 token。
- 真实手机、真实麦克风、真实讯飞/DeepSeek/TTS/MCP 服务验收。

这些事项不阻塞本地实现，但会影响远程 CI 和生产验收的最终闭环。
