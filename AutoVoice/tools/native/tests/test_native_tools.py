"""Tiny synthetic archives exercise packaging policy, not real native/runtime capability."""
import hashlib
import json
import struct
import subprocess
import tempfile
import sys
from unittest.mock import patch
import unittest
import warnings
import zipfile
from pathlib import Path

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))
import native_artifacts
REV = {"webrtc": "8838a7c28655afbf9336063b71a51429e7d72774", "firered": "af2d8d81dbfa61c4cea4c568da78f3982edca005"}
LIBS = {"webrtc": ["libautovoice_frontend_jni.so"], "firered": ["libsherpa-onnx-jni.so", "libonnxruntime.so"]}


def digest(data):
    return hashlib.sha256(data).hexdigest()


def elf(needed=("libc.so",), machine=183):
    # Independent fixed ELF64 fixture: two program headers, dynamic table and strings.
    data = bytearray(1024)
    ident = b"\x7fELF\x02\x01\x01" + b"\0" * 9
    struct.pack_into("<16sHHIQQQIHHHHHH", data, 0, ident, 3, machine, 1, 0, 64, 0, 0, 64, 56, 2, 0, 0, 0)
    struct.pack_into("<IIQQQQQQ", data, 64, 1, 5, 0, 0, 0, len(data), len(data), 4096)
    strings = bytearray(b"\0")
    entries = []
    for lib in needed:
        entries.append((1, len(strings)))
        strings.extend(lib.encode() + b"\0")
    entries.extend([(5, 512), (10, len(strings)), (0, 0)])
    struct.pack_into("<IIQQQQQQ", data, 120, 2, 6, 256, 256, 256, len(entries) * 16, len(entries) * 16, 8)
    for i, entry in enumerate(entries):
        struct.pack_into("<qQ", data, 256 + i * 16, *entry)
    data[512:512 + len(strings)] = strings
    return bytes(data)


class NativeToolsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.project = self.root / "project"
        self.project.mkdir()
        self.dev = self.root / "dev-output"

    def invoke(self, script, *args):
        return subprocess.run(["bash", str(TOOLS / script), *map(str, args)], capture_output=True, text=True)

    def fixture(self, component="webrtc", mutate=None, extra=None, missing=None, verified=True, machine=183, needed=None):
        lock = json.loads((TOOLS / "native-lock.json").read_text())
        record = lock["components"][component]
        paths = {f"jni/arm64-v8a/{name}": elf(needed or (("libonnxruntime.so", "libc.so") if name == "libsherpa-onnx-jni.so" else ("libc.so",)), machine) for name in LIBS[component]}
        auxiliary = {"licenses/fixture-LICENSE.txt": b"Synthetic test license; not an upstream distribution.\n"}
        toolchain = {"host": "Linux x86_64", "compiler_version": "fixture compiler"}
        if component == "webrtc":
            toolchain.update(depot_tools_revision=record["build"]["depot_tools_revision"], deps_sha256=digest(b"fixture DEPS"))
        else:
            toolchain.update(ndk_revision="27.2.12479018", ort_version="1.28.2", kotlin_api_revision=REV[component])
            auxiliary["api/kotlin/Recognizer.kt"] = b"// synthetic API fixture, not sherpa implementation\n"
        manifest = {"schema_version": 1, "component": component, "source_revision": REV[component],
                    "abi": "arm64-v8a", "android_api": 26, "recipe": record["build"], "toolchain": toolchain,
                    "files": {p: digest(b) for p, b in paths.items()}, "auxiliary": {p: digest(b) for p, b in auxiliary.items()}}
        if mutate:
            mutate(manifest)
        manifest_bytes = (json.dumps(manifest, sort_keys=True, indent=2) + "\n").encode()
        archive = self.root / f"{component}.zip"
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(archive, "w") as z:
                z.writestr("manifest.json", manifest_bytes)
                for p, b in (paths | auxiliary).items():
                    if p != missing:
                        z.writestr(p, b)
                for p, b in extra or []:
                    z.writestr(p, b)
        record["artifact"] = {"verification": "verified" if verified else "unverified",
                              "archive_sha256": digest(archive.read_bytes()), "manifest_sha256": digest(manifest_bytes),
                              "files": {name: digest(paths[f"jni/arm64-v8a/{name}"]) for name in LIBS[component]},
                              "toolchain": toolchain,
                              "validation_report_sha256": digest(b"synthetic fixture validation report")}
        path = self.root / "lock.json"
        path.write_text(json.dumps(lock))
        return archive, path

    def stage(self, archive, lock, *args):
        return self.invoke("stage-native-artifacts.sh", "--archive", archive, "--lock", lock,
                           "--project-root", self.project, *args)

    def fails(self, expected, **fixture_args):
        archive, lock = self.fixture(**fixture_args)
        result = self.stage(archive, lock)
        self.assertNotEqual(0, result.returncode)
        self.assertIn(expected, result.stderr)
        self.assertEqual([], list(self.project.rglob("*.so")))

    def test_release_success_installs_only_allowlisted_libraries(self):
        archive, lock = self.fixture("firered")
        result = self.stage(archive, lock)
        self.assertEqual(0, result.returncode, result.stderr)
        installed = self.project / "adapter-firered/src/main/jniLibs/arm64-v8a"
        self.assertEqual(set(LIBS["firered"]), {p.name for p in installed.iterdir()})
        self.assertEqual([], list((self.project / "app").rglob("*.so")))
        self.assertEqual([], list(self.project.rglob("*.kt")))

    def test_missing_library_fails_before_install(self):
        self.fails("missing archive member", missing="jni/arm64-v8a/libautovoice_frontend_jni.so")

    def test_outer_archive_hash_mismatch_fails(self):
        archive, lock = self.fixture()
        archive.write_bytes(archive.read_bytes() + b"tampered")
        result = self.stage(archive, lock)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("archive SHA-256 mismatch", result.stderr)

    def test_wrong_library_hash_fails(self):
        self.fails("file SHA-256 mismatch", mutate=lambda m: m["files"].update({next(iter(m["files"])): digest(b"wrong")}))

    def test_wrong_abi_metadata_fails(self):
        self.fails("ABI mismatch", mutate=lambda m: m.update(abi="x86_64"))

    def test_wrong_elf_machine_fails(self):
        self.fails("ELF must be AArch64", machine=62)

    def test_wrong_api_and_revision_fail(self):
        self.fails("Android API mismatch", mutate=lambda m: m.update(android_api=27))
        self.fails("source revision mismatch", mutate=lambda m: m.update(source_revision="2" * 40))

    def test_path_traversal_and_absolute_names_fail(self):
        for name in ["../escape.so", "/tmp/escape.so", "jni/../escape.so", "C:\\escape.so"]:
            with self.subTest(name=name):
                self.fails("unsafe archive path", extra=[(name, b"bad")])
        self.assertFalse((self.root / "escape.so").exists())

    def test_symlink_member_fails(self):
        archive, lock = self.fixture()
        with zipfile.ZipFile(archive, "a") as z:
            info = zipfile.ZipInfo("licenses/link")
            info.create_system = 3
            info.external_attr = (0o120777 << 16)
            z.writestr(info, "../../escape")
        data = json.loads(lock.read_text());data["components"]["webrtc"]["artifact"]["archive_sha256"] = digest(archive.read_bytes());lock.write_text(json.dumps(data))
        result = self.stage(archive, lock)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("symlink", result.stderr)

    def test_duplicate_archive_member_fails(self):
        self.fails("duplicate archive member", extra=[("jni/arm64-v8a/libautovoice_frontend_jni.so", elf())])

    def test_duplicate_ort_archive_and_existing_other_adapter_fail(self):
        self.fails("duplicate ORT", component="firered", extra=[("extra/libonnxruntime.so", elf())])
        archive, lock = self.fixture("firered")
        wrong = self.project / "adapter-webrtc/src/main/jniLibs/arm64-v8a/libonnxruntime.so"
        wrong.parent.mkdir(parents=True);wrong.write_bytes(elf())
        result = self.stage(archive, lock)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("duplicate ORT", result.stderr)
        self.assertFalse((self.project / "adapter-firered/src/main/jniLibs").exists())

    def test_unknown_runtime_dependency_fails(self):
        self.fails("undeclared runtime dependency", needed=("libnot-delivered.so",))

    def test_unknown_library_is_never_installed(self):
        self.fails("unexpected archive member", extra=[("jni/arm64-v8a/libextra.so", elf())])

    def test_documented_symbols_are_verified_but_not_installed(self):
        def symbols(m):
            m["auxiliary"]["symbols/arm64-v8a/libautovoice_frontend_jni.so.debug"] = digest(elf())
        archive, lock = self.fixture(mutate=symbols, extra=[("symbols/arm64-v8a/libautovoice_frontend_jni.so.debug", elf())])
        result = self.stage(archive, lock)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([], list(self.project.rglob("*.debug")))

    def test_unknown_release_hash_and_unverified_release_are_refused(self):
        archive, lock = self.fixture(verified=False)
        result = self.stage(archive, lock)
        self.assertNotEqual(0, result.returncode);self.assertIn("not verified for release", result.stderr)
        data = json.loads(lock.read_text());data["components"]["webrtc"]["artifact"]["verification"] = "verified";data["components"]["webrtc"]["artifact"]["archive_sha256"] = None;lock.write_text(json.dumps(data))
        result = self.stage(archive, lock)
        self.assertNotEqual(0, result.returncode);self.assertIn("release digest is unknown", result.stderr)

    def test_dev_mode_is_explicit_and_separate_from_production(self):
        archive, lock = self.fixture(verified=False)
        result = self.stage(archive, lock, "--mode", "dev", "--expected-sha256", digest(archive.read_bytes()), "--dev-output", self.dev)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("UNVERIFIED DEVELOPMENT", result.stdout)
        self.assertEqual([], list(self.project.rglob("*.so")))
        self.assertTrue((self.dev / "adapter-webrtc/src/main/jniLibs/arm64-v8a/libautovoice_frontend_jni.so").is_file())
        forbidden = self.stage(archive, lock, "--mode", "dev", "--expected-sha256", digest(archive.read_bytes()), "--dev-output", self.project)
        self.assertNotEqual(0, forbidden.returncode);self.assertIn("dev output must be outside", forbidden.stderr)

    def test_destination_symlink_cannot_escape_project(self):
        archive, lock = self.fixture()
        outside = self.root / "outside";outside.mkdir()
        (self.project / "adapter-webrtc").symlink_to(outside, target_is_directory=True)
        result = self.stage(archive, lock)
        self.assertNotEqual(0, result.returncode);self.assertIn("destination symlink", result.stderr)
        self.assertEqual([], list(outside.iterdir()))

    def test_failed_directory_replacement_restores_previous_libraries(self):
        archive, lock_path = self.fixture()
        component, libraries, receipt = native_artifacts.validate_bundle(
            archive, native_artifacts.load_lock(lock_path), "release")
        target = self.project / "adapter-webrtc/src/main/jniLibs/arm64-v8a"
        target.mkdir(parents=True)
        previous = target / LIBS["webrtc"][0]
        previous.write_bytes(b"previous installed library fixture")
        original_replace = native_artifacts.os.replace

        def fail_install(source, destination):
            if Path(destination) == target and not str(source).endswith("-previous"):
                raise OSError("simulated directory replacement failure")
            return original_replace(source, destination)

        with patch.object(native_artifacts.os, "replace", side_effect=fail_install):
            with self.assertRaisesRegex(OSError, "simulated directory"):
                native_artifacts.install_bundle(component, libraries, receipt, self.project)
        self.assertEqual(b"previous installed library fixture", previous.read_bytes())
        self.assertEqual(["arm64-v8a"], [p.name for p in target.parent.iterdir()])

    def test_default_build_plans_have_no_filesystem_or_network_side_effects(self):
        for component in ["webrtc", "firered"]:
            before = set(self.root.rglob("*"))
            result = self.invoke(f"build-{component}-android.sh", "--source-dir", self.root / component, "--output", self.root / "artifacts")
            self.assertEqual(0, result.returncode, result.stderr)
            plan = json.loads(result.stdout)
            self.assertEqual(REV[component], plan["source_revision"])
            self.assertFalse(plan["executed"])
            self.assertEqual(before, set(self.root.rglob("*")))
            if component == "webrtc":
                self.assertIn("--fail-on-unused-args", json.dumps(plan))
                self.assertIn("//autovoice_frontend:autovoice_frontend_jni", json.dumps(plan))
            else:
                self.assertEqual("OFF", plan["environment"]["SHERPA_ONNX_ENABLE_QNN"])
                self.assertEqual("1.28.2", plan["environment"]["SHERPA_ONNX_ONNXRUNTIME_VERSION"])
                self.assertEqual("android-26", plan["environment"]["SHERPA_ONNX_ANDROID_PLATFORM"])


if __name__ == "__main__":
    unittest.main()
