"""Run serial real64 correctness captures; accept expected main error only after verification."""
import json
from pathlib import Path
import subprocess
import sys
BASE = Path(__file__).resolve().parent
for state in ['cold', 'warm', 'startup-prepared']:
    receipt = BASE / ('native-' + state + '-process.json')
    if receipt.exists():
        previous = json.loads(receipt.read_text())
        assert previous['status'] == 'exited', 'Inspect the existing process handle; never restart a possibly live capture'
        code = previous['exitCode']
    else:
        code = subprocess.call([sys.executable, str(BASE / 'run-state.py'), 'native', state])
    assert code in [0, 1], (state, code)
    with (BASE / ('verify-' + state + '.log')).open('x') as log:
        subprocess.run([sys.executable, str(BASE / 'verify-state.py'), state], stdout=log, stderr=subprocess.STDOUT, check=True)
    report = json.loads((BASE / (state + '-comparison.json')).read_text())
    assert report['phaseCaseCounts'] == ({'warmup':1267} if state == 'warm' else {'replay':1267})
    print(state, 'full capture and all seven counters verified', flush=True)
