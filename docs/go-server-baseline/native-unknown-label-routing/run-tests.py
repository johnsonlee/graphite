"""Run full native module race tests and vet against frozen input hashes."""
import hashlib
import json
import os
from pathlib import Path
import subprocess

BASE = Path(__file__).resolve().parent
ROOT = BASE.parents[2]
MODULE = ROOT / 'graphite-server'
OUT = BASE / 'tests'
OUT.mkdir()
GO = '/opt/homebrew/Cellar/go/1.22.0/libexec/bin/go'
inputs = {str(p): hashlib.sha256(p.read_bytes()).hexdigest()
          for p in MODULE.rglob('*') if p.is_file()}
# All committed oracle inputs used by module tests, plus the new corpus.
for parent in (BASE.parent,):
    for p in parent.rglob('*.json'):
        if 'real64' not in p.parts and (p.name in ('cases.json', 'main.json', 'source-fixture.json', 'mutations.json', 'error-scheduling-main.json') or p.name.startswith('general-where-')):
            inputs[str(p)] = hashlib.sha256(p.read_bytes()).hexdigest()
(OUT / 'inputs.json').write_text(json.dumps(inputs, indent=2) + '\n')
overrides = dict(UNKNOWN_LABEL_OUTPUT=str(OUT / 'unknown-label.json'),
                 SOURCE_CONSTRUCTOR_OUTPUT=str(OUT / 'source-constructor.json'),
                 FILTERED_COUNT_OUTPUT=str(OUT / 'filtered-count.json'),
                 GENERAL_WHERE_OUTPUT=str(OUT / 'general-where.json'))
env = dict(os.environ, **overrides)
steps = []
for command, name in [([GO, 'test', '-race', '-count=1', './...'], 'race.log'),
                      ([GO, 'vet', './...'], 'vet.log')]:
    with (OUT / name).open('x') as log:
        result = subprocess.run(command, cwd=MODULE, env=env, stdout=log, stderr=subprocess.STDOUT)
    steps.append(dict(command=command, exitCode=result.returncode, log=name))
    unchanged = all(hashlib.sha256(Path(p).read_bytes()).hexdigest() == h for p, h in inputs.items())
    receipt = dict(steps=steps, environmentOverrides=overrides,
                   allInputHashesUnchanged=unchanged, inputFilesVerified=len(inputs))
    (OUT / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print(json.dumps(steps[-1]), flush=True)
    assert unchanged, 'Test inputs changed during execution'
    assert result.returncode == 0, 'Required module check failed'
