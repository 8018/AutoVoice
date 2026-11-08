#!/usr/bin/env python3
"""Default to side-effect-free plans. Native execution is explicit and never installs tools."""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import struct
import sys
import zipfile

from native_artifacts import (LIBRARIES, NativeError, elf_info, file_sha256, install_bundle,
                              json_bytes, load_lock, require, safe_name, sha256, validate_bundle)

HERE = Path(__file__).resolve().parent
PROJECT = HERE.parents[1]


def host():
    machine = {"AMD64": "x86_64", "aarch64": "arm64"}.get(platform.machine(), platform.machine())
    return platform.system() + " " + machine


def command_output(argv, cwd=None, env=None):
    result = subprocess.run(list(map(str, argv)), cwd=cwd, env=env, capture_output=True, text=True)
    require(result.returncode == 0, f"command failed: {argv[0]}: {result.stderr.strip()}")
    return result.stdout.strip()


def git_revision(path):
    return command_output(["git", "rev-parse", "HEAD"], path)


def clean_checkout(path, revision):
    require(Path(path).is_dir(), f"source checkout is missing: {path}; --fetch is explicit")
    require(git_revision(path) == revision, "source checkout revision mismatch")
    require(not command_output(["git", "status", "--porcelain", "--untracked-files=all"], path), "source checkout is dirty")


def source_files(root):
    require(root.is_dir(), f"required source directory is missing: {root}")
    files = {}
    for path in sorted(root.rglob("*")):
        require(not path.is_symlink(), f"source symlink is forbidden: {path}")
        if path.is_file():
            relative = path.relative_to(root).as_posix()
            safe_name(relative)
            files[relative] = path.read_bytes()
    require(files, f"source directory is empty: {root}")
    return files


def tree_digest(files):
    return sha256(json_bytes({p: sha256(b) for p, b in files.items()}))


def tool_digest():
    paths = [HERE / "native_tools.py", HERE / "native_artifacts.py", *sorted(HERE.glob("*.sh"))]
    return tree_digest({p.name: p.read_bytes() for p in paths})


def plan_build(args, lock):
    component = args.command.removeprefix("build-")
    record = lock["components"][component]
    source = Path(args.source_dir or PROJECT / ".native/checkouts" / component / ("src" if component == "webrtc" else "sherpa-onnx")).absolute()
    output = Path(args.output or PROJECT / ".native/artifacts" / component).absolute()
    plan = {"component": component, "source_revision": record["source"]["revision"],
            "source_dir": str(source), "output": str(output), "executed": False,
            "fetch_requested": args.fetch, "verification": "unverified", "recipe": record["build"]}
    if component == "webrtc":
        depot = Path(args.depot_tools or PROJECT / ".native/depot_tools").absolute()
        native = Path(args.project_native_dir or PROJECT / "adapter-webrtc/native").absolute()
        patch = Path(args.gn_patch or native / "webrtc-root.patch").absolute()
        out = source / "out/AutoVoiceArm64"
        gn_args = "\n".join(f"{k}={json.dumps(v)}" for k, v in record["build"]["gn_args"].items())
        plan.update(depot_tools=str(depot), depot_tools_revision=args.depot_tools_revision or record["build"]["depot_tools_revision"],
                    project_native_dir=str(native), gn_patch=str(patch), build_dir=str(out),
                    checkout_commands=[[str(depot / "fetch"), "--nohooks", "webrtc_android"],
                                       [str(depot / "gclient"), "sync", "--revision", "src@" + record["source"]["revision"]],
                                       [str(depot / "gclient"), "runhooks"]],
                    commands=[[str(depot / "gn"), "gen", str(out), "--check", "--fail-on-unused-args", "--args=" + gn_args],
                              [str(depot / "gn"), "check", str(out), record["build"]["gn_target"]],
                              [str(depot / "autoninja"), "-C", str(out), "autovoice_frontend_jni"],
                              [str(depot / "gn"), "desc", str(out), record["build"]["gn_target"], "outputs"]])
    else:
        ndk = args.ndk or os.environ.get("ANDROID_NDK")
        plan.update(ndk=str(Path(ndk).absolute()) if ndk else None,
                    environment=record["build"]["environment"], build_dir=str(source / "build-android-arm64-v8a"),
                    checkout_commands=[["git", "init", str(source)],
                                       ["git", "-C", str(source), "remote", "add", "origin", record["source"]["url"]],
                                       ["git", "-C", str(source), "fetch", "--depth", "1", "origin", record["source"]["revision"]],
                                       ["git", "-C", str(source), "checkout", "--detach", "FETCH_HEAD"]],
                    commands=[["bash", str(source / record["build"]["upstream_script"])]])
    return plan


