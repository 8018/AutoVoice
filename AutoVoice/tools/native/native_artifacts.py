"""Bounded, non-extracting verification and transactional installation of native ZIP bundles."""
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import struct
import tempfile
import zipfile

LIBRARIES = {"webrtc": ("libautovoice_frontend_jni.so",),
             "firered": ("libsherpa-onnx-jni.so", "libonnxruntime.so")}
MODULES = {"webrtc": "adapter-webrtc", "firered": "adapter-firered"}
SYSTEM_LIBRARIES = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so", "libz.so"}
MAX_ARCHIVE = 512 * 1024 * 1024
MAX_UNPACKED = 1024 * 1024 * 1024
MAX_MANIFEST = 1024 * 1024


class NativeError(Exception):
    pass


def require(condition, message):
    if not condition:
        raise NativeError(message)


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def file_sha256(path):
    h = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def is_digest(value):
    return isinstance(value, str) and re.fullmatch(r"[0-9a-f]{64}", value) is not None


def json_bytes(value):
    return (json.dumps(value, sort_keys=True, indent=2) + "\n").encode()


def load_lock(path):
    data = json.loads(Path(path).read_text())
    require(data.get("schema_version") == 1, "unsupported lock schema")
    require(data.get("target") == {"abi": "arm64-v8a", "android_api": 27}, "lock target must be arm64-v8a/API 27")
    require(set(data.get("components", {})) == set(LIBRARIES), "lock components must be webrtc and firered")
    for component, record in data["components"].items():
        require(re.fullmatch(r"[0-9a-f]{40}", record["source"]["revision"]) is not None, "source revision must be an exact commit")
        require(set(record["artifact"]["files"]) == set(LIBRARIES[component]), "lock library whitelist mismatch")
    return data


def safe_name(name):
    p = PurePosixPath(name)
    require(name and "\\" not in name and not p.is_absolute() and
            all(part not in ("", ".", "..") and ":" not in part for part in name.split("/")),
            f"unsafe archive path: {name!r}")


def inventory(archive):
    require(Path(archive).stat().st_size <= MAX_ARCHIVE, "archive exceeds size limit")
    z = zipfile.ZipFile(archive)
    try:
        members = {}
        total = 0
        for item in z.infolist():
            safe_name(item.filename.rstrip("/") if item.is_dir() else item.filename)
            require(item.filename not in members, f"duplicate archive member: {item.filename}")
            require(not item.is_dir(), "directory entries are not part of the bundle format")
            mode = item.external_attr >> 16
            require(not stat.S_ISLNK(mode), f"archive symlink is forbidden: {item.filename}")
            require(stat.S_IFMT(mode) in (0, stat.S_IFREG), f"special archive member is forbidden: {item.filename}")
            require(not (item.flag_bits & 1), "encrypted archives are not supported")
            total += item.file_size
            require(total <= MAX_UNPACKED, "archive exceeds unpacked size limit")
            members[item.filename] = item
        ort = [p for p in members if PurePosixPath(p).name == "libonnxruntime.so"]
        require(len(ort) <= 1, "duplicate ORT library in archive")
        require("manifest.json" in members, "missing archive member: manifest.json")
        require(members["manifest.json"].file_size <= MAX_MANIFEST, "manifest exceeds size limit")
        return z, members
    except Exception:
        z.close()
        raise


