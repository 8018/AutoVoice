# 导航质量面板验证（I06）

Android 在导航操作开始时捕获设备及已知网关会话。终态通过现有有界上传器单独提交至 `POST /api/telemetry/round`，记录类型为 `navigation`，与语音轮次分开。操作 ID 随机生成，事件只包含操作、结果和单调时钟计算的耗时。开始时没有已知会话的操作保留空会话，设备可用时仍参与设备统计。客户端替换后，旧回调不会使用新客户端的会话。上传失败、队列丢弃及客户端提前关闭均可能造成样本缺失。

统计由现有 SQLite 存储提供。管理查询 `GET /api/telemetry/navigation/stats?from=…&to=…&device=…` 要求明确的毫秒时间范围，最长 7 天，结束时间两端均包含，设备筛选可选。新查询沿用现有遥测读权限。分离配置下需要管理令牌，浏览器沿用现有面板请求方式，配置令牌的环境需通过现有管理入口添加请求头。

按操作计入首次有效终态，重复上传无法改写已保存的设备或会话。匿名旧记录、未知操作或结果、非整数耗时和超过 24 小时的耗时均不计入。成功率分母是该操作有效唯一终态样本数，成功率分子只包含 `succeeded`。`accepted` 单独计数，停止等受理耗时只量到本地提交。P50 和 P95 使用所有有效终态实际耗时，按最近秩计算。面板提供 1 小时、24 小时、7 天范围，支持刷新及共享设备筛选，并区分无数据与查询失败。独立导航记录不进入原语音统计分母。

## 离线证据

| 基准用例 | 验证行为 | 自动验证 |
| --- | --- | --- |
| I06-N01 | 不同操作分别汇总，成功、受理、失败与未知分列，成功率和分位数使用实际样本 | `NavigationStatisticsTest.countsValidUniqueTerminalSamplesByDeviceAndCompletionWindow` |
| I06-F01 | 上传令牌无管理权限，缺失或非法时间窗返回 400，面板显示鉴权失败并可刷新重试 | `NavigationStatisticsTest.requiresAdminAccessAndAnExplicitBoundedWindow`、`App.test.tsx` |
| I06-B01 | 重复操作及跨窗重传去重，变更设备的重传仍保留原归属，非法和匿名样本过滤，空数据明确显示 | `NavigationStatisticsTest`、`App.test.tsx` |
| I06-M01 | 开始后切换会话，晚到终态绑定原会话且与新语音轮次隔离，重复回调无第二条事件 | `TelemetryClientTest`、`NavigationOperationTrackerTest` |
| I06-M02 | 切换设备及时间范围，旧请求晚到无法覆盖当前结果 | `App.test.tsx` |

- 服务端 `:telemetry:test` 26 项通过。使用临时 SQLite 和管理路由验证，含真实 HTTP 路由鉴权和聚合结果。
- Android Open Debug 聚焦 `TelemetryClientTest` 与 `NavigationOperationTrackerTest`，16 项通过。
- 遥测 Web 9 项通过，lint、coverage 及生产构建通过。语句、分支、函数、行覆盖率分别为 94.57%、78.87%、91.30%、94.57%。

真实设备的成功率及延迟仍待实车终态样本。测试数据仅写入临时本地数据库。dev 部署后的完整云端文本基准验证导航对话回归，实车、语音及驾驶中的行为另需验收。
