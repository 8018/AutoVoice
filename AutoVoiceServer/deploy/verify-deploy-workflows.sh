#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
workflows=(
  "$repo_root/.github/workflows/deploy.yml"
  "$repo_root/.github/workflows/deploy-dev.yml"
)

grep -Fq 'group: autovoice-production-deploy-host' "${workflows[0]}"
grep -Fq 'group: autovoice-dev-deploy-host' "${workflows[1]}"
grep -Fq 'DEPLOY_HOST: ${{ vars.DEV_SSH_HOST }}' "${workflows[1]}"
grep -Fq 'Require configured dev host' "${workflows[1]}"

for workflow in "${workflows[@]}"; do
  grep -Fq "github.event.workflow_run.event == 'push'" "$workflow"
  grep -Fq 'timeout-minutes: 20' "$workflow"
  [[ "$(grep -c -- '-o ConnectTimeout=15' "$workflow")" -eq 3 ]]
  [[ "$(grep -c -- '-o ServerAliveInterval=15' "$workflow")" -eq 3 ]]
  [[ "$(grep -c 'source AutoVoiceServer/deploy/ssh-retry.sh' "$workflow")" -eq 2 ]]
  [[ "$(grep -c 'AutoVoiceServer/deploy/install-resource-guards.sh' "$workflow")" -eq 1 ]]
  grep -Fq 'retry_ssh_operation 5 30' "$workflow"
  [[ "$(grep -c 'retry_ssh_operation 3 180' "$workflow")" -eq 1 ]]
  [[ "$(grep -c 'retry_ssh_operation 1 300' "$workflow")" -eq 1 ]]
done

retry_script="$repo_root/AutoVoiceServer/deploy/ssh-retry.sh"
grep -Fq 'timeout --foreground --signal=TERM --kill-after=5s' "$retry_script"
grep -Fq 'failed after $attempts attempts' "$retry_script"

release_script="$repo_root/AutoVoiceServer/deploy/deploy-release.sh"
grep -Fq 'require_host_capacity' "$release_script"
grep -Fq 'prune_directory_history "$release_root/releases" 5' "$release_script"
grep -Fq 'AUTOVOICE_HOST_ROLE="$environment" bash "$staging_dir/install-resource-guards.sh"' "$release_script"

guard_script="$repo_root/AutoVoiceServer/deploy/install-resource-guards.sh"
grep -Fq 'MemoryMax=85%' "$guard_script"
grep -Fq 'host_role="${AUTOVOICE_HOST_ROLE:-shared}"' "$guard_script"
grep -Fq 'install_guard autovoice-dev-gateway 40% 50% 100' "$guard_script"
grep -Fq 'install_guard autovoice-gateway 40% 50% 100' "$guard_script"

grep -Fq 'install -d -m 0700 /etc/autovoice-dev' "$repo_root/AutoVoiceServer/deploy/init-dev.sh"

echo "Dedicated deployment hosts, bounded SSH retries, capacity checks and resource guards verified."
