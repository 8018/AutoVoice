# AutoVoice 云端服务部署（已部署: 47.94.4.204）

## Dev 部署栈（同机隔离，2026-09-11 起）

dev 分支工作流见 `docs/development-workflow.md`。dev 栈与生产同机但完全隔离：

| 项 | 生产 | dev |
|---|---|---|
| 根目录 | `/opt/autovoice/` | `/opt/autovoice-dev/` |
| systemd 服务 | autovoice-gateway / tts / skill-manager | autovoice-dev-gateway / tts / skill-manager |
| 端口 | 8080 / 8082 / 8083 | 8090 / 8092 / 8093 |
| .env | `/etc/autovoice/.env` | `/etc/autovoice-dev/.env` |
| 遥测 SQLite / TTS 缓存 / Skill DB / 离线 work | 生产路径 | 全部 dev 目录独立 |

离线 SDK 只读资源（libs/resource/cn_fsa.txt/.so）由 dev 的 .env 直接指向生产路径共享，
work 目录独立。安全组需放行入方向 TCP 8090（手机测试）。

### 首次初始化（服务器上，root）

```bash
# 本机：把本目录脚本与 unit 文件上传服务器（或 clone 仓库后进入 AutoVoiceServer/deploy）
bash init-dev.sh
vi /etc/autovoice-dev/.env          # 密钥从 /etc/autovoice/.env 复制；端口类变量已预置
systemctl enable --now autovoice-dev-gateway autovoice-dev-tts autovoice-dev-skill-manager
systemctl status autovoice-dev-gateway
```

### GitHub 侧配置（deploy-dev.yml）

在 GitHub 仓库的 `dev` Environment 中配置（与 production 同款）：

- Secret `DEV_SSH_PRIVATE_KEY`：dev 部署专用 SSH 私钥（公钥在服务器 `~/.ssh/authorized_keys`；
  生成与安装步骤见下文"部署密钥"）
- Secret `DEV_SSH_KNOWN_HOSTS`：服务器 known_hosts 记录
- 可选 Variable `DEV_SSH_HOST`（默认 `47.94.4.204`）、`DEV_SSH_USER`（默认 `root`）、
  `DEV_SSH_PORT`（默认 `22`）

首次在 Actions 手动运行 **Deploy dev**（须从 dev 分支）验证三服务就绪后，将仓库
Variable `AUTO_DEPLOY_DEV` 设为 `true`，之后 dev 分支 CI 成功时自动发布。发布脚本
`deploy-release.sh dev` 与生产共用备份/回滚逻辑，失败只回滚 dev 栈。

### 部署密钥（为本机生成 + 安装到服务器）

```bash
ssh-keygen -t ed25519 -C "autovoice-dev-deploy" -f ~/.ssh/autovoice_dev_deploy -N ""
cat ~/.ssh/autovoice_dev_deploy.pub | ssh root@47.94.4.204 \
  "mkdir -p ~/.ssh && chmod 700 ~/.ssh && cat >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys"
ssh -i ~/.ssh/autovoice_dev_deploy -o IdentitiesOnly=yes root@47.94.4.204 "echo dev-key-ok"
ssh-keygen -F 47.94.4.204    # 取指纹行 → DEV_SSH_KNOWN_HOSTS
gh secret set DEV_SSH_PRIVATE_KEY --env dev < ~/.ssh/autovoice_dev_deploy
gh secret set DEV_SSH_KNOWN_HOSTS --env dev   # 交互粘贴指纹行
```

## 服务器侧布局（生产）

- `/opt/autovoice/app.jar` — 可执行 jar（`./gradlew :app:bootJar` 产出）
- `/opt/autovoice/tts-server.jar` — TTS 服务 jar（端口 8082）
- `/opt/autovoice/skill-manager/skill-manager.jar` — 技能管理服务 jar（端口 8083）
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

### GitHub Actions（推荐）

仓库的 `.github/workflows/deploy.yml` 会构建两个 Web 前端和三个 Spring Boot
jar，然后通过 SSH 发布。发布顺序为技能管理服务、TTS、网关；每个服务都要通过
systemd 状态和本机端口检查。任一服务在 90 秒内未就绪，会自动恢复本次发布前的
三个 jar 并重启服务。服务器上的 `.env`、离线 SDK、SQLite 数据和遥测数据不会被
覆盖。

在 GitHub 仓库的 `production` Environment 中配置：

- Secret `PROD_SSH_PRIVATE_KEY`：有权登录部署用户的 SSH 私钥；
- Secret `PROD_SSH_KNOWN_HOSTS`：服务器的 known_hosts 记录，用于严格校验主机身份；
- 可选 Variable `PROD_SSH_HOST`（默认 `47.94.4.204`）、`PROD_SSH_USER`
  （默认 `root`）、`PROD_SSH_PORT`（默认 `22`）。

