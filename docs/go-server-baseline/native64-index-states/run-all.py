"""Run four captures serially; preserve the original query failure gates."""
import json
from pathlib import Path
import subprocess
import sys

BASE = Path(__file__).resolve().parent
for state in ['warm', 'startup-prepared']:
    for runtime in ['main', 'native']:
        code = subprocess.call([sys.executable, str(BASE / 'run-state.py'), runtime, state])
        name = runtime + '-' + state
        receipt = json.loads((BASE / (name + '-process.json')).read_text())
        assert receipt['status'] == 'exited' and receipt['inputsUnchanged']
        assert code == receipt['exitCode'] and code in [0, 1]
        if runtime == 'native':
            assert receipt['binaryUnchanged']
        with (BASE / name / 'responses.jsonl').open() as stream:
            cases = [r for line in stream if (r := json.loads(line))['kind'] == 'case']
        # A failed warmup must remain failed. Require its full original case
        # coverage before proceeding; never bypass setupInvocation's gate.
        assert len(cases) in [1267, 2534]
        assert [r['index'] for r in cases] == list(range(1267)) * (len(cases) // 1267)
        post = json.loads((BASE / (name + '-postrun-fixtures.json')).read_text())
        assert post['matched'] == 1152 and not post['changed'] and not post['missing'] and not post['added']
        print(name, 'captured', len(cases), 'cases; proceeding serially', flush=True)
