#!/usr/bin/env bash
# CI post-deploy gate; the device token never enters logs or command arguments.
set -Eeuo pipefail
: "${DEPLOY_HOST:?}" "${DEPLOY_USER:?}" "${DEPLOY_SHA:?}" "${RUNNER_TEMP:?}"
SSH_ARGS=(-i "$RUNNER_TEMP/autovoice-ssh/id_key" -p "${DEPLOY_PORT:-22}"
  -o BatchMode=yes -o IdentitiesOnly=yes -o StrictHostKeyChecking=yes
  -o "UserKnownHostsFile=$RUNNER_TEMP/autovoice-ssh/known_hosts" -o ConnectTimeout=15)
target="$DEPLOY_USER@$DEPLOY_HOST"
token_file="$(mktemp "$RUNNER_TEMP/navigation-token.XXXXXX")"
tunnel_pid=''
cleanup() { rm -f "$token_file"; if [[ -n "$tunnel_pid" ]]; then kill "$tunnel_pid" 2>/dev/null || true; fi; }
trap cleanup EXIT
chmod 600 "$token_file"
ssh "${SSH_ARGS[@]}" "$target" 'python3 -' > "$token_file" <<'PY'
import json
from pathlib import Path
lines=Path('/etc/autovoice-dev/.env').read_text().splitlines()
raw=next(line.split('=',1)[1] for line in lines if line.startswith('AUTOVOICE_GATEWAY_AUTH_DEVICES='))
devices=json.loads(raw.strip().strip("'\""))
token=devices['demo-1']
assert isinstance(token,str) and token
print(token,end='')
PY
# Force the benchmark through the same authenticated dev SSH connection.
ssh "${SSH_ARGS[@]}" -N -o ExitOnForwardFailure=yes -L 127.0.0.1:18090:127.0.0.1:8090 "$target" &
tunnel_pid=$!
for attempt in {1..15}; do
  kill -0 "$tunnel_pid"
  if curl -fsS --max-time 2 http://127.0.0.1:18090/health/ready >/dev/null; then break; fi
  sleep 1
done
curl -fsS --max-time 3 http://127.0.0.1:18090/health/ready >/dev/null
NAVIGATION_BENCHMARK_TOKEN_FILE="$token_file" \
NAVIGATION_BENCHMARK_ENDPOINT=ws://127.0.0.1:18090/ws \
node tools/qa/navigation-cloud-text.mjs --release="$DEPLOY_SHA"

NAVIGATION_BENCHMARK_TOKEN_FILE="$token_file" \
NAVIGATION_BENCHMARK_ENDPOINT=ws://127.0.0.1:18090/ws \
node tools/qa/navigation-route-benchmark.mjs --release="$DEPLOY_SHA"

# Synthetic public speech exercises cloud ASR and exact second-candidate selection.
# Voice latency includes paced upload and stays separate from the unchanged text gate.
NAVIGATION_BENCHMARK_TOKEN_FILE="$token_file" \
NAVIGATION_BENCHMARK_ENDPOINT=ws://127.0.0.1:18090/ws \
node tools/qa/navigation-voice-benchmark.mjs --release="$DEPLOY_SHA"

# Additional business coverage: real admin endpoint/empty state/assets, without fake uploads.
mkdir -p docs/qa/navigation-stats-runs
stats_result="docs/qa/navigation-stats-runs/$(date -u +%Y%m%dT%H%M%SZ)-$DEPLOY_SHA.json"
ssh "${SSH_ARGS[@]}" "$target" 'python3 - --dev-env' < tools/qa/navigation-statistics-benchmark.py > "$stats_result"
cat "$stats_result"
