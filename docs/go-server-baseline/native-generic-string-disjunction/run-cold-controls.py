"""Predeclared baseline/candidate cold correctness controls, retaining failures."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess

BASE = Path(__file__).resolve().parent
ROOT = BASE.parents[2]
DRIVERS = BASE.parent / 'native64-filtered-count-attempt21'
p = argparse.ArgumentParser()
p.add_argument('--baseline-module', type=Path, required=True)
p.add_argument('--candidate-module', type=Path, required=True)
p.add_argument('--output', type=Path, required=True)
p.add_argument('--suffix', required=True)
a = p.parse_args()
out = a.output.resolve()
out.mkdir(exist_ok=False)
steps = []
for name, module in [('baseline', a.baseline_module.resolve()), ('candidate', a.candidate_module.resolve())]:
    capture = out / name
    capture.mkdir()
    inputs = {str(f): hashlib.sha256(f.read_bytes()).hexdigest() for f in module.rglob('*') if f.is_file()}
    (capture / 'module-inputs.json').write_text(json.dumps(inputs, indent=2) + '\n')
    (capture / 'processes-before.txt').write_text(subprocess.check_output(['ps', '-Ao', 'pid,ppid,etime,command'], text=True))
    env = dict(os.environ, PATH='/opt/homebrew/Cellar/go/1.22.0/libexec/bin:' + os.environ['PATH'],
        GOTOOLCHAIN='local', GRAPHITE_NATIVE_MODULE=str(module), GRAPHITE_CAPTURE_BASE=str(capture),
        GRAPHITE_TRIAL_SUFFIX=a.suffix + '-' + name)
    command = ['python3', str(DRIVERS / 'run-state.py'), 'native', 'cold']
    result = subprocess.run(command, cwd=ROOT, env=env)
    step = dict(name=name, module=str(module), command=command, exitCode=result.returncode,
        performanceMeasurements=0)
    steps.append(step)
    (out / 'controller.json').write_text(json.dumps(steps, indent=2) + '\n')
    assert result.returncode == 1, 'Expected only original failed-query gate'
    command = ['python3', str(DRIVERS / 'verify-state.py'), 'cold']
    with (capture / 'verify-cold.log').open('x') as log:
        result = subprocess.run(command, cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT)
    step.update(verifyCommand=command, verifyExitCode=result.returncode,
        sourceUnchanged=all(hashlib.sha256(Path(f).read_bytes()).hexdigest() == h for f, h in inputs.items()))
    (capture / 'processes-after.txt').write_text(subprocess.check_output(['ps', '-Ao', 'pid,ppid,etime,command'], text=True))
    (out / 'controller.json').write_text(json.dumps(steps, indent=2) + '\n')
    assert step['sourceUnchanged']
    print(name, 'strict comparator exit', result.returncode, flush=True)
    # A state comparison failure is evidence, not permission to repeat until green.
    # Complete both predeclared controls regardless of that comparison outcome.
raise SystemExit(0 if all(s['verifyExitCode'] == 0 for s in steps) else 1)
