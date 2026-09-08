"""Archive terminal correctness captures and verify decompressed bytes exactly."""
import gzip
import hashlib
import json
from pathlib import Path
import shutil

BASE = Path(__file__).resolve().parent


def digest(stream):
    value = hashlib.sha256()
    size = 0
    for block in iter(lambda: stream.read(1024 * 1024), b''):
        value.update(block)
        size += len(block)
    return size, value.hexdigest()


entries = []
for relative in ['native-cold', 'native-warm', 'final/native-cold', 'final/native-warm', 'final/native-startup-prepared']:
    directory = BASE / relative
    receipt = json.loads((directory.parent / (directory.name + '-process.json')).read_text())
    assert receipt['status'] == 'exited' and receipt['exitCode'] == 1
    assert receipt['inputsUnchanged'] and receipt['binaryUnchanged']
    raw = directory / 'responses.jsonl'
    archive = raw.with_suffix(raw.suffix + '.gz')
    with raw.open('rb') as stream:
        raw_size, raw_hash = digest(stream)
    if not archive.exists():
        with raw.open('rb') as source, archive.open('xb') as output:
            with gzip.GzipFile(filename='', mode='wb', fileobj=output, mtime=0) as compressed:
                shutil.copyfileobj(source, compressed, 1024 * 1024)
    with gzip.open(archive, 'rb') as stream:
        assert digest(stream) == (raw_size, raw_hash), relative
    with archive.open('rb') as stream:
        size, sha = digest(stream)
    entries.append(dict(raw=str(raw.relative_to(BASE)), archive=str(archive.relative_to(BASE)),
                        rawBytes=raw_size, rawSHA256=raw_hash, archiveBytes=size, archiveSHA256=sha,
                        decompressedBytesVerified=True))
(BASE / 'capture-archives.json').write_text(json.dumps(entries, indent=2)+'\n')
print(json.dumps(entries, indent=2))
