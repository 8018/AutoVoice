# AutoVoice 周末 Demo 验收手册（Runbook）

本文档是里程碑 ①（周末 demo）的完整真机验收手册。四个剧本的定义见
`docs/specs/2026-08-08-autovoice-demo-design.md` §9.3；端云竞速规则见同文档 §5。
Headless 侧的自动化验证记录见仓库根 `ACCEPTANCE.md`。

---

## 1. 前置条件

### 1.1 环境变量（服务端 provider 密钥）

服务端 `AutoVoiceServer/app/src/main/resources/application.yml` 中 secrets 全部为
`${VAR:}` 空默认占位：**无环境变量也能启动**（provider 构造不触网，调用时才失败），
但剧本 2/3 依赖真实云端 API，必须导出以下变量后再启动服务端：

| 环境变量 | 用途 | 来源 |
|---|---|---|
| `XFYUN_APPID` | 讯飞在线听写 AppID——云端 ASR（同时是**服务端离线命令词**的联网激活凭据） | 讯飞开放平台（应用详情） |
| `XFYUN_API_KEY` | 讯飞在线听写 API Key——云端 ASR（同上） | 同上 |
| `XFYUN_API_SECRET` | 讯飞在线听写 API Secret——云端 ASR（同上） | 同上 |
| `DEEPSEEK_API_KEY` | DeepSeek LLM——云端 LLM（剧本 2/3） | DeepSeek 开放平台 |
| `ALIYUN_AK` | 阿里云 AccessKey ID——NLS 鉴权 | 阿里云 RAM 控制台 |
| `ALIYUN_SK` | 阿里云 AccessKey Secret——NLS 鉴权 | 同上 |
| `ALIYUN_NLS_APPKEY` | 阿里云智能语音交互 AppKey——云端 ASR | 阿里云 NLS（智能语音交互）控制台 |
| `DASHSCOPE_API_KEY` | 阿里云百炼 DashScope Key——云端 TTS（sambert 播报） | 阿里云百炼（Model Studio）控制台 |

> **服务端离线命令词链路**（仅阿里云部署启用）：额外环境变量见 §6 部署步骤——
> `AUTOVOICE_OFFLINE_ENABLED=true` 等。默认关（`offline.enabled=false`），Mac 本地
> 跑纯云端链路（LLM 兜底），行为与改造前一致。

### 1.2 讯飞离线命令词体验版账号（可选增强，凭据需接线）

> **现状**：本构建离线命令词 ASR 为 fake（凭据未接线）——`VoiceEngine.buildLocalChain`
> 以空凭据构造 `IflytekOfflineCommandAsrStage`（`appId=""`/`apiKey=""`/`apiSecret=""`），
> SDK 抛 `NOT_CONFIGURED` 即降级 fake（Log.w 后回退 fake-cmd），未走真实 SDK。
> 拿到体验版授权后需在 `buildLocalChain` 接线 appId/apiKey/apiSecret（一行改动，
> stage 已注入就绪）→ 届时改 `local.asr="iflytek.offline"` + 推送模型即生效。

demo 默认 `local.asr=iflytek.fake-cmd`（内置 fake 命令词识别，离线可演示）；
若已申请到**讯飞开放平台「离线命令词」体验版授权**（3 台设备 / 35 天有效期）：

1. 将讯飞 SDK 归档（`AIKit.aar` + `resource/` 离线资源）放入
   `AutoVoice/adapter-iflytek/libs/`（本地文件不入库，见 `.gitignore`；缺失时
   用 fake-cmd 默认链路即可，不影响其余验收）。
2. 接线凭据（一行改动）：`VoiceEngine.buildLocalChain` 中
   `IflytekOfflineCommandAsrStage(appId = "", apiKey = "", apiSecret = "")`
   填入体验版 appid / apiKey / apiSecret。
3. 改配置：编辑 `AutoVoice/app/src/main/assets/demo-full.json`，
   把 `local.asr` 从 `"iflytek.fake-cmd"` 改为 `"iflytek.offline"`。
4. 把离线资源推送到手机：`adb push <SDK>/resource/CNENESR /sdcard/iflytek/`
   （引擎读取目录硬编码为 `/sdcard/iflytek/`，含 `e75f07b62_*.bin` 模型与 `fsa/cn_fsa.txt`）。

未接线时切 `iflytek.offline` 会看到「讯飞离线命令词 SDK 未配置」降级提示（预期内，
见 §5.1），链路自动回退 fake-cmd，功能不中断。

### 1.3 AIUI 平台配置

云端传统 NLU（剧本 2/3 的 `nlu-traditional` 链路）走讯飞语义 API，需在 AIUI 控制台
完成应用配置，并将 `XFYUN_APPID` / `XFYUN_API_KEY` 导出（见 §1.1）。

### 1.4 网络要求

- 手机与开发机**同一局域网**（开发机直连网线/同一 Wi-Fi）。
- 手机系统时间准确（云端鉴权对时间敏感）。