class Runner:
    def __init__(self, output):
        self.output = output
        self.commands = []

    def run(self, argv, cwd, env):
        argv = list(map(str, argv))
        with (self.output / "build.log").open("ab") as log:
            log.write(("\nCOMMAND " + json.dumps(argv) + "\n").encode())
            process = subprocess.Popen(argv, cwd=cwd, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
            captured = bytearray()
            for line in iter(process.stdout.readline, b""):
                log.write(line)
                log.flush()
                if len(captured) < 4 * 1024 * 1024:
                    captured.extend(line)
            process.stdout.close()
            result = process.wait()
        self.commands.append({"argv": argv, "cwd": str(cwd), "returncode": result})
        require(result == 0, f"build command failed ({result}); see {self.output / 'build.log'}")
        return captured.decode(errors="replace").strip()


def check_tools(names, env=None):
    for name in names:
        require(shutil.which(name, path=(env or os.environ).get("PATH")) is not None, f"required tool is unavailable: {name}; no tools are installed automatically")


def prepare_output(output, plan):
    require(not output.exists(), "output already exists; use a new isolated artifact directory")
    output.mkdir(parents=True)
    (output / "build-plan.json").write_bytes(json_bytes(plan))


def create_package(component, lock, plan, libraries, auxiliary, toolchain, output, runner):
    for lib, data in libraries.items():
        elf_info(data)
        require(lib in LIBRARIES[component], "unexpected generated library")
    require(set(libraries) == set(LIBRARIES[component]), "required build libraries are missing")
    auxiliary["provenance/build-plan.json"] = json_bytes(plan)
    auxiliary["provenance/commands.json"] = json_bytes(runner.commands)
    files = {f"jni/arm64-v8a/{name}": data for name, data in libraries.items()}
    manifest = {"schema_version": 1, "component": component, "source_revision": plan["source_revision"],
                "abi": "arm64-v8a", "android_api": 27, "recipe": lock["components"][component]["build"],
                "toolchain": toolchain, "files": {p: sha256(b) for p, b in files.items()},
                "auxiliary": {p: sha256(b) for p, b in auxiliary.items()}}
    archive = output / (component + "-android-arm64-v8a-unverified.zip")
    with zipfile.ZipFile(archive, "x", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for name, data in sorted(({"manifest.json": json_bytes(manifest)} | files | auxiliary).items()):
            safe_name(name)
            entry = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            entry.create_system = 3
            entry.external_attr = (0o100644 << 16)
            z.writestr(entry, data, compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)
    # Build success still does not certify a release. Run the same package validator in dev mode.
    _, _, receipt = validate_bundle(archive, lock, "dev", file_sha256(archive), component)
    (output / "artifact-manifest.json").write_bytes(json_bytes(manifest))
    (output / "observed-checksums.json").write_bytes(json_bytes({"verification": "unverified", "archive_sha256": file_sha256(archive),
        "manifest_sha256": sha256(json_bytes(manifest)), "files": {p: sha256(b) for p, b in libraries.items()}}))
    return {"archive": str(archive), "archive_sha256": receipt["archive_sha256"], "verification": "UNVERIFIED DEVELOPMENT"}


def select_webrtc_runtime_output(outputs, source, build_dir):
    source, build_dir = Path(source).resolve(), Path(build_dir).resolve()
    name = "libautovoice_frontend_jni.so"
    runtime = []
    for line in outputs.splitlines():
        line = line.strip()
        if not line.endswith("/" + name):
            continue
        path = source / line.removeprefix("//") if line.startswith("//") else Path(line)
        if not path.is_absolute():
            path = source / path
        path = path.resolve()
        require(path.is_relative_to(build_dir), "GN output escapes build directory")
        if path == build_dir / "lib.unstripped" / name:
            require(path.is_file(), "GN symbol output is missing")
            continue
        require(path == build_dir / name, "unexpected GN runtime output path")
        runtime.append(path)
    require(len(runtime) == 1 and runtime[0].is_file(), "GN did not report exactly one runtime JNI output")
    return runtime[0]


def execute_build(args, lock, plan):
    component = plan["component"]
    source, output = Path(plan["source_dir"]), Path(plan["output"])
    require(host() in (["Linux x86_64"] if component == "webrtc" else lock["components"][component]["build"]["hosts"]),
            f"unsupported build host {host()}; {component} requires " + ("Linux x86_64" if component == "webrtc" else "a supported Linux/macOS host"))
    require(args.licenses_dir, "--licenses-dir is required: supply the reviewed dependency license inventory")
    license_files = source_files(Path(args.licenses_dir))
    auxiliary = {"licenses/dependencies/" + p: b for p, b in license_files.items()}
    env = os.environ.copy()
    for key in list(env):
        if key.startswith(("SHERPA_", "CMAKE_")) or key in ("CC", "CXX", "CFLAGS", "CXXFLAGS", "LDFLAGS", "BUILD_SHARED_LIBS", "ANDROID_NDK"):
            env.pop(key)
    if component == "webrtc":
        depot = Path(plan["depot_tools"])
        expected_depot = plan["depot_tools_revision"]
        require(isinstance(expected_depot, str) and re.fullmatch(r"[0-9a-f]{40}", expected_depot) is not None,
                "an explicit pinned depot_tools revision is required for this unverified candidate")
        locked_depot = lock["components"][component]["build"].get("depot_tools_revision")
        require(locked_depot is None or locked_depot == expected_depot, "depot_tools override differs from the lock")
        clean_checkout(depot, expected_depot)
        for name in ("fetch", "gclient", "gn", "autoninja"):
            require((depot / name).is_file(), f"depot_tools entry is missing: {name}")
        env["PATH"] = str(depot) + os.pathsep + env.get("PATH", "")
        env["DEPOT_TOOLS_UPDATE"] = "0"
        env["DEPOT_TOOLS_METRICS"] = "0"
        native = Path(plan["project_native_dir"])
        project_sources = source_files(native)
        for required in ("BUILD.gn", "frontend_jni.cc", "frontend_session.cc", "frontend_session.h"):
            require(required in project_sources, f"Task 4 project native source is missing: {required}")
        patch = Path(plan["gn_patch"])
        require(patch.is_file() and not patch.is_symlink(), "Task 4 root GN patch is missing")
        require((source / "autovoice_frontend").exists() is False, "checkout already has an autovoice_frontend directory")
        check_tools(["git", "python3"], env)
    else:
        require(plan["ndk"], "--ndk or ANDROID_NDK must explicitly select NDK " + lock["components"][component]["build"]["ndk_revision"])
        ndk = Path(plan["ndk"])
        props = ndk / "source.properties"
        require(props.is_file(), "NDK source.properties is missing")
        revision = re.search(r"^Pkg.Revision\s*=\s*(\S+)", props.read_text(), re.MULTILINE)
        require(revision and revision.group(1) == lock["components"][component]["build"]["ndk_revision"], "NDK revision mismatch")
        env.update(plan["environment"])
        env["ANDROID_NDK"] = str(ndk)
        check_tools(["git", "bash", "cmake", "make", "wget", "unzip", "java"], env)
    if args.fetch:
        require(not source.exists() or not any(source.iterdir()), "--fetch requires a new empty dedicated checkout")
        if component == "webrtc":
            require(source.name == "src" and (not source.parent.exists() or not any(source.parent.iterdir())),
                    "WebRTC --fetch requires an empty checkout parent and source named src")
    else:
        clean_checkout(source, plan["source_revision"])
    require(not Path(plan["build_dir"]).exists(), "native build directory already exists; use a clean dedicated checkout")
    prepare_output(output, plan)
    runner = Runner(output)
    original_root = patched_root = None
    copied_native = None
    result = {"component": component, "executed": True, "host": host(), "success": False, "verification": "unverified"}
    try:
        if args.fetch:
            source.parent.mkdir(parents=True, exist_ok=True)
            for command in plan["checkout_commands"]:
                runner.run(command, source.parent, env)
        clean_checkout(source, plan["source_revision"])
        root_license = source / "LICENSE"
        require(root_license.is_file(), "upstream root LICENSE is missing")
        auxiliary["licenses/" + component + "-LICENSE"] = root_license.read_bytes()
        toolchain = {"host": host(), "build_tools_sha256": tool_digest(),
                     "python_version": platform.python_version(),
                     "git_version": runner.run(["git", "--version"], source, env).splitlines()[0]}
        if component == "webrtc":
            root_gn = source / "BUILD.gn"
            require(root_gn.is_file() and (source / "DEPS").is_file(), "WebRTC BUILD.gn/DEPS is missing")
            original_root = root_gn.read_bytes()
            runner.run(["git", "apply", "--check", plan["gn_patch"]], source, env)
            runner.run(["git", "apply", plan["gn_patch"]], source, env)
            patched_root = root_gn.read_bytes()
            copied_native = source / "autovoice_frontend"
            copied_native.mkdir()
            copied_files = {p: b for p, b in project_sources.items() if not p.endswith(".patch")}
            for p, data in copied_files.items():
                path = copied_native / p
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(data)
            for command in plan["commands"][:-1]:
                runner.run(command, source, env)
            outputs = runner.run(plan["commands"][-1], source, env)
            runtime = select_webrtc_runtime_output(outputs, source, Path(plan["build_dir"]))
            libraries = {LIBRARIES[component][0]: runtime.read_bytes()}
            symbols = Path(plan["build_dir"]) / "lib.unstripped" / LIBRARIES[component][0]
            if symbols.is_file():
                auxiliary["symbols/arm64-v8a/" + LIBRARIES[component][0] + ".debug"] = symbols.read_bytes()
            revinfo = runner.run([str(Path(plan["depot_tools"]) / "gclient"), "revinfo", "--actual"], source.parent, env)
            auxiliary["provenance/gclient-revinfo.txt"] = (revinfo + "\n").encode()
            toolchain["gn_version"] = runner.run([str(Path(plan["depot_tools"]) / "gn"), "--version"], source, env).splitlines()[0]
            compiler = source / "third_party/llvm-build/Release+Asserts/bin/clang"
            require(compiler.is_file(), "DEPS-managed clang is missing")
            toolchain.update(depot_tools_revision=git_revision(Path(plan["depot_tools"])), deps_sha256=file_sha256(source / "DEPS"),
                             compiler_version=runner.run([str(compiler), "--version"], source, env).splitlines()[0],
                             project_sources_sha256=tree_digest(project_sources), project_patch_sha256=file_sha256(Path(plan["gn_patch"])))
        else:
            toolchain["cmake_version"] = runner.run(["cmake", "--version"], source, env).splitlines()[0]
            toolchain["make_version"] = runner.run(["make", "--version"], source, env).splitlines()[0]
            toolchain["java_version"] = runner.run(["java", "-version"], source, env).splitlines()[0]
            runner.run(plan["commands"][0], source, env)
            clean_checkout(source, plan["source_revision"])
            installed = source / "build-android-arm64-v8a/install/lib"
            libraries = {}
            for name in LIBRARIES[component]:
                path = installed / name
                require(path.is_file(), f"required build library is missing: {path}")
                libraries[name] = path.read_bytes()
            api = source_files(source / "sherpa-onnx/kotlin-api")
            auxiliary.update({"api/kotlin/" + p: b for p, b in api.items()})
            tag = "linux-x86_64" if platform.system() == "Linux" else "darwin-x86_64"
            clang = Path(plan["ndk"]) / "toolchains/llvm/prebuilt" / tag / "bin/clang"
            require(clang.is_file(), "NDK clang is missing")
            toolchain.update(ndk_revision=lock["components"][component]["build"]["ndk_revision"],
                             ort_version=plan["environment"]["SHERPA_ONNX_ONNXRUNTIME_VERSION"],
                             kotlin_api_revision=plan["source_revision"], kotlin_api_sha256=tree_digest(api),
                             compiler_version=runner.run([str(clang), "--version"], source, env).splitlines()[0],
                             upstream_script_sha256=file_sha256(source / lock["components"][component]["build"]["upstream_script"]))
        executed_plan = dict(plan, executed=True)
        result.update(create_package(component, lock, executed_plan, libraries, auxiliary, toolchain, output, runner), success=True)
        return result
    except Exception as error:
        result["error"] = str(error)
        raise
    finally:
        # Restore only the exact files this invocation owned; never reset a checkout or discard changes.
        if original_root is not None and patched_root is not None:
            root_gn = source / "BUILD.gn"
            if root_gn.read_bytes() == patched_root:
                root_gn.write_bytes(original_root)
        if copied_native is not None and copied_native.exists():
            if source_files(copied_native) == copied_files:
                shutil.rmtree(copied_native)
        (output / "commands.json").write_bytes(json_bytes(runner.commands))
        result["finished_at_utc"] = datetime.now(timezone.utc).isoformat()
        (output / "result.json").write_bytes(json_bytes(result))


def parser():
    p = argparse.ArgumentParser(description=__doc__)
    commands = p.add_subparsers(dest="command", required=True)
    for component in ("webrtc", "firered"):
        build = commands.add_parser("build-" + component, help="print a plan by default; --execute builds an unverified bundle")
        build.add_argument("--lock", default=str(HERE / "native-lock.json"))
        choice = build.add_mutually_exclusive_group()
        choice.add_argument("--plan", action="store_true")
        choice.add_argument("--execute", action="store_true")
        build.add_argument("--fetch", action="store_true", help="explicitly create the pinned upstream checkout")
        for option in ("source-dir", "output", "licenses-dir"):
            build.add_argument("--" + option)
        if component == "webrtc":
            for option in ("depot-tools", "depot-tools-revision", "project-native-dir", "gn-patch"):
                build.add_argument("--" + option)
        else:
            build.add_argument("--ndk")
    stage = commands.add_parser("stage", help="verify a native ZIP and install only component libraries")
    stage.add_argument("--archive", required=True)
    stage.add_argument("--component", choices=LIBRARIES)
    stage.add_argument("--lock", default=str(HERE / "native-lock.json"))
    stage.add_argument("--mode", choices=("release", "dev"), default="release")
    stage.add_argument("--project-root", default=str(PROJECT))
    stage.add_argument("--expected-sha256")
    stage.add_argument("--dev-output")
    return p


def main():
    args = parser().parse_args()
    try:
        lock = load_lock(args.lock)
        if args.command == "stage":
            component, libraries, receipt = validate_bundle(args.archive, lock, args.mode, args.expected_sha256, args.component)
            installed = install_bundle(component, libraries, receipt, args.project_root, args.dev_output)
            print(json.dumps({"component": component, "verification": receipt["verification"], "installed": installed}, indent=2))
        else:
            plan = plan_build(args, lock)
            print(json.dumps(execute_build(args, lock, plan) if args.execute else plan, indent=2))
        return 0
    except (NativeError, OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile, struct.error) as error:
        print("native tooling: " + str(error), file=sys.stderr)
        return 2



if __name__ == "__main__":
    raise SystemExit(main())
