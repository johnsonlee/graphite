"""Repeat real64 on explicitly selected frozen source; never replace a capture."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
DRIVERS = HERE.parents[1] / 'native64-filtered-count-attempt21'


def inventory(folder):
    return {str(p.relative_to(folder)): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in folder.rglob('*') if p.is_file()}


p = argparse.ArgumentParser()
p.add_argument('--module', type=Path, required=True)
p.add_argument('--output', type=Path, required=True)
p.add_argument('--source', type=Path, required=True)
p.add_argument('--suffix', required=True)
p.add_argument('--states', nargs='+', choices=['cold', 'warm', 'startup-prepared'], default=['cold'])
a = p.parse_args()
module, out, source = a.module.resolve(), a.output.resolve(), a.source.resolve()
assert module.is_dir() and not source.exists()
out.mkdir(parents=True, exist_ok=False)
before = inventory(module)
subprocess.run(['/bin/cp', '-cRp', str(module), str(source)], check=True)
assert inventory(source) == before
shutil.copy2(__file__, out / 'runner.py')
(out / 'module-source.json').write_text(json.dumps(dict(module=str(source),
    inputModule=str(module), baselineRevision=subprocess.check_output(
        ['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
    files=before, performanceMeasurements=0), indent=2) + '\n')
env = dict(os.environ, PATH='/opt/homebrew/Cellar/go/1.22.0/libexec/bin:' + os.environ['PATH'],
    GOTOOLCHAIN='local', GRAPHITE_NATIVE_MODULE=str(source), GRAPHITE_CAPTURE_BASE=str(out),
    GRAPHITE_TRIAL_SUFFIX=a.suffix)
steps = []
code = 0
for state in a.states:
    cmd = ['python3', str(DRIVERS / 'run-state.py'), 'native', state]
    r = subprocess.run(cmd, cwd=ROOT, env=env)
    step = dict(state=state, command=cmd, exitCode=r.returncode)
    steps.append(step)
    (out / 'controller.json').write_text(json.dumps(steps, indent=2) + '\n')
    assert r.returncode == 1, 'Preserve original case-821 all-success gate failure'
    cmd = ['python3', str(DRIVERS / 'verify-state.py'), state]
    with (out / ('verify-' + state + '.log')).open('x') as log:
        result = subprocess.run(cmd, cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT)
    step.update(verifyCommand=cmd, verifyExitCode=result.returncode)
    (out / 'controller.json').write_text(json.dumps(steps, indent=2) + '\n')
    if result.returncode != 0:
        code = 1
        break
assert inventory(module) == before and inventory(source) == before
(out / 'source-verification.json').write_text(json.dumps(dict(
    originalModuleUnchanged=True, frozenModuleUnchanged=True, moduleFiles=len(before),
    controllerExitCode=code, performanceMeasurements=0), indent=2) + '\n')
print('Frozen replay terminal; source unchanged; comparison exit', code, flush=True)
raise SystemExit(code)
