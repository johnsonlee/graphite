"""Audit fixed full-check-v4/cursor-cold-v2 evidence without executing engines."""
from pathlib import Path
import collections
import gzip
import hashlib
import itertools
import json

HERE = Path(__file__).resolve().parent
CHECKS = HERE.parent
ROOT = CHECKS.parents[3]
MODULE = ROOT / 'graphite-server'
observed = {}


def sha(data):
    return hashlib.sha256(data).hexdigest()


def read(path):
    data = path.read_bytes()
    observed[str(path)] = sha(data)
    return data


def load(path):
    return json.loads(read(path))


full = CHECKS / 'cursor-full-checks'
receipt = load(full / 'receipt.json')
assert [(s['name'], s['exitCode'], s['inputsUnchanged']) for s in receipt['steps']] == [
    ('context', 0, True), ('race', 0, True), ('vet', 0, True)]
full_archive = load(full / 'archive-verification.json')
for row in full_archive['files']:
    data = read(full / row['archive'])
    assert sha(data) == row['archiveSHA256']
    raw = gzip.decompress(data) if row['archive'].endswith('.gz') else data
    assert sha(raw) == row['sourceSHA256'] and raw == read(Path(row['source']))
logs = {}
for name in ['context', 'race', 'vet']:
    log = gzip.decompress(read(full / (name + '.log.gz'))).decode()
    assert 'FAIL' not in log
    if name == 'vet':
        assert log == ''
    else:
        assert 'ok  \tgithub.com/johnsonlee/graphite/graphite-server/internal/query' in log
    logs[name] = dict(sha256=sha(log.encode()), lastLines=log.splitlines()[-3:])
inputs = load(full / 'inputs.json')
assert len(inputs) == receipt['inputCount'] == 3300
checked_module = {str(Path(k).relative_to(MODULE)): v for k, v in inputs.items() if Path(k).is_relative_to(MODULE)}
assert len(checked_module) == 2563
focused = load(CHECKS / 'cursor-focused-v3/module-inputs.json')
assert checked_module == focused
current_module = {str(p.relative_to(MODULE)): sha(read(p)) for p in MODULE.rglob('*') if p.is_file()}
assert current_module == checked_module

cold = CHECKS / 'cursor-real64-cold'
cold_archive = load(cold / 'archive-manifest.json')
for row in cold_archive['files']:
    data = read(cold / row['archive'])
    assert (len(data), sha(data)) == (row['archiveBytes'], row['archiveSHA256'])
    raw = gzip.decompress(data) if row['archive'].endswith('.gz') else data
    assert (len(raw), sha(raw)) == (row['sourceBytes'], row['sourceSHA256'])
    assert raw == read(Path(row['source']))
source = load(cold / 'module-source.json')
assert source['files'] == checked_module
frozen = Path(source['module'])
actual_frozen = {str(p.relative_to(frozen)): sha(read(p)) for p in frozen.rglob('*') if p.is_file()}
assert actual_frozen == checked_module
compiled = load(cold / 'native-cold-inputs.json')
for name, expected in compiled.items():
    file = Path(name)
    assert sha(read(file)) == expected
    assert checked_module[str(file.relative_to(frozen))] == expected
process = load(cold / 'native-cold-process.json')
assert process['status'] == 'exited' and process['exitCode'] == 1
assert process['inputsUnchanged'] and process['binaryUnchanged']
assert sha(read(Path(process['command'][0]))) == process['binarySHA256']
controller = load(cold / 'controller.json')
assert len(controller) == 1 and controller[0]['state'] == 'cold'
assert controller[0]['exitCode'] == controller[0]['verifyExitCode'] == 1
before = load(cold / 'native-cold-preflight.json')
assert before == load(cold / 'native-cold-postrun-fixtures.json')

main_folder = CHECKS / 'main-cold-repeat-audit/evidence/capture'
workload = load(MODULE / 'internal/benchmarkcase/testdata/main64.json')
assert workload['cases'] == load(main_folder / 'actual-cases.json')


def records(path):
    data = read(path)
    if path.suffix == '.gz':
        data = gzip.decompress(data)
    for line in data.splitlines():
        yield json.loads(line)


cases = observations = 0
public_differences = []
state_differences = collections.Counter()
first_differences = {}
error_observers = []
successes = errors = 0
fields = ['id', 'retained', 'mappedView', 'trigrams', 'loadedFromPersistence',
          'mappedRangeCount', 'rawMatchCount', 'rawProjectionCount']
