#!/usr/bin/env python3
"""Provision immutable public fixtures and checksum-pinned models outside the repository."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys

REPO = Path(__file__).resolve().parents[2]


def digest(file):
    with file.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def verified(file, asset):
    return file.is_file() and file.stat().st_size == asset['bytes'] and digest(file) == asset['sha256']


def provision(output):
    root = output.resolve()
    if root == REPO or REPO in root.parents:
        raise ValueError('Keep downloaded models outside the repository')
    root.mkdir(parents=True, exist_ok=True)
    models_file = REPO / 'docs/qa/navigation-asr-models-v1.json'
    suite_file = REPO / 'docs/qa/navigation-asr-fixtures-v1.json'
    models, suite = json.loads(models_file.read_text()), json.loads(suite_file.read_text())
    vad_file = REPO / 'docs/qa/navigation-asr-vad-v1.json'
    vad = json.loads(vad_file.read_text())
    items = [(entry, None) for model in models['models'] for entry in model['files'].values()]
    items += [(vad[key], None) for key in ['asset', 'licenseAsset']]
    items += [(case['audio'], REPO / 'tools/qa/fixtures/navigation-asr-v1' / case['audio']['path']) for case in suite['cases']]
    for asset, source in items:
        destination = root / asset['path']
        if root not in destination.resolve().parents:
            raise ValueError('Unsafe asset path')
        destination.parent.mkdir(parents=True, exist_ok=True)
        if verified(destination, asset):
            continue
        partial = destination.with_name(destination.name + '.partial')
        if source or asset.get('repositoryFile'):
            source = source or REPO / asset['repositoryFile']
            shutil.copyfile(source, partial)
        else:
            if not asset['url'].startswith('https://huggingface.co/'):
                raise ValueError('Unexpected model source')
            subprocess.run(['curl', '-fL', '--retry', '2', '--max-time', '900', '-o', str(partial), asset['url']], check=True)
        if not verified(partial, asset):
            raise ValueError('Asset verification failed: ' + asset['path'])
        partial.replace(destination)
        print('Verified ' + asset['path'], flush=True)
    shutil.copyfile(models_file, root / 'models.json')
    shutil.copyfile(suite_file, root / 'suite.json')
    shutil.copyfile(vad_file, root / 'vad.json')
    print('Ready: ' + str(root))


if __name__ == '__main__':
    if len(sys.argv) != 2:
        raise SystemExit('Usage: python3 tools/qa/navigation-asr-provision.py /absolute/output/directory')
    if not Path(sys.argv[1]).is_absolute():
        raise SystemExit('Output directory must be absolute')
    provision(Path(sys.argv[1]))
