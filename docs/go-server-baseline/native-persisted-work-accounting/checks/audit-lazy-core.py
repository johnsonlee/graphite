"""Bind the lazy-core candidate to terminal checks, preserving failed replay gates."""
import argparse
import hashlib
import json
from pathlib import Path


def read(path):
    return json.loads(path.read_text())


def inventory(folder):
    return {str(p.relative_to(folder)): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in folder.rglob('*') if p.is_file()}


p = argparse.ArgumentParser()
p.add_argument('--focused', type=Path, required=True)
p.add_argument('--full', type=Path, required=True)
p.add_argument('--replay', type=Path, required=True)
p.add_argument('--previous', type=Path, required=True)
p.add_argument('--output', type=Path, required=True)
a = p.parse_args()
module = Path(__file__).resolve().parents[4] / 'graphite-server'
current = inventory(module)
focused = read(a.focused / 'module-inputs.json')
assert current == focused
assert read(a.focused / 'receipt.json')['exitCode'] == 0
full_inputs = read(a.full / 'inputs.json')
checked = {str(Path(name).relative_to(module)): value
           for name, value in full_inputs.items() if Path(name).is_relative_to(module)}
assert current == checked
full = read(a.full / 'receipt.json')
assert len(full['steps']) == 3
assert all(step['exitCode'] == 0 and step['inputsUnchanged'] for step in full['steps'])
source = read(a.replay / 'module-source.json')
assert current == source['files'] == inventory(Path(source['module']))
source_receipt = read(a.replay / 'source-verification.json')
assert source_receipt['originalModuleUnchanged'] and source_receipt['frozenModuleUnchanged']
previous = inventory(a.previous)
changes = [name for name in sorted(set(current) | set(previous))
           if current.get(name) != previous.get(name)]
assert changes == ['internal/query/main_fixed_workers.go',
                   'internal/query/main_fixed_workers_test.go']
controller = read(a.replay / 'controller.json')
comparisons = {}
for step in controller:
    assert step['exitCode'] == 1  # Original main case-821 all-success failure.
    assert step['verifyExitCode'] in (0, 1)
    state = step['state']
    receipt = read(a.replay / ('native-' + state + '-process.json'))
    assert receipt['status'] == 'exited' and receipt['inputsUnchanged'] and receipt['binaryUnchanged']
    comparison = read(a.replay / (state + '-comparison.json'))
    assert comparison['originalGraphFilesUnchangedPerRuntime'] == 1152
    comparisons[state] = comparison
result = dict(moduleFiles=len(current), moduleFilesSHA256=current,
              changedFromPrevious=changes, fullCheckInputs=len(full_inputs),
              fullCheckSteps=full['steps'], controller=controller,
              comparisons=comparisons,
              unexecutedStates=[s for s in ['cold', 'warm', 'startup-prepared'] if s not in comparisons],
              performanceMeasurements=0, functionalAcceptanceProven=False,
              performanceAcceptanceProven=False)
with a.output.open('x') as output:
    json.dump(result, output, indent=2)
    output.write('\n')
print('Bound', len(current), 'files; changes:', changes)
for state, comparison in comparisons.items():
    print(state, 'public differences', len(comparison['publicDifferences']),
          'state differences', comparison['stateDifferenceCounts'])
