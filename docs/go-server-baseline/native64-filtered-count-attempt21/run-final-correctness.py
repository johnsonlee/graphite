"""Serial complete correctness captures for the frozen final candidate."""
import json
import os
from pathlib import Path
import subprocess

BASE = Path(__file__).resolve().parent
ROOT = BASE.parents[2]
OUT = BASE / 'final'
MODULE = Path('/Users/johnsonlee/.codex/benchmarks/graphite/filtered-count-attempt21-final-source')
assert MODULE.is_dir()
OUT.mkdir()
environment = dict(os.environ, GRAPHITE_NATIVE_MODULE=str(MODULE), GRAPHITE_CAPTURE_BASE=str(OUT), GRAPHITE_TRIAL_SUFFIX='final-run1')
steps = []
for state in ['cold', 'warm', 'startup-prepared']:
    command = ['python3', str(BASE / 'run-state.py'), 'native', state]
    result = subprocess.run(command, cwd=ROOT, env=environment)
    steps.append(dict(state=state, command=command, exitCode=result.returncode))
    (OUT / 'controller.json').write_text(json.dumps(steps, indent=2)+'\n')
    assert result.returncode == 1, 'Expected complete capture with original failed query gate'
    verify = ['python3', str(BASE / 'verify-state.py'), state]
    with (OUT / ('verify-' + state + '.log')).open('x') as log:
        check = subprocess.run(verify, cwd=ROOT, env=environment, stdout=log, stderr=subprocess.STDOUT)
    steps[-1]['verifyCommand'] = verify
    steps[-1]['verifyExitCode'] = check.returncode
    (OUT / 'controller.json').write_text(json.dumps(steps, indent=2)+'\n')
    assert check.returncode == 0, 'Full case/state comparison failed'
print('All three full correctness captures and comparisons terminal', flush=True)
