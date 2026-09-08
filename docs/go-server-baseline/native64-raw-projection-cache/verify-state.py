"""Compare full phase coverage, public outputs, state, and original main gates."""
import argparse
import collections
import csv
import gzip
import hashlib
import json
from pathlib import Path

BASE = Path(__file__).resolve().parent
ROOT = BASE.parents[2]
parser = argparse.ArgumentParser()
parser.add_argument('state', choices=['cold', 'warm', 'startup-prepared'])
args = parser.parse_args()


def source_path(path):
    if not path.is_relative_to(BASE):
        return path
    relative = path.relative_to(BASE)
    name = relative.parts[0]
    if name.startswith('main-cold'):
        return BASE.parent / 'native64-fullcase-replay' / str(relative).replace('main-cold', 'main-cold-complete', 1)
    if name.startswith('main-'):
        return BASE.parent / 'native64-index-states' / relative
    return path


def read(path):
    return json.loads(source_path(path).read_text())


def records(name):
    path = source_path(BASE / name / 'responses.jsonl')
    with (path.open('rb') if path.exists() else gzip.open(str(path) + '.gz', 'rb')) as stream:
        return [json.loads(line) for line in stream]


workload = read(ROOT / 'graphite-server/internal/benchmarkcase/testdata/main64.json')
data = {}
loaded_capabilities = {}
for runtime in ['main', 'native']:
    name = runtime + '-' + args.state
    receipt = read(BASE / (name + '-process.json'))
    assert receipt['status'] == 'exited' and receipt.get('inputsUnchanged', receipt.get('classpathUnchanged', False))
    if runtime == 'native':
        assert receipt['binaryUnchanged']
    post = read(BASE / (name + '-postrun-fixtures.json'))
    assert post['matched'] == 1152 and not post['changed'] and not post['missing'] and not post['added']
    data[runtime] = records(name)
    header = data[runtime][0]
    assert header['kind'] == 'header' and header['state'] == args.state
    assert header['graphCount'] == 64 and header['caseCount'] == 1267
    assert header['performanceMeasurement'] is False
    assert [r['id'] for r in header['loaded']] == workload['sourceOrder']
    loaded_capabilities[runtime] = {field: sum(r[field] for r in header['loaded'])
                                   for field in ['retained', 'mappedView', 'trigrams', 'loadedFromPersistence']}
assert read(BASE / ('main-' + args.state) / 'actual-cases.json') == workload['cases']
assert data['native'][0]['unavailableStateCounters'] == []
assert data['native'][0]['workloadSHA256'] == hashlib.sha256((ROOT / 'graphite-server/internal/benchmarkcase/testdata/main64.json').read_bytes()).hexdigest()
assert [r['kind'] for r in data['main']] == [r['kind'] for r in data['native']]
fields = ['id', 'retained', 'mappedView', 'trigrams', 'loadedFromPersistence', 'mappedRangeCount', 'rawMatchCount', 'rawProjectionCount']
public_fields = ['columns', 'rows', 'canonical', 'error', 'message']
differences = []
state_differences = collections.Counter()
state_first = {}
states_checked = 0
phases = collections.Counter()
phase_outcomes = collections.defaultdict(collections.Counter)
errors = []
raw_nonzero = collections.Counter()
raw_maximum = collections.Counter()
observer_differences = []


def compare_states(main, native, label):
    global states_checked
    assert len(main) == len(native) == 64
    for m, n in zip(main, native):
        states_checked += 1
        for field in fields:
            if m[field] != n[field]:
                state_differences[field] += 1
                state_first.setdefault(field, {'observation': label, 'graph': m['id'], 'main': m[field], 'native': n[field]})
        for field in ['rawMatchCount', 'rawProjectionCount']:
            raw_nonzero[field] += m[field] != 0
            raw_maximum[field] = max(raw_maximum[field], m[field])


