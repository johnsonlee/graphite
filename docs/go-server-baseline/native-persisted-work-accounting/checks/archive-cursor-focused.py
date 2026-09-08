"""Keep successful and failed cursor checks with their exact source receipts."""
import argparse
import gzip
import hashlib
import json
from pathlib import Path
import shutil
import tarfile


def sha(data):
    return hashlib.sha256(data).hexdigest()


p = argparse.ArgumentParser()
p.add_argument('--source', type=Path, required=True)
p.add_argument('--output', type=Path, required=True)
a = p.parse_args()
source, out = a.source.resolve(), a.output.resolve()
receipt = json.loads((source / 'receipt.json').read_text())
assert receipt['exitCode'] in (0, 1) and receipt['inputsUnchanged']
inputs = json.loads((source / 'module-inputs.json').read_text())
with tarfile.open(source / 'source.tar.gz') as archive:
    archived = {m.name: sha(archive.extractfile(m).read())
                for m in archive if m.isfile()}
assert archived == inputs
assert sha((source / 'source.tar.gz').read_bytes()) == receipt['sourceArchiveSha256']
out.mkdir(parents=True, exist_ok=False)
files = ['receipt.json', 'preflight.json', 'process.json', 'module-inputs.json',
         'runner.py', 'artifacts.json']
verified = []
for name in files:
    shutil.copyfile(source / name, out / name)
    assert (source / name).read_bytes() == (out / name).read_bytes()
    verified.append(dict(file=name, sha256=sha((out / name).read_bytes())))
raw = (source / 'test.log').read_bytes()
with (out / 'test.log.gz').open('xb') as f:
    f.write(gzip.compress(raw, mtime=0))
assert gzip.decompress((out / 'test.log.gz').read_bytes()) == raw
verified.append(dict(file='test.log.gz', uncompressedSha256=sha(raw)))
verification = dict(externalSource=str(source), sourceArchive=str(source / 'source.tar.gz'),
                    sourceArchiveSha256=receipt['sourceArchiveSha256'],
                    sourceModuleFiles=len(inputs), verified=verified,
                    exitCode=receipt['exitCode'], performanceMeasurements=0)
(out / 'archive-verification.json').write_text(json.dumps(verification, indent=2) + '\n')
print(json.dumps(verification, indent=2))
