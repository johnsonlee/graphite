"""Audit final source, complete module checks, original-main cases and real64."""
import gzip
import hashlib
import json
import subprocess
from pathlib import Path

BASE = Path(__file__).resolve().parent
ROOT = BASE.parents[2]
def read(path): return json.loads(path.read_text())
def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()
source = read(BASE / 'module-source.json')
for relative, expected in source['files'].items():
    assert sha(ROOT / 'graphite-server' / relative) == expected, relative
    assert sha(Path(source['module']) / relative) == expected, relative
tests = read(BASE / 'tests/receipt.json')
assert len(tests['steps']) == 2 and all(s['exitCode'] == 0 for s in tests['steps'])
assert tests['allInputHashesUnchanged']
for name, expected in read(BASE / 'tests/inputs.json').items():
    assert sha(Path(name)) == expected, name
oracle_artifacts = 0
for name in ['oracle-manifest.json', 'general-where-manifest.json']:
    for item in read(BASE / name)['files']:
        assert sha(BASE / item['file']) == item['sha256'], item['file']
        oracle_artifacts += 1
overlay = read(BASE / 'native-final-before-overlay.json')
for item in overlay['files']:
    if item['source'] == overlay['baselineRevision']:
        data = subprocess.check_output(['git', 'show', overlay['baselineRevision'] + ':' + item['file']], cwd=ROOT)
        assert hashlib.sha256(data).hexdigest() == item['sha256'], item['file']
receipt = read(BASE / 'native-final148-receipt.json')
assert receipt['before']['exitCode'] == 1 and receipt['after']['exitCode'] == 0
for group, primary, single, differences in [('unknownLabel', 140, 73, 60), ('generalWhere', 8, 8, 2)]:
    before, after = receipt['before'][group], receipt['after'][group]
    assert len(before['primaryDifferences']) == differences
    assert after['primaryObservations'] == primary and after['singleMatchedContextObservations'] == single
    assert not after['primaryDifferences'] and not after['singleMatchedContextDifferences']
for item in receipt['files']:
    assert sha(ROOT / item['file']) == item['sha256'], item['file']
comparisons = []
for expected_name, actual_name, count in [('main.json', 'unknown-label.json', 140),
                                        ('general-where-main.json', 'general-where.json', 8),
                                        ('../native-source-constructor/main.json', 'source-constructor.json', 298)]:
    main = read(BASE / expected_name)
    candidate = read(BASE / 'tests' / actual_name)
    assert len(main) == len(candidate) == count
    fields = ['name', 'spec', 'outcome', 'columns', 'rows', 'error', 'message', 'before', 'after']
    for expected, actual in zip(main, candidate):
        for field in fields:
            assert expected.get(field) == actual.get(field), (expected['name'], field)
        if 'matchedContextBoundary' in actual:
            for field in fields:
                assert expected.get(field) == actual['matchedContextBoundary'].get(field), (expected['name'], 'singleAux', field)
    comparisons.append(dict(oracle=expected_name, observations=count, publicAndStateDifferences=0))
main = read(BASE.parent / 'native-filtered-string-aggregation/main.json')
native = read(BASE / 'tests/filtered-count.json')
assert len(main) == len(native) == 388
for expected, actual in zip(main, native):
    for field in ['name', 'repetition', 'columns', 'rows', 'error', 'message']:
        assert expected.get(field) == actual.get(field), (expected['name'], field)
# Full module tests independently verify scalar/container types and the narrowly
# bounded scheduling-dependent retained flag using 400 actual main observations.
steps = read(BASE / 'real64/controller.json')
assert len(steps) == 3
observations = 0
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
    observations += comparison['graphStateObservations']
    receipt = read(BASE / 'real64' / ('native-' + state + '-process.json'))
    assert receipt['status'] == 'exited' and receipt['exitCode'] == 1
    assert receipt['inputsUnchanged'] and receipt['binaryUnchanged']
    for name, expected in read(BASE / 'real64' / ('native-' + state + '-inputs.json')).items():
        assert sha(Path(name)) == expected, name
assert observations == 486848
for item in read(BASE / 'capture-archives.json'):
    assert sha(BASE / item['archive']) == item['archiveSHA256']
    h, size = hashlib.sha256(), 0
    with gzip.open(BASE / item['archive'], 'rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(block); size += len(block)
    assert h.hexdigest() == item['rawSHA256'] and size == item['rawBytes']
result = dict(frozenModuleFilesVerified=len(source['files']), testInputFilesVerified=tests['inputFilesVerified'],
              actualMainOracleArtifactsVerified=oracle_artifacts, baselineOverlayFilesVerified=len(overlay['files']),
              strictPublicAndStateComparisons=comparisons, strictFilteredCountPublicObservations=388,
              real64StatesVerified=3, originalCasesPerState=1267, graphStateObservationsVerified=observations,
              originalFailureRetained=True, formalWarmReplayAvailable=False,
              performanceMeasurement=False, fullGoalComplete=False)
(BASE / 'final-audit.json').write_text(json.dumps(result, indent=2) + '\n')
print(json.dumps(result, indent=2))
