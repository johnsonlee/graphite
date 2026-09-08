#!/usr/bin/env python3
"""Archive terminal trace top-level artifacts; do not copy physical fixtures/modules."""
import argparse
import gzip
import hashlib
import json
from pathlib import Path
import shutil


def sha(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--capture', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args(); source, output = args.capture.resolve(), args.output.resolve()
    assert source.is_dir() and not output.exists()
    receipt = json.loads((source / 'receipt.json').read_text())
    assert receipt['status'] in ['complete', 'failed']
    assert all('exitCode' in step for step in receipt['steps']), 'Child has no terminal receipt'
    original_manifest = json.loads((source / 'artifact-manifest.json').read_text())
    for item in original_manifest:
        path = source / item['path']
        assert path.stat().st_size == item['bytes'] and sha(path) == item['sha256'], item
    output.mkdir(parents=True)
    archived, omitted = [], []
    for path in sorted(source.iterdir()):
        if path.is_dir():
            omitted.append({'path': path.name, 'reason': 'physical directory excluded; source tars and fixture identity audits retained'})
            continue
        assert path.is_file() and not path.is_symlink()
        identity = {'externalPath': str(path), 'originalBytes': path.stat().st_size, 'originalSHA256': sha(path)}
        if path.name == 'diagnostic-replay':
            assert identity['originalSHA256'] == receipt['binarySHA256']
            omitted.append(dict(identity, reason='binary identity retained; executable remains external'))
            continue
        if path.suffix == '.jsonl':
            target = output / (path.name + '.gz')
            with path.open('rb') as src, target.open('wb') as dst:
                with gzip.GzipFile(filename='', mode='wb', fileobj=dst, mtime=0) as zipped:
                    shutil.copyfileobj(src, zipped)
            with gzip.open(target, 'rb') as stream:
                h = hashlib.sha256(); size = 0
                for block in iter(lambda: stream.read(1024 * 1024), b''):
                    size += len(block); h.update(block)
            assert size == identity['originalBytes'] and h.hexdigest() == identity['originalSHA256']
            identity['encoding'] = 'gzip; decoded bytes verified exactly'
        else:
            target = output / path.name; shutil.copy2(path, target)
            assert sha(target) == identity['originalSHA256']
            identity['encoding'] = 'original bytes'
        identity.update(path=target.name, bytes=target.stat().st_size, sha256=sha(target))
        archived.append(identity)
    result = {'source': str(source), 'terminalStatus': receipt['status'], 'noRuntimeLaunched': True,
              'archived': archived, 'omitted': omitted,
              'originalArtifactManifestSHA256': sha(source / 'artifact-manifest.json')}
    (output / 'archive-receipt.json').write_text(json.dumps(result, indent=2, sort_keys=True) + '\n')
    print(json.dumps({'archivedFiles': len(archived), 'output': str(output), 'status': receipt['status']}))


if __name__ == '__main__':
    main()
