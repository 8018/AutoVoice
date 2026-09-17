#!/usr/bin/env bash

# Source this file from a deployment workflow running on Ubuntu. OpenSSH's ConnectTimeout does not
# reliably bound a TCP connection that is accepted but never delivers an SSH banner, so every
# operation also receives an outer GNU timeout.
retry_ssh_operation() {
  local attempts="$1"
  local timeout_seconds="$2"
  local label="$3"
  shift 3

  local attempt status=1
  for ((attempt = 1; attempt <= attempts; attempt++)); do
    if timeout --foreground --signal=TERM --kill-after=5s "${timeout_seconds}s" "$@"; then
      return 0
    else
      status=$?
    fi

    if [[ "$attempt" -eq "$attempts" ]]; then
      echo "::error::$label failed after $attempts attempts (last exit code: $status)"
      return "$status"
    fi

    local delay_seconds=$((attempt * 10))
    echo "::warning::$label attempt $attempt/$attempts failed (exit code: $status); retrying in ${delay_seconds}s"
    sleep "$delay_seconds"
  done
}
