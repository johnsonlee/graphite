"""Freeze check inputs and retain public-context, race, and vet results."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
MODULE = ROOT / 'graphite-server'
GO = Path('/opt/homebrew/Cellar/go/1.22.0/libexec/bin/go')


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write(path, value):
    path.write_text(json.dumps(value, indent=2) + '\n')


p = argparse.ArgumentParser()
p.add_argument('--output', type=Path, required=True)
a = p.parse_args()
out = a.output.resolve()
out.mkdir(parents=True, exist_ok=False)
inputs = {str(f): sha(f) for f in MODULE.rglob('*') if f.is_file()}
previous = HERE.parent.parent / 'native-work-context'
for folder in [HERE.parent, previous, previous / 'checks/seek-oracle', previous / 'checks/bounded-oracle', previous / 'checks/ordered-oracle']:
    for name in ['cases.json', 'main.json', 'fixtures.tar.gz', 'fixture-variants.json']:
        f = folder / name
        inputs[str(f)] = sha(f)
for f in (previous / 'checks/constructor-oracle').rglob('*'):
    if f.is_file():
        inputs[str(f)] = sha(f)
for f in HERE.parent.rglob('*'):
    if f.is_file() and HERE not in f.parents:
        inputs[str(f)] = sha(f)
for f in (HERE / 'method-zero-oracle').rglob('*'):
    if f.is_file():
        inputs[str(f)] = sha(f)
for f in [GO, Path(__file__)]:
    inputs[str(f)] = sha(f)
write(out / 'inputs.json', inputs)
env = dict(os.environ, PATH=str(GO.parent) + os.pathsep + os.environ['PATH'], GOTOOLCHAIN='local')
steps = []
for name, cmd in [
    ('context', [str(GO), 'test', '-count=1', '-v', '-run', '^Test(ExecutionContext|RawWork|BufferedGraphWork)', './internal/query']),
    ('race', [str(GO), 'test', '-race', '-count=1', './...']),
    ('vet', [str(GO), 'vet', './...']),
]:
    with (out / (name + '.log')).open('x') as log:
        result = subprocess.run(cmd, cwd=MODULE, env=env, stdout=log, stderr=subprocess.STDOUT)
    unchanged = all(Path(f).is_file() and sha(Path(f)) == h for f, h in inputs.items())
    steps.append(dict(name=name, command=cmd, cwd=str(MODULE), exitCode=result.returncode, inputsUnchanged=unchanged))
    write(out / 'receipt.json', dict(baselineRevision='cf920cd4e84eafe7d3c641fe747bb0b1d14da16a',
                                    steps=steps, inputCount=len(inputs), performanceMeasurements=0))
    print(name, result.returncode, 'inputs unchanged', unchanged, flush=True)
    assert unchanged and result.returncode == 0, 'Retain the failure before changing code or inputs'
write(out / 'artifacts.json', {str(f.relative_to(out)): sha(f) for f in sorted(out.rglob('*')) if f.is_file()})