for m, n in zip(data['main'], data['native']):
    if m['kind'] == 'header':
        compare_states(m['loaded'], n['loaded'], 'loaded')
    elif m['kind'] == 'prepared':
        compare_states(m['sources'], n['sources'], 'prepared')
    elif m['kind'] == 'case':
        assert (m['phase'], m['index'], m['id']) == (n['phase'], n['index'], n['id'])
        phase = m['phase']
        assert m['index'] == phases[phase]
        assert m['id'] == workload['cases'][m['index']]['id']
        phases[phase] += 1
        phase_outcomes[phase]['success' if 'canonical' in m else 'error'] += 1
        changed = [field for field in public_fields if (field in m, m.get(field)) != (field in n, n.get(field))]
        if changed:
            differences.append({'phase': phase, 'index': m['index'], 'id': m['id'], 'fields': changed})
        if 'error' in m:
            errors.append({key: m[key] for key in ['phase', 'index', 'id', 'error', 'message']})
        if m.get('errorClass') != n.get('errorClass'):
            observer_differences.append({'phase': phase, 'index': m['index'], 'field': 'errorClass',
                                         'main': m.get('errorClass'), 'native': n.get('errorClass')})
        for when in ['before', 'after']:
            compare_states(m[when], n[when], phase + '/' + str(m['index']) + '/' + when)
assert all(count == 1267 for count in phases.values())
if args.state == 'warm':
    assert phases['warmup'] == 1267
else:
    assert phases['replay'] == 1267
main_receipt = read(BASE / ('main-' + args.state + '-process.json'))
native_receipt = read(BASE / ('native-' + args.state + '-process.json'))
assert main_receipt['exitCode'] == native_receipt['exitCode']
manifest_verified = 0
observations = source_path(BASE / ('main-' + args.state) / 'main-observations.tsv')
if observations.exists():
    rows = list(csv.DictReader(observations.open(), delimiter='\t'))
    captured = [r for r in data['main'] if r['kind'] == 'case' and r['phase'] == 'replay']
    correctness = [line.split('|') for line in source_path(BASE / ('main-' + args.state) / 'main-correctness.tsv').read_text().splitlines()]
    assert len(rows) == len(captured) == len(correctness) == 1267
    for row, case, original in zip(rows, captured, correctness):
        assert row['id'] == case['id'] == original[0]
        if 'canonical' in case:
            canonical = case['canonical'].encode('utf-8')
            assert row['outcome'] == original[10] == 'success'
            assert row['digest'] == original[13] == hashlib.sha256(canonical).hexdigest()
            assert int(row['responseBytes']) == int(original[12]) == len(canonical)
            assert int(row['rowCount']) == int(original[11]) == len(case['rows'])
            manifest_verified += 1
        else:
            assert row['outcome'] == original[10] == 'failed'
report = {'state': args.state, 'caseDefinitionsExactlyEqual': True,
          'loadedGraphCapabilityCounts': loaded_capabilities,
          'phaseCaseCounts': dict(phases), 'mainPhaseOutcomes': {k: dict(v) for k, v in phase_outcomes.items()},
          'publicDifferences': differences, 'stateDifferenceCounts': dict(state_differences),
          'firstStateDifferences': state_first, 'graphStateObservations': states_checked,
          'errors': errors, 'observerFieldDifferences': observer_differences,
          'nativeUnavailableStateCounters': data['native'][0]['unavailableStateCounters'],
          'mainRawCacheNonzeroObservationCounts': dict(raw_nonzero), 'mainRawCacheMaximumCounts': dict(raw_maximum),
          'mainManifestSuccessfulDigestsVerified': manifest_verified,
          'invocationPrepared': any(r['kind'] == 'prepared' for r in data['main']),
          'replayPhaseCovered': phases.get('replay', 0) == 1267,
          'originalAllSuccessGatePassed': main_receipt['exitCode'] == 0,
          'runtimeExitCode': main_receipt['exitCode'], 'originalGraphFilesUnchangedPerRuntime': 1152,
          'performanceMeasurement': False}
(BASE / (args.state + '-comparison.json')).write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps(report, indent=2))
assert not differences and not state_differences, 'Captured runtime behavior differs'
