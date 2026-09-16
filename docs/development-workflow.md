# 分支与发布流程(dev 工作流)

更新日期:2026-09-11

## 流程总览

```
codex/feature-* ──PR──▶ dev ──(发布 train PR)──▶ main ──自动部署──▶ 生产(8080)
                          │
                          └── 合并后自动部署 dev 栈(8090/8092/8093,需 AUTO_DEPLOY_DEV=true)
```

1. **日常开发**:feature 分支(`codex/*`)→ PR 目标 `dev` → CI 全绿 → 合并。
   合并后 dev 栈自动部署；安装 debug APK 即连接 dev，无需在手机上切换服务器。
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

dev 网关的 `SKILL_MANAGER_URL` 必须是 `http://127.0.0.1:8093`。初始化 dev
环境时只能从生产 `.env` 复制供应商密钥和令牌，不能整文件覆盖，否则会把内部路由带成
8083，形成 dev 静默读取生产 Skill/Prompt 的跨环境依赖。发布脚本会在重启前拒绝该配置。

部署细节(初始化、密钥配置、运维命令)见 `AutoVoiceServer/deploy/README.md`。

## CI 与部署触发

- CI 在 `push: main/dev` 和所有 PR 上运行,检查内容相同。
- 生产:CI(main)成功 + `AUTO_DEPLOY_PRODUCTION=true` → 自动部署。
- dev:CI(dev)成功 + `AUTO_DEPLOY_DEV=true` → 自动部署。
- 两者都可手动触发(Deploy production / Deploy dev),互不影响。

## Android 连 dev

构建期即绑定环境:本地 `debug` APK 默认加载 `demo-dev.json`,连接
`ws://47.94.4.204:8090/ws`;CI 对 dev 分支及目标为 dev 的 PR 同样固定为 dev，
main 验收构建固定为生产。`release` APK 无条件加载 `demo-full.json`,连接生产。
手机设置区只保留“在线 / 离线”功能模式,不提供 dev/生产服务器切换入口。

配置资产不保存接入令牌；当前 dev 验收环境已启用网关鉴权。开发机在
`AutoVoice/local.properties` 配置
`gateway.authToken=<token>`；CI/发行构建通过 `AUTOVOICE_GATEWAY_AUTH_TOKEN` 注入。
dev 与生产使用不同令牌时，由各自构建环境提供，手机端不可切换。

## TLS 与明文策略(D03c)

- Android **debug 构建**保留明文流量例外(局域网/dev 栈 `ws://` 联调);
  **release 构建禁用非必要明文**,生产网关地址必须 `wss://`。
- 服务端生产 TLS 两种方式:反向代理终结(推荐)或网关直连(application-production.yml
  的 `server.ssl.*` 占位),见 `AutoVoiceServer/deploy/README.md`。
- CI 校验 release 合并 manifest 必须 `usesCleartextTraffic="false"`。
