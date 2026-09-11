#!/usr/bin/env bash
# 通用发布脚本：按环境(prod|dev)选择服务名、端口与目标路径。
# 生产与 dev 共用备份/回滚/就绪检查逻辑，互不干扰。
#
# 用法: deploy-release.sh <prod|dev> <release_sha> <staging_dir>
set -Eeuo pipefail

environment="${1:-}"
release_sha="${2:-}"
staging_dir="${3:-}"

if [[ "${EUID:-$(id -u)}" -ne 0 ]]; then
  echo "Deployment must run as root." >&2
  exit 1
fi
if [[ "$environment" != "prod" && "$environment" != "dev" ]]; then
  echo "Environment must be prod or dev: $environment" >&2
  exit 1
fi
if [[ ! "$release_sha" =~ ^[0-9a-f]{40}$ ]]; then
  echo "Invalid release SHA: $release_sha" >&2
  exit 1
fi

case "$environment" in
  prod)
    services=(
      autovoice-skill-manager
      autovoice-tts
      autovoice-gateway
    )
    ports=(8083 8082 8080)
    release_root="/opt/autovoice"
    ;;
  dev)
    services=(
      autovoice-dev-skill-manager
      autovoice-dev-tts
      autovoice-dev-gateway
    )
    ports=(8093 8092 8090)
    release_root="/opt/autovoice-dev"
    ;;
esac

if [[ "$staging_dir" != "$release_root/incoming/$release_sha" ]]; then
  echo "Unexpected staging directory: $staging_dir" >&2
  exit 1
fi

artifact_names=(
  skill-manager.jar
  tts-server.jar
  app.jar
)
targets=(
  "$release_root/skill-manager/skill-manager.jar"
  "$release_root/tts-server.jar"
  "$release_root/app.jar"
)

release_dir="$release_root/releases/$release_sha"
backup_dir="$release_root/backups/${release_sha}-$(date -u +%Y%m%dT%H%M%SZ)"
rollback_armed=false

wait_for_service() {
  local service="$1"
  local port="$2"
  local attempt

  for ((attempt = 1; attempt <= 90; attempt++)); do
    if systemctl is-active --quiet "$service" &&
      timeout 1 bash -c ": </dev/tcp/127.0.0.1/$port" 2>/dev/null; then
      echo "$service is ready on port $port."
      return 0
    fi
    sleep 1
  done

  echo "$service did not become ready on port $port." >&2
  systemctl status "$service" --no-pager >&2 || true
  journalctl -u "$service" -n 80 --no-pager >&2 || true
  return 1
}

rollback() {
  local exit_code=$?
  local index
  trap - ERR

  if [[ "$rollback_armed" == true ]]; then
    echo "Deployment failed; restoring jars from $backup_dir." >&2
    for index in "${!targets[@]}"; do
      install -m 0644 \
        "$backup_dir/${artifact_names[$index]}" \
        "${targets[$index]}" || true
    done
    systemctl restart "${services[@]}" || true
    systemctl --no-pager --full status "${services[@]}" >&2 || true
  fi

  exit "$exit_code"
}
trap rollback ERR

for artifact in "${artifact_names[@]}"; do
  if [[ ! -s "$staging_dir/$artifact" ]]; then
    echo "Missing or empty artifact: $staging_dir/$artifact" >&2
    exit 1
  fi
done

install -d -m 0755 "$release_dir" "$backup_dir" "$release_root/skill-manager"
for index in "${!targets[@]}"; do
  if [[ ! -s "${targets[$index]}" ]]; then
    echo "Current jar is missing; refusing a deployment without rollback: ${targets[$index]}" >&2
    exit 1
  fi
  install -m 0644 \
    "$staging_dir/${artifact_names[$index]}" \
    "$release_dir/${artifact_names[$index]}"
  install -m 0644 \
    "${targets[$index]}" \
    "$backup_dir/${artifact_names[$index]}"
done

rollback_armed=true
for index in "${!targets[@]}"; do
  install -m 0644 \
    "$release_dir/${artifact_names[$index]}" \
    "${targets[$index]}.new"
  mv -f "${targets[$index]}.new" "${targets[$index]}"
done

for index in "${!services[@]}"; do
  systemctl restart "${services[$index]}"
  wait_for_service "${services[$index]}" "${ports[$index]}"
done

rollback_armed=false
trap - ERR
rm -rf -- "$staging_dir"
echo "Deployed AutoVoice release $release_sha to $environment successfully."
