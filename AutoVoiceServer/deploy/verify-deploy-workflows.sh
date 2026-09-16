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
  [[ "$(grep -c 'for attempt in 1 2 3' "$workflow")" -eq 1 ]]
done

echo "Deployment workflow serialization, trigger filtering, and SSH bounds verified."