首次建议在 Actions 页面手动运行 **Deploy production**。确认稳定后，将仓库
Variable `AUTO_DEPLOY_PRODUCTION` 设为 `true`，之后
`main` 分支的 **CI** 成功时会自动发布对应 commit。未设置该变量时，CI 后的自动
发布 job 会跳过，不会产生失败记录。

`PROD_SSH_KNOWN_HOSTS` 应从已经验证过的管理机取得，不要在 workflow 中临时执行
`ssh-keyscan`，否则无法防止中间人攻击。例如先确认当前连接使用的指纹，再读取：

```bash
ssh-keygen -F 47.94.4.204
```

每次发布的构建产物和发布前备份分别保存在 `/opt/autovoice/releases/<commit>` 与
`/opt/autovoice/backups/<commit>-<UTC时间>`，便于审计和手工回退。

### 手工更新

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

## HTTPS/WSS 接入（D03c，生产上线前置）

生产接入必须 TLS。两种方式二选一，域名/证书由运维确认后配置：

1. **反向代理终结 TLS（推荐）**：nginx 等终结 TLS，`wss://域名/ws` 转发到
   `ws://127.0.0.1:8080/ws`（网关注入默认端口，安全组不再需要对外放行明文端口）。
2. **网关注直连 TLS**：`.env` 置 `AUTOVOICE_SSL_ENABLED=true` +
   `AUTOVOICE_SSL_KEYSTORE` / `AUTOVOICE_SSL_KEYSTORE_PASSWORD`（PKCS12），
   客户端连 `wss://域名:端口/ws`。

客户端配合：Android release 构建禁用明文流量（debug 构建保留局域网明文例外），
生产地址必须 `wss://`（见 docs/development-workflow.md）。域名、证书与设备凭据
发放方式确认前不切换线上接入方式。

## 低权限运行模板（D12b，可选）

`deploy/autovoice-gateway-hardened.service` 提供以专用低权限用户运行、收敛文件系统/能力、
带资源上限的 systemd 模板（默认**不启用**，启用步骤见文件头部注释）。要点：

- 进程不再以 root 运行；`/opt/autovoice` 属主改为 `autovoice`；
- `/etc/autovoice/.env` 保持 `root:autovoice 0640`（systemd 以 root 读取环境文件）；
- 可写路径仅限：离线 SDK work 目录、TTS 缓存、遥测库、动作账本库；
- 内存/文件描述符/任务数上限，避免单实例耗尽主机。

## 部署判据与产物追溯（D13）

`deploy-release.sh` 已升级：

- **就绪判据**：gateway 用 `GET /health/ready`（200 才算就绪，区分"端口在监听"与
  "业务可用"）；该端点不存在时自动回退 TCP 端口判据（兼容旧 jar）。其他服务沿用端口判据。
- **产物追溯**：每次发布在 `/opt/autovoice/releases/<sha>/` 写入 `metadata.txt`
  （SHA、环境、后端变体、时间）与 `checksums.sha256`（部署产物摘要）。
- **回滚边界**：jar 回退不改变数据库 schema；若发布包含不向后兼容的 schema 变更，
  按 D13 恢复流程处理（不要盲目回退代码）。

> 以上判据与模板的**实际效果需在服务器上验收**（本地无法验证 systemd/权限行为）。

## MCP 凭据用秘密引用（D14a）

平台侧 `authValue` 现在应填**引用**而非明文：

| 形式 | 含义 | 示例 |
| --- | --- | --- |
| `env:NAME` | 由部署方注入的环境变量（推荐） | `env:AMAP_MCP_KEY` |
| `file:/path` | 受控文件内容（建议 0600，属主为服务账号） | `file:/etc/autovoice/secrets/amap.key` |
| 空 | 该 Skill 无需认证头 | —— |

- **解析失败会让该 Skill 连接失败**（日志含引用名，不含秘密值），不会静默退化为
  "无凭据连接"——那会以未认证身份访问下游。
- 历史明文值仍可读，但网关会记录 `uses inline credential; migrate to env:/file: reference`
  提示；生产建议尽快迁移。
- 环境变量在 `/etc/autovoice/.env` 中配置（该文件不入库）。

## 动作执行语义（2026-09-13）

- 网络断开即结束当前语音轮：不重传音频、不重放业务回复、不补执行动作。
- 客户端仅允许当前 `turnId` 抢占一次动作执行；迟到、重复和空轮回调均不执行。
- 执行失败使用失败话术，不能继续播报“已完成”类成功文案。
- `actionId` 仅作旧协议兼容关联字段，不落持久化动作账本；遥测与安全审计仍按各自策略保留。
