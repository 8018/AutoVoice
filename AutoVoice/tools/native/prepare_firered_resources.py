#!/usr/bin/env python3
"""Build a fixed, versioned external model resource directory. Offline, no runtime downloads.
Catalog comes from the same packaged adapter policy; external manifests cannot select hashes.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import tempfile

CATALOG = Path(__file__).resolve().parents[2]/'adapter-firered/src/main/resources/firered-model-catalog.json'

def prepare(model_source, attribution_source, output_root, catalog):
    output_root = Path(output_root).resolve()
    parent = output_root/catalog['modelId']
    target = parent/catalog['exporterRevision']
    if target.exists():
        raise ValueError('versioned resource destination already exists; use a new independent output root')
    parent.mkdir(parents=True,exist_ok=True)
    stage = Path(tempfile.mkdtemp(prefix='.preparing-',dir=parent))
    try:
        for name,entry in catalog['files'].items():
            source = Path(attribution_source)/name.split('/')[-1] if name.startswith('licenses/') else Path(model_source)/name
            if source.is_symlink() or not source.is_file():
                raise ValueError('fixed resource missing: '+name)
            destination = stage/name
            destination.parent.mkdir(parents=True,exist_ok=True)
            digest = hashlib.sha256();size = 0
            with source.open('rb') as src, destination.open('xb') as dst:
                while True:
                    block = src.read(1024*1024)
                    if not block: break
                    dst.write(block);digest.update(block);size += len(block)
            if size != entry['size'] or digest.hexdigest() != entry['sha256']:
                raise ValueError('fixed resource digest mismatch: '+name)
        (stage/'manifest.json').write_text(json.dumps(catalog,indent=2)+'\n')
        if target.exists(): raise ValueError('versioned resource destination already exists')
        os.rename(stage,target)
        return target
    finally:
        if stage.exists(): shutil.rmtree(stage)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--model-source',type=Path,required=True)
    parser.add_argument('--attribution-source',type=Path,required=True)
    parser.add_argument('--output-root',type=Path,required=True)
    args = parser.parse_args()
    project = CATALOG.parents[4].resolve()
    if args.output_root.resolve().is_relative_to(project):
        parser.error('external resource output must be outside the production project')
    print(prepare(args.model_source,args.attribution_source,args.output_root,json.loads(CATALOG.read_text())))

if __name__ == '__main__': main()
