#!/usr/bin/env bash
set -Eeuo pipefail

if [[ $# -ne 2 && $# -ne 3 ]]; then
  echo "Usage: $0 <command-word-AIKit.aar> <wake-word-AIKit.aar> [output.aar]" >&2
  exit 2
fi

command_aar="$1"
wake_aar="$2"
output_aar="${3:-adapter-iflytek/libs/AIKit.aar}"
if [[ "$output_aar" != /* ]]; then
  output_aar="$(pwd)/$output_aar"
fi

for input in "$command_aar" "$wake_aar"; do
  if [[ ! -f "$input" ]]; then
    echo "Missing AAR: $input" >&2
    exit 1
  fi
done

work_dir="$(mktemp -d)"
trap 'rm -rf -- "$work_dir"' EXIT
mkdir -p "$work_dir/merged" "$work_dir/check"
# Combining plugins cannot combine the SDK identity or ability protocol embedded
# in libAIKIT.so. Only accept deliveries with identical cores for every ABI.
# Even matching cores require licensed device verification of both abilities.
unzip -q "$wake_aar" -d "$work_dir/merged"
unzip -p "$command_aar" classes.jar > "$work_dir/check/command-classes.jar"

if ! cmp -s "$work_dir/merged/classes.jar" "$work_dir/check/command-classes.jar"; then
  echo "The two SDK packages expose different AIKit Java APIs; refusing an unsafe merge." >&2
  exit 1
fi

unzip -Z1 "$command_aar" | sed -n '/^jni\/.*\/libAIKIT\.so$/p' | sort > "$work_dir/check/command-cores.txt"
unzip -Z1 "$wake_aar" | sed -n '/^jni\/.*\/libAIKIT\.so$/p' | sort > "$work_dir/check/wake-cores.txt"
if [[ ! -s "$work_dir/check/command-cores.txt" ]] || ! cmp -s "$work_dir/check/command-cores.txt" "$work_dir/check/wake-cores.txt"; then
  echo "Missing or mismatched libAIKIT.so ABI sets; use an official combined SDK." >&2
  exit 1
fi
while IFS= read -r core_path; do
  unzip -p "$command_aar" "$core_path" > "$work_dir/check/command-core.so"
  if ! cmp -s "$work_dir/check/command-core.so" "$work_dir/merged/$core_path"; then
    echo "Conflicting $core_path: plugin merging cannot combine SDK identities. Obtain an official combined SDK for both abilities." >&2
    exit 1
  fi
done < "$work_dir/check/command-cores.txt"

while IFS= read -r native_path; do
  mkdir -p "$work_dir/merged/$(dirname "$native_path")"
  unzip -p "$command_aar" "$native_path" > "$work_dir/merged/$native_path"
done < <(unzip -Z1 "$command_aar" | sed -n '/^jni\/.*\/lib.*_aee\.so$/p')

(
  cd "$work_dir/merged"
  jar cf "$work_dir/prepared.aar" .
)

ability_count="$(unzip -Z1 "$work_dir/prepared.aar" | sed -n '/^jni\/arm64-v8a\/lib.*_aee\.so$/p' | wc -l | tr -d ' ')"
if [[ "$ability_count" -lt 2 ]]; then
  echo "Merged AAR does not contain both arm64 AIKit ability plugins." >&2
  exit 1
fi

mkdir -p "$(dirname "$output_aar")"
cp "$work_dir/prepared.aar" "$output_aar"
echo "Prepared $output_aar with matching SDK cores and both plugins; licensed device verification of both abilities is still required."
