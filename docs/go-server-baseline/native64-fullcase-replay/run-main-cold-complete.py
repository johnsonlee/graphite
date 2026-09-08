"""Repeat original main replay with its missing benchmark support class supplied."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess

BASE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('fixtures', BASE / 'audit-fixtures.py')
fixtures = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixtures)
receipt = BASE / 'main-cold-complete-process.json'
assert not receipt.exists(), 'Never overwrite an earlier run'
reference = Path(json.loads((BASE / 'main-cold-preflight.json').read_text())['reference'])
run = reference.parents[1] / 'benchmarks/graphite/main64-cold-8bbd8835-run3'
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
(BASE / 'main-cold-complete-preflight.json').write_text(json.dumps(check, indent=2) + '\n')
classpath = (BASE / 'capture-classpath-complete.txt').read_text().strip()
inputs = {}
for component in classpath.split(os.pathsep):
    path = Path(component)
    paths = sorted(path.rglob('*')) if path.is_dir() else [path]
    for item in paths:
        if item.is_file():
            inputs[str(item)] = fixtures.sha(item)
(BASE / 'main-cold-complete-classpath-inputs.json').write_text(json.dumps(inputs, indent=2) + '\n')
command = ['java', '-Xmx8g', '-cp', classpath, 'MainReplayCapture', str(manifest),
           'cold', str(BASE / 'main-cold-complete')]
record = {'command': command, 'performanceMeasurement': False, 'status': 'starting'}
receipt.write_text(json.dumps(record, indent=2) + '\n')
with (BASE / 'main-cold-complete-process.log').open('x') as log:
    process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT)
    record.update(status='running', pid=process.pid)
    receipt.write_text(json.dumps(record, indent=2) + '\n')
    print('Main full1267 corrected-classpath capture PID', process.pid, flush=True)
    code = process.wait()
record.update(status='exited', exitCode=code,
              classpathUnchanged=all(Path(p).is_file() and fixtures.sha(Path(p)) == digest
                                     for p, digest in inputs.items()))
receipt.write_text(json.dumps(record, indent=2) + '\n')
post = fixtures.audit(clone)
(BASE / 'main-cold-complete-postrun-fixtures.json').write_text(json.dumps(post, indent=2) + '\n')
print('Main corrected-classpath exit', code, 'original files unchanged', post['matched'], flush=True)
raise SystemExit(code)
