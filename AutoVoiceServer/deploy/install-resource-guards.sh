#!/usr/bin/env bash
# Persistent cgroup guards for AutoVoice JVM services.
set -Eeuo pipefail

if [[ "${EUID:-$(id -u)}" -ne 0 ]]; then
  echo "Resource guards must be installed as root." >&2
  exit 1
fi

systemd_root=/etc/systemd/system
slice_file="$systemd_root/autovoice.slice"
host_role="${AUTOVOICE_HOST_ROLE:-shared}"

if [[ "$host_role" != "prod" && "$host_role" != "dev" && "$host_role" != "shared" ]]; then
  echo "AUTOVOICE_HOST_ROLE must be prod, dev or shared: $host_role" >&2
  exit 1
fi

install -d -m 0755 "$systemd_root"
cat > "$slice_file" <<'UNIT'
[Unit]
Description=AutoVoice service resource boundary

[Slice]
# Preserve memory for sshd, systemd and the operating system.
MemoryHigh=75%
MemoryMax=85%
TasksMax=2048
UNIT

install_guard() {
  local service="$1"
  local memory_high="$2"
  local memory_max="$3"
  local oom_score="$4"
  local drop_in_dir="$systemd_root/${service}.service.d"

  install -d -m 0755 "$drop_in_dir"
  cat > "$drop_in_dir/50-autovoice-resources.conf" <<UNIT
[Service]
Slice=autovoice.slice
MemoryHigh=$memory_high
MemoryMax=$memory_max
TasksMax=512
LimitNOFILE=65535
OOMScoreAdjust=$oom_score
UNIT
}

case "$host_role" in
  prod)
    install_guard autovoice-gateway 40% 50% 100
    install_guard autovoice-tts 15% 20% 150
    install_guard autovoice-skill-manager 15% 20% 150
    ;;
  dev)
    install_guard autovoice-dev-gateway 40% 50% 100
    install_guard autovoice-dev-tts 15% 20% 150
    install_guard autovoice-dev-skill-manager 15% 20% 150
    ;;
  shared)
    # Compatibility mode for a host that still carries both stacks.
    install_guard autovoice-gateway 25% 30% 100
    install_guard autovoice-tts 10% 15% 150
    install_guard autovoice-skill-manager 10% 15% 150
    install_guard autovoice-dev-gateway 15% 20% 500
    install_guard autovoice-dev-tts 7% 10% 550
    install_guard autovoice-dev-skill-manager 7% 10% 550
    ;;
esac

systemctl daemon-reload

# A drop-in takes full effect on the next restart (the current deployment
# restarts its own stack). Apply the memory/task limits to the other, already
# running stack immediately without restarting it.
apply_live_guard() {
  local service="$1"
  local memory_high="$2"
  local memory_max="$3"
  if systemctl is-active --quiet "$service"; then
    systemctl set-property --runtime "$service" \
      "MemoryHigh=$memory_high" "MemoryMax=$memory_max" TasksMax=512
  fi
}

case "$host_role" in
  prod)
    apply_live_guard autovoice-gateway 40% 50%
    apply_live_guard autovoice-tts 15% 20%
    apply_live_guard autovoice-skill-manager 15% 20%
    ;;
  dev)
    apply_live_guard autovoice-dev-gateway 40% 50%
    apply_live_guard autovoice-dev-tts 15% 20%
    apply_live_guard autovoice-dev-skill-manager 15% 20%
    ;;
  shared)
    apply_live_guard autovoice-gateway 25% 30%
    apply_live_guard autovoice-tts 10% 15%
    apply_live_guard autovoice-skill-manager 10% 15%
    apply_live_guard autovoice-dev-gateway 15% 20%
    apply_live_guard autovoice-dev-tts 7% 10%
    apply_live_guard autovoice-dev-skill-manager 7% 10%
    ;;
esac

echo "AutoVoice resource guards installed for host role: $host_role."
