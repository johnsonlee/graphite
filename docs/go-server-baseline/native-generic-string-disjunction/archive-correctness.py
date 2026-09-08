"""Archive terminal correctness captures, including failed comparisons, byte-exactly."""
import argparse
import gzip
import hashlib
import json
from pathlib import Path
import shutil

def digest(stream):
    h, size = hashlib.sha256(), 0
    for block in iter(lambda: stream.read(1024 * 1024), b''):
        h.update(block)
        size += len(block)
    return size, h.hexdigest()

p = argparse.ArgumentParser()
p.add_argument('--source', type=Path, required=True)
p.add_argument('--output', type=Path, required=True)
a = p.parse_args()
source, out = a.source.resolve(), a.output.resolve()
receipts = list(source.rglob('*-process.json'))
assert receipts, 'No runtime receipts'
for receipt in receipts:
    r = json.loads(receipt.read_text())
    assert r['status'] == 'exited' and r['exitCode'] == 1, receipt
    assert r.get('inputsUnchanged', r.get('classpathUnchanged', False)), receipt
    assert r.get('binaryUnchanged', True), receipt
out.mkdir(parents=True, exist_ok=False)
entries = []
for file in sorted(source.rglob('*')):
    if not file.is_file():
        continue
    relative = file.relative_to(source)
    destination = out / relative
    destination.parent.mkdir(parents=True, exist_ok=True)
    with file.open('rb') as stream:
        size, sha = digest(stream)
    if file.name == 'responses.jsonl':
        destination = destination.with_suffix('.jsonl.gz')
        with file.open('rb') as src, destination.open('xb') as dst:
            with gzip.GzipFile(filename='', mode='wb', fileobj=dst, mtime=0) as zipped:
                shutil.copyfileobj(src, zipped, 1024 * 1024)
        with gzip.open(destination, 'rb') as stream:
            assert digest(stream) == (size, sha)
    else:
        shutil.copy2(file, destination)
        with destination.open('rb') as stream:
            assert digest(stream) == (size, sha)
    with destination.open('rb') as stream:
        stored_size, stored_sha = digest(stream)
    entries.append(dict(source=str(file), sourceBytes=size, sourceSHA256=sha,
        archive=str(destination.relative_to(out)), archiveBytes=stored_size,
        archiveSHA256=stored_sha, decompressedBytesEqual=True))
(out / 'archive-manifest.json').write_text(json.dumps(dict(performanceMeasurements=0,
    terminalRuntimeCount=len(receipts), comparisonsMustBeReadSeparately=True, files=entries), indent=2) + '\n')
print('Archived', len(entries), 'files from', len(receipts), 'terminal runtimes; no failed result removed')
