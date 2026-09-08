"""Archive every top-level trace artifact; source/fixture directories stay external."""
import argparse
import gzip
import hashlib
import json
from pathlib import Path
import shutil


def sha(data):
    return hashlib.sha256(data).hexdigest()


p = argparse.ArgumentParser()
p.add_argument('--source', required=True, type=Path)
p.add_argument('--output', required=True, type=Path)
a = p.parse_args()
source, out = a.source.resolve(), a.output.resolve()
receipt = json.loads((source / 'receipt.json').read_text())
assert receipt['status'] == 'complete'
assert all(step['exitCode'] == 0 for step in receipt['steps'])
assert receipt['actualCaseIndices'] == [0, 1, 2, 3]
assert receipt['traceSummary']['dropped'] == receipt['traceSummary']['unpublished'] == 0
for name in ['reference-before', 'reference-after', 'fixture-before', 'fixture-after']:
    audit = json.loads((source / (name + '.json')).read_text())
    assert audit['matched'] == 1152 and not any(audit[k] for k in ['changed', 'missing', 'added'])
for before, after in [('source-before', 'source-after'), ('source-instrumented', 'instrumented-after')]:
    assert json.loads((source / (before + '.json')).read_text()) == json.loads((source / (after + '.json')).read_text())
out.mkdir(parents=True, exist_ok=False)
entries = []
for file in sorted(source.iterdir()):
    if not file.is_file():
        continue
    data = file.read_bytes()
    compressed = file.suffix == '.jsonl' or file.name == 'diagnostic-replay'
    dest = out / (file.name + '.gz' if compressed else file.name)
    if compressed:
        dest.write_bytes(gzip.compress(data, mtime=0))
        assert gzip.decompress(dest.read_bytes()) == data
    else:
        shutil.copyfile(file, dest)
        assert dest.read_bytes() == data
    entries.append(dict(source=str(file), sourceBytes=len(data), sourceSHA256=sha(data),
                        archive=dest.name, archiveSHA256=sha(dest.read_bytes()),
                        decompressedBytesEqual=True))
(out / 'archive-verification.json').write_text(json.dumps(dict(
    source=str(source), files=entries, diagnosticOnly=True, performanceMeasurements=0,
    excludedDirectories=[str(x) for x in sorted(source.iterdir()) if x.is_dir()]), indent=2) + '\n')
print('Archived', len(entries), 'terminal diagnostic artifacts; no replay launched')
