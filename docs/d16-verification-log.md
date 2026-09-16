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
| 0.5 metadata 记录后端变体 | 2026-09-12T16:02Z | ✅ **已修复并复验**:PR #103 合并 main 后,dev 部署 `releases/cde3a0a3…/metadata.txt` 显示 `voice_backend=omni`(此前 `unknown`) | ✅ |
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

**发现 3 处置(2026-09-12 完成)**:单独提 PR #103(基于 main、只含两处 `VOICE_BACKEND` 透传、
零夹带)→ CI 全绿 → 合并 main(`21d84802`)。合并流程按用户给定的安全顺序执行:

1. 记录 `AUTO_DEPLOY_PRODUCTION` 原值(`true`)并临时置 `false`;
2. 合并 #103;
3. **等 main CI 完成并验证生产部署明确 skipped**:run `34703792961` → `completed skipped`,
   job `Build and deploy` → `skipped`(0/0 步骤执行);
4. dev 复验:手动触发 dev 部署(`34703958696`, success)→ `metadata.txt` 记
   `voice_backend=omni`;`/health/ready` 全组件 READY;
5. 确认 pending deploy=0 且 pending main=0 后恢复 `AUTO_DEPLOY_PRODUCTION=true`(16:06:38Z)。

> 关键点:**不能合并后立即恢复开关**——未完成的 main CI 仍可能触发生产部署;
> 必须先确认对应运行已 skipped 且无待运行任务。

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

### A2 排空 **空载通过 2026-09-12T15:21:54Z —— 在途请求与接入拒绝验证待补**

授权范围:允许排空 8090 的 dev 网关并重启 `autovoice-dev-gateway` 恢复;**禁止操作生产服务**;
无论断言成败都必须尝试恢复并核验 dev 就绪与生产健康。

**本次已覆盖(空载场景)**:

| 步骤 | 实测 |
| --- | --- |
| 前置(只读) | dev `live=200` / `ready=200`;生产 gateway/tts/skill-manager 均 `active` |
| 进入排空 | `POST /health/drain` → `{"draining":true,"inFlight":0}` |
| 排空后 `live` | **200**(存活不受排空影响,与就绪正确分离) |
| 排空后 `ready` | **503**,正文 `{"status":"DOWN","draining":true,"inFlight":0,"components":"config=READY, gateway=READY, tts=READY, skill-registry=READY, offline-engine=READY"}`(排空时仍保留组件状态供排障) |
| 恢复 | `systemctl restart autovoice-dev-gateway` → `rc=0` |
| **恢复耗时** | **6 秒**(restart → `ready=200`) |
| dev 最终态 | `ready` 200、`draining=false`、components 全 READY、服务 `active`、8090 监听中 |

**本次未覆盖(必须补测,不得据此宣称通过)**:

1. **在途请求能否正常结束**:本次全程 `inFlight=0`(空载),未制造在途轮次,因此
   **未证明**排空时正在处理的请求能正常收尾、也不了解排空等待与超期兜底的实际表现;
2. **排空后新连接是否被拒绝**:未实测(预期由 `closePolicy("server draining")` 拒绝),
   接入侧尚无证据;
3. **生产的业务链路**:本次对生产只做了 `systemctl is-active` 与端口监听核验——这只证明
   **进程层正常**,**不等价于业务链路完整验证**(未经过握手/识别/仲裁/播报任何一环)。

**判定:空载通过;在途与接入验证待补。** 就绪语义方向正确(排空 → 不就绪但存活),
排空状态在内存、重启即清除(与 `docs/recovery-objectives.md` 的"重启丢失内存态"一致)。

> 耗时口径:**6 秒是本次单次 restart→`ready=200` 的实测值**,不含故障发现、依赖就绪、
> 客户端重连与业务恢复;不得作为对外 RTO 承诺(完整数值待 A5 场景实测)。

### A4 回滚演练 **[待批准]**

### A5 故障场景矩阵 **[待批准]**

> 用户指示:回滚、杀进程、主机重启**另行批准**;**dev 与生产同机**,不当作只影响 dev。

### A6 离线 SDK 演练 / 多进程实测 **[待批准]**

## 记录汇总

| 项 | 日期 | 实测值/结果 | 判定 |
| --- | --- | --- | --- |
| 0.3 健康端点 | 2026-09-12 | dev live/ready 均 200 | ✅ |
| 0.4 部署元数据 | 2026-09-12 | metadata + checksums 存在;摘要与运行 jar 一致 | ✅ |
| 0.5 后端变体记录 | 2026-09-12T16:02Z | dev metadata 显示 `voice_backend=omni`(已修复) | ✅ |
| 0.6 自动部署链路 | 2026-09-12 | workflow_run success ×3;SHA 与摘要均一致 | ✅ |
| A1 探测语义 | 2026-09-12 | 发现 components 为空 → 已修(PR #100/#101)并复验全 READY | ✅ |
| A2 排空(空载) | 2026-09-12T15:21:54Z | ready 503 / live 200;**本次单次 restart→ready 6 秒** | ⚠️ **空载通过,在途与接入验证待补** |
| A2 待补:在途请求结束 | — | 需制造 `inFlight>0` 场景 | ⏳ |
| A2 待补:排空后新连接被拒 | — | 需发起新 WS 连接尝试 | ⏳ |
| 生产业务链路 | — | 本轮仅核验进程层(systemd active + 端口监听),**未验证业务链路** | ⏳ |
| A3 部署判据 | 2026-09-12 | 日志证实 gateway 用 `/health/ready -> 200` | ✅ |
| A4 回滚演练 | — | 待批准 | ⏳ |
| A5 进程重启 / kill -9 / 主机重启 / 账本可读性 / 断网重连 | — | 待批准 | ⏳ |
| A6 离线演练 / 多进程实测 | — | 待批准 | ⏳ |
| B 真机矩阵 | — | 待安排 | ⏳ |
| C 容量与费用 | — | 待提供参数 | ⏳ |

## B. 真机侧 **[待验收]**（保持未标记,待你安排）

## C. 容量与费用 **[待验收]**（需先确认参数）
