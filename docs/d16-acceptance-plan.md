# D16 验收执行清单(2026-09-12)

依据计划文档 D16。**分两部分**:本地可自动化的验收(已尽量完成)与需要环境/额度/真机的部分
(需你安排)。**未完成的项不得标记为已验收。**

## 一、故障场景覆盖盘点(对照 D02–D15 验收标准)

| 工作包 | 验收要点 | 覆盖状态 |
| --- | --- | --- |
| D01 | Classic/Omni 配置校验有测试;每条架构约束有回归归属 | ✅ `ProductionConfigGuardTest` + `docs/production-regression-scenarios.md` |
| D02 | A 不能恢复 B;坏/过期凭据拒绝;重连可恢复有效候选;日志不含凭据 | ✅ `SessionRecoveryTest` + handler 三态测试;**日志不含凭据**由 `resumeToken` 不出现在任何日志语句保证(代码审查项,无自动化断言) |
| D03 | 上传凭据不能查询;注销 cookie 不能重放;跨站写入拒绝;Web 可用 | ✅ `TelemetryAuthInterceptorTest`(读写分权)+ `AdminAuthTest`(注销重放/SameSite/限流)。**跨站写入拒绝**由 `SameSite=Strict` 属性断言体现,浏览器行为需真机/浏览器验证 |
| D04 | 伪装只读/缺分类不能执行写入;工具域隔离不回退 | ✅ `RequestToolExecutorTest` + `McpToolSession` traits 测试 |
| D05 | 晚到不覆盖;端侧胜出不激活云端候选;重复确认幂等;过期/重连一致 | ✅ `NavigationDialogServiceTest` + `NavigationAdoptionTest` + handler 准入提交测试 |
| D06 | 排队关闭、回调抛错、重复关闭、跨设备同名轮次确定性完成 | ✅ `AgentExecutionRuntimeTest` + `DeepSeekCloseTest` + `TurnIndexTest` |
| D07 | 落败不执行;并发/重试不盲目重复;越权拒绝;结果不伪报 | ✅ `ActionExecutionGatewayTest` + `SqliteActionLedgerTest` + handler 签发测试 |
| D08 | 永久阻塞不造成堆积;健康候选正常完成 | ✅ `EngineHealthTest` + `OfflineEnginePoolHealthTest` + provider 有界队列 |
| D09 | 杀/卡死 worker 不终止网关 | ⏳ **待环境**(本地已用假 worker 验证监督状态机;真实 SDK 演练未做) |
| D10 | 慢读/无 hello/长文本/突发音频下资源有上界 | ✅ `DownlinkBudgetTest` + `downlinkBudgetExhaustionReportsExplicitError`(本轮补)+ `helloDeadlineEnforcement…`(确定性)+ TTS 上限 |
| D11 | 更新不破坏旧请求;新请求用新版本;无效版本不覆盖 | ✅ `RegistrySnapshotTest` + `leasedSnapshotDefersRetirementUntilRelease` |
| D12 | 坏配置/坏依赖不误报成功;回滚后业务探测通过 | ✅ 部分(`ServiceReadinessTest` + ready 端点);⏳ **回滚演练待环境** |
| D13 | 产物可追溯、摘要可比对 | ⏳ **待环境**(脚本与 CI 已就绪) |
| D14 | 满盘/队列满可控;删除与备份恢复可验证 | ✅ 容量上限与丢弃行为有测试;⏳ 备份恢复待环境 |
| D15 | 断网/重启/候选过期/结果未知均有端到端结果 | ✅ 语义与解析有测试;⏳ **实际恢复数值待环境实测** |

**本轮补上的缺口**:`DOWNLINK_OVERLOADED`(下行预算耗尽)此前只有组件级测试、缺少
端到端行为断言;现已补测(预算改为可配置,便于测试与按容量调优)。

## 二、待环境验收清单(需你安排)

### A. 服务器侧(阿里云 47.94.4.204)

| 项 | 命令/方式 | 记录什么 |
| --- | --- | --- |
| 加固模板启用 | 按 `autovoice-gateway-hardened.service` 头部步骤执行 | 服务以 autovoice 用户运行;`/health/ready` 200 |
| ready 判据实测 | 停掉一个可降级依赖,观察部署脚本与探测行为 | 是否 503;是否误摘全部业务 |
| **回滚演练** | 发布一个坏 jar → 观察自动回滚 → 验证业务探测 | 实际回滚耗时;数据是否丢失 |
| 故障场景矩阵 | 按 `docs/recovery-objectives.md` 六类场景逐一执行 | **实际恢复耗时、数据损失范围**(不使用估算) |
| 离线 SDK 演练(D09b) | kill -9 引擎进程 / 制造卡死 | 网关是否存活;恢复时间;是否有重启风暴 |
| 多进程实测(D09b 遗留) | 启动 2 个 worker 各自 init SDK | 是否被许可证拒绝(厂商文档未声明) |

### B. 真机侧(Android)

按计划文档 D16 的真机矩阵:导航二轮(序号/地址名)、后台返回唤醒、播报打断、
VAD 误报、多语种、蓝牙音频焦点、持续闲聊与退出、网络切换。
另需验证本轮新增:**重连时 `resumed` 保留候选列表、`reset` 清理并要求重新搜索**。

### C. 容量与费用(执行前需确认参数)

计划文档要求先确认:注册设备数、峰值连接数、同时说话比例、音频长度分布、
闲聊持续时长、允许的首字/首音频/动作延迟、每设备费用上限。
**未确认前不承诺任何容量或 SLO 数字。**

## 三、D16 完成定义

- 上述 A/B 均有**实测记录**(机型、时间、数值、原始日志),而非"预计";
- 未能执行的项明确标记"未验收",不以 Stub/本地结果替代;
- 记录 p95/p99、错误率、队列深度、资源趋势、恢复耗时与费用;
- 全部通过后才关闭 R11 与相关风险。
