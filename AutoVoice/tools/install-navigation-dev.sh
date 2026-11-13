#!/usr/bin/env bash
# Install the real Iflytek dev build and its existing licensed model files.
set -Eeuo pipefail
if [[ $# -lt 1 || $# -gt 2 ]]; then
  echo "Usage: $0 <adb-device-serial> [iflytek-debug.apk]" >&2
  exit 2
fi
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
device_serial="$1"
apk="${2:-$project_dir/app/build/outputs/apk/iflytek/debug/app-iflytek-debug.apk}"
models="$project_dir/adapter-iflytek/libs/iflytek-resource"
adb_bin="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}/platform-tools/adb"
if [[ ! -x "$adb_bin" ]]; then adb_bin="$(command -v adb)"; fi
if [[ ! -f "$apk" || ! -d "$models/CNENESR" || ! -d "$models/ivw" ]]; then
  echo "Missing device APK or licensed Iflytek model files." >&2
  exit 1
fi
python3 "$project_dir/tools/native/verify_apk.py" iflytek-real "$apk"
[[ "$("$adb_bin" -s "$device_serial" get-state)" == device ]]
"$adb_bin" -s "$device_serial" install -r "$apk"
"$adb_bin" -s "$device_serial" shell mkdir -p /sdcard/iflytek
"$adb_bin" -s "$device_serial" push "$models/CNENESR" /sdcard/iflytek/
"$adb_bin" -s "$device_serial" push "$models/ivw" /sdcard/iflytek/
echo "Installed dev APK and speech resources. Open AutoVoice, grant requested permissions, and choose online mode."
