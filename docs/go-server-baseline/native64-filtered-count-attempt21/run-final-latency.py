"""Fresh main/candidate cold and startup pairs, serial and after correctness gates."""
import hashlib
import json
from pathlib import Path
import subprocess

BASE = Path(__file__).resolve().parent
ROOT = BASE.parents[2]
PILOT = BASE.parent / 'native64-latency-pilot'
CORRECTNESS = BASE / 'final'
steps = json.loads((CORRECTNESS / 'controller.json').read_text())
assert len(steps) == 3 and all(s['exitCode'] == 1 and s['verifyExitCode'] == 0 for s in steps)
source = json.loads((BASE / 'final-source.json').read_text())
for relative, digest in source['files'].items():
    path = ROOT / 'graphite-server' / relative
    assert path.is_file() and hashlib.sha256(path.read_bytes()).hexdigest() == digest, relative
out = BASE / 'latency'
out.mkdir()
order = [('main', 'cold'), ('native', 'cold'), ('native', 'startup-prepared'), ('main', 'startup-prepared')]
steps = []
for runtime, state in order:
    name = 'attempt21-' + runtime + '-' + state + '-run1'
    runner = PILOT / 'main-tooling/run-main.py' if runtime == 'main' else BASE / 'run-latency.py'
    command = ['python3', str(runner), '--state', state, '--output', str(out / name)]
    result = subprocess.run(command, cwd=ROOT)
    steps.append(dict(runtime=runtime, state=state, directory=str(out / name), command=command, exitCode=result.returncode))
    (out / 'controller.json').write_text(json.dumps(steps, indent=2)+'\n')
    assert result.returncode == 1, 'Preserve original single failed-query gate'
for state in ['cold', 'startup-prepared']:
    command = ['python3', str(PILOT / 'verify-pilot.py'), '--state', state,
               '--main', str(out / ('attempt21-main-' + state + '-run1')),
               '--native', str(out / ('attempt21-native-' + state + '-run1')),
               '--output-prefix', str(out / (state + '-comparison'))]
    with (out / ('verify-' + state + '.log')).open('x') as log:
        result = subprocess.run(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
    assert result.returncode == 0, 'Exact full-case timing signatures failed verification'
print('Four serial runtimes terminal and both per-case comparisons verified; n=1 only', flush=True)
