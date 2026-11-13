import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / 'prepare-iflytek-aikit.sh'


class PrepareIflytekAikitTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.folder = Path(self.temp.name)
        self.output = self.folder / 'output.aar'
        self.output.write_bytes(b'existing artifact')

    def archive(self, name, core, classes=b'same Java API', abi='arm64-v8a'):
        path = self.folder / (name + '.aar')
        with zipfile.ZipFile(path, 'w') as archive:
            archive.writestr('classes.jar', classes)
            archive.writestr(f'jni/{abi}/libAIKIT.so', core)
            archive.writestr(f'jni/{abi}/lib{name}_aee.so', b'plugin')
        return path

    def prepare(self, command, wake):
        return subprocess.run(['bash', str(SCRIPT), str(command), str(wake), str(self.output)],
                              capture_output=True, text=True)

    def test_different_cores_rejected_without_changing_existing_output(self):
        result = self.prepare(self.archive('command', b'command core'), self.archive('wake', b'wake core'))
        self.assertNotEqual(0, result.returncode)
        self.assertIn('libAIKIT.so', result.stderr)
        self.assertEqual(b'existing artifact', self.output.read_bytes())

    def test_identical_cores_and_java_allow_both_plugins(self):
        result = self.prepare(self.archive('command', b'combined core'), self.archive('wake', b'combined core'))
        self.assertEqual(0, result.returncode, result.stderr)
        with zipfile.ZipFile(self.output) as archive:
            self.assertEqual(b'combined core', archive.read('jni/arm64-v8a/libAIKIT.so'))
            self.assertIn('jni/arm64-v8a/libcommand_aee.so', archive.namelist())
            self.assertIn('jni/arm64-v8a/libwake_aee.so', archive.namelist())

    def test_different_abi_sets_rejected_without_changing_existing_output(self):
        result = self.prepare(self.archive('command', b'core'),
                              self.archive('wake', b'core', abi='armeabi-v7a'))
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(b'existing artifact', self.output.read_bytes())

    def test_different_java_rejected_without_changing_existing_output(self):
        result = self.prepare(self.archive('command', b'core', b'API one'),
                              self.archive('wake', b'core', b'API two'))
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(b'existing artifact', self.output.read_bytes())


if __name__ == '__main__':
    unittest.main()
