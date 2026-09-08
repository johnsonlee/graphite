"""Fresh real64 correctness replay after ordinary mapped-range correction."""
import importlib.util
import json
from pathlib import Path
import subprocess

BASE = Path(__file__).resolve().parent
ROOT = BASE.parents[2]
PREVIOUS = BASE.parent / 'native64-fullcase-replay'
MODULE = ROOT / 'graphite-server'
spec = importlib.util.spec_from_file_location('fixtures', PREVIOUS / 'audit-fixtures.py')
fixtures = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixtures)
name = 'native-cold'
receipt = BASE / (name + '-process.json')
assert not receipt.exists(), 'Never overwrite a run'
reference = Path(json.loads((PREVIOUS / 'main-cold-preflight.json').read_text())['reference'])
run = reference.parents[1] / 'benchmarks/graphite/go64-cold-ordinary-mapped-run1'
run.mkdir()
clone = run / 'fixture'
subprocess.run(['/bin/cp', '-cRp', str(reference), str(clone)], check=True)
check = fixtures.audit(clone)
assert check['matched'] == 1152 and not check['added']
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
(BASE / (name + '-preflight.json')).write_text(json.dumps(check, indent=2) + '\n')
# Conservatively freeze all module Go sources, including tests, plus module files.
inputs = {str(p): fixtures.sha(p) for p in sorted(MODULE.rglob('*.go'))}
for p in [MODULE / 'go.mod', MODULE / 'go.sum']:
    if p.is_file():
        inputs[str(p)] = fixtures.sha(p)
(BASE / (name + '-source-inputs.json')).write_text(json.dumps(inputs, indent=2) + '\n')
binary = run / 'graphite-benchmark-replay'
build = ['go', 'build', '-o', str(binary), './cmd/graphite-benchmark-replay']
subprocess.run(build, cwd=MODULE, check=True)
binary_sha = fixtures.sha(binary)
(BASE / name).mkdir()
command = [str(binary), '--graphs', str(manifest), '--state', 'cold',
           '--workload', str(MODULE / 'internal/benchmarkcase/testdata/main64.json'),
           '--output', str(BASE / name / 'responses.jsonl')]
record = {'command': command, 'buildCommand': build, 'binarySHA256': binary_sha,
          'performanceMeasurement': False, 'status': 'starting',
          'goEnvironment': subprocess.check_output(['go', 'env', 'GOVERSION', 'GOROOT', 'GOOS', 'GOARCH'],
                                                  cwd=MODULE, text=True).splitlines()}
receipt.write_text(json.dumps(record, indent=2) + '\n')
with (BASE / (name + '-process.log')).open('x') as log:
    process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT)
    record.update(status='running', pid=process.pid)
    receipt.write_text(json.dumps(record, indent=2) + '\n')
    print('Native full1267 ordinary mapped correction replay PID', process.pid, flush=True)
    code = process.wait()
record.update(status='exited', exitCode=code, binaryUnchanged=fixtures.sha(binary) == binary_sha,
              sourcesUnchanged=all(Path(p).is_file() and fixtures.sha(Path(p)) == digest
                                   for p, digest in inputs.items()))
receipt.write_text(json.dumps(record, indent=2) + '\n')
post = fixtures.audit(clone)
(BASE / (name + '-postrun-fixtures.json')).write_text(json.dumps(post, indent=2) + '\n')
print('Native ordinary mapped correction exit', code, 'original files unchanged', post['matched'], flush=True)
raise SystemExit(code)
