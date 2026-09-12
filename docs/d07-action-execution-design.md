# D07 动作执行边界设计提案(2026-09-12)

依据:`docs/decisions-2026-09-12-D05b-D07.md`(Neo 拍板)与
`docs/production-development-plan.md` D07。状态:设计提案,待确认后实施。

## 决策落点

| 决策 | 设计落点 |
| --- | --- |
| 独立 SQLite 业务库 | 服务端新模块 `action-ledger`,独立 DB 文件(`AUTOVOICE_ACTION_LEDGER_DB` 可配),使用与 telemetry 相同的 SQLite 驱动方式,不复用遥测库连接;不自研 WAL,用 SQLite 默认日志模式 |
| 云端动作云端记账 / 手机动作手机本地记账 | 服务端账本只记"已签发计划"(PLANNED/DISPATCHED)与"云端执行动作"(未来 MCP 写工具,现为空);手机动作的执行账本在 Android 本地 SQLite(客户端执行入口校验+记账) |
| 会话层采用 / 执行端允许 / 显示独立 | 采用 = 客户端会话层(D05b 已落地 navigate/choose 的采用);允许执行 = 执行端入口校验(actionId 已签发、参数/状态校验);显示确认是独立的 UI 层协议(不在本包实现) |
| 未知不重试、真实车控暂不开放 | 执行结果未知 → 状态 RESULT_UNKNOWN,不自动重试;真实车控执行入口保持关闭 |

## D07a(服务端):计划签发与业务账本

1. **ActionPlan 契约**(contracts):`ActionPlan(actionId, sessionId, utteranceId, domain, intent, summary, state, createdAtMs)` + `ActionLedger` 端口
   (`recordDispatch` / `findByActionId` / `markResult` / `close`)。
2. **签发点**:gateway 输出准入通过后、sendReply(action 类回复)前:
   - 生成 UUID `actionId`,注入回复(intent slots 增加 `actionId`?或 reply payload 顶层字段——**协议变更点,倾向 reply.payload 增可选 `actionId`,Android `ActionReply` 增字段**)
   - `ledger.recordDispatch(plan)`(状态 DISPATCHED;落败/旧轮到不了签发点 → 无 actionId → 客户端执行入口可拒绝)
3. **幂等语义**:账本以 actionId 为主键;重复签发(缓存重放同一轮)复用同一 actionId(completedTurns 缓存已保证同轮单次下发,重放走缓存 → 同一 actionId)。
4. **审计查询**(可选,最小):`findByActionId` 供执行回报对账;不对外暴露 HTTP 接口(首期仅服务端内部使用)。

**验收映射**:旧轮/落败不执行 → 无 actionId 的客户端动作被客户端执行入口拒绝;
并发重复 → actionId 幂等账本去重;执行结果不伪报 → 客户端本地账本状态机。

## D07b(Android):客户端执行入口与本地账本

1. **本地业务库**:app 内 SQLite(独立文件,如 `action-ledger.db`),`ActionLedgerStore`
   (actionId 主键 + 状态机:PLANNED→EXECUTING→SUCCESS/FAILED/RESULT_UNKNOWN)。
2. **执行入口**(`ActionExecutionGateway`):导航/模拟车控执行前:
   - actionId 非空且本地账本无记录 → 记账(EXECUTING)→ 执行 → 更新终态;
   - 已有记录 → 幂等返回既有结果(不重复执行);
   - 结果未知(无法确认下游) → RESULT_UNKNOWN,不重试。
3. **接入点**:`ResponseDispatcher.applyAndNotify` 的导航/车控分支改走执行入口
   (端侧胜出的本地动作同样走该入口,由本地链生成 actionId 或按"无云端签发"语义处理)。
4. 真实车控保持关闭:执行入口对真实车控域返回拒绝。

## 顺序与 PR 拆分

- D07a:contracts(ActionPlan/ActionLedger 端口)+ action-ledger 模块(独立 SQLite)
  + gateway 签发与注入 + 协议字段 + 测试
- D07b:Android 本地账本 + 执行入口 + 导航/模拟动作接入 + 测试

## 待确认点

1. reply.payload 增 `actionId` 字段(而不是塞进 intent slots)——倾向 payload 顶层,确认?
2. 服务端账本是否需要对外查询接口(客户端回报对账用),还是首期仅内部审计——倾向首期不对外。
