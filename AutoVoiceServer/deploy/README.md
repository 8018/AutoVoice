# AutoVoice 云端服务部署（已部署: 47.94.4.204）

## 服务器侧布局

- `/opt/autovoice/app.jar` — 可执行 jar（`./gradlew :app:bootJar` 产出）
- `/etc/autovoice/.env` — 密钥环境变量（**不入库**；按 `AutoVoiceServer/env.example`
  建模板，真实值由服务器管理员填写；改后 `systemctl restart autovoice-gateway`）
- `/etc/systemd/system/autovoice-gateway.service` — systemd 服务（本目录同名文件），
  开机自启 + 崩溃自动重启（Restart=on-failure）

## 服务端离线命令词链路（offline.enabled=true，仅阿里云）

离线命令词 = 服务端「传统链路」：讯飞离线命令词原生 SDK（x86-64 Linux .so，
C++ API，FSA 词表 GBK），识别命中在 LLM 前胜出（`offline_won`），未命中由
LLM 兜底（`llm_reply`）。**默认关闭**；Mac 本地跑纯云端链路。

### 服务器侧布局（新增目录）

- `/opt/autovoice/iflytek-offline/` — 讯飞 SDK 原生目录：
  - `libs/libautovoice_offline_esr.so` — 官方 x86-64 Linux .so（+ 其依赖的
    `libaikit.so` 等，SDK 自带的全部 `libs/` 一并放这）
  - `resource/` — 离线资源（`CNENESR/` 等，与端侧 SDK 同源）
  - `work/` — SDK 工作目录（运行时产物，无需预置）
  - `cn_fsa.txt` — FSA 命令词表（GBK 编码）
  - `autovoice_offline_esr.so` — JNI 桥（`offline-command/native/build.sh` 产出）
- 授权方式二选一：
  - **联网激活**（默认，authType=0）：服务器需能出网访问讯飞授权服务（443）；
    appId/apiKey/apiSecret 复用 `XFYUN_APPID` / `XFYUN_API_KEY` /
    `XFYUN_API_SECRET`（§1.1 已导出）；
  - **license 文件**：`AUTOVOICE_OFFLINE_LICENSE_FILE=/opt/autovoice/iflytek-offline/license.txt`
    （authType=1，离线授权，不依赖出网）。

### 环境变量（/etc/autovoice/.env 追加）

```bash
AUTOVOICE_OFFLINE_ENABLED=true
# AUTOVOICE_OFFLINE_LICENSE_FILE=/opt/autovoice/iflytek-offline/license.txt   # 选填
AUTOVOICE_TTS_CACHE_DIR=/opt/autovoice/tts-cache
```

> 安全组无需新增端口：SDK 联网激活走 443 出站，入站仍只有 8080。

### 首次部署步骤

1. 本机：把讯飞 SDK 归档（`libs/` + `resource/` + `cn_fsa.txt`）与 JNI 桥源码
   上传服务器：
   ```bash
   scp -r SDK/讯飞离线命令-Linux/sdk/libs root@47.94.4.204:/opt/autovoice/iflytek-offline/
   scp -r SDK/讯飞离线命令-Linux/sdk/resource root@47.94.4.204:/opt/autovoice/iflytek-offline/
   scp SDK/讯飞离线命令-Linux/cn_fsa.txt root@47.94.4.204:/opt/autovoice/iflytek-offline/
   scp -r AutoVoiceServer/offline-command/native root@47.94.4.204:/opt/autovoice/iflytek-offline/native
   ```
2. 服务器：编译 JNI 桥（`native/build.sh`，已内置 SDK 路径与 rpath）→ 产出
   `autovoice_offline_esr.so`，确认位于 `/opt/autovoice/iflytek-offline/`。
3. 服务器：`.env` 追加上面的变量 → `systemctl restart autovoice-gateway`。
4. 验证：日志出现 `Offline SDK init ok (license: online-activation)`；
   说一句命令词（如"打开空调"）→ `Offline ASR ok: "打开空调"` +
   decision `offline_won` + 回复 < 2s；同文本二次 → `TTS cache HIT`。
   排障见 `offline-command/native/README.md`（错误码 10017/18301、
   `aikit/aeeLog.txt`）。

## 更新流程

```bash
# 本机
cd AutoVoiceServer && ./gradlew :app:bootJar
scp app/build/libs/app.jar root@47.94.4.204:/opt/autovoice/app.jar
# 服务器
systemctl restart autovoice-gateway && journalctl -u autovoice-gateway -f
```

> 离线 SDK（libs/resource/cn_fsa.txt）与 JNI 桥 .so 不随 jar 发布，只在
> 首次部署或 SDK 升级时按上节单独更新。

## 运维命令

```bash
systemctl status autovoice-gateway   # 状态
journalctl -u autovoice-gateway -f   # 实时日志
```

## 前置要求

- 服务器: JDK 21（`dnf install java-21-openjdk-headless`），`demo-full` profile 下
  启动（unit 已带 `--spring.profiles.active=demo-full`）
- 阿里云安全组放行入方向 TCP 8080（手机 `ws://<公网IP>:8080/ws`）
