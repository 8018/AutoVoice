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

# dev 与生产同机时，错误的内部 URL 不会表现为连接失败，而会静默读取生产数据。
# 发布前显式拒绝这种跨环境依赖，避免“dev 三服务都健康”掩盖配置串线。
if [[ "$environment" == "dev" ]]; then
  dev_env_file=/etc/autovoice-dev/.env
  expected_skill_manager_url='SKILL_MANAGER_URL=http://127.0.0.1:8093'
  if [[ ! -r "$dev_env_file" ]] || ! grep -Fqx "$expected_skill_manager_url" "$dev_env_file"; then
    echo "Dev deployment refused: $dev_env_file must contain exactly:" >&2
    echo "  $expected_skill_manager_url" >&2
    exit 1
  fi
fi

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

require_host_capacity() {
  local available_kib
  local available_disk_kib

  available_kib="$(awk '/^MemAvailable:/ { print $2 }' /proc/meminfo)"
  available_disk_kib="$(df -Pk "$release_root" | awk 'NR == 2 { print $4 }')"
  if [[ ! "$available_kib" =~ ^[0-9]+$ || "$available_kib" -lt 262144 ]]; then
    echo "Deployment refused: host has less than 256 MiB available memory." >&2
    free -h >&2 || true
    exit 1
  fi
  if [[ ! "$available_disk_kib" =~ ^[0-9]+$ || "$available_disk_kib" -lt 1048576 ]]; then
    echo "Deployment refused: host has less than 1 GiB free disk space." >&2
    df -h "$release_root" >&2 || true
    exit 1
  fi
}

prune_directory_history() {
  local root="$1"
  local keep="$2"
  local entries=()
  local index

  [[ -d "$root" ]] || return 0
  mapfile -t entries < <(
    find "$root" -mindepth 1 -maxdepth 1 -type d -printf '%T@ %p\n' |
      sort -rn |
      cut -d' ' -f2-
  )
  for ((index = keep; index < ${#entries[@]}; index++)); do
    case "${entries[$index]}" in
      "$root"/*) rm -rf -- "${entries[$index]}" ;;
      *) echo "Refusing to prune path outside $root: ${entries[$index]}" >&2; return 1 ;;
    esac
  done
}

if [[ ! -s "$staging_dir/install-resource-guards.sh" ]]; then
  echo "Missing resource guard installer in $staging_dir." >&2
  exit 1
fi
bash "$staging_dir/install-resource-guards.sh"
# Reclaim old deployment data before the disk-space gate; otherwise a host that
# has already filled its disk could never deploy the cleanup fix.
prune_directory_history "$release_root/releases" 5
prune_directory_history "$release_root/backups" 5
find "$release_root/incoming" -mindepth 1 -maxdepth 1 -type d -mtime +1 -exec rm -rf -- {} +
require_host_capacity

# D12b:优先用 /health/ready 判定业务就绪(区分"端口在listen"与"依赖可用"),
# 端点不存在(旧 jar/无该端点的服务)时回退到 TCP 端口判据,保持兼容。
wait_for_service() {
  local service="$1"
  local port="$2"
  local health_path="${3:-}"
  local attempt
  local ready

  for ((attempt = 1; attempt <= 90; attempt++)); do
    if systemctl is-active --quiet "$service"; then
      if [[ -n "$health_path" ]]; then
        ready="$(timeout 2 curl -fsS -o /dev/null -w '%{http_code}'           "http://127.0.0.1:${port}${health_path}" 2>/dev/null || true)"
        if [[ "$ready" == "200" ]]; then
          echo "$service is ready (${health_path} -> 200, port $port)."
          return 0
        fi
      elif timeout 1 bash -c ": </dev/tcp/127.0.0.1/$port" 2>/dev/null; then
        echo "$service is ready on port $port."
        return 0
      fi
    fi
    sleep 1
  done

  if [[ -n "$health_path" ]]; then
    echo "$service did not report ready (${health_path} on port $port)." >&2
  else
    echo "$service did not become ready on port $port." >&2
  fi
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

# D13:记录本次发布的兼容元数据(代码 SHA、schema 版本约束、回滚说明)
cat > "$release_dir/metadata.txt" <<META
release_sha=$release_sha
environment=$environment
deployed_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)
voice_backend=${VOICE_BACKEND:-unknown}
# 回滚:jar 回退不改变数据库 schema;若发布包含不向后兼容的 schema 变更,
# 回滚前需按 docs/production-development-plan.md D13 的恢复流程处理。
META

# D13:产物摘要(可追溯:确认部署的是 CI 产出的同一份)
sha256sum "${targets[@]}" > "$release_dir/checksums.sha256" 2>/dev/null || true

for index in "${!services[@]}"; do
  systemctl restart "${services[$index]}"
  health_path=""
  # 仅 gateway(app.jar)暴露 /health/ready;其余服务沿用端口判据
  if [[ "${targets[$index]}" == */app.jar ]]; then
    health_path="/health/ready"
  fi
  wait_for_service "${services[$index]}" "${ports[$index]}" "$health_path"
done

rollback_armed=false
trap - ERR
rm -rf -- "$staging_dir"
# Bound disk growth caused by frequent automatic deployments.
prune_directory_history "$release_root/releases" 5
prune_directory_history "$release_root/backups" 5
find "$release_root/incoming" -mindepth 1 -maxdepth 1 -type d -mtime +1 -exec rm -rf -- {} +
echo "Deployed AutoVoice release $release_sha to $environment successfully."
