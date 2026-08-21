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
# The vendor's two standalone AARs contain different libAIKIT.so cores. IVW
# cannot be registered when the command-word core is used, even if the IVW
# ability plugin is copied into the package. Use the IVW AAR as the base so
# wake-up is guaranteed to work, then add the command-word ability plugin.
unzip -q "$wake_aar" -d "$work_dir/merged"
unzip -p "$command_aar" classes.jar > "$work_dir/check/command-classes.jar"

if ! cmp -s "$work_dir/merged/classes.jar" "$work_dir/check/command-classes.jar"; then
  echo "The two SDK packages expose different AIKit Java APIs; refusing an unsafe merge." >&2
  exit 1
fi

while IFS= read -r native_path; do
  mkdir -p "$work_dir/merged/$(dirname "$native_path")"
  unzip -p "$command_aar" "$native_path" > "$work_dir/merged/$native_path"
done < <(unzip -Z1 "$command_aar" | sed -n '/^jni\/.*\/lib.*_aee\.so$/p')

mkdir -p "$(dirname "$output_aar")"
(
  cd "$work_dir/merged"
  jar cf "$output_aar" .
)

ability_count="$(unzip -Z1 "$output_aar" | sed -n '/^jni\/arm64-v8a\/lib.*_aee\.so$/p' | wc -l | tr -d ' ')"
if [[ "$ability_count" -lt 2 ]]; then
  echo "Merged AAR does not contain both arm64 AIKit ability plugins." >&2
  exit 1
fi

echo "Prepared $output_aar with the wake-word core and both native ability plugins."
