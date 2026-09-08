"""Losslessly archive successful and retained setup-failure correctness streams."""
import gzip, hashlib, json, shutil
from pathlib import Path
BASE = Path(__file__).resolve().parent
def h(data): return hashlib.sha256(data).hexdigest()
records = []
for subdir, external in [('', 'regex-quote-functional-bc708-v2'), ('failed-setup', 'regex-quote-functional-bc708-v1')]:
    root = BASE / subdir
    source = root / 'race.jsonl'
    target = root / 'race.jsonl.gz'
    data = source.read_bytes()
    retained = Path('/Users/johnsonlee/.codex/benchmarks/graphite') / external / 'raw-checks' / 'race.jsonl'
    retained.parent.mkdir(exist_ok=True)
    assert not retained.exists() and not target.exists()
    shutil.copyfile(source, retained)
    with gzip.GzipFile(filename=str(target), mode='wb', mtime=0) as f: f.write(data)
    assert gzip.decompress(target.read_bytes()) == retained.read_bytes() == data
    records.append({'source': str(source.relative_to(BASE)), 'archive': str(target.relative_to(BASE)), 'retainedRaw': str(retained), 'decompressedBytes': len(data), 'decompressedSha256': h(data), 'archiveSha256': h(target.read_bytes()), 'verified': True})
    source.unlink()
(BASE / 'check-log-archives.json').write_text(json.dumps(records, indent=2) + '\n')
print(json.dumps(records, indent=2))
