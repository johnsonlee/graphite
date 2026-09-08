"""Retain two terminal actual-JVM captures and check every deterministic field."""
import argparse
import gzip
import hashlib
import json
from pathlib import Path
import shutil
import tarfile


def sha(p):
    return hashlib.sha256(p.read_bytes()).hexdigest()


def write(p, value):
    p.write_text(json.dumps(value, indent=2) + '\n')


parser = argparse.ArgumentParser()
parser.add_argument('--first', type=Path, required=True)
parser.add_argument('--repeat', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
out = args.output.resolve()
assert not (out / 'main.json').exists(), 'Never replace previous evidence'
out.mkdir(parents=True, exist_ok=True)
captures = []
for label, source in [('main-capture', args.first.resolve()), ('repeat-capture', args.repeat.resolve())]:
    receipt = json.loads((source / 'receipt.json').read_text())
    assert receipt['inputsUnchanged'] and all(c['exitCode'] == 0 for c in receipt['commands'])
    inputs = json.loads((source / 'inputs.json').read_text())
    assert all(sha(Path(p)) == h for p, h in inputs.items())
    dest = out / label
    dest.mkdir(exist_ok=False)
    for p in sorted(source.rglob('*')):
        if not p.is_file() or p.relative_to(source).parts[0] in ['variants', 'fixtures']:
            continue
        target = dest / p.relative_to(source)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(p, target)
        assert sha(p) == sha(target)
    with tarfile.open(dest / 'input-sources.tar.gz', 'w:gz') as archive:
        for p in inputs:
            if Path(p).suffix in ['.py', '.java', '.json', '.kt']:
                archive.add(p, arcname=p.lstrip('/'), recursive=False)
    before = json.loads(gzip.decompress((dest / 'fixture-before.json.gz').read_bytes()))
    after = json.loads(gzip.decompress((dest / 'fixture-after.json.gz').read_bytes()))
    assert before == after, 'Retain and investigate changed fixture inputs'
    with tarfile.open(dest / 'fixtures.tar.gz') as archive:
        members = [m for m in archive if m.isfile()]
        manifest = json.loads((dest / 'fixture-variants.json').read_text())
        assert len(members) == len(manifest)
        assert {m.name: hashlib.sha256(archive.extractfile(m).read()).hexdigest() for m in members} == {e['file']: e['sha256'] for e in manifest}
    captures.append(json.loads((dest / 'main.json').read_text()))
assert captures[0] == captures[1], 'Do not discard any differing operation field'
for name in ['main.json', 'fixtures.tar.gz', 'fixture-variants.json', 'mutations.json']:
    shutil.copyfile(out / 'main-capture' / name, out / name)
    assert sha(out / name) == sha(out / 'main-capture' / name)
write(out / 'repeat-audit.json', dict(fullRecordsEqual=True,
    cases=len(captures[0]['cases']), operations=sum(len(c['operations']) for c in captures[0]['cases']),
    performanceMeasurements=0))
write(out / 'archive-manifest.json', {str(p.relative_to(out)): sha(p) for p in sorted(out.rglob('*')) if p.is_file()})
print(out, 'all deterministic records equal')
