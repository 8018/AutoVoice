# system prompt 平台化配置设计

## 背景与目标

`DeepSeekLlmProvider.SYSTEM_PROMPT` 当前为代码硬编码（static final）。需求：将 LLM system
prompt 改为通过 skill 管理平台（47.94.4.204:8083）配置 —— 管理员在平台 UI 编辑，网关拉取并
注入，**运行时热更新立即生效**，不重启网关。

已确认决策：

1. **粒度**：平台级全局配置 —— 单个 systemPrompt 字符串，全网关 LLM 共用（skill 是 MCP server
   封装，不携带 prompt）。
2. **生效时机**：热更新 —— 平台保存后 webhook 推网关立即生效，轮询兜底。
3. **方案**：方案 A —— 独立端点 `/api/config/system-prompt` + `settings` 表 + 网关
   `Supplier<String>` 注入；`/api/skills` 协议不动（向后兼容）。

## 架构

```
平台 (8083)                               网关 (8080)
┌─────────────────────────┐              ┌──────────────────────────────┐
│ settings 表               │              │ SkillPlatformClient           │
│  system_prompt 单行       │              │  ├ fetchEnabled()             │
│ ConfigController          │              │  └ fetchSystemPrompt() ← 新增 │
│  GET/PUT /api/config/     │              │ McpSkillRegistry.refresh()    │
│  system-prompt            │              │  ├ sessions 更新（现有）        │
└─────┬───────────────┬─────┘              │  └ SystemPromptStore.set() ← 新增
      │ admin cookie  │ service token      │        ▲  AtomicReference     │
      ▼               ▼                    │ DeepSeekLlmProvider           │
  前端设置区（编辑+保存）  网关拉取（只读）       │  systemMessage() ← get()    │
                                            └──────────────────────────────┘
```

## 组件

### 平台侧（skill-manager 模块）

**存储** — `SqliteSkillStore`：

- 新增 `settings` 表：`key TEXT PRIMARY KEY, value TEXT`。
- 行 `system_prompt` 存当前 prompt（可为空字符串 = 未配置）。
- `CREATE TABLE IF NOT EXISTS` 建表，对已部署库（已有 skills 表）零迁移；连接共用现有
  SQLite 文件与 `withConnection` 机制。
- 读写方法：`getSetting(key)` → `Optional<String>`；`setSetting(key, value)`（upsert）。

**API** — 新 `ConfigController`（`@RequestMapping("/api/config")`）：

| 方法 | 路径 | 鉴权 | 语义 |
|---|---|---|---|
| GET | `/api/config/system-prompt` | admin cookie **或** X-Skill-Service-Token | 返回 `{"value":"..."}`，未配置 → `{"value":""}` |
| PUT | `/api/config/system-prompt` | **仅 admin cookie**（service token 只读） | body `{"value":"..."}`（空串合法），保存后**触发 webhook**（复用 SkillWebhookPublisher，向网关推 refresh） |