### 1.5 网关地址配置

`AutoVoice/app/src/main/assets/demo-full.json` 的云端网关**默认已指向已部署的
云服务器**（`AutoVoiceServer` 已部署在公网 `47.94.4.204:8080`，systemd 服务
`autovoice-gateway` 管理）：

```json
"cloud": { "enabled": true, "gatewayUrl": "ws://47.94.4.204:8080/ws", "waitMs": 2000 }
```

- 使用已部署的服务端：**无需修改**，手机联网即可（不要求同一局域网；需云服务器
  安全组放行 TCP 8080）。
- 自建服务端（开发机跑 `bootRun`）：把 `gatewayUrl` 改为开发机局域网 IP，手机与
  开发机同一局域网：

```json
"gatewayUrl": "ws://192.168.x.x:8080/ws"
```

（`192.168.x.x` = 开发机 `ifconfig`/`ipconfig` 查到的局域网地址；`waitMs=2000` 即
端侧仲裁的云端等待窗口，剧本 4 依赖它。）

---

## 2. 启动步骤

### 2.1 云端服务端（开发机）

```bash
cd AutoVoiceServer
# 先导出 §1.1 的全部密钥
export XFYUN_APPID=... XFYUN_API_KEY=... DEEPSEEK_API_KEY=... \
       ALIYUN_AK=... ALIYUN_SK=... ALIYUN_NLS_APPKEY=... DASHSCOPE_API_KEY=...
./gradlew :app:bootRun --args='--spring.profiles.active=demo-full'
```

**期望**：监听 `0.0.0.0:8080`，WS 端点为 `ws://<开发机IP>:8080/ws`；等待日志出现
`Tomcat started on port(s): 8080`（Spring Boot 默认输出）即服务就绪；此时若手机
App 已连接，`hello` 会收到 `ready`（带 `sessionId` / `protocolVersion=1.1` /
`language=zh-CN`）。离线命令链默认关（`offline.enabled=false`），日志出现
`Offline no result` 前的启动日志不涉及离线 SDK。

> 注意：`bootRun` 走真实 provider（讯飞语义 / DeepSeek / 阿里云 ASR/TTS），
> 密钥未配齐时对应调用会报错——先核对 §1.1。

### 2.2 端侧 App（手机）

```bash
cd AutoVoice
./gradlew :app:installDebug   # 连接手机后安装 debug 包（含弱网开关）
```

**期望**：安装成功（`app-debug.apk`），手机桌面出现 AutoVoice 图标。首次启动：

- 弹出 **RECORD_AUDIO 录音权限**授权框（§4.4 检查项）→ 允许；
- 设置区默认 `demo-offline`；切换到 `demo-full` 后设置区可开「模拟弱网（云端延迟 3s）」；
- 按住录音按钮说话，VAD 自动分帧，决策日志逐条出现在屏幕（哪条链路赢、为什么）。

---

## 3. 四个验收剧本

> 每个剧本都同时确认：**决策日志在屏幕可见**（`cloud_won` / `cloud_unreachable` /
> `cloud_timeout_use_local` 等），且日志与下述期望一致。

### 剧本 1：断网本地兜底

- **前置**：开发机服务端可开可不开（本剧本不依赖云端）。
- **操作**：手机开**飞行模式**（或关 Wi-Fi）→ 设置区切到 `demo-offline` →
  按住录音说 **"打开空调"**。
- **期望**：
  1. 端侧决策日志出现 `cloud_unreachable`（云端不可达，本轮只跑本地链）；
  2. 模拟车控面板**空调开启**；
  3. **系统 TTS 播报**（本地 Android TextToSpeech，离线可用）。
- **验证点**：端侧仲裁 + 端侧传统语音链路（VAD → 降噪 → fake/离线命令词 → 规则 NLU）。

### 剧本 2：云端车控指令

- **前置**：服务端已按 §2.1 启动，手机联网（同一局域网），§1.5 网关地址已改。
- **操作**：设置区切 `demo-full` → 按住录音说 **"空调调到二十四度"**。
- **期望**：
  1. 端侧决策日志：`cloud_won`（云端先到，赢家为云端）；
  2. 云端决策日志：`llm_reply`（LLM function calling 产出 action 车控）；
     **阿里云部署且离线命令链开启**（§6）时，命中 FSA 词表的命令词（如"打开空调"）
     为 `offline_won`；
  3. 模拟车控面板**温度 = 24**；
  4. 设备执行 intent 后按 `speakText` 发 `tts_request` → `tts_response` 播报
     （**TTS 解耦**：识别回复不带音频）。
- **验证点**：云端 LLM 车控链路（讯飞 ASR → DeepSeek function calling → 设备执行
  intent → 独立 TTS 播报）。

### 剧本 3：云端 LLM 闲聊

- **前置**：同上（在线 + `demo-full`）。
- **操作**：按住录音说 **"明天上海天气怎么样"**（或 **"讲个笑话"**——命令词
  词表外的任意闲聊句均可）。