def elf_info(data, symbols_only=False):
    require(len(data) >= 64 and data[:7] == b"\x7fELF\x02\x01\x01", "ELF must be 64-bit little-endian")
    fields = struct.unpack_from("<16sHHIQQQIHHHHHH", data)
    require(fields[2] == 183, "ELF must be AArch64")
    if symbols_only:
        return {"machine": "AArch64", "symbols_only": True}
    require(fields[1] == 3 and fields[3] == 1 and fields[8] == 64, "ELF must be a shared object")
    phoff, phsize, phnum = fields[5], fields[9], fields[10]
    require(phsize == 56 and 0 < phnum <= 1024 and phoff + phsize * phnum <= len(data), "invalid ELF program headers")
    headers = [struct.unpack_from("<IIQQQQQQ", data, phoff + i * phsize) for i in range(phnum)]
    for h in headers:
        require(h[2] + h[5] <= len(data), "ELF segment exceeds file bounds")
    loads = [h for h in headers if h[0] == 1]
    require(loads, "ELF has no loadable segments")
    dynamic = [h for h in headers if h[0] == 2]
    require(len(dynamic) <= 1, "ELF has duplicate dynamic tables")
    tags = []
    if dynamic:
        h = dynamic[0]
        require(h[5] % 16 == 0, "invalid ELF dynamic table")
        for offset in range(h[2], h[2] + h[5], 16):
            tag, value = struct.unpack_from("<qQ", data, offset)
            if tag == 0:
                break
            tags.append((tag, value))
        else:
            raise NativeError("ELF dynamic table has no terminator")
    string_tags = [value for tag, value in tags if tag == 5]
    sizes = [value for tag, value in tags if tag == 10]
    require(len(string_tags) <= 1 and len(sizes) <= 1, "ambiguous ELF string table")
    strings = b""
    if any(tag in (1, 15, 29) for tag, _ in tags):
        require(string_tags and sizes and sizes[0] <= len(data), "ELF dynamic strings missing")
        address = string_tags[0]
        matches = [h[2] + address - h[3] for h in loads if h[3] <= address and address + sizes[0] <= h[3] + h[5]]
        require(len(matches) == 1, "invalid ELF string table address")
        strings = data[matches[0]:matches[0] + sizes[0]]
    needed = []
    runpath = None
    for tag, value in tags:
        if tag not in (1, 15, 29):
            continue
        require(value < len(strings), "ELF string offset out of bounds")
        end = strings.find(b"\0", value)
        require(end >= 0, "unterminated ELF string")
        value = strings[value:end].decode("ascii")
        require(tag == 1 or not value or (tag == 29 and value == "$ORIGIN"),
                "ELF RPATH/RUNPATH is forbidden except exact DT_RUNPATH $ORIGIN")
        if tag == 29 and value:
            runpath = value
        if tag == 1:
            require(value and "/" not in value and "\\" not in value, "unsafe ELF runtime dependency")
            needed.append(value)
    android_api_note = None
    for h in headers:
        if h[0] != 4:
            continue
        pos, stop = h[2], h[2] + h[5]
        while pos + 12 <= stop:
            name_size, desc_size, _ = struct.unpack_from("<III", data, pos)
            pos += 12
            name_end = pos + name_size
            desc_start = (name_end + 3) & ~3
            end = desc_start + desc_size
            require(end <= stop, "invalid ELF note")
            if data[pos:name_end].rstrip(b"\0") == b"Android" and desc_size >= 4:
                android_api_note = struct.unpack_from("<I", data, desc_start)[0]
                require(1 <= android_api_note <= 27, "ELF Android API note exceeds minSdk 27")
            pos = (end + 3) & ~3
    return {"machine": "AArch64", "needed": sorted(set(needed)), "android_api_note": android_api_note, "runpath": runpath}


def auxiliary_allowed(path, component):
    p = PurePosixPath(path)
    if p.parts[0] == "licenses" and len(p.parts) >= 2 and not path.endswith(".so"):
        return True
    if component == "firered" and path.startswith("api/kotlin/") and not path.endswith((".so", ".aar", ".class")):
        return True
    if path in ("provenance/build-plan.json", "provenance/commands.json", "provenance/gclient-revinfo.txt"):
        return True
    return path in {f"symbols/arm64-v8a/{lib}.debug" for lib in LIBRARIES[component]}


