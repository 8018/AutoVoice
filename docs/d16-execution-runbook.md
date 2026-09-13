# D16 执行清单(可操作版,2026-09-12)

配套文档:`docs/d16-acceptance-plan.md`(验收要点对照)、`docs/recovery-objectives.md`(故障场景矩阵)。
本文件是**逐条可执行的步骤**,每条含:命令、期望结果、记录字段、判定。

> 标注说明:**[可自动]** = 我(助手)可执行;**[需批准]** = 破坏性/影响服务,需你点头;
> **[需你]** = 只能由你完成(真机、额度、业务参数)。

---

## 0. 前置:部署链路(本轮已发现的问题)

| # | 项 | 命令 | 期望 | 状态 |
| --- | --- | --- | --- | --- |
| 0.1 | `AUTO_DEPLOY_DEV` 必须是**仓库级**变量 | `gh variable list` | 与 `AUTO_DEPLOY_PRODUCTION` 同级 | ✅ 已修(此前误设 environment 级 → 所有 dev 自动部署静默 skipped) |
| 0.2 | dev 栈部署最新代码 | `gh workflow run deploy-dev.yml --ref dev -f voice_backend=omni` | workflow 真实执行(非 skipped) | ✅ 已触发 |
| 0.3 | 健康端点可用 | `curl -s -o /dev/null -w '%{http_code}' http://<host>:<port>/health/live` | `200`(此前 404 = 部署未生效) | ✅ **实测 2026-09-12**:dev 栈 live=200 / ready=200 |
| 0.4 | 部署元数据与摘要 | `cat /opt/autovoice-dev/releases/<sha>/metadata.txt`、`checksums.sha256` | 存在且与 CI 摘要一致 | ✅ **实测 2026-09-12**:metadata 与 checksums 存在;运行中 app.jar 摘要 `fbd94a92…` 与记录**一致**(产物可追溯) |
| 0.5 | metadata 记录实际后端变体 | 同上 metadata 的 `voice_backend` | 应为实际变体(omni/classic) | ✅ 已修(此前远端 shell 无该变量而记为 `unknown`;workflow 现已透传) |

**教训(建议同步给运维)**:workflow 条件里的 `vars.X` 读仓库级变量;job 的 `environment` 变量在
`if:` 求值时尚不可见——错放层级时运行会显示 `skipped`(不报错,极易漏看)。

---

## A. 服务器侧验收(阿里云 47.94.4.204)

### A1. 健康探测与就绪语义 **[可自动]**

```bash
# 存活/就绪(dev 栈 8090,生产 8080)
curl -s -o /dev/null -w 'live=%{http_code}\n' http://127.0.0.1:8090/health/live
curl -s             http://127.0.0.1:8090/health/ready
```
期望:`live=200`;`ready` 返回 `{"status":"UP","draining":false,"inFlight":N,"components":"..."}`。
**记录**:两个端点的状态码与 ready 正文(首次有 components 数据)。

### A2. 排空行为 **[需批准]**(会短暂停止 dev 接入)

```bash
curl -s -X POST http://127.0.0.1:8090/health/drain    # 进入排空
curl -s http://127.0.0.1:8090/health/ready            # 期望 503 + draining:true
systemctl restart autovoice-dev-gateway               # 恢复
```
**记录**:排空后 ready 是否 503;重启后 ready 恢复时间(秒)。

### A3. 部署判据实测 **[需批准]**

用 CI 产物正常发布一次,观察 `deploy-release.sh` 是否用 `/health/ready` 判据:
```bash
journalctl -u autovoice-dev-gateway -n 50 --no-pager | grep -i "ready\|draining"
```
**记录**:日志中出现的就绪判定方式(health 判据 vs 端口回退)。

### A4. 回滚演练 **[需批准,高风险]**

```bash
# 用一次正常发布作为基线,然后手工把坏 jar 放到 staging 并触发部署,
# 或在服务器上模拟:把 app.jar 换成不可启动的版本后走 deploy-release.sh
```
**记录**:回滚是否自动触发、回滚耗时、回滚后业务是否可用(ready=200)、数据是否有损。
**判定**:坏产物必须被判失败并且业务恢复——这是 D13 的核心验收。

### A5. 故障场景矩阵 **[需批准,逐场景]**（详见 `docs/recovery-objectives.md`）

