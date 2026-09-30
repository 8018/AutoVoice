#!/usr/bin/env bash

# Source after ssh-retry.sh. The caller supplies SSH_ARGS and SCP_ARGS arrays.
# Every completed chunk is content-addressed and verified on the host, so a timed-out
# transfer can be retried without starting an entire jar from byte zero.
upload_release_chunks() {
  local target="$1"
  local staging_dir="$2"
  shift 2

  if [[ -z "$target" || -z "$staging_dir" || ! -d "${RUNNER_TEMP:-}/autovoice-ssh" ]]; then
    echo "Invalid chunk upload configuration" >&2
    return 2
  fi

  retry_ssh_operation 5 30 "Prepare dev staging directory" \
    ssh "${SSH_ARGS[@]}" "$target" \
      "install -d -m 700 '$staging_dir' && df -h '$staging_dir' && command -v sha256sum >/dev/null"

  local probe="$RUNNER_TEMP/autovoice-ssh/upload-probe"
  printf '%s\n' "$staging_dir" > "$probe"
  retry_ssh_operation 2 30 "Upload small SSH transfer probe" \
    scp "${SCP_ARGS[@]}" "$probe" "$target:$staging_dir/.upload-probe"

  local artifact name digest local_dir remote_dir chunk part chunk_digest
  local completed total
  for artifact in "$@"; do
    [[ -s "$artifact" ]] || { echo "Missing artifact: $artifact" >&2; return 2; }
    name="$(basename "$artifact")"
    [[ "$name" =~ ^[A-Za-z0-9._-]+$ ]] || { echo "Unsafe artifact name: $name" >&2; return 2; }
    digest="$(sha256sum "$artifact" | awk '{print $1}')"
    [[ "$digest" =~ ^[0-9a-f]{64}$ ]] || return 2

    if timeout --foreground --signal=TERM --kill-after=5s 30s \
      ssh "${SSH_ARGS[@]}" "$target" \
        "test -f '$staging_dir/$name' && printf '%s  %s\\n' '$digest' '$staging_dir/$name' | sha256sum -c --status"; then
      echo "$name already present with matching SHA-256"
      continue
    fi

    local_dir="$RUNNER_TEMP/autovoice-upload-chunks/$name/$digest"
    remote_dir="$staging_dir/.upload/$name/$digest"
    install -d -m 700 "$local_dir"
    split -b 4m -d -a 3 "$artifact" "$local_dir/part."
    retry_ssh_operation 3 30 "Prepare chunks for $name" \
      ssh "${SSH_ARGS[@]}" "$target" "install -d -m 700 '$remote_dir'"

    local chunks=("$local_dir"/part.*)
    [[ -f "${chunks[0]}" ]] || { echo "No chunks produced for $name" >&2; return 2; }
    completed=0
    total="${#chunks[@]}"
    for chunk in "${chunks[@]}"; do
      part="$(basename "$chunk")"
      chunk_digest="$(sha256sum "$chunk" | awk '{print $1}')"
      if ! timeout --foreground --signal=TERM --kill-after=5s 30s \
        ssh "${SSH_ARGS[@]}" "$target" \
          "test -f '$remote_dir/$part' && printf '%s  %s\\n' '$chunk_digest' '$remote_dir/$part' | sha256sum -c --status"; then
        retry_ssh_operation 3 120 "Upload $name chunk $part" \
          scp "${SCP_ARGS[@]}" "$chunk" "$target:$remote_dir/$part.uploading"
        retry_ssh_operation 3 30 "Verify $name chunk $part" \
          ssh "${SSH_ARGS[@]}" "$target" \
            "printf '%s  %s\\n' '$chunk_digest' '$remote_dir/$part.uploading' | sha256sum -c --status && mv -f -- '$remote_dir/$part.uploading' '$remote_dir/$part'"
      fi
      completed=$((completed + 1))
      echo "$name: verified chunk $completed/$total"
    done

    # Only the verified complete jar receives its final name. Activation never sees a partial jar.
    retry_ssh_operation 2 180 "Assemble and verify $name" \
      ssh "${SSH_ARGS[@]}" "$target" \
        "cat '$remote_dir'/part.[0-9][0-9][0-9] > '$staging_dir/$name.uploading' && printf '%s  %s\\n' '$digest' '$staging_dir/$name.uploading' | sha256sum -c --status && mv -f -- '$staging_dir/$name.uploading' '$staging_dir/$name'"
    echo "$name: verified complete SHA-256 $digest"
  done
}
