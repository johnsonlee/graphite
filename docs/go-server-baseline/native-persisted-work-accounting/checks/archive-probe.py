"""Archive a terminated probe without changing its inputs or discarding failure."""
import argparse
import gzip
import hashlib
import json
from pathlib import Path
import shutil
import tarfile


def digest(data):
    return hashlib.sha256(data).hexdigest()


p = argparse.ArgumentParser()
p.add_argument('--source', type=Path, required=True)
p.add_argument('--output', type=Path, required=True)
a = p.parse_args()
source, out = a.source.resolve(), a.output.resolve()
receipt = json.loads((source / 'receipt.json').read_text())
assert receipt['inputsUnchanged'] is True
artifacts = json.loads((source / 'artifacts.json').read_text())
for name, expected in artifacts.items():
    assert digest((source / name).read_bytes()) == expected, name
inputs = json.loads((source / 'inputs.json').read_text())
module = Path(receipt['cwd'])
with tarfile.open(source / 'source.tar.gz') as archive:
    members = [m for m in archive.getmembers() if m.isfile()]
    assert len(members) == receipt['moduleFiles']
    for member in members:
        assert digest(archive.extractfile(member).read()) == inputs[str(module / member.name)], member.name
out.mkdir(parents=True, exist_ok=False)
for name in ['inputs.json', 'receipt.json', 'artifacts.json']:
    shutil.copy2(source / name, out / name)
raw = (source / 'query.log').read_bytes()
with (out / 'query.log.gz').open('xb') as f:
    f.write(gzip.compress(raw, mtime=0))
assert gzip.decompress((out / 'query.log.gz').read_bytes()) == raw
record = dict(source=str(source), terminatedExitCode=receipt['exitCode'],
              moduleSnapshotFilesVerified=len(members),
              externalFullModuleSnapshot=dict(path=str(source / 'source.tar.gz'),
                                              sha256=digest((source / 'source.tar.gz').read_bytes())),
              rawLogSHA256=digest(raw),
              copiedFiles={f.name:digest(f.read_bytes()) for f in sorted(out.iterdir()) if f.is_file()},
              performanceMeasurements=0)
(out / 'archive-verification.json').write_text(json.dumps(record, indent=2) + '\n')
print(json.dumps(record, indent=2))
