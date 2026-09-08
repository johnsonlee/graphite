"""Full three-state correctness replay for the isolated Java quote compatibility correction."""
import hashlib
import json
import os
from pathlib import Path
import subprocess

BASE = Path(__file__).resolve().parent
ROOT = BASE.parents[3]
MODULE = Path('/Users/johnsonlee/.codex/benchmarks/graphite/regex-quote-functional-bc708-v1/module')
DRIVERS = BASE.parents[1] / 'native64-filtered-count-attempt21'
OUT = BASE / 'real64'
OUT.mkdir()
source = Path('/Users/johnsonlee/.codex/benchmarks/graphite/regex-quote-functional-bc708-real64-source-v1')
assert not source.exists(), 'Never replace a frozen source'
subprocess.run(['/bin/cp', '-cRp', str(MODULE), str(source)], check=True)
files = {str(p.relative_to(MODULE)): hashlib.sha256(p.read_bytes()).hexdigest()
         for p in MODULE.rglob('*') if p.is_file()}
for relative, digest in files.items():
    assert hashlib.sha256((source / relative).read_bytes()).hexdigest() == digest
(BASE / 'real64-module-source.json').write_text(json.dumps(dict(module=str(source),
    baselineRevision=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
    files=files), indent=2)+'\n')
env = dict(os.environ, PATH='/opt/homebrew/Cellar/go/1.22.0/libexec/bin:' + os.environ['PATH'], GRAPHITE_NATIVE_MODULE=str(source), GRAPHITE_CAPTURE_BASE=str(OUT),
           GRAPHITE_TRIAL_SUFFIX='quote-functional-run1')
steps = []
for state in ['cold', 'warm', 'startup-prepared']:
    command = ['python3', str(DRIVERS / 'run-state.py'), 'native', state]
    result = subprocess.run(command, cwd=ROOT, env=env)
    step = dict(state=state, command=command, exitCode=result.returncode)
    steps.append(step)
    (OUT / 'controller.json').write_text(json.dumps(steps, indent=2)+'\n')
    assert result.returncode == 1, 'Preserve the original failed-query gate'
    command = ['python3', str(DRIVERS / 'verify-state.py'), state]
    with (OUT / ('verify-' + state + '.log')).open('x') as log:
        result = subprocess.run(command, cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT)
    step.update(verifyCommand=command, verifyExitCode=result.returncode)
    (OUT / 'controller.json').write_text(json.dumps(steps, indent=2)+'\n')
    assert result.returncode == 0, 'Full case/state comparison failed'
for relative, digest in files.items():
    assert hashlib.sha256((MODULE / relative).read_bytes()).hexdigest() == digest
    assert hashlib.sha256((source / relative).read_bytes()).hexdigest() == digest
print('Three full correctness captures terminal; module source unchanged; no performance measurements', flush=True)