for main, native in itertools.zip_longest(records(main_folder / 'responses.jsonl.gz'),
                                         records(cold / 'native-cold/responses.jsonl.gz')):
    assert main is not None and native is not None and main['kind'] == native['kind']
    kind = main['kind']
    if kind == 'header':
        assert [g['id'] for g in main['loaded']] == workload['sourceOrder']
    if kind == 'case':
        assert (main['phase'], main['index'], main['id']) == (native['phase'], native['index'], native['id'])
        assert main['index'] == cases and main['id'] == workload['cases'][cases]['id']
        cases += 1
        for field in ['columns', 'rows', 'canonical', 'error', 'message']:
            if (field in main, main.get(field)) != (field in native, native.get(field)):
                public_differences.append(dict(index=main['index'], field=field))
        if 'canonical' in main:
            successes += 1
        else:
            errors += 1
            assert main['index'] == 821 and main['error'] == 'IllegalStateException'
            assert main['message'] == 'Unsafe expression reached parallel string projection'
        if main.get('errorClass') != native.get('errorClass'):
            error_observers.append(dict(index=main['index'], main=main.get('errorClass'), native=native.get('errorClass')))
    for key in {'header': ['loaded'], 'prepared': ['sources'], 'case': ['before', 'after']}.get(kind, []):
        assert len(main[key]) == len(native[key]) == 64
        where = f"{main['phase']}/{main['index']}/{key}" if kind == 'case' else key
        for a, b in zip(main[key], native[key]):
            observations += 1
            assert a['id'] == b['id']
            for field in fields:
                if a[field] != b[field]:
                    state_differences[field] += 1
                    first_differences.setdefault(field, dict(observation=where, graph=a['id'], main=a[field], native=b[field]))
assert (cases, observations, successes, errors) == (1267, 162304, 1266, 1)
assert public_differences == [] and state_differences == {'mappedRangeCount': 15162}
assert error_observers == [dict(index=821, main='java.lang.IllegalStateException', native=None)]
comparison = load(cold / 'cold-comparison.json')
assert comparison['stateDifferenceCounts'] == state_differences
assert comparison['firstStateDifferences'] == first_differences
assert comparison['publicDifferences'] == public_differences
assert not comparison['originalAllSuccessGatePassed'] and comparison['originalGraphFilesUnchangedPerRuntime'] == 1152
root_audit = load(CHECKS / 'cursor-cold-audit.json')
assert root_audit['moduleIdentityEqualsFocusedV3'] and root_audit['stateDifferenceCounts'] == state_differences
assert root_audit['unexecutedStates'] == ['warm', 'startup-prepared']
old_raw = next(r for r in load(CHECKS / 'cursor-real64-cold/archive-manifest.json')['files']
               if r['archive'] == 'native-cold/responses.jsonl.gz')['sourceSHA256']
old_captures = load(CHECKS / 'cold-repeat-audit.json')['captures']
assert all(c['responsesSHA256'] == old_raw for c in old_captures)

report = dict(scope='New terminal evidence, supplementing rather than changing the earlier cursor-stage audit',
              fullChecks=dict(contextExitCode=0, raceExitCode=0, vetExitCode=0, inputCount=3300,
                              logs=logs, originalAndArchiveHashesVerified=True),
              sourceBinding=dict(moduleFiles=2563, fullChecksEqualsFocusedV3=True,
                                 fullChecksEqualsCurrentModule=True, fullChecksEqualsReal64Manifest=True,
                                 allReal64FrozenFilesRehashed=True, compiledInputsVerified=len(compiled),
                                 binarySHA256=process['binarySHA256']),
              coldReal64=dict(cases=cases, successes=successes, originalErrors=errors,
                              graphStateObservations=observations, publicDifferences=public_differences,
                              stateDifferenceCounts=dict(state_differences), firstStateDifferences=first_differences,
                              additionalErrorClassObserverDifferences=error_observers,
                              runtimeExitCode=1, verifierExitCode=1, originalAllSuccessGatePassed=False,
                              responseBytesIdenticalToBothEarlierNativeColdCaptures=True,
                              originalFixtureFilesUnchangedPerReceipt=1152),
              real64RegressionPassed=False, overallAcceptancePassed=False,
              unexecutedStates=['warm', 'startup-prepared'],
              matrix201ExecutedForThisSource=False, p95SamplesCollected=0, p95AcceptanceProven=False,
              limitations=['The fixture receipt and archive are verified; this audit does not reread all original real64 graph bytes.',
                           'No assertion of implementation of unrelated private counters, JVM stack/FQCN parity, or global memory reservation budget.'],
              performanceMeasurements=0, engineProcessesStartedByAudit=0,
              inspectedInputHashes=observed)
(HERE / 'terminal-audit.json').write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps({k: v for k, v in report.items() if k != 'inspectedInputHashes'}, indent=2))
