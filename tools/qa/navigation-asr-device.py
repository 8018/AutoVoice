#!/usr/bin/env python3
"""Run the fixed public ASR benchmark against an already installed Open test APK."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import subprocess

REPO = Path(__file__).resolve().parents[2]
REMOTE = '/sdcard/Android/data/com.autovoice.app.open/files/asr-comparison'
RUNNER = 'com.autovoice.app.open.test/androidx.test.runner.AndroidJUnitRunner'
TEST = 'com.autovoice.app.asr.OpenAsrComparisonTest#comparePublicFixtures'


def sha(file):
    with file.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--serial', required=True)
    parser.add_argument('--pipeline', choices=['raw', 'silero'], default='raw')
    parser.add_argument('--flush-ms', type=int, choices=[500, 1000, 1500], default=500)
    parser.add_argument('--assets', type=Path, required=True)
    parser.add_argument('--test-apk', type=Path, required=True)
    parser.add_argument('--output-dir', type=Path, default=REPO / 'docs/qa/navigation-asr-runs')
    args = parser.parse_args()
    root = args.assets.resolve()
    expected_apk = sha(args.test_apk)
    models_file, suite_file = root / 'models.json', root / 'suite.json'
    for actual, canonical in [(models_file, 'models'), (suite_file, 'fixtures')]:
        if sha(actual) != sha(REPO / f'docs/qa/navigation-asr-{canonical}-v1.json'):
            raise ValueError('Provisioned manifest differs from approved v1: ' + actual.name)
    models, suite = json.loads(models_file.read_text()), json.loads(suite_file.read_text())
    manifest_names = ['models.json', 'suite.json']
    assets = [a for m in models['models'] for a in m['files'].values()] + [c['audio'] for c in suite['cases']]
    if args.pipeline == 'silero':
        vad_file = root / 'vad.json'
        if sha(vad_file) != sha(REPO / 'docs/qa/navigation-asr-vad-v1.json'):
            raise ValueError('VAD manifest differs from approved v1')
        vad = json.loads(vad_file.read_text())
        assets += [vad['asset'], vad['licenseAsset']]
        manifest_names.append('vad.json')
    for a in assets:
        if not re.fullmatch(r'[A-Za-z0-9_./-]+', a['path']) or '..' in a['path'].split('/'):
            raise ValueError('Unsafe asset path')
        local = root / a['path']
        if root not in local.resolve().parents or local.stat().st_size != a['bytes'] or sha(local) != a['sha256']:
            raise ValueError('Asset verification failed: ' + a['path'])
    adb = [args.adb, '-s', args.serial]
    def call(*command, timeout=120):
        return subprocess.run([*adb, *command], check=True, timeout=timeout, capture_output=True, text=True).stdout
    if call('get-state').strip() != 'device':
        raise RuntimeError('Device not ready')
    call('shell', 'mkdir', '-p', REMOTE)
    # Sequential operations: no installs or reconnects while transferring data.
    for a in assets:
        remote = REMOTE + '/' + a['path']
        call('shell', 'mkdir', '-p', remote.rsplit('/', 1)[0])
        try:
            existing = call('shell', 'sha256sum', remote, timeout=60).split()[0]
        except (subprocess.CalledProcessError, IndexError):
            existing = None
        if existing != a['sha256']:
            call('push', str(root / a['path']), remote, timeout=900)
        print('Provisioned ' + a['path'], flush=True)
    for name in manifest_names:
        call('push', str(root / name), REMOTE + '/' + name)
    # Stop any prior test process before clearing its checkpoint or starting another run.
    call('shell', 'am', 'force-stop', 'com.autovoice.app.open')
    # An old successful report must not survive a native crash or disconnected new run.
    call('shell', 'rm', '-f', REMOTE + '/comparison-results.json', REMOTE + '/comparison-results.json.bak')
    stamp = datetime.now(timezone.utc).isoformat(timespec='milliseconds').replace(':', '-').replace('+00-00', 'Z')
    args.output_dir.mkdir(parents=True, exist_ok=True)
    raw_file = args.output_dir / (stamp + '-raw.json')
    scored_file = args.output_dir / (stamp + '-score.json')
    if raw_file.exists() or scored_file.exists():
        raise FileExistsError('Result timestamp already exists')
    log_file = args.output_dir / (stamp + '-instrumentation.txt')
    try:
        execution = subprocess.run([*adb, 'shell', 'am', 'instrument', '-w', '-e', 'class', TEST, '-e', 'asrPipeline', args.pipeline, '-e', 'zipformerFlushMs', str(args.flush_ms), RUNNER], timeout=600, capture_output=True, text=True)
    except subprocess.TimeoutExpired as failure:
        def text(value):
            return value.decode('utf-8', errors='replace') if isinstance(value, bytes) else (value or '')
        log_file.write_text((text(failure.stdout) + text(failure.stderr)).rstrip() + '\n')
        state = {'status': 'instrumentation-timeout', 'remoteStopped': False, 'checkpointPulled': False}
        try:
            call('shell', 'am', 'force-stop', 'com.autovoice.app.open', timeout=20)
            state['remoteStopped'] = True
        except (subprocess.SubprocessError, OSError):
            pass
        try:
            call('pull', REMOTE + '/comparison-results.json', str(raw_file), timeout=20)
            state['checkpointPulled'] = True
        except (subprocess.SubprocessError, OSError):
            pass
        (args.output_dir / (stamp + '-failure.json')).write_text(json.dumps(state, indent=2) + '\n')
        raise RuntimeError('Instrumentation timed out; evidence retained, next run must stop old target first') from failure
    log_file.write_text((execution.stdout + execution.stderr).rstrip() + '\n')
    try:
        call('pull', REMOTE + '/comparison-results.json', str(raw_file))
    except (subprocess.SubprocessError, OSError) as failure:
        (args.output_dir / (stamp + '-failure.json')).write_text(json.dumps({
            'status': 'checkpoint-unavailable', 'instrumentationExitCode': execution.returncode,
            'errorType': type(failure).__name__, 'passed': False,
            'note': 'No local complete result; remote execution status must not be assumed. Next run stops the old target first.'
        }, indent=2) + '\n')
        raise
    raw = json.loads(raw_file.read_text())
    if raw.get('runtime', {}).get('instrumentationApkSha256') != expected_apk:
        raise RuntimeError('Executed test APK differs from --test-apk; raw result retained')
    if raw.get('runtime', {}).get('pipeline', 'raw') != args.pipeline or raw.get('runtime', {}).get('zipformerFlushSilenceMs') != args.flush_ms:
        raise RuntimeError('Executed pipeline/config differs from requested parameters; raw result retained')
    if execution.returncode:
        raise RuntimeError('Instrumentation failed; raw result retained')
    subprocess.run(['node', str(REPO / 'tools/qa/navigation-asr-score.mjs'), str(root), str(raw_file), str(scored_file)], cwd=REPO, check=True)


if __name__ == '__main__':
    main()
