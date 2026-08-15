# 高德 MCP 模糊导航方案（探索结论 + 落地设计）

**日期**：2026-08-15
**状态**：探索完成，方案待用户拍板（端侧执行部分是否实现）
**对标**：特斯拉 Grok 语音模糊导航（"去三里屯"→ 自动解析 → 下发导航）

## 1. 目标

用户说一句自然语言目的地（如"导航去三里屯"、"找最近的充电站"），系统：
1. 语音 → LLM 语义解析出导航意图
2. 调地图能力把模糊表述解析为具体 POI/坐标
3. 执行导航（播报结果，或真正拉起高德 App 导航）

## 2. 外部事实

### 2.1 高德官方 MCP Server（存在，可直接用）

- 在线服务地址：`https://mcp.amap.com/mcp?key=<Web服务Key>`（**Streamable HTTP** 协议，2025-06-23 起提供）
- 旧 SSE 版 `https://mcp.amap.com/sse?key=...` 已于 **2026-03-17 下线**
- 12 大核心服务，与模糊导航相关的：

| 类别 | 能力 | 用途 |
|---|---|---|
| 解析 | 地理编码 / 逆地理编码 | "人民广场" → 坐标（核心） |
| 解析 | POI 关键词搜索 / 周边搜索 / 详情搜索 | "三里屯"、"最近的充电站"（核心） |
| 规划 | 驾车 / 步行 / 骑行 / 公交路径规划 | 路线 + 耗时播报 |
| 导航 | 一键导航 / 一键打车 | 与高德 App 联动（云端拉不起手机 App，端侧执行用） |
| 辅助 | 距离测量 / IP 定位 / 天气 / 行政区查询 | 会话增强 |

- 第三方开源实现（自备 key）：luodora/mcp-amap、zengzeruidd-a11y/amap-mcp-server（9 工具）、Keldon-Pro/amap-mcp-streamable_http 等——本项目不需要，官方即可。

### 2.2 账号申请（一一列举）

