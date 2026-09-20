#!/usr/bin/env bash
# dev 部署栈一次性初始化(幂等,可重复执行)。
# 在服务器上以 root 运行:bash init-dev.sh [部署目录(含 unit/env 模板,默认当前目录)]
#
# 完成后还需管理员手动:
#   1. 填 /etc/autovoice-dev/.env(只复制密钥；端口、URL、目录保留 dev 模板值)
#   2. systemctl enable --now autovoice-dev-{gateway,tts,skill-manager}
#   3. 阿里云安全组放行入方向 TCP 8090(手机测试)
set -Eeuo pipefail

if [[ "${EUID:-$(id -u)}" -ne 0 ]]; then
  echo "Init must run as root." >&2
  exit 1
fi

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEV_ROOT=/opt/autovoice-dev

echo "== 1/4 创建目录结构 =="
install -d -m 0755 \
  "$DEV_ROOT" \
  "$DEV_ROOT/skill-manager" \
  "$DEV_ROOT/tts-cache" \
  "$DEV_ROOT/iflytek-offline/work" \
  "$DEV_ROOT/releases" \
  "$DEV_ROOT/backups" \
  "$DEV_ROOT/incoming"
install -d -m 0700 /etc/autovoice-dev

echo "== 2/4 安装环境变量模板(已存在则跳过)=="
if [[ -f /etc/autovoice-dev/.env ]]; then
  echo "skip: /etc/autovoice-dev/.env 已存在"
else
  install -m 0600 "$DEPLOY_DIR/env.example-dev" /etc/autovoice-dev/.env
  echo "created: /etc/autovoice-dev/.env(只填密钥；不要从生产覆盖 dev 内部路由)"
fi

echo "== 3/4 安装 systemd unit =="
install -m 0644 \
  "$DEPLOY_DIR/autovoice-dev-gateway.service" \
  "$DEPLOY_DIR/autovoice-dev-tts.service" \
  "$DEPLOY_DIR/autovoice-dev-skill-manager.service" \
  /etc/systemd/system/
systemctl daemon-reload
echo "installed: autovoice-dev-{gateway,tts,skill-manager}.service"

echo "== 4/4 离线 SDK(可选,只读资源共享)=="
if [[ -d /opt/autovoice/iflytek-offline ]]; then
  echo "生产离线 SDK 已存在。dev 的 work 目录已独立($DEV_ROOT/iflytek-offline/work);"
  echo "只读资源(libs/resource/cn_fsa.txt/.so)在 dev 的 .env 中直接指向生产路径即可共享。"
else
  echo "skip: 未发现生产离线 SDK,dev 离线链路保持关闭即可。"
fi

echo
echo "初始化完成。接下来:"
echo "  1. vi /etc/autovoice-dev/.env"
echo "  2. systemctl enable --now autovoice-dev-gateway autovoice-dev-tts autovoice-dev-skill-manager"
echo "  3. systemctl status autovoice-dev-gateway"
