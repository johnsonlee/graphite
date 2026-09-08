"""Preserve terminal full-check steps, including failures and unexecuted gates."""
import argparse
import gzip
import hashlib
import json
from pathlib import Path
import shutil


def sha(data):
    return hashlib.sha256(data).hexdigest()


p = argparse.ArgumentParser()
p.add_argument('--source', type=Path, required=True)
p.add_argument('--output', type=Path, required=True)
a = p.parse_args()
source, out = a.source.resolve(), a.output.resolve()
receipt = json.loads((source / 'receipt.json').read_text())
assert receipt['steps'] and all(s['inputsUnchanged'] for s in receipt['steps'])
last = receipt['steps'][-1]
assert last['exitCode'] != 0 or len(receipt['steps']) == 3
out.mkdir(parents=True, exist_ok=False)
entries = []
for f in sorted(source.iterdir()):
    assert f.is_file(), f
    data = f.read_bytes()
    destination = out / (f.name + '.gz' if f.suffix == '.log' else f.name)
    if f.suffix == '.log':
        destination.write_bytes(gzip.compress(data, mtime=0))
        assert gzip.decompress(destination.read_bytes()) == data
    else:
        shutil.copy2(f, destination)
        assert destination.read_bytes() == data
    entries.append(dict(source=str(f), sourceSHA256=sha(data), archive=destination.name,
                        archiveSHA256=sha(destination.read_bytes()), originalBytesVerified=True))
(out / 'archive-verification.json').write_text(json.dumps(dict(files=entries,
    passedAllSteps=len(receipt['steps']) == 3 and all(s['exitCode'] == 0 for s in receipt['steps']),
    unexecutedSteps=['context','race','vet'][len(receipt['steps']):], performanceMeasurements=0), indent=2) + '\n')
print('Archived', len(entries), 'files; completed steps:', [(s['name'], s['exitCode']) for s in receipt['steps']])
