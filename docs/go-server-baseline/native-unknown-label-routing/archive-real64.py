"""Archive each terminal real64 response stream without changing its bytes."""
import gzip
import hashlib
import json
from pathlib import Path
import shutil

BASE = Path(__file__).resolve().parent


def digest(stream):
    value, size = hashlib.sha256(), 0
    for block in iter(lambda: stream.read(1024 * 1024), b''):
        value.update(block)
        size += len(block)
    return size, value.hexdigest()


entries = []
for state in ['cold', 'warm', 'startup-prepared']:
    receipt = json.loads((BASE / 'real64' / ('native-' + state + '-process.json')).read_text())
    assert receipt['status'] == 'exited' and receipt['exitCode'] == 1
    assert receipt['inputsUnchanged'] and receipt['binaryUnchanged']
    raw = BASE / 'real64' / ('native-' + state) / 'responses.jsonl'
    archive = raw.with_suffix(raw.suffix + '.gz')
    with raw.open('rb') as stream:
        raw_size, raw_hash = digest(stream)
    if not archive.exists():
        with raw.open('rb') as source, archive.open('xb') as target:
            with gzip.GzipFile(filename='', mode='wb', fileobj=target, mtime=0) as zipped:
                shutil.copyfileobj(source, zipped, 1024 * 1024)
    with gzip.open(archive, 'rb') as stream:
        assert digest(stream) == (raw_size, raw_hash)
    with archive.open('rb') as stream:
        size, sha = digest(stream)
    entries.append(dict(raw=str(raw.relative_to(BASE)), archive=str(archive.relative_to(BASE)),
                        rawBytes=raw_size, rawSHA256=raw_hash, archiveBytes=size, archiveSHA256=sha,
                        decompressedBytesVerified=True))
(BASE / 'capture-archives.json').write_text(json.dumps(entries, indent=2)+'\n')
print('Three terminal correctness streams archived and decompressed hashes verified')
