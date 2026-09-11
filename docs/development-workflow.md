# 分支与发布流程(dev 工作流)

更新日期:2026-09-11

## 流程总览

```
codex/feature-* ──PR──▶ dev ──(发布 train PR)──▶ main ──自动部署──▶ 生产(8080)
                          │
                          └── 合并后自动部署 dev 栈(8090/8092/8093,需 AUTO_DEPLOY_DEV=true)
```

1. **日常开发**:feature 分支(`codex/*`)→ PR 目标 `dev` → CI 全绿 → 合并。
   合并后 dev 栈自动部署,手机 app 切 `demo-dev` 模式即可联调。
2. **发布**:一批功能在 dev 验证稳定后,从 `dev` 提一个 PR 到 `main`(发布 train)。
   合并后走既有生产自动部署,流程不变。
3. **hotfix**:紧急修复直接 `codex/fix-*` → PR 到 `main`;合并后立即以
   "main → dev 同步" PR 带回 dev,避免漂移。

## 分支保护

- `main` / `dev` 均要求 PR 合并,且必须通过全部 6 个 CI 检查。
- 不允许直接 push 到 `main` 或 `dev`。

## dev 栈

同一台云服务器(47.94.4.204)上的独立部署栈,与生产完全隔离:

| | 生产 | dev |
|---|---|---|
| 端口 | 8080 / 8082 / 8083 | 8090 / 8092 / 8093 |
| systemd | autovoice-gateway / tts / skill-manager | autovoice-dev-* |
| 目录 | /opt/autovoice/ | /opt/autovoice-dev/ |
| .env | /etc/autovoice/.env | /etc/autovoice-dev/.env |
| 状态(遥测/缓存/Skill DB/离线 work) | 独立 | 独立 |

部署细节(初始化、密钥配置、运维命令)见 `AutoVoiceServer/deploy/README.md`。

## CI 与部署触发

- CI 在 `push: main/dev` 和所有 PR 上运行,检查内容相同。
- 生产:CI(main)成功 + `AUTO_DEPLOY_PRODUCTION=true` → 自动部署。
- dev:CI(dev)成功 + `AUTO_DEPLOY_DEV=true` → 自动部署。
- 两者都可手动触发(Deploy production / Deploy dev),互不影响。

## Android 连 dev

设置区选择 `demo-dev` 模式(第三个选项),网关地址指向
`ws://47.94.4.204:8090/ws`(assets/demo-dev.json)。

dev 网关默认不启用鉴权(`AUTOVOICE_GATEWAY_AUTH_ENABLED` 默认 false),因此
demo-dev.json 的 `authToken` 为空;若 dev 栈开启鉴权做多设备测试,本机改该
字段即可(勿提交真实 token)。
