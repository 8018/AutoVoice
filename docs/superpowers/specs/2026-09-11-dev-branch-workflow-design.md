# Dev 分支工作流与 dev 云环境设计(2026-09-11)

> 状态:已批准(用户逐节确认)。将单 main 分支开发改为:feature → PR → dev(自动部署 dev 环境验证)→ 发布 train PR → main(自动部署生产)。

## 一、分支结构与工作流

```
codex/feature-* ──PR──▶ dev ──(发布 train PR)──▶ main ──自动部署──▶ 生产
                          ▲                            │
                          └─── hotfix 合入 main 后同步 ──┘
```

1. `dev` 从当前 main 切出。
2. 保护规则:`main` 维持现状;`dev` 套用相同 6 个 CI 状态检查 + 要求 PR 合并。
3. 日常开发:feature(`codex/*`)→ PR 目标 `dev` → CI 绿 → 合并 → 自动部署 dev 环境。
4. 发布 train:dev 验证稳定后 `dev` → `main` 一个 PR,合并后走现有生产部署。
5. hotfix:紧急修复直接 PR 到 `main`,合并后以"main → dev 同步"PR 带回 dev。

## 二、CI 变更

`ci.yml` 的 `push.branches` 从 `[main]` 改为 `[main, dev]`,其余不变。

## 三、服务器侧 dev 栈(同机、全隔离)

| 项 | 生产 | dev |
|---|---|---|
| 根目录 | `/opt/autovoice/` | `/opt/autovoice-dev/` |
| systemd 服务 | autovoice-gateway / tts / skill-manager | autovoice-dev-gateway / tts / skill-manager |
| 端口 | 8080 / 8082 / 8083 | 8090 / 8092 / 8093 |
| .env | `/etc/autovoice/.env` | `/etc/autovoice-dev/.env` |
| 状态(遥测 SQLite / TTS 缓存 / skill DB / 离线 work) | 生产路径 | 全部 dev 目录独立 |

- 离线 SDK 只读资源(libs/resource/cn_fsa.txt/.so)共享,**work 目录独立**。
- `application-demo-full.yml` 的离线 SDK 四条路径由硬编码改为环境变量(默认值不变,生产零影响)。
- 初始化:仓库 `deploy/init-dev.sh`(幂等)在服务器跑一次:建目录、装 unit、提示填 env。
- 内存:dev 多 3 个 JVM;必要时在 dev unit 中加 `-Xmx` 上限。

## 四、自动部署

- `deploy-production.sh` 参数化为 `deploy-release.sh <prod|dev>`(服务名/端口/路径按环境切换,备份回滚逻辑共用);`deploy.yml` 改调 `deploy-release.sh prod`,行为不变。
- 新增 `deploy-dev.yml`:CI 成功 + `head_branch == 'dev'` + 变量 `AUTO_DEPLOY_DEV == 'true'` 时自动部署;`environment: dev`,secret `DEV_SSH_PRIVATE_KEY`/`DEV_SSH_KNOWN_HOSTS`;手动触发要求从 dev 分支;staging/releases/backups 在 dev 目录。
- 语音后端:自动路径读仓库变量 `VOICE_BACKEND`(当前 omni,与生产一致);dev 手动触发输入默认 omni。

## 五、Android 连 dev

- 新增第三个 demo 模式 `DEMO_DEV("demo-dev")`:资产 `demo-dev.json`(内容同 demo-full,`gatewayUrl` 指向 `ws://47.94.4.204:8090/ws`),设置区出现第三个选项。

## 六、需要用户手动完成的步骤

1. 本机生成部署密钥并安装公钥到服务器(见 deploy/README.md dev 章节)。
2. 服务器上以 root 运行 `deploy/init-dev.sh`;填 `/etc/autovoice-dev/.env`(密钥从生产复制,改端口类变量)。
3. `gh secret set DEV_SSH_PRIVATE_KEY --env dev`、`gh secret set DEV_SSH_KNOWN_HOSTS --env dev`。
4. 手动触发一次 Deploy dev 验证三服务就绪 → `AUTO_DEPLOY_DEV` 置 true。
5. 安全组放行入站 8090(手机测试)。

## 七、验收标准

- dev 分支保护生效;feature PR → dev 全流程 CI 绿并合并。
- 手动触发 Deploy dev:3 服务在 8090/8092/8093 就绪,生产服务不受影响。
- 手机切 demo-dev 模式,hello 握手成功。
- dev → main train PR 合并后,生产部署流程与之前完全一致。

## 八、风险与回退

- dev 部署失败只回滚 dev 栈,生产隔离。
- 服务器内存不足 → dev 服务加 -Xmx 或临时停 dev 栈(生产不受影响)。
- 如 dev 工作流不合用,删除 dev 分支/环境/服务即可回到单 main 流程,代码零改动依赖。