- 沿用 AdminAuthInterceptor 的鉴权接线（/api/config/** 加入拦截范围；GET 双通道、PUT
  admin-only 的区分在 Controller 或拦截器内实现 —— 实现时按现有代码风格选择，测试锁定语义）。
- PUT 响应 `{"value": "..."}`（保存后的值）；失败 4xx。

**前端**（skill-manager-web）：

- skill 列表页顶部加"系统提示词"可折叠面板：textarea + 「保存」按钮 + 「恢复默认」按钮
  （恢复默认 = 保存空串，网关回退内置文案）。
- 登录后加载 GET 现值；保存走 PUT；401 → 现有 unauthorized 处理路径。
- 构建产物照旧打包进 `skill-manager/src/main/resources/static/skill-manager/` 并提交。

### 网关侧（skill-mcp + llm 模块）

**`SkillPlatformClient.fetchSystemPrompt()`**：

- `GET {baseUrl}/api/config/system-prompt`，带 X-Skill-Service-Token。
- 返回 `String`（body 解析 `value` 字段）；HTTP 非 2xx、网络异常、JSON 解析失败 → 返回
  `null` 不抛（调用方 keep 现值）；baseUrl 空白 → `null`（功能关闭，同 fetchEnabled 语义）。

**`SystemPromptStore`**（skill-mcp 模块，新类）：

- 内部 `AtomicReference<String>`；构造时初始为 null。
- `get()` → 当前值，null/空白 → `DEFAULT`（由构造参数传入的默认文案）。
- `set(String)` → 更新引用（null 忽略）。

**`McpSkillRegistry`**：

- `refresh()` 拉完 skills 后顺带 `client.fetchSystemPrompt()` → `store.set()`；
  失败（null）不覆盖、仅 warn（不影响 sessions 更新与既有日志节奏）。
- ctor 加 `SystemPromptStore` 参数（或工厂函数注入 —— 实现时按现有装配风格定，测试锁定语义）。

**`DeepSeekLlmProvider`**（llm 模块）：

- 现有 `SYSTEM_PROMPT` static final 改名为 `DEFAULT_SYSTEM_PROMPT`（文案不变，语义=未配置回退）。
- ctor 第 8 参加 `Supplier<String> systemPrompt`（纯 JDK `java.util.function`，llm 模块零新依赖）。
- `systemMessage()` 由 static 改实例方法：`supplier.get()` 取值，null/空白 → `DEFAULT_SYSTEM_PROMPT`。
- AppConfig `llmProvider(...)` 装配时传入 SystemPromptStore 引用（`store::get`）。

## 数据流与时序

1. 管理员在平台 UI 编辑 prompt → PUT（admin cookie）。
2. 平台存 `settings.system_prompt` → 触发 webhook（网关 `/api/internal/skills/refresh`，service token）。
3. 网关 `McpSkillRegistry.refresh()` → 拉 skills + 拉 prompt → `store.set(newPrompt)`。
4. 下一次（或正在进行的）LLM 调用 `systemMessage()` 读到新值 —— 立即生效。
5. 兜底：轮询（SKILL_MANAGER_POLL_MS，默认 600000ms）每次刷新都重拉 prompt；
   平台保存失败/网络抖动由轮询收敛。

## 兼容性与错误处理

- **老平台**（无 /api/config 端点）：网关 404 → `fetchSystemPrompt()` 返回 null → keep 默认，
  功能无缝降级，`/api/skills` 协议完全不变。
- **平台 401/网络错误**：null → keep 现值，不影响 sessions 更新。
- **空值配置**：`{"value":""}` → 网关回退内置 `DEFAULT_SYSTEM_PROMPT`（与未接入平台行为一致）。
- **鉴权**：service token 只读 —— 内部 token 泄漏不能改配置；admin cookie 才可写。
- **多网关实例**：各自拉取，无一致性问题。

## 测试

平台侧：

- `ConfigControllerTest`（MockMvc + 现有测试风格）：
  - GET 无凭据 401；service token 200；admin cookie 200
  - PUT service token → 401（只读）；PUT admin cookie → 200 且落库；GET 回读一致
  - PUT 空值 → 200，GET 返回 `{"value":""}`
  - PUT 后 webhook 被触发（mock SkillWebhookPublisher 或验证调用）
  - 未配置初始状态 GET → `{"value":""}`

网关侧：

- `SystemPromptStoreTest`：初始 get → 默认；set 后 get → 新值；set(null) 忽略；set("") → 默认
- `DeepSeekLlmProviderTest`（现有测试扩展）：systemMessage 用 supplier 提供的值；supplier 返回
  空白 → 默认文案；多轮工具循环回归不回归
- `McpSkillRegistry` 相关测试（fake client）：refresh 拉 prompt 并 set；client 返回 null → keep
  现值；sessions 逻辑不受影响

端到端：平台 PUT 后网关日志 `skill registry refreshed`（webhook 链路，部署后按部署文档验证）。

## 范围外（YAGNI）

- 模板变量（如自动注入当前工具清单）
- 按 skill 覆盖 prompt（本次决策为全局单值）
- 多租户 / 版本历史 / 灰度
- LLM 其他参数（temperature、model 名等）平台化 —— 本次仅 system prompt

## 涉及文件（预期）

- 平台：`SqliteSkillStore`（settings 表）、新 `ConfigController`、`SkillService` 或新
  ConfigService（读写）、WebMvcConfig 拦截器接线、前端 App.tsx / api.ts / 相关组件
- 网关：`SkillPlatformClient`、新 `SystemPromptStore`、`McpSkillRegistry`、`AppConfig`、
  `DeepSeekLlmProvider`
- 文档：`docs/skill-mcp-deploy.md`（/api/config 说明）、runbook 相关章节
