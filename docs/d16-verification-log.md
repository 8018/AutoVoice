# D16 验收记录(实测登记)

执行清单见 `docs/d16-execution-runbook.md`。**本文件只记录实测结果与发现,不记录预计值。**
未执行的项保持空白或标注"待验收"。

## 前置:部署链路

| 项 | 日期 | 实测结果 | 判定 |
| --- | --- | --- | --- |
| 0.1 `AUTO_DEPLOY_DEV` 层级 | 2026-09-12 | 曾误设 environment 级 → 所有 dev 自动部署 **skipped**;已改仓库级 | ✅ 已修(待自动路径验证) |
| 0.2 dev 栈部署最新代码 | 2026-09-12 | 手动触发成功;jar 时间 9/12 21:16 | ✅ 手动路径 |
| 0.3 健康端点 | 2026-09-12 | dev 栈 `live=200` / `ready=200`(此前 404 = 旧 jar) | ✅ |
| 0.4 部署元数据与摘要 | 2026-09-12 | `metadata.txt` + `checksums.sha256` 存在;运行中 `app.jar` 摘要 `fbd94a92…` 与记录**一致** | ✅ |
| 0.5 metadata 记录后端变体 | 2026-09-12 | 仍记 `unknown`——见**发现 3**:workflow 修复需合并 main 才生效 | ⏳ 修复已提交 dev,待发布 |
| 0.6 **自动部署证据链** | 2026-09-12 | ✅ **已验证**:PR #98 合并 → dev CI 成功 → `workflow_run` 自动部署 **success**(此前所有自动运行均为 `skipped`)→ 服务器 `release_sha=55ecbb5f…` 与 `metadata.txt` 一致 → 运行中 `app.jar` 摘要 `b8796e97…` 与 `checksums.sha256` **一致** | ✅ |

> 手动触发成功**只**验证了手动路径;0.6 已补齐自动路径的端到端证据。

### 发现 3(运维约束,重要)

**`workflow_run` 触发的 workflow 使用默认分支(main)上的 workflow 定义。**

实测:run `34696636545` 的 `head_branch=main`;main 上的 `deploy-dev.yml` 尚无本次 `VOICE_BACKEND`
透传修复,因此 metadata 仍记 `unknown`。

后果与要求:

- **workflow 逻辑修复必须经 train 合并 main 才对自动部署生效**;只在 dev 上改 workflow
  无法验证自动路径;
- 变量层级修复(`AUTO_DEPLOY_DEV` 移到仓库级)属 GitHub 配置而非 workflow 文件,**立即生效**
  ——这正是 0.6 能通过的原因;
- 用户指示:`voice_backend` 修复**保留待发布**,不为让工作流生效而把未验收的 dev 提前合进 main;
  必要时单独提工作流修复 PR。

## A. 服务器侧

### A1 健康探测与就绪语义(只读)**2026-09-12**

| 端点 | dev 栈(8090) | 生产栈(8080) |
| --- | --- | --- |
| `/health/live` | `200` | `404`(生产仍为旧 jar,符合预期——新代码需 train 合并 main) |
| `/health/ready` | `200` `{"status":"UP","draining":false,"inFlight":0,"components":""}` | `404` |

**发现(缺陷,已修复并复验)**:`components` 为空——`ServiceReadiness` 已实现但**无组件登记**,
就绪判定恒为 UP,违背 D12 意图("坏配置/坏依赖不能误报部署成功")。

