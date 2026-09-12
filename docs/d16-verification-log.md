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

### A2 排空 **[待批准]**（会停止 dev 接入）

### A4 回滚演练 **[待批准]**

### A5 故障场景矩阵 **[待批准]**

> 用户指示:回滚、杀进程、主机重启**另行批准**;**dev 与生产同机**,不当作只影响 dev。

### A6 离线 SDK 演练 / 多进程实测 **[待批准]**

## B. 真机侧 **[待验收]**（保持未标记,待你安排）

## C. 容量与费用 **[待验收]**（需先确认参数）