| 场景 | 命令 | 记录字段 |
| --- | --- | --- |
| 进程重启 | `systemctl restart autovoice-dev-gateway` | 从发起到 `ready=200` 的实际秒数;客户端重连得到的 `sessionState` |
| 进程 kill -9 | `kill -9 $(systemctl show -p MainPID --value autovoice-dev-gateway)` | 崩溃后是否自动重启;恢复秒数;日志有无半写记录 |
| 主机重启 | `reboot`(需你确认时机) | 服务自启顺序;依赖就绪;数据完整性 |
| 断线不补执行 | 发送一轮后切断连接再恢复 | 原轮明确失败且不重传音频、不重放回复、不补执行；下一轮可重新建连 |
| 断网重连 | 手机切飞行模式再恢复 | 重连耗时；只恢复会话上下文，不恢复未完成动作 |

**每项必须填写实测数值**;未能执行的标记"未验收"。

### A6. 离线 SDK 演练(D09b)**[需批准,可能影响 dev 离线链路]**

```bash
# 前提:dev 栈 .env 开启 AUTOVOICE_OFFLINE_ENABLED=true
ssh <host> 'pkill -9 -f autovoice_offline_esr'   # 模拟引擎进程被杀(若独立进程)
# 当前实现是 JVM 内 JNI,进程被杀即 JVM 崩溃 → 观察 systemd 重启行为
```
**记录**:网关是否存活(或按 systemd 重启);恢复时间;是否出现重启风暴;
`docs/d09-vendor-constraint-review.md` 的"多进程实测"是否可做(需 2 个 worker 各 init SDK)。

---

## B. 真机侧验收 **[需你]**

前置:安装 debug APK(构建时固定连接 dev 8090),设备与账号就绪；手机端无需选择环境。

| # | 场景 | 期望 | 记录 |
| --- | --- | --- | --- |
| B1 | 导航二轮选择(说序号 / 说地址名) | 二轮不出模型、选中项与屏幕一致 | 通过/失败 + 耗时 |
| B2 | 后台返回唤醒 | 返回后唤醒可识别 | 同上 |
| B3 | 播报打断(普通话术) | 播报停止且新轮成立 | 同上 |
| B4 | VAD 误报 | 噪声不建立新轮 | 同上 |
| B5 | 多语种 | 识别语言符合预期 | 同上 |
| B6 | 蓝牙音频焦点 | 切换设备后音频正常 | 同上 |
| B7 | 持续闲聊与退出 | 闲聊锁域、退出恢复业务域 | 同上 |
| B8 | 网络切换(WiFi↔4G) | 重连成功 | 重连耗时 |
| B9 | **重连恢复语义(本轮新增)** | 会话有效 → `resumed` **保留候选列表**;会话重建 → `reset` 并提示重新搜索 | 两种情况的界面表现 |
| B10 | **超窗动作**(可选) | 窗口外动作被明确拒绝 | 无重复执行 |

---

## C. 容量与费用 **[需你先给参数]**

执行前必须确认(计划文档要求,未确认不承诺任何数字):

- 注册设备数、峰值连接数、同时说话比例、音频长度分布、闲聊持续时长
- 允许的首字/首音频/动作延迟目标
- 每设备费用上限(付费调用额度)

确认后我再准备压测脚本与观测口径(p95/p99、错误率、队列深度、资源趋势、费用)。

---

## D. 执行顺序建议

1. **0.3 / 0.4** 验证最新代码已上 dev(否则后续验收都基于旧代码)
2. **A1 / A2 / A3** 健康与排空(低风险,可连续做)
3. **A5 进程重启 + 账本可读性**(低风险,给 `docs/recovery-objectives.md` 填第一批实测值)
4. **A4 回滚演练**(需你批准,建议在低峰时段)
5. **A6 / 多进程实测**(离线链路,需确认 dev 是否开启离线)
6. **B 真机矩阵**(你安排)
7. **C 容量与费用**(拿到参数后)

---

## E. 记录模板

每项验收完成后在此登记(见下),便于 D16 收尾时汇总:

| 项 | 日期 | 执行人 | 实测值/结果 | 判定 |
| --- | --- | --- | --- | --- |
| 0.3 健康端点 | | | | |
| 0.4 部署元数据 | | | | |
| A1 探测语义 | | | | |
| A2 排空 | | | | |
| A3 部署判据 | | | | |
| A4 回滚演练 | | | | |
| A5 进程重启 | | | | |
| A5 kill -9 | | | | |
| A5 主机重启 | | | | |
| A5 账本可读性 | | | | |
| A5 断网重连 | | | | |
| A6 离线演练 | | | | |
| A6 多进程实测 | | | | |
| B1–B10 真机 | | | | |
| C 容量/费用 | | | | |
