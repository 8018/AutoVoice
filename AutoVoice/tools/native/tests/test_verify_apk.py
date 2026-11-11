import sys
import tempfile
import unittest
import warnings
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from verify_apk import verify


class ApkVerificationTest(unittest.TestCase):
    common = ["libandroidx.graphics.path.so"]
    vendor = ["libAIKIT.so", "libeabb2f029_v10170_aee.so", "libef7d69542_v10260_aee.so"]
    legacy = ["libonnxruntime.so", "libonnxruntime4j_jni.so", "librnnoise_jni.so"]
    opened = ["libonnxruntime.so", "libautovoice_frontend_jni.so", "libsherpa-onnx-jni.so"]
    assets = ["cloud-test.pcm", "demo-dev.json", "demo-full.json", "demo-offline.json"]
    elf = b"\x7fELF\x02\x01" + bytes(12) + b"\xb7\x00"

    def archive(self, libraries, assets, extras=()):
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        apk = Path(folder.name) / "app.apk"
        with warnings.catch_warnings(), zipfile.ZipFile(apk, "w") as archive:
            warnings.simplefilter("ignore", UserWarning)
            for name in libraries:
                archive.writestr(f"lib/arm64-v8a/{name}", self.elf)
            for name in assets:
                archive.writestr(f"assets/{name}", b"fixture")
            for name, content in extras:
                archive.writestr(name, content)
        return apk

    def test_real_and_compile_only_profiles(self):
        for profile, libraries, assets in [
            ("open-real", self.common + self.vendor + self.opened, self.assets + ["supplierconfig.json"]),
            ("iflytek-real", self.common + self.vendor + self.legacy, self.assets + ["supplierconfig.json", "silero_vad.onnx"]),
            ("open-ci", self.common, self.assets),
            ("iflytek-ci", self.common + self.legacy, self.assets + ["silero_vad.onnx"]),
        ]:
            with self.subTest(profile=profile):
                self.assertEqual([], verify(self.archive(libraries, assets), profile))
        self.assertTrue(verify(self.archive(self.common, self.assets), "open-real"))

    def test_rejects_duplicate_ort_even_with_identical_member_name(self):
        apk = self.archive(self.common + self.vendor + self.opened + ["libonnxruntime.so"],
                           self.assets + ["supplierconfig.json"])
        self.assertTrue(any("duplicate" in issue for issue in verify(apk, "open-real")))

    def test_rejects_wrong_channel_abi_and_embedded_fixtures(self):
        for extra in [
            ("lib/arm64-v8a/librnnoise_jni.so", self.elf),
            ("lib/x86_64/libonnxruntime.so", self.elf),
            ("assets/silero_vad.onnx", b"model"),
            ("assets/playback/command-24k-mono.wav", b"audio"),
            ("assets/synthetic-open-window-once.pcm", b"audio"),
            ("model.int8.onnx", b"model"),
        ]:
            with self.subTest(extra=extra[0]):
                apk = self.archive(self.common + self.vendor + self.opened,
                                   self.assets + ["supplierconfig.json"], [extra])
                self.assertTrue(verify(apk, "open-real"))

    def test_arm64_path_cannot_hide_wrong_elf_abi(self):
        apk = self.archive([], self.assets,
                           [("lib/arm64-v8a/libandroidx.graphics.path.so", self.elf[:-2] + b"\x3e\x00")])
        self.assertTrue(any("ELF" in issue for issue in verify(apk, "open-ci")))
