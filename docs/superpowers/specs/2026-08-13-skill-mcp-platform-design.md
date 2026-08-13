# Skill 管理平台 + MCP host 设计

> 目标：为 AutoVoice 语音系统做一个独立部署的 skill 管理平台——平台管理"skill"（第三方 MCP server 的封装配置：地址/凭据/工具勾选），启用即发布；语音网关作为 MCP host 拉取启用的 skill，把 MCP 工具注入 DeepSeek 的 function calling，语音指令可调用第三方能力（如高德地图 MCP）。

## 1. 背景与现状

AutoVoice 语音链路：设备说话 → 网关 WS → VAD/ASR → 双候选竞速（离线命令词 vs 云端 LLM）→ 仲裁 → reply → TTS 播报。

现状事实：
- `DeepSeekLlmProvider`（llm 模块）：OpenAI 兼容 chat/completions，**单轮硬编码工具** `car_control`——模型调工具后直接转 action 回复，不回 LLM 续轮；tools 是常量 `TOOLS_JSON`，不可注入。
- RaceArbiter：safety-timeout 6500ms、offline-grace 1500ms；reason 含 offline_won / llm_reply / asr_failed_fallback。
- 用户目标：语音里能说"导航去西湖"→ 调高德 MCP（POI 搜索/路线规划）→ 结果播报。skill = 对第三方 MCP server 的封装（网络调研确认的业界形态：**中央注册表 + 网关集成**，见 Stacklok/MintMCP 模式）。

## 2. 架构总览

```
┌─ skill-manager 平台（独立应用，阿里云 8083，systemd 独立服务）──┐
│  Spring Boot + SQLite + React/Vite 管理界面（jar 内 static）    │
│  界面：skill 列表 / 新建 / 编辑 / 启用禁用 / "发现工具"按钮        │
│  API：管理 CRUD + PATCH enabled + POST discover +              │
│       GET /api/skills?enabled=true（网关拉取）                  │
│  写操作后 POST 网关 /api/internal/skills/refresh（webhook 推送） │
└──────────────┬────────────────────────────────────────────────┘
               │ ①启动拉取 ②webhook 刷新通知 ③10 分钟兜底轮询
┌─ AutoVoice 网关（现有 app.jar，8080）──────────────────────────┐
│  skill-mcp 模块（新）：                                        │
│  McpSkillRegistry：内存快照（会话零感知），每次会话直接用缓存      │
│  McpClient（官方 io.modelcontextprotocol Java SDK，            │
│   streamable HTTP + 认证头）：list_tools 自动发现 → 勾选过滤      │
│  → 分级注入（≤8 direct 全量 / >8 selector 2 meta 工具）          │
│  DeepSeekLlmProvider 改造：tools 注入式 + 多轮循环（时间预算 5s） │
│  RaceArbiter 零改动（LLM 候选=黑盒）                            │
└────────────────────────────────────────────────────────────────┘
```

- **独立部署**（调研结论：registry 平台应独立部署，网关只做消费者；同机不同端口 + 独立 systemd 服务，不碰网关进程）。
- 端侧/WS 协议/TTS/仲裁**零改动**。

## 3. skill 数据模型

| 字段 | 说明 |
|---|---|
| id | 唯一标识（`amap-maps`），新建时指定 |
| name | 显示名（"高德地图"） |
| description | 用途描述，**面向 LLM**（何时使用此 skill 的工具） |
| mcpUrl | MCP server 地址（streamable HTTP，如 `https://mcp.amap.com/mcp`） |
| authHeader / authValue | 认证头（`x-api-key` / `Authorization`）；**secret，不打印不进日志** |
| toolsJson | 自动发现后勾选：`[{"name":"poi_search","enabled":true},...]` |
| enabled | 发布开关：启用 = 网关注入 LLM；禁用 = 网关移除 |
| updatedAt | 变更时间（网关轮询增量判断依据） |

SQLite 单表，凭据列存原文（SQLite 文件权限 600，同 telemetry 模式）。

## 4. 平台 API

| 方法/路径 | 说明 |
|---|---|
| POST /api/skills | 新建（幂等：id 冲突 409） |
| GET /api/skills | 列表（管理端：全部；可 ?enabled= 过滤） |
| GET /api/skills/{id} | 详情 |
| PUT /api/skills/{id} | 编辑 |
| DELETE /api/skills/{id} | 删除 |
| PATCH /api/skills/{id}/enabled | 启用/禁用（发布开关） |
| POST /api/skills/{id}/discover | 连一次 MCP server 拉工具列表返回（不落库），供界面勾选 |
| GET /api/skills?enabled=true | **网关拉取**：返回完整配置（含认证头，同机内网 + API token） |

管理端鉴权：demo 阶段平台管理界面加简单口令（env `ADMIN_TOKEN`，登录态 cookie）；网关拉取 API 用 `SKILL_SERVICE_TOKEN`（env）校验，防内网越权。

**发布语义**：点"启用" → 平台落库 + webhook 推网关 → 网关立即重拉 → 下一句生效（秒级）。

## 5. 网关 MCP host 集成（skill-mcp 模块）

