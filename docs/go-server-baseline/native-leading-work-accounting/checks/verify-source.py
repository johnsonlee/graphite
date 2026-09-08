"""Bind final module bytes to checks, real64, and the complete diagnostic matrix."""
import argparse
import hashlib
import json
from pathlib import Path


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


p = argparse.ArgumentParser()
p.add_argument('--checks', type=Path, required=True)
p.add_argument('--real64', type=Path, required=True)
p.add_argument('--matrix', type=Path, required=True)
p.add_argument('--output', type=Path, required=True)
a = p.parse_args()
root = Path(__file__).resolve().parents[4]
module = root / 'graphite-server'
files = {str(f.relative_to(module)): sha(f) for f in module.rglob('*') if f.is_file()}
inputs = json.loads((a.checks / 'inputs.json').read_text())
check_files = {str(Path(f).relative_to(module)): h for f, h in inputs.items() if Path(f).is_relative_to(module)}
assert files == check_files
checks = json.loads((a.checks / 'receipt.json').read_text())
assert len(checks['steps']) == 3 and all(s['exitCode'] == 0 and s['inputsUnchanged'] for s in checks['steps'])
real = json.loads((a.real64 / 'module-source.json').read_text())
assert files == real['files']
frozen = Path(real['module'])
assert files == {str(f.relative_to(frozen)): sha(f) for f in frozen.rglob('*') if f.is_file()}
controller = json.loads((a.real64 / 'controller.json').read_text())
assert [s['state'] for s in controller] == ['cold', 'warm', 'startup-prepared']
assert all(s['exitCode'] == 1 and s['verifyExitCode'] == 0 for s in controller)
matrix = json.loads((a.matrix / 'source-before.json').read_text())
assert files == {r['path']: r['sha256'] for r in matrix}
comparison = json.loads((a.matrix / 'comparison.json').read_text())
assert comparison['cases'] == 201 and comparison['sourceUnchanged'] and comparison['fixtureOriginalChanged'] == []
commands = json.loads((a.matrix / 'commands.json').read_text())
assert len(commands) == 2 and all(c['exitCode'] == 0 for c in commands)
result = dict(moduleFiles=len(files), allComparedModuleBytesEqual=True,
              checkInputs=len(inputs), checks=checks['steps'], real64=controller,
              matrixCases=201, matrixComparedDifferences=[len(c['differences']) for c in comparison['comparisons']],
              files=files, performanceMeasurements=0, performanceAcceptanceProven=False)
a.output.write_text(json.dumps(result, indent=2) + '\n')
print('Bound', len(files), 'module files to checks, all real64 states and the 201-case matrix')