def validate_bundle(archive, lock, mode, expected_sha256=None, requested_component=None):
    z, members = inventory(archive)
    with z:
        manifest_data = z.read("manifest.json")
        manifest = json.loads(manifest_data)
        component = manifest.get("component")
        require(component in LIBRARIES, "unknown artifact component")
        require(requested_component is None or component == requested_component, "artifact component mismatch")
        record = lock["components"][component]
        artifact = record["artifact"]
        actual_archive_hash = file_sha256(archive)
        if mode == "release":
            require(artifact.get("verification") == "verified", "artifact is not verified for release")
            digests = [artifact.get("archive_sha256"), artifact.get("manifest_sha256"), artifact.get("validation_report_sha256"), *artifact["files"].values()]
            require(all(is_digest(d) for d in digests), "release digest is unknown")
            require(expected_sha256 is None, "release expected digest must come from the trusted lock")
            expected_sha256 = artifact["archive_sha256"]
        else:
            require(is_digest(expected_sha256), "dev mode requires an explicit expected SHA-256")
        require(actual_archive_hash == expected_sha256, "archive SHA-256 mismatch")
        if mode == "release":
            require(sha256(manifest_data) == artifact["manifest_sha256"], "manifest SHA-256 mismatch")
        require(manifest.get("schema_version") == 1, "unsupported bundle schema")
        require(manifest.get("source_revision") == record["source"]["revision"], "source revision mismatch")
        require(manifest.get("abi") == "arm64-v8a", "ABI mismatch")
        require(type(manifest.get("android_api")) is int and manifest["android_api"] == 27, "Android API mismatch")
        require(manifest.get("recipe") == record["build"], "build recipe mismatch")
        toolchain = manifest.get("toolchain", {})
        require(toolchain.get("host") and toolchain.get("compiler_version"), "toolchain provenance is incomplete")
        if component == "webrtc":
            require(re.fullmatch(r"[0-9a-f]{40}", toolchain.get("depot_tools_revision", "")) is not None and is_digest(toolchain.get("deps_sha256")), "WebRTC toolchain provenance is incomplete")
            locked_depot = record["build"].get("depot_tools_revision")
            require(locked_depot is None or locked_depot == toolchain["depot_tools_revision"], "depot_tools revision mismatch")
        else:
            require(toolchain.get("ndk_revision") == record["build"]["ndk_revision"], "NDK revision mismatch")
            require(toolchain.get("ort_version") == record["build"]["environment"]["SHERPA_ONNX_ONNXRUNTIME_VERSION"], "ORT version mismatch")
            require(toolchain.get("kotlin_api_revision") == record["source"]["revision"], "Kotlin API revision mismatch")
        if mode == "release":
            require(toolchain == artifact.get("toolchain"), "release toolchain provenance mismatch")
        files = manifest.get("files", {})
        native_paths = {f"jni/arm64-v8a/{name}" for name in LIBRARIES[component]}
        require(set(files) == native_paths, "manifest library whitelist mismatch")
        auxiliary = manifest.get("auxiliary", {})
        require(isinstance(auxiliary, dict) and auxiliary and any(p.startswith("licenses/") for p in auxiliary), "license inventory is missing")
        require(all(auxiliary_allowed(p, component) for p in auxiliary), "unexpected auxiliary archive member")
        if component == "firered":
            require(any(p.startswith("api/kotlin/") and p.endswith(".kt") for p in auxiliary), "matching Kotlin API inventory is missing")
        expected_members = {"manifest.json", *files, *auxiliary}
        for path in expected_members:
            require(path in members, f"missing archive member: {path}")
        require(set(members) == expected_members, "unexpected archive member")
        libraries, elf_checks = {}, {}
        for path, expected in (files | auxiliary).items():
            require(is_digest(expected), "file digest is unknown")
            data = z.read(path)
            require(sha256(data) == expected, f"file SHA-256 mismatch: {path}")
            if path in native_paths:
                name = PurePosixPath(path).name
                if mode == "release":
                    require(sha256(data) == artifact["files"][name], f"file SHA-256 mismatch against lock: {path}")
                info = elf_info(data)
                allowed = SYSTEM_LIBRARIES | set(LIBRARIES[component])
                require(set(info["needed"]) <= allowed, f"undeclared runtime dependency: {set(info['needed']) - allowed}")
                libraries[name] = data
                elf_checks[name] = info
            elif path.startswith("symbols/"):
                elf_info(data, symbols_only=True)
        receipt = {"schema_version": 1, "component": component, "mode": mode,
                   "verification": "verified" if mode == "release" else "UNVERIFIED DEVELOPMENT",
                   "archive_sha256": actual_archive_hash, "manifest_sha256": sha256(manifest_data),
                   "manifest": manifest, "elf_checks": elf_checks}
        return component, libraries, receipt


