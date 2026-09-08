"""Capture an initial public-oracle comparison without hiding known gaps."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tarfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
MODULE = ROOT / 'graphite-server'
GO = Path('/opt/homebrew/Cellar/go/1.22.0/libexec/bin/go')


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


p = argparse.ArgumentParser()
p.add_argument('--output', type=Path, required=True)
a = p.parse_args()
out = a.output.resolve()
out.mkdir(parents=True, exist_ok=False)
files = sorted(f for f in MODULE.rglob('*') if f.is_file())
inputs = {str(f): sha(f) for f in files}
for folder in [HERE.parent, HERE.parent / 'mapped-oracle',
               HERE.parent / 'mapped-oracle/large-oracle',
               HERE.parent / 'build-trigram-oracle']:
    for name in ['main.json', 'repeat-capture/main.json', 'fixtures.tar.gz',
                 'cases.json', 'fixture-variants.json']:
        f = folder / name
        inputs[str(f)] = sha(f)
for f in [GO, Path(__file__)]:
    inputs[str(f)] = sha(f)
(out / 'inputs.json').write_text(json.dumps(inputs, indent=2) + '\n')
with tarfile.open(out / 'source.tar.gz', 'w:gz') as archive:
    for f in files:
        archive.add(f, arcname=str(f.relative_to(MODULE)), recursive=False)
cmd = [str(GO), 'test', '-count=1', '-v', '-run',
       '^Test(ExecutionContextStoreWorkFailureIdentity|PersistedWorkAccountingMainOracle|MappedWorkAccountingMainOracle|MappedWorkLargeAccountingMainOracle|BuildTrigramWorkAccountingMainOracle)$', './internal/query']
env = dict(os.environ, PATH=str(GO.parent) + os.pathsep + os.environ['PATH'], GOTOOLCHAIN='local')
with (out / 'query.log').open('x') as log:
    result = subprocess.run(cmd, cwd=MODULE, env=env, stdout=log, stderr=subprocess.STDOUT)
unchanged = all(Path(f).is_file() and sha(Path(f)) == h for f, h in inputs.items())
receipt = dict(baselineRevision='f0838dda4f0d67628d028826d0577a8151765288',
               command=cmd, cwd=str(MODULE), exitCode=result.returncode,
               inputsUnchanged=unchanged, moduleFiles=len(files), inputCount=len(inputs),
               performanceMeasurements=0)
(out / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
(out / 'artifacts.json').write_text(json.dumps({f.name: sha(f) for f in sorted(out.iterdir())
                                               if f.is_file()}, indent=2) + '\n')
print(json.dumps(receipt, indent=2), flush=True)
assert unchanged
raise SystemExit(result.returncode)
