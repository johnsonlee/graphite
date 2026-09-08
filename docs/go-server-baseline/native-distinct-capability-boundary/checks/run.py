"""Capture baseline/candidate public regressions and module checks, without timing."""
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
BASELINE = '2e75f69e19ee4660a9187ac9af719bf7cd567c6a'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write(path, value):
    path.write_text(json.dumps(value, indent=2) + '\n')


p = argparse.ArgumentParser()
p.add_argument('--output', type=Path, required=True)
a = p.parse_args()
out = a.output.resolve()
out.mkdir(parents=True, exist_ok=False)
replace = {}
for relative in ['internal/query/indexed_distinct.go', 'internal/query/main_string_source.go', 'internal/store/distinct_projection.go']:
    target = MODULE / relative
    old = out / ('baseline-' + target.name)
    old.write_bytes(subprocess.check_output(['git', 'show', BASELINE + ':' + str(target.relative_to(ROOT))], cwd=ROOT))
    replace[str(target)] = str(old)
overlay = out / 'baseline-overlay.json'
write(overlay, {'Replace': replace})
inputs = {str(f): sha(f) for f in MODULE.rglob('*') if f.is_file()}
for folder in [HERE.parent, HERE.parent / 'supplement', HERE.parent.parent / 'native-generic-provenance-prefix/initial-missing-callsite-control']:
    for name in ['main.json', 'fixtures.tar.gz', 'fixture-variants.json']:
        f = folder / name
        inputs[str(f)] = sha(f)
for f in [GO, Path(__file__), overlay] + [Path(p) for p in replace.values()]:
    inputs[str(f)] = sha(f)
write(out / 'inputs.json', inputs)
env = dict(os.environ, PATH=str(GO.parent) + os.pathsep + os.environ['PATH'], GOTOOLCHAIN='local')
steps = []
for name, cmd, expected in [
    ('baseline', [str(GO), 'test', '-overlay', str(overlay), '-count=1', '-v', '-run', '^TestDistinctCapability(Main|InitialMain)$', './internal/query'], 1),
    ('candidate', [str(GO), 'test', '-count=1', '-v', '-run', '^TestDistinctCapability(Main|InitialMain)$', './internal/query'], 0),
    ('race', [str(GO), 'test', '-race', '-count=1', './...'], 0),
    ('vet', [str(GO), 'vet', './...'], 0),
]:
    step_env = dict(env, INDEXED_DISTINCT_OUTPUT=str(out / (name + '-outputs')))
    with (out / (name + '.log')).open('x') as log:
        result = subprocess.run(cmd, cwd=MODULE, env=step_env, stdout=log, stderr=subprocess.STDOUT)
    unchanged = all(Path(f).is_file() and sha(Path(f)) == h for f, h in inputs.items())
    steps.append(dict(name=name, command=cmd, cwd=str(MODULE), exitCode=result.returncode,
                      expectedExitCode=expected, inputsUnchanged=unchanged))
    write(out / 'receipt.json', dict(baselineRevision=BASELINE, steps=steps,
                                    inputCount=len(inputs), performanceMeasurements=0))
    print(name, result.returncode, 'inputs unchanged', unchanged, flush=True)
    assert unchanged and result.returncode == expected, 'Inspect retained failure; do not overwrite or discard it'
write(out / 'artifacts.json', {str(f.relative_to(out)): sha(f) for f in sorted(out.rglob('*')) if f.is_file()})
