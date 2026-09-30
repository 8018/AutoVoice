#!/usr/bin/env bash
set -Eeuo pipefail

upload_test_dir="$(mktemp -d)"
trap 'rm -r -- "$upload_test_dir"' EXIT
export RUNNER_TEMP="$upload_test_dir/runner"
install -d "$RUNNER_TEMP/autovoice-ssh"
staging_dir="$upload_test_dir/remote/incoming/$(printf 'a%.0s' {1..40})"
install -d "$staging_dir"

# The SSH/SCP surface is mocked; remote commands execute in the temporary directory.
SSH_ARGS=(--mock)
SCP_ARGS=(--mock)
if [[ "$(uname -s)" == Darwin ]]; then
  # macOS sha256sum lacks GNU --status; preserve the check semantics in this mock.
  sha256sum() {
    if [[ "${1:-}" == -c && "${2:-}" == --status ]]; then
      shasum -a 256 -c - >/dev/null
    else
      command sha256sum "$@"
    fi
  }
  export -f sha256sum
fi
ssh() {
  local command="${*: -1}"
  bash -c "$command"
}
scp() {
  local arguments=("$@")
  local source="${arguments[${#arguments[@]}-2]}"
  local destination="${arguments[${#arguments[@]}-1]}"
  cp -- "$source" "${destination#*:}"
  scp_calls=$((scp_calls + 1))
}
timeout() {
  while [[ "$1" != ssh && "$1" != scp ]]; do shift; done
  "$@"
}

source "$(dirname "$0")/ssh-retry.sh"
source "$(dirname "$0")/upload-release-chunks.sh"

artifact="$upload_test_dir/app.jar"
head -c 9000000 /dev/urandom > "$artifact"
scp_calls=0
upload_release_chunks "fake@host" "$staging_dir" "$artifact"
[[ "$(sha256sum "$artifact" | awk '{print $1}')" == \
   "$(sha256sum "$staging_dir/app.jar" | awk '{print $1}')" ]]
first_calls="$scp_calls"
[[ "$first_calls" -eq 10 ]] # probe + nine 1 MiB chunks for 9,000,000 bytes

# A rerun after interruption must reuse verified chunks, transferring only the tiny probe.
rm -- "$staging_dir/app.jar"
upload_release_chunks "fake@host" "$staging_dir" "$artifact"
[[ "$scp_calls" -eq $((first_calls + 1)) ]]
cmp -- "$artifact" "$staging_dir/app.jar"

# A corrupt cached chunk must be transferred again, not trusted by filename alone.
digest="$(sha256sum "$artifact" | awk '{print $1}')"
printf 'corrupt' > "$staging_dir/.upload/app.jar/$digest/1m/part.001"
rm -- "$staging_dir/app.jar"
upload_release_chunks "fake@host" "$staging_dir" "$artifact"
[[ "$scp_calls" -eq $((first_calls + 3)) ]]
cmp -- "$artifact" "$staging_dir/app.jar"
echo "Chunk upload and resume test passed"