- **期望**：
  1. 云端决策日志：`llm_reply`（离线命令未命中 / 无结果 → LLM 胜出）；设备端
     2s 窗口内未收到则落本地（unknown → 静默拒识，属预期，见 §5.5）；
  2. 收到 `reply`（kind=text，`text` 与 `speakText` 同带）→ 设备按文本发
     `tts_request` 播报 LLM 回答。
- **验证点**：云端 LLM 链路 + 独立 TTS 播报。
- **注**：LLM 到达后的 1500ms 离线宽限期（`offline-grace-ms`）时序不可控，
  不列为必验项（已由仲裁收敛单测覆盖）。

### 剧本 4：云端超时用本地

- **前置**：服务端在跑、手机在线 + `demo-full`。
- **操作**：设置区打开 **「模拟弱网（云端延迟 3s）」** → 按住录音说 **"打开车窗"**。
- **期望**：
  1. 端侧决策日志：`cloud_timeout_use_local`（云端在 `waitMs=2000` 内未回，
     本地先到并收敛）；
  2. 本地结果执行：模拟车控面板**车窗开启**（+ 本地播报）。
- **验证点**：2000ms 云端超时收敛（弱网 hook 人为延迟 3000ms > 2000ms，
  仅 debug 构建生效，见 §5.4）。

---

## 4. 真机验收检查项（设备清单）

除四剧本外，以下来自开发期 ledger 的 carry-over 项需在真机上逐一确认：

| # | 检查项 | 通过标准 |
|---|---|---|
| 4.1 | RNNoise 降噪运行质量 | 背景噪声（空调/音乐/播客声）下说话，语音仍被识别；静音期不误触发 |
| 4.2 | AIKit 授权流程 | 切 `iflytek.offline` 后首次授权成功；**首次授权需联网**（体验版授权在线校验） |
| 4.3 | 单帧 BEGIN/END 分帧 | 真实语音（远大于单帧 320B）始终先 BEGIN 后 END，SpeechEnd 与段 PCM 配对一致 |
| 4.4 | RECORD_AUDIO 授权弹窗 | 首次启动弹出权限框；拒绝后录音被 UI 拦截并提示 |
| 4.5 | 降噪流连续性 | 每 1024B 块 RNNoise 独立处理丢弃尾帧 32 samples（6.25%），听感/识别无明显断音 |

---

## 5. 常见问题

### 5.1 「讯飞离线命令词 SDK 未配置」降级提示

`local.asr=iflytek.offline` 但 AIKit.aar / 授权 / 离线资源任一未就绪时，日志出现
`讯飞离线命令词 SDK 未配置...`（`NOT_CONFIGURED_MSG`），链路自动降级到
`FakeCommandAsrProvider`（fake-cmd）继续演示——**属预期行为**；demo 默认配置
（`iflytek.fake-cmd`）不会出现该提示。解决：按 §1.2 补齐 SDK 与授权，或保持默认配置。

### 5.2 `cloud_unreachable` 判定

出现 `cloud_unreachable` 决策日志的三种情形，均走「本轮只跑本地链」兜底：

1. 话语开始时无 active network（`ConnectivityManager` 判空）；
2. WS 连接失败（`CONNECTION_FAILED`）；
3. `ready` 已收后中途断开（`CONNECTION_CLOSED`）。

故障按**轮次**重试而非跨轮 latch：下一轮话语若网络可用即重新启用云端路由
（观察日志：断网 → `cloud_unreachable`；恢复后下一轮 → `cloud_won`）。

### 5.3 TTS 解耦与播报降级（v1.1）

识别回复（`reply`）**只携带语义**（`action` / `text`），不带音频；设备执行 intent
后按 `speakText` 发 `tts_request` 获取音频播报：

- `tts_response` 到达 → 播放 WAV；失败/超时 → **系统 TTS 兜底**（不静默）；
- 服务端 TTS 有缓存：重复文本 `TTS cache HIT` 秒回（阿里云部署可见日志）；
- 服务端 `tts_request` 合成失败 → `error`（`TTS_FAILED`，**不关连接**）；
- `both_failed`（双败）→ 设备播报兜底话术，其余 `unknown` 拒识**静默**（不执行不播报）。

### 5.4 弱网开关仅 debug 构建生效

「模拟弱网」给云端链人为加 3000ms 延迟（`WEAK_NETWORK_DELAY_MS`），由
`BuildConfig.DEBUG` 门控——**release 构建开关无效**，属预期。验收使用
`installDebug` 安装的 debug 包。

### 5.5 其它

- **App 收不到任何回复**：先看服务端日志（§2.1 期望），再核对 §1.5 网关 IP
  （`10.0.2.2` 是模拟器占位，真机必改）；
- **TTS 无声但车控已执行**：检查手机媒体音量/静音模式；
- **改 `demo-full.json` 后需重装 App**：配置资产随 APK 打包。
