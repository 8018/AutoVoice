# skill 管理平台（MCP host）部署与验证

可选组件：网关按需从平台拉取启用 skill，把外部 MCP 工具注入 LLM 多轮调用
（POI 搜索等）。不部署时功能完全关闭，网关仅注入 car_control 默认 skill。

> 所有 `<...>` 占位符只填在服务器 `/etc/autovoice/.env`，**绝不提交仓库**。

## 1. 构建

```bash
cd /Users/michaelliu/code/AutoVoice/.worktrees/skill-mcp-platform/AutoVoiceServer
./gradlew :app:bootJar :skill-manager:bootJar   # 产出 app/build/libs/app-*.jar、skill-manager/build/libs/skill-manager-*.jar
```

- 需 JDK 21（`dnf install java-21-openjdk-headless`），同网关要求。
- Web 管理界面已随 `skill-manager-*.jar` 打包（构建产物入库于
  `skill-manager/src/main/resources/static/skill-manager/`）；仅改前端时才需
  `cd skill-manager-web && npm install && npm run build` 重建。

## 2. 平台部署（阿里云 47.94.4.204）

目录 `/opt/autovoice/skill-manager/`，SQLite 落
`/opt/autovoice/skill-manager/skill-manager.db`。

1. 上传并安装：

   ```bash
   scp skill-manager/build/libs/skill-manager-*.jar \
       root@47.94.4.204:/opt/autovoice/skill-manager/skill-manager.jar
   ```

2. systemd 服务 `/etc/systemd/system/autovoice-skill-manager.service`
   （镜像 autovoice-gateway.service 结构）：

   ```ini
   [Unit]
   Description=AutoVoice skill manager platform
   After=network.target

   [Service]
   User=root
   EnvironmentFile=/etc/autovoice/.env
   ExecStart=/usr/bin/java -jar /opt/autovoice/skill-manager/skill-manager.jar
   Restart=always
   RestartSec=5

   [Install]
   WantedBy=multi-user.target
   ```

3. `/etc/autovoice/.env` 追加（`<...>` 换成真实值，不提交仓库）：

   ```
   SKILL_MANAGER_PORT=8083
   SKILL_MANAGER_DB=/opt/autovoice/skill-manager/skill-manager.db
   SKILL_MANAGER_ADMIN_TOKEN=<平台管理口令>
   SKILL_SERVICE_TOKEN=<与网关一致的内部 token>
   SKILL_MANAGER_GATEWAY_WEBHOOK_URL=http://127.0.0.1:8080
   ```

4. 启动：

   ```bash
   systemctl daemon-reload && systemctl enable --now autovoice-skill-manager
   ```

说明：

- `SKILL_SERVICE_TOKEN` 是网关 ↔ 平台内部调用口令，必须与网关侧同值（配置 webhook 时必填）；
- `SKILL_MANAGER_ADMIN_TOKEN` 是管理口令（web 登录 / 管理 API，cookie 会话），
  必填：空 → 平台启动快速失败（终审 M1）；
- `SKILL_MANAGER_GATEWAY_WEBHOOK_URL` 填网关 **base URL**（如 `http://127.0.0.1:8080`）：
  平台会追加 `/api/internal/skills/refresh`（SkillWebhookPublisher 语义，勿写全端点）；
  为空时改 skill 不推网关，仅靠轮询收敛；
- 可选 `SKILL_MANAGER_MCP_TIMEOUT_MS`（默认 5000）：平台 discover 时 MCP 连接超时；
- 浏览器访问管理面板需安全组放行入方向 TCP 8083，或走 SSH 隧道：
  `ssh -L 8083:127.0.0.1:8083 root@47.94.4.204` 后访问本机 8083。

## 3. 网关侧

`/etc/autovoice/.env` 追加：

```
SKILL_MANAGER_URL=http://127.0.0.1:8083
SKILL_SERVICE_TOKEN=<同平台>
SKILL_MANAGER_POLL_MS=600000
```

重启 `systemctl restart autovoice-gateway`。

> `SKILL_MANAGER_URL` 为空白（默认）时网关不注入 MCP 工具（仅 car_control），
> 功能关闭，行为与接入前一致。

## 4. 高德 MCP 示例

平台里新建 skill：mcpUrl `https://mcp.amap.com/mcp`（示例地址，以高德开放平台实际
文档为准），authHeader `x-api-key`，authValue=高德 Web 服务 API key；发现工具后勾选
poi_search 等；启用。

## 5. 验证清单

1. **平台 API**：
   `curl -H 'X-Skill-Service-Token: <token>' http://127.0.0.1:8083/api/skills?enabled=true`
   返回启用 skill 明文。
2. **网关拉取**：网关日志出现 `skill registry refreshed: N sessions`。
3. **webhook**：平台改 skill 后网关日志立即出现重拉（无需等 10min）。
4. **降级**：`systemctl stop autovoice-skill-manager` → 网关日志
   `skill platform pull failed, keep N sessions`，链路不崩；重启平台后恢复。
5. **E2E 真机/探针**：说话"导航去西湖" → 网关 LLM 多轮调 MCP → 播报 POI 结果；
   命令词"打开空调"仍离线秒回（回归）。
6. **前端**：浏览器 `http://47.94.4.204:8083/skill-manager/index.html` 登录 →
   新建/发现/勾选/启用。

## 6. 风险 / 已知限制

- MCP SDK 2.0.0 对应 MCP spec 2025-11-25，若目标 MCP server 实现更新的 spec
  （2026-07-28）需验证兼容。
- selector 分级未实现：启用工具 >8 个时仍全量注入，并打告警日志
  （`启用工具 N 个超过 direct 上限 8，selector 策略未实现，仍全量注入`）。
- 空 MCP server（tools 为空）→ 该 skill 不注入工具但 skill 保持启用。
