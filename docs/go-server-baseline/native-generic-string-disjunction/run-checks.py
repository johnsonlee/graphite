"""Full module correctness checks; no performance measurements."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess

BASE = Path(__file__).resolve().parent
ROOT = BASE.parents[2]
MODULE = ROOT / 'graphite-server'
GO = Path('/opt/homebrew/Cellar/go/1.22.0/libexec/bin/go')
parser = argparse.ArgumentParser()
parser.add_argument('--output', type=Path, required=True)
out = parser.parse_args().output.resolve()
out.mkdir(exist_ok=False)
paths = [p for p in MODULE.rglob('*') if p.is_file()]
paths += [BASE / p for p in ['main.json', 'fixtures.tar.gz', 'cases.json', 'run-checks.py']]
inputs = {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in paths}
(out / 'inputs.json').write_text(json.dumps(inputs, indent=2) + '\n')
env = dict(os.environ, PATH=str(GO.parent) + os.pathsep + os.environ['PATH'], GOTOOLCHAIN='local')
steps = []
for command, cwd, logname in [
    ([str(GO), 'test', '-race', '-count=1', './...'], MODULE, 'race.log'),
    ([str(GO), 'vet', './...'], MODULE, 'vet.log'),
    (['python3', str(BASE / 'verify.py')], ROOT, 'oracle.log'),
    (['python3', str(BASE / 'go-baseline/verify.py')], ROOT, 'baseline.log'),
]:
    with (out / logname).open('x') as log:
        result = subprocess.run(command, cwd=cwd, env=env, stdout=log, stderr=subprocess.STDOUT)
    steps.append(dict(command=command, cwd=str(cwd), exitCode=result.returncode, log=logname))
    unchanged = all(hashlib.sha256(Path(p).read_bytes()).hexdigest() == h for p, h in inputs.items())
    (out / 'receipt.json').write_text(json.dumps(dict(steps=steps, inputsUnchanged=unchanged,
        inputCount=len(inputs), performanceMeasurements=0), indent=2) + '\n')
    print(steps[-1], flush=True)
    assert unchanged and result.returncode == 0
