"""Capture original main or native warm/startup behavior on a fresh real64 clone."""
import argparse
import datetime
import importlib.util
import json
import os
from pathlib import Path
import subprocess

BASE = Path(__file__).resolve().parent
ROOT = BASE.parents[2]
PREVIOUS = BASE.parent / 'native64-fullcase-replay'
MODULE = ROOT / 'graphite-server'
spec = importlib.util.spec_from_file_location('fixtures', PREVIOUS / 'audit-fixtures.py')
fixtures = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixtures)
parser = argparse.ArgumentParser()
parser.add_argument('runtime', choices=['main', 'native'])
parser.add_argument('state', choices=['warm', 'startup-prepared'])
args = parser.parse_args()
name = args.runtime + '-' + args.state
receipt = BASE / (name + '-process.json')
assert not receipt.exists() and not (BASE / name).exists(), 'Never overwrite an earlier run'
reference = Path(json.loads((PREVIOUS / 'main-cold-preflight.json').read_text())['reference'])
run = reference.parents[1] / ('benchmarks/graphite/' + name + '-64-5451d576-run1')
run.mkdir()
clone = run / 'fixture'
subprocess.run(['/bin/cp', '-cRp', str(reference), str(clone)], check=True)
preflight = fixtures.audit(clone)
assert preflight['matched'] == 1152 and not preflight['added']
lines = []
for line in (clone / 'graphs.tsv').read_text().splitlines():
    if not line.strip() or line.lstrip().startswith('#'):
        lines.append(line)
        continue
    fields = line.split('\t')
    assert len(fields) == 6
    fields[1] = str(clone / fields[0])
    lines.append('\t'.join(fields))
manifest = clone / 'graphs-relocated.tsv'
manifest.write_text('\n'.join(lines) + '\n')
(BASE / (name + '-preflight.json')).write_text(json.dumps(preflight, indent=2) + '\n')
record = {'runtime': args.runtime, 'state': args.state, 'performanceMeasurement': False}
if args.runtime == 'main':
    classpath = (PREVIOUS / 'capture-classpath-complete.txt').read_text().strip()
    inputs = {}
    for component in classpath.split(os.pathsep):
        path = Path(component)
        for item in (sorted(path.rglob('*')) if path.is_dir() else [path]):
            if item.is_file():
                inputs[str(item)] = fixtures.sha(item)
    frozen = json.loads((PREVIOUS / 'main-cold-complete-classpath-inputs.json').read_text())
    assert inputs == frozen, 'Main runtime differs from the verified cold capture'
    command = ['java', '-Xmx8g', '-cp', classpath, 'MainReplayCapture', str(manifest), args.state, str(BASE / name)]
    record['classpathMatchesColdCapture'] = True
else:
    inputs = {str(path): fixtures.sha(path) for path in sorted(MODULE.rglob('*.go'))}
    for path in [MODULE / 'go.mod', MODULE / 'go.sum']:
        if path.is_file():
            inputs[str(path)] = fixtures.sha(path)
    binary = run / 'graphite-benchmark-replay'
    build = ['go', 'build', '-o', str(binary), './cmd/graphite-benchmark-replay']
    subprocess.run(build, cwd=MODULE, check=True)
    record.update(buildCommand=build, binarySHA256=fixtures.sha(binary),
                  goEnvironment=subprocess.check_output(['go', 'env', 'GOVERSION', 'GOROOT', 'GOOS', 'GOARCH'], cwd=MODULE, text=True).splitlines())
    (BASE / name).mkdir()
    command = [str(binary), '--graphs', str(manifest), '--state', args.state,
               '--workload', str(MODULE / 'internal/benchmarkcase/testdata/main64.json'),
               '--output', str(BASE / name / 'responses.jsonl')]
(BASE / (name + '-inputs.json')).write_text(json.dumps(inputs, indent=2) + '\n')
record.update(command=command, status='starting')
receipt.write_text(json.dumps(record, indent=2) + '\n')
with (BASE / (name + '-process.log')).open('x') as log:
    process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT)
    record.update(status='running', pid=process.pid, startedAt=datetime.datetime.now(datetime.timezone.utc).isoformat())
    receipt.write_text(json.dumps(record, indent=2) + '\n')
    print(name, 'PID', process.pid, flush=True)
    code = process.wait()
record.update(status='exited', exitCode=code, finishedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),
              inputsUnchanged=all(Path(p).is_file() and fixtures.sha(Path(p)) == digest for p, digest in inputs.items()))
if args.runtime == 'native':
    record['binaryUnchanged'] = fixtures.sha(binary) == record['binarySHA256']
receipt.write_text(json.dumps(record, indent=2) + '\n')
post = fixtures.audit(clone)
(BASE / (name + '-postrun-fixtures.json')).write_text(json.dumps(post, indent=2) + '\n')
print(name, 'exit', code, 'unchanged original files', post['matched'], flush=True)
raise SystemExit(code)