1. 注册：[lbs.amap.com](https://lbs.amap.com/)（或 console.amap.com），手机号/支付宝登录
2. **实名认证**（2023 年起必须，仅手机号注册无法开 API）：
   - 个人：身份证正反面 + 人脸识别（当天过，免费）
   - 企业：营业执照等（配额高 20 倍，demo 不需要）
3. 控制台 → 应用管理 → 创建新应用，应用类型 **Server**
4. 应用内 **添加 Key → 服务类型必须勾选"Web 服务"**（最常见踩坑点）
5. 记录 Key；**严禁硬编码/提交**，放 skill 平台 authValue（管理端视图脱敏）
6. 可选：IP 白名单（只放行 47.94.4.204 出口 IP）

免费配额（个人认证）：路径规划/地理编码等基础服务 **15万次/月**，POI 搜索 **5000次/月**（企业 300万/月、10万+/月）——demo 足够。

### 2.3 特斯拉 Grok 模糊导航的做法（拆解）

三步，本项目每步都有对应物或缺口：

1. 语音 → **LLM 语义解析**（Grok 理解意图 + 抽取目的地）
   → 本项目已有：云端 DeepSeek function calling（`DeepSeekLlmProvider`，OpenAI 兼容 tools + 3 轮工具循环，预算 5s）
2. 语义 → **POI/坐标**（Grok 调地图搜索/地理编码）
   → 对应高德 MCP 的 POI 搜索/地理编码（**核心能力，待接入**）
3. 坐标 → **导航执行**（特斯拉：车机原生导航深度集成；还可加途经点、路线偏好）
   → **本项目缺口**：当前 spec 设计是"云端 MCP 结果语音播报"，无端侧导航执行（见 §4.2）

## 3. 本地现状（已探明，无需改动即可用的部分）

- **skill 平台**（47.94.4.204:8083，`autovoice-skill-manager`）：skill = 第三方 MCP server 封装
  `SkillRecord(id, name, description, mcpUrl, authHeader, authValue, toolsJson, enabled)`；SQLite `skills` 表；写操作后 webhook 推网关；另有平台级 `settings.system_prompt`
- **MCP host = 网关 app.jar**（skill-mcp 模块）：`SkillPlatformClient` 拉取 → `McpSkillRegistry` 快照（webhook 热重拉 + 10min 轮询兜底）→ `McpToolSession`（官方 MCP Java SDK 2.0.0，streamable HTTP）`listTools` 自动发现 → `toolsJson` 勾选过滤 → 转 `FunctionTool` 注入 LLM（≤8 个直注，超量打告警仍全量）
- **DeepSeekLlmProvider**：完整 OpenAI 兼容 function calling；默认 skill `car_control`（domain 仅 climate/window）；MCP 工具结果作为文本回 LLM 续轮（`McpToolExecutor`）
- **端侧**：无任何 navigation 意图/高德代码；`rule.nlu` 只有空调/车窗；执行出口 `vehicle.apply(intent)`（MockVehicleState 只处理 climate/window）
- **system prompt 是纯云端链**：平台 settings → 网关 SystemPromptStore（热更新）→ DeepSeek systemMessage（未配置回退内置默认）

## 4. 方案

### 4.1 配置即用（无需改代码）

**流程**：账号申请 → skill 平台配置 → webhook 自动热生效。

1. 平台管理面板（8083）新建 skill：
   - `name`：高德地图；`mcpUrl`：`https://mcp.amap.com/mcp`
   - 认证优先 `authHeader=x-api-key` + `authValue=<Key>`（平台视图脱敏）；若官方 streamable HTTP 不接受 header，退化为 `mcpUrl` 带 `?key=<Key>`（Key 明文存 mcp_url 字段，管理端不脱敏，权衡）
2. discover（`POST /api/skills/{id}/discover`，平台用官方 MCP SDK list_tools）→ 勾选 POI 搜索/地理编码等 → `toolsJson`
3. 启用 → webhook → 网关 `refreshAsync` → 工具注入 DeepSeek
4. `PUT /api/config/system-prompt` 加引导："用户说'导航去 X''去 X 怎么走'时，先用高德 POI 工具解析 X 为具体地点，再规划路线"

API 示例（幂等可重放）：

```bash
curl -X POST https://47.94.4.204:8083/api/skills \
  -H "X-Skill-Service-Token: $SKILL_SERVICE_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "id": "amap-maps",
    "name": "高德地图",
    "description": "目的地解析与路径规划",
    "mcpUrl": "https://mcp.amap.com/mcp",
    "authHeader": "x-api-key",
    "authValue": "<你的Key>",
    "toolsJson": "[{\"name\":\"poi_search\",\"enabled\":true}]",
    "enabled": true
  }'
```

**此形态下效果**："导航去三里屯" → LLM 调 POI 搜索 → 播报"三里屯，距您 X 公里…"（不真正导航）。

### 4.2 端侧导航执行（做出特斯拉效果的缺口，待拍板实现）

- `shared/contracts/intent.schema.json` 增加 `action:navigate {poiname, lat, lon}`
- 端侧新增 navigation 执行器：收到 action 后跳高德 App：
  `androidamap://navi?sourceApplication=autovoice&poiname=<名称>&lat=<纬度>&lon=<经度>`
- 链路：云端 POI 解析出坐标 → 端侧跳转 → 高德 App 接管导航
- 依赖：手机装有高德 App

### 4.3 不需要联网搜索 MCP（明确结论）

- 模糊导航的歧义是**地图语义**，高德 POI 搜索/周边/地理编码天然覆盖（带联想纠错）
- 通用 web 搜索 → 网页文本 → 二次转坐标，链路长、噪声大
- "新闻里说的那家店"这类无地点名实体是**对话记忆**问题，不是搜索能解决的
- 未来若做"充电站比价"，加专门充电 POI 数据源 MCP，而非通用搜索

## 5. 待办清单

**用户侧**：
- [ ] 申请高德 Web 服务 Key（§2.2）
- [ ] 平台配置 skill（§4.1）或提供 Key 由会话执行配置
- [ ] 拍板 §4.2 端侧导航执行是否实现

**开发侧（若做 §4.2）**：
- [ ] intent.schema.json 加 navigate action
- [ ] 端侧 navigation 执行器 + 高德 URL scheme 跳转
- [ ] 云端工具循环联调（POI 搜索 → 坐标 → action 下发）

## 6. 参考来源

- 高德官方 MCP：[文档](https://lbs.amap.com/api/mcp-server/summary#s6)、[更新日志](https://developer.amap.com/api/mcp-server/changelog)、[应用案例](https://lbs.amap.com/api/mcp-server/application-case/tourism-planning)
- 第三方实现：[luodora/mcp-amap](https://github.com/luodora/mcp-amap)、[amap-mcp-server](https://github.com/zengzeruidd-a11y/amap-mcp-server)、[streamable_http 版](https://github.com/Keldon-Pro/amap-mcp-streamable_http)
- 特斯拉 Grok：[2026.26 更新](https://teslascope.com/software/2026.26)、[Grok 升级语音控车](https://www.d1ev.com/newsflash/308188)、[夏季更新 Grok 接管车厢控制](https://dushi.singtao.ca/toronto/%e6%96%b0%e9%97%bb/%e5%8d%b3%e6%97%b6%e5%9b%bd%e9%99%85/tesla-2026%e5%a4%8f%e6%97%a5%e6%9b%b4%e6%96%b0%e6%8e%a8%e9%80%81%ef%bc%81grok-ai%e6%8e%a5%e7%ae%a1%e8%bd%a6%e5%8e%a2%e6%8e%a7%e5%88%b6-%e8%a1%8c%e7%a8%8b%e8%a7%84%e5%88%92%e5%81%8f%e5%a5%bd%e4%bc%98/)
- 账号/配额：[注册开发者](https://console.amap.com/dev/id/phone)、[个人 vs 企业认证差异](https://developer.amap.com/faq/account/certification/39670)、[Web 服务 key 获取](https://www.yuque.com/yuehou/dian/apply-for-amap)