**修复(PR #100)**:`isReady()` 改为三条件——未排空 + **已声明至少一个必需组件** + 全部必需组件
READY;未声明任何必需组件视为接线缺陷 → 返回 false(fail-closed)。`AppConfig.readinessInitializer`
在启动完成后声明必需组件 `config`/`gateway` 并置 READY,登记可降级组件
`skill-registry`/`tts`/`offline-engine`。

**复验(2026-09-12 22:4x,部署 `a41ac8f8`,即 dev HEAD)**:

```
ready: {"status":"UP","draining":false,"inFlight":0,
        "components":"config=READY, gateway=READY, tts=PENDING, skill-registry=PENDING, offline-engine=PENDING"}
```

- ✅ 必需组件 `config`/`gateway` 明确 READY,`components` 不再为空;
- 观察:可降级组件显示 `PENDING` 语义含糊(易被误读为"未接线")→ 已追加修正为
  "装配完成即 READY"(可降级失败仍不阻断整体就绪)。

**结论:发现已在本阶段关闭**;按用户顺序,下一步可安排 A2(排空)验收。

### A3 部署判据(只读)**2026-09-12**

来自手动部署日志(run 34695911493):

```
autovoice-dev-skill-manager is ready on port 8093.        ← 端口判据(无 health 端点,符合设计)
autovoice-dev-tts is ready on port 8092.                  ← 端口判据
autovoice-dev-gateway is ready (/health/ready -> 200, port 8090).  ← health 判据生效 ✅
```

**判定**:D12b 的就绪判据升级已生效;无该端点的服务正确回退到端口判据。

### A2 排空 **✅ 通过 2026-09-12T15:21:54Z**

授权范围:允许排空 8090 的 dev 网关并重启 `autovoice-dev-gateway` 恢复;**禁止操作生产服务**;
无论断言成败都必须尝试恢复并核验 dev 就绪与生产健康。

| 步骤 | 实测 |
| --- | --- |
| 前置(只读) | dev `live=200` / `ready=200`;生产 gateway/tts/skill-manager 均 `active` |
| 进入排空 | `POST /health/drain` → `{"draining":true,"inFlight":0}` |
| 排空后 `live` | **200**(存活不受排空影响,与就绪正确分离) |
| 排空后 `ready` | **503**,正文 `{"status":"DOWN","draining":true,"inFlight":0,"components":"config=READY, gateway=READY, tts=READY, skill-registry=READY, offline-engine=READY"}`(排空时仍保留组件状态供排障) |
| 恢复 | `systemctl restart autovoice-dev-gateway` → `rc=0` |
| **恢复耗时** | **6 秒**(restart → `ready=200`) |
| dev 最终态 | `ready` 200、`draining=false`、components 全 READY、服务 `active`、8090 监听中 |
| **生产核验(只读)** | gateway/tts/skill-manager 均 `active`;8080/8082/8083 监听数 **3**;**未做任何操作** |

**判定**:通过。就绪语义正确(排空 → 不就绪但存活,符合负载均衡摘流量的预期);
排空状态在内存、重启即清除(与 `docs/recovery-objectives.md` 的"重启丢失内存态"一致)。

> 观察:恢复耗时 6 秒是"restart → ready 200";完整 RTO 还需加故障发现与客户端重连
> (见 A5 场景实测),不得以 6 秒作为对外 RTO 承诺。

### A4 回滚演练 **[待批准]**

### A5 故障场景矩阵 **[待批准]**

> 用户指示:回滚、杀进程、主机重启**另行批准**;**dev 与生产同机**,不当作只影响 dev。

### A6 离线 SDK 演练 / 多进程实测 **[待批准]**

## 记录汇总

| 项 | 日期 | 实测值/结果 | 判定 |
| --- | --- | --- | --- |
| 0.3 健康端点 | 2026-09-12 | dev live/ready 均 200 | ✅ |
| 0.4 部署元数据 | 2026-09-12 | metadata + checksums 存在;摘要与运行 jar 一致 | ✅ |
| 0.5 后端变体记录 | — | 仍为 unknown(workflow 修复待发布) | ⏳ |
| 0.6 自动部署链路 | 2026-09-12 | workflow_run success ×3;SHA 与摘要均一致 | ✅ |
| A1 探测语义 | 2026-09-12 | 发现 components 为空 → 已修(PR #100/#101)并复验全 READY | ✅ |
| A2 排空 | 2026-09-12T15:21:54Z | ready 503 / live 200;**恢复 6 秒**;生产未受影响 | ✅ |
| A3 部署判据 | 2026-09-12 | 日志证实 gateway 用 `/health/ready -> 200` | ✅ |
| A4 回滚演练 | — | 待批准 | ⏳ |
| A5 进程重启 / kill -9 / 主机重启 / 账本可读性 / 断网重连 | — | 待批准 | ⏳ |
| A6 离线演练 / 多进程实测 | — | 待批准 | ⏳ |
| B 真机矩阵 | — | 待安排 | ⏳ |
| C 容量与费用 | — | 待提供参数 | ⏳ |

## B. 真机侧 **[待验收]**（保持未标记,待你安排）

## C. 容量与费用 **[待验收]**（需先确认参数）
