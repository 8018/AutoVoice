#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
workflows=(
  "$repo_root/.github/workflows/deploy.yml"
  "$repo_root/.github/workflows/deploy-dev.yml"
)

for workflow in "${workflows[@]}"; do
  grep -Fq 'group: autovoice-shared-deploy-host' "$workflow"
  grep -Fq "github.event.workflow_run.event == 'push'" "$workflow"
  grep -Fq 'timeout-minutes: 20' "$workflow"
  [[ "$(grep -c -- '-o ConnectTimeout=15' "$workflow")" -eq 3 ]]
  [[ "$(grep -c -- '-o ServerAliveInterval=15' "$workflow")" -eq 3 ]]
  [[ "$(grep -c 'source AutoVoiceServer/deploy/ssh-retry.sh' "$workflow")" -eq 2 ]]
  grep -Fq 'retry_ssh_operation 5 30' "$workflow"
  [[ "$(grep -c 'retry_ssh_operation 3 180' "$workflow")" -eq 1 ]]
  [[ "$(grep -c 'retry_ssh_operation 1 300' "$workflow")" -eq 1 ]]
done

retry_script="$repo_root/AutoVoiceServer/deploy/ssh-retry.sh"
grep -Fq 'timeout --foreground --signal=TERM --kill-after=5s' "$retry_script"
grep -Fq 'failed after $attempts attempts' "$retry_script"

echo "Deployment workflow serialization, trigger filtering, and bounded SSH retries verified."
