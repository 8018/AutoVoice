#!/usr/bin/env python3
"""Check the expected native and asset layout of an AutoVoice App APK."""

import argparse
from collections import Counter
from pathlib import Path, PurePosixPath
import zipfile


SHARED_LIBS = {"libandroidx.graphics.path.so"}
VENDOR_LIBS = {"libAIKIT.so", "libeabb2f029_v10170_aee.so", "libef7d69542_v10260_aee.so"}
LEGACY_LIBS = {"libonnxruntime.so", "libonnxruntime4j_jni.so", "librnnoise_jni.so"}
OPEN_LIBS = {"libonnxruntime.so", "libautovoice_frontend_jni.so", "libsherpa-onnx-jni.so"}
COMMON_ASSETS = {"cloud-test.pcm", "demo-dev.json", "demo-full.json", "demo-offline.json"}
BASELINE_PROFILE = {"assets/dexopt/baseline.prof", "assets/dexopt/baseline.profm"}
PROFILES = {
    "open-real": (OPEN_LIBS | VENDOR_LIBS, {"supplierconfig.json"}),
    "iflytek-real": (LEGACY_LIBS | VENDOR_LIBS, {"supplierconfig.json", "silero_vad.onnx"}),
    "open-ci": (set(), set()),
    "iflytek-ci": (LEGACY_LIBS, {"silero_vad.onnx"}),
}


def verify(apk: Path, profile: str) -> list[str]:
    profile_libs, profile_assets = PROFILES[profile]
    expected_libs = {f"lib/arm64-v8a/{name}" for name in SHARED_LIBS | profile_libs}
    expected_assets = {f"assets/{name}" for name in COMMON_ASSETS | profile_assets}
    with zipfile.ZipFile(apk) as archive:
        entries = archive.infolist()
        counts = Counter(entry.filename for entry in entries)
        issues = [f"duplicate member: {name}" for name, count in sorted(counts.items()) if count > 1]
        files = {entry.filename for entry in entries if not entry.is_dir()}
        libraries = {name for name in files if name.startswith("lib/") or name.lower().endswith(".so")}
        assets = {name for name in files if name.startswith("assets/")}
        if profile.endswith("-real") and BASELINE_PROFILE <= assets:
            expected_assets |= BASELINE_PROFILE
        for label, actual, expected in (("library", libraries, expected_libs), ("asset", assets, expected_assets)):
            issues.extend(f"missing {label}: {name}" for name in sorted(expected - actual))
            issues.extend(f"unexpected {label}: {name}" for name in sorted(actual - expected))
        for entry in entries:
            name = entry.filename
            if name in expected_libs:
                with archive.open(entry) as library:
                    header = library.read(20)
                if header[:6] != b"\x7fELF\x02\x01" or header[18:20] != b"\xb7\x00":
                    issues.append(f"expected arm64 ELF: {name}")
            if name not in expected_assets and PurePosixPath(name).suffix.lower() in {".onnx", ".ort", ".pcm", ".wav", ".m4a"}:
                issues.append(f"embedded model or audio fixture: {name}")
    return issues


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("profile", choices=PROFILES)
    parser.add_argument("apk", type=Path)
    args = parser.parse_args()
    try:
        issues = verify(args.apk, args.profile)
    except (OSError, RuntimeError, zipfile.BadZipFile) as error:
        issues = [str(error)]
    for issue in issues:
        print(f"{args.apk}: {issue}")
    if issues:
        return 1
    print(f"{args.profile} APK layout verified: {args.apk}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