def reject_symlink_parents(path, root):
    relative = path.relative_to(root)
    current = root
    require(not root.is_symlink(), "destination symlink is forbidden")
    for part in relative.parts:
        current /= part
        require(not current.is_symlink(), f"destination symlink is forbidden: {current}")


def duplicate_ort(root):
    canonical = root / "adapter-firered/src/main/jniLibs/arm64-v8a/libonnxruntime.so"
    for module in ("adapter-webrtc", "adapter-firered", "audio-frontend", "app"):
        source = root / module / "src"
        if not source.exists():
            continue
        reject_symlink_parents(source, root)
        for libs in source.rglob("jniLibs"):
            require(not libs.is_symlink(), "native library directory symlink is forbidden")
            for path in libs.rglob("libonnxruntime.so"):
                require(path == canonical and not path.is_symlink(), f"duplicate ORT library: {path}")


def install_bundle(component, libraries, receipt, project_root, dev_output=None):
    project = Path(project_root).absolute()
    if receipt["mode"] == "dev":
        require(dev_output is not None, "dev mode requires --dev-output")
        root = Path(dev_output).absolute()
        require(not root.resolve().is_relative_to(project.resolve()), "dev output must be outside the production project")
    else:
        require(dev_output is None, "release mode cannot use --dev-output")
        root = project
    destination = root / MODULES[component] / "src/main/jniLibs/arm64-v8a"
    reject_symlink_parents(destination, root)
    # Check the production tree as well in dev mode; do not conceal an existing packaging conflict.
    duplicate_ort(project)
    if root != project:
        duplicate_ort(root)
    if destination.exists():
        require(destination.is_dir() and all(p.is_file() and not p.is_symlink() and p.name in LIBRARIES[component] for p in destination.iterdir()), "destination contains unmanaged files")
    receipt_dir = root / ".native/receipts"
    reject_symlink_parents(receipt_dir, root)
    destination.parent.mkdir(parents=True, exist_ok=True)
    receipt_dir.mkdir(parents=True, exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix=".native-stage-", dir=destination.parent))
    backup = temporary.with_name(temporary.name + "-previous")
    receipt_path = receipt_dir / (component + ".json")
    receipt_temp = receipt_dir / (temporary.name + ".json")
    reject_symlink_parents(receipt_path, root)
    replaced = False
    try:
        for name, data in libraries.items():
            (temporary / name).write_bytes(data)
        receipt_temp.write_bytes(json_bytes(receipt))
        if destination.exists():
            os.replace(destination, backup)
        try:
            os.replace(temporary, destination)
            replaced = True
            os.replace(receipt_temp, receipt_path)
        except Exception:
            if replaced:
                shutil.rmtree(destination)
            if backup.exists():
                os.replace(backup, destination)
            raise
        if backup.exists():
            shutil.rmtree(backup)
    finally:
        if temporary.exists():
            shutil.rmtree(temporary)
        receipt_temp.unlink(missing_ok=True)
    return [str(destination / name) for name in libraries]
