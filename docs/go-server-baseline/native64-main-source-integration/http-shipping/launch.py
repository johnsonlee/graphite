import datetime
import hashlib
import json
import os
from pathlib import Path
import subprocess

here = Path(__file__).resolve().parent
root = here.parents[3]
identity = json.loads((here / 'source-identity.json').read_text())
inputs = json.loads((here / 'source-inputs.json').read_text())
frozen = json.loads((here.parents[1] / 'native64-profile-a7de0bec/fixture-files.json').read_text())
clone = Path(inputs['clone'])

def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()

def save(name, value):
    (here / name).write_text(json.dumps(value, indent=2) + '\n')

def verify_sources():
    for name, expected in inputs['testedFiles'].items():
        assert digest(Path(identity['worktree']) / 'graphite-server' / name) == expected, name
    assert digest(Path(identity['binary'])) == identity['binarySHA256']

env = os.environ.copy()
removed = {key: env.pop(key, None) for key in ['GRAPHITE_NATIVE_CPU_PROFILE', 'GRAPHITE_PROFILE', 'GOGC', 'GODEBUG', 'GOMEMLIMIT', 'GOMAXPROCS']}
phases = [
    ('global64', here / 'threshold-main.json', 8, 18868),
    ('routed32', here / 'routed-main.json', 6, 18869),
    ('regression42', root / 'docs/go-server-baseline/native64-preflight/queries/observations.json', 42, 18870),
]
results = []
for name, oracle, required, port in phases:
    verify_sources()
    for item in frozen:
        path = clone / item['graphId'] / item['file']
        assert path.stat().st_size == item['bytes'] and digest(path) == item['sha256'], str(path)
    command = ['python3', str(here / 'replay-http.py'), '--binary', identity['binary'], '--out', str(here / name), '--source-identity', str(here / 'source-identity.json'), '--port', str(port), '--frozen', str(oracle), '--required', str(required)]
    record = {'name': name, 'command': command, 'required': required, 'oracleSHA256': digest(oracle), 'all1152FixtureHashesVerifiedBefore': True, 'startedUTC': datetime.datetime.now(datetime.timezone.utc).isoformat()}
    save('run-command.json', {'removedEnvironment': removed, 'completed': results, 'current': record})
    with (here / (name + '-runner.log')).open('w') as log:
        result = subprocess.run(command, env=env, stdout=log, stderr=subprocess.STDOUT)
    record['exitCode'] = result.returncode
    record['finishedUTC'] = datetime.datetime.now(datetime.timezone.utc).isoformat()
    results.append(record)
    save('run-results.json', results)
    assert result.returncode == 0, name
    summary = json.loads((here / name / 'summary.json').read_text())
    assert summary['complete'] and summary['cases'] == summary['required'] == required and not summary['mismatches'], summary
    verify_sources()
    print(name, required, 'full responses passed', flush=True)
assert len(results) == 3
save('completion.json', {'complete': True, 'required': 56, 'phaseCounts': [8, 6, 42], 'allModuleSourcesAndBinaryUnchanged': True, 'purpose': 'Correctness only; fresh process per phase, no performance claim'})
