"""Independently audit final source, tests, oracle, and real64 evidence."""
import hashlib
import json
from pathlib import Path
import subprocess

BASE = Path(__file__).resolve().parent
ROOT = BASE.parents[2]


def read(path):
    return json.loads(path.read_text())


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


source = read(BASE / 'module-source.json')
for relative, expected in source['files'].items():
    assert sha(ROOT / 'graphite-server' / relative) == expected, relative
    assert sha(Path(source['module']) / relative) == expected, relative
tests = read(BASE / 'tests/receipt.json')
assert len(tests['steps']) == 2 and all(s['exitCode'] == 0 for s in tests['steps'])
assert tests['allInputHashesUnchanged']
for name, expected in read(BASE / 'tests/inputs.json').items():
    assert sha(Path(name)) == expected, name
oracle = read(BASE / 'oracle-manifest.json')
for entry in oracle['files']:
    assert sha(BASE / entry['file']) == entry['sha256'], entry['file']
for entry in read(BASE / 'oracle-inputs.json'):
    assert sha(Path(entry['path'])) == entry['sha256'], entry['path']
native = read(BASE / 'native-verification.json')
for entry in native['artifacts']:
    assert sha(BASE / entry['file']) == entry['sha256'], entry['file']
assert sha(ROOT / native['testFile']['file']) == native['testFile']['sha256']
overlay = read(BASE / 'native-final-before-overlay.json')
for entry in overlay['originalFiles']:
    data = subprocess.check_output(['git', 'show', overlay['baseline'] + ':' + entry['file']], cwd=ROOT)
    assert hashlib.sha256(data).hexdigest() == entry['sha256'], entry['file']
main = read(BASE / 'main.json')
candidate = read(BASE / 'tests/source-constructor.json')
assert len(main) == len(candidate) == 298
for expected, actual in zip(main, candidate):
    for field in ['name', 'spec', 'outcome', 'columns', 'rows', 'error', 'message', 'before', 'after']:
        assert expected.get(field) == actual.get(field), (expected['name'], field)
steps = read(BASE / 'real64/controller.json')
assert len(steps) == 3
count = 0
for step in steps:
    assert step['exitCode'] == 1 and step['verifyExitCode'] == 0
    state = step['state']
    comparison = read(BASE / 'real64' / (state + '-comparison.json'))
    assert comparison['caseDefinitionsExactlyEqual']
    assert comparison['publicDifferences'] == [] and comparison['stateDifferenceCounts'] == {}
    assert not comparison['originalAllSuccessGatePassed'] and not comparison['performanceMeasurement']
    assert comparison['originalGraphFilesUnchangedPerRuntime'] == 1152
    assert sum(comparison['phaseCaseCounts'].values()) == 1267
    assert comparison['replayPhaseCovered'] == (state != 'warm')
    count += comparison['graphStateObservations']
    receipt = read(BASE / 'real64' / ('native-' + state + '-process.json'))
    assert receipt['status'] == 'exited' and receipt['exitCode'] == 1
    assert receipt['inputsUnchanged'] and receipt['binaryUnchanged']
    inputs = read(BASE / 'real64' / ('native-' + state + '-inputs.json'))
    for name, expected in inputs.items():
        assert sha(Path(name)) == expected, name
assert count == 486848
result = dict(frozenModuleFilesVerified=len(source['files']), testInputFilesVerified=tests['inputFilesVerified'],
              actualMainOracleArtifactsVerified=len(oracle['files']), baselineGoOverlayFilesVerified=len(overlay['originalFiles']),
              strictPublicAndStateOracleObservationsVerified=298, real64StatesVerified=3,
              originalCasesPerState=1267, graphStateObservationsVerified=count,
              originalFailureRetained=True, formalWarmReplayAvailable=False, performanceMeasurement=False,
              fullGoalComplete=False)
(BASE / 'final-audit.json').write_text(json.dumps(result, indent=2)+'\n')
print(json.dumps(result, indent=2))