### 5.1 配置同步（McpSkillRegistry）
- 启动拉取一次 → 内存快照；会话直接用快照（零延迟、零请求）。
- 平台 webhook（`POST /api/internal/skills/refresh`，ServiceToken 校验）→ 立即重拉。
- 10 分钟兜底轮询（防通知丢失/平台重启间隙改动）。
- 平台不可达：保留上次成功快照（缓存降级），日志告警不崩链路。

### 5.2 MCP client 与工具发现
- 每启用 skill 一个 `McpClient`（官方 Java SDK，streamable HTTP，认证头注入）。
- `list_tools` 自动发现 → 按 toolsJson 勾选过滤 → 缓存工具定义。
- MCP server 连接失败：该 skill 工具不注入、告警日志、其余 skill 不受影响。
- 工具调用（call_tool）按需一次 HTTP，无长连接维护。

### 5.3 工具注入分级策略（网络调研修正）
| 启用工具总数 | 策略 | 说明 |
|---|---|---|
| ≤8 | direct：全量 schema 注入 | 语音场景快、单轮决策 |
| >8 | selector：2 个 meta 工具（`mcp_tools_get` 查目录 / `mcp_tools_execute` 按名调用） | 防 token 爆炸（业界实测省 ~97%） |

selector 模式下 meta 工具由网关 skill-mcp 模块实现（内部路由到对应 skill 的 client）。首版实现 direct；selector 作为扩展点（接口预留 `ToolInjector` 策略）。

## 6. DeepSeekLlmProvider 改造

- `tools` 从常量改为**注入式**：配置的 `car_control` + McpSkillRegistry 快照的启用的 MCP 工具合并。
- **多轮工具循环**（原单轮→多轮）：
  ```
  loop (≤3 轮, 时间预算 5s):
    LLM 调用(带 tools + 历史)
    if tool_calls:
      执行（car_control 本地 / MCP call_tool 网关侧）
      tool_result 追加 messages → continue
    else: 返回最终文本
  ```
- **时间预算**：每轮前检查剩余预算，超预算 → 丢 tools 强制模型直答——LLM 候选总耗时永不突破 6.5s 仲裁超时。
- 工具调用失败：错误文本回 LLM 续轮 → LLM 生成兜底回复（"高德服务暂时不可用"）。MCP 整体故障（skill 全挂）→ 工具不注入 → 纯文本回复。
- `LlmProvider` 接口不变，RaceArbiter 零改动（LLM 候选 = 黑盒）。

## 7. 仲裁协调（用户确认）

- 多轮封装在 provider 内部，仲裁不感知轮数。
- 时间预算 5s ≤ safety-timeout 6.5s：简单指令 <1s（离线/单轮），MCP 指令 3-4s（预算内），超时兜底仍有效。
- 命令词（打开空调）仍走离线快速通道，offline_won 语义不变。
- asr_failed_fallback 语义不变。

## 8. 安全

- 凭据（高德 key 等）：只存平台 SQLite（600 权限）；日志/接口响应脱敏（authValue 永不回传明文给管理端 UI——回显掩码 `****`，编辑时留空=不变）。
- 网关内部端点 + 平台拉取 API：ServiceToken 校验（env `SKILL_SERVICE_TOKEN`）。
- 管理界面：AdminToken 口令登录（demo 级）。
- MCP 工具结果视为不可信文本（prompt injection 向量）：工具描述/结果只作为用户消息文本进 LLM，不拼接 system 提示。

## 9. 测试

- **平台**（skill-manager 模块）：CRUD API 测试（MockMvc）、enabled 过滤、discover（MockWebServer 模拟 MCP JSON-RPC）、webhook 推送（MockWebServer 断言网关收到 POST）、secret 掩码测试。
- **网关**（skill-mcp 模块）：McpSkillRegistry（平台不可达→缓存快照、webhook 触发重拉、轮询增量）、工具发现与过滤（MockWebServer 模拟 MCP list_tools）、分级注入策略、McpToolExecutor。
- **LLM**：DeepSeekLlmProvider 多轮循环（MockWebServer 模拟 chat/completions 两轮 tool_use）、时间预算强制收尾、工具失败兜底。
- **E2E**：本地双进程（平台 + 网关）probe——配置启用 skill → 说话 → LLM 调 MCP 工具 → 播报（mock 高德 MCP server）。
- **真机**：说"导航去西湖" → 高德 POI 搜索 → 结果播报；命令词回归（打开空调不退化）。

## 10. 部署

- 平台：新 jar（skill-manager 模块 bootJar）+ systemd 服务（8083，EnvironmentFile 复用 /etc/autovoice/.env 追加 SKILL_* 键）+ SQLite 落 /opt/autovoice/skill-manager/。
- 网关：app.jar 重新构建（含 skill-mcp 模块）+ .env 追加 `SKILL_MANAGER_URL=http://127.0.0.1:8083`、`SKILL_SERVICE_TOKEN`（与平台一致）、`SKILL_MANAGER_POLL_MS=600000`。
- 端侧 APK 不变（协议零改动）。

## 11. 范围外（后续，YAGNI）

- OAuth 凭据模型（调研确认的成熟方向，demo 用 static key 够）。
- 多用户/RBAC、审计日志、MCP server 安全扫描。
- selector 分级策略首版仅预留接口，不实现。
- 平台高可用/多实例。
