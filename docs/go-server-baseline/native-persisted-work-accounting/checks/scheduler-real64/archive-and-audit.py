"""Archive immutable scheduler captures and independently audit their declared scope.

No Go/JVM process is started. Existing destination bytes must already match.
"""
from pathlib import Path
import collections
import csv
import gzip
import hashlib
import itertools
import json

HERE = Path(__file__).resolve().parent
CHECKS = HERE.parent
ROOT = CHECKS.parents[3]
MODULE = ROOT / 'graphite-server'
EXTERNAL = Path('/Users/johnsonlee/.codex/benchmarks/graphite/persisted-work-f0838dda-real64-v3')
STATES = ['cold', 'warm', 'startup-prepared']
observed = {}
copied = []


def sha(data):
    return hashlib.sha256(data).hexdigest()


def read(path):
    data = path.read_bytes()
    observed[str(path)] = sha(data)
    return data


def load(path):
    return json.loads(read(path))


def put(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists():
        assert path.read_bytes() == data, 'Do not replace existing evidence: ' + str(path)
    else:
        path.write_bytes(data)


for file in sorted(EXTERNAL.rglob('*')):
    if not file.is_file():
        continue
    relative = file.relative_to(EXTERNAL)
    raw = read(file)
    compress = file.suffix in ['.jsonl', '.log']
    target = HERE / 'evidence' / (str(relative) + '.gz' if compress else str(relative))
    archive = gzip.compress(raw, mtime=0) if compress else raw
    put(target, archive)
    assert (gzip.decompress(read(target)) if compress else read(target)) == raw
    copied.append(dict(source=str(file), archive=str(target.relative_to(HERE)),
                       rawBytes=len(raw), rawSHA256=sha(raw), archiveBytes=len(archive),
                       archiveSHA256=sha(archive), gzipEncoded=compress))

source = load(HERE / 'evidence/module-source.json')
files = source['files']
assert len(files) == 2568
focused = load(CHECKS / 'scheduler-focused-v1/module-inputs.json')
assert files == focused
full_inputs = load(CHECKS / 'scheduler-full-checks/inputs.json')
probe_inputs = load(CHECKS / 'probe-v5/inputs.json')


def module_part(inputs):
    return {str(Path(k).relative_to(MODULE)): h for k, h in inputs.items() if Path(k).is_relative_to(MODULE)}


assert files == module_part(full_inputs) == module_part(probe_inputs)
proof = load(CHECKS / 'scheduler-source-verification.json')
assert proof['files'] == files and proof['allComparedModuleBytesEqual']
assert proof['moduleFiles'] == 2568 and proof['checkInputs'] == len(full_inputs) == 3388
matrix_sources = load(CHECKS / 'matrix201/source-before.json')
assert files == {r['path']: r['sha256'] for r in matrix_sources}
matrix_after = load(CHECKS / 'matrix201/source-after.json')
matrix_compiled = load(CHECKS / 'matrix201/compiled-source.json')
assert matrix_after == matrix_compiled
matrix_after_files = {r['path']: r['sha256'] for r in matrix_after}
assert all(matrix_after_files[name] == expected for name, expected in files.items())
matrix_added_sources = {k: v for k, v in matrix_after_files.items() if k not in files}
assert matrix_added_sources == {'internal/query/generic_disjunction_diagnostic_test.go':
                               '8e788981cbcd8a3ef96edd5bf3257cfa9eb4ebdc7dc0c2d6c6bb8fca031d4594'}
frozen = Path(source['module'])
# Independently rehash every production/test Go source, module definition and
# sum in both workspaces. Other fixture/member hashes are bound by the complete
# source maps and the separately completed full-file source verifier.
source_files_rehashed = 0
for name, expected in files.items():
    if name.endswith(('.go', '.mod', '.sum')):
        assert sha(read(MODULE / name)) == sha(read(frozen / name)) == expected
        source_files_rehashed += 1
full_receipt = load(CHECKS / 'scheduler-full-checks/receipt.json')
assert [(s['name'], s['exitCode'], s['inputsUnchanged']) for s in full_receipt['steps']] == [
    ('context', 0, True), ('race', 0, True), ('vet', 0, True)]
full_archive = load(CHECKS / 'scheduler-full-checks/archive-verification.json')
for row in full_archive['files']:
    data = read(CHECKS / 'scheduler-full-checks' / row['archive'])
    assert sha(data) == row['archiveSHA256']
    raw = gzip.decompress(data) if row['archive'].endswith('.gz') else data
    assert sha(raw) == row['sourceSHA256'] and raw == read(Path(row['source']))
for name in ['context', 'race', 'vet']:
    assert b'FAIL' not in gzip.decompress(read(CHECKS / 'scheduler-full-checks' / (name + '.log.gz')))
probe_receipt = load(CHECKS / 'probe-v5/receipt.json')
assert probe_receipt['exitCode'] == 0 and probe_receipt['inputsUnchanged']
probe_log = gzip.decompress(read(CHECKS / 'probe-v5/query.log.gz'))
probe_archive = load(CHECKS / 'probe-v5/archive-verification.json')
assert sha(probe_log) == probe_archive['rawLogSHA256']
assert probe_log == read(Path(probe_archive['source']) / 'query.log') and b'FAIL' not in probe_log

controller = load(HERE / 'evidence/controller.json')
assert [r['state'] for r in controller] == STATES
assert all(r['exitCode'] == 1 and r['verifyExitCode'] == 0 for r in controller)
assert proof['real64'] == controller
workload_bytes = read(MODULE / 'internal/benchmarkcase/testdata/main64.json')
workload = json.loads(workload_bytes)
gate = 'Unsafe expression reached parallel string projection'
fields = ['id', 'retained', 'mappedView', 'trigrams', 'loadedFromPersistence',
          'mappedRangeCount', 'rawMatchCount', 'rawProjectionCount']
state_reports = []


def records(path):
    data = read(path)
    if path.suffix == '.gz':
        data = gzip.decompress(data)
    for line in data.splitlines():
        yield json.loads(line)


for state in STATES:
    main = ROOT / 'docs/go-server-baseline' / ('native64-fullcase-replay/main-cold-complete'
                                             if state == 'cold' else 'native64-index-states/main-' + state)
    main_records = main / 'responses.jsonl.gz'
    if not main_records.exists():
        main_records = main / 'responses.jsonl'
    assert load(main / 'actual-cases.json') == workload['cases']
    process = load(HERE / 'evidence' / ('native-' + state + '-process.json'))
    assert process['status'] == 'exited' and process['exitCode'] == 1
    assert process['inputsUnchanged'] and process['binaryUnchanged']
    assert sha(read(Path(process['command'][0]))) == process['binarySHA256']
    compiled = load(HERE / 'evidence' / ('native-' + state + '-inputs.json'))
    for path, expected in compiled.items():
        assert files[str(Path(path).relative_to(frozen))] == expected
        assert sha(read(Path(path))) == expected
    main_process = load(Path(str(main) + '-process.json'))
    assert main_process['status'] == 'exited' and main_process['exitCode'] == 1
    for prefix in [HERE / 'evidence' / ('native-' + state), main]:
        post = load(Path(str(prefix) + '-postrun-fixtures.json'))
        assert post['matched'] == post['originalFiles'] == 1152
        assert not post['changed'] and not post['missing'] and not post['added']
    phases = collections.Counter()
    outcomes = collections.Counter()
    kinds = collections.Counter()
    observations = 0
    public_differences = []
    state_differences = collections.Counter()
    observer_differences = []
    raw_nonzero = collections.Counter()
    raw_maximum = collections.Counter()
    loaded = {}
    main_cases = []
    for a, b in itertools.zip_longest(records(main_records), records(HERE / 'evidence' / ('native-' + state) / 'responses.jsonl.gz')):
        assert a is not None and b is not None and a['kind'] == b['kind']
        kind = a['kind']; kinds[kind] += 1
        if kind == 'header':
            assert a['state'] == b['state'] == state and a['graphCount'] == b['graphCount'] == 64
            assert a['caseCount'] == b['caseCount'] == 1267
            assert not a['performanceMeasurement'] and not b['performanceMeasurement']
            assert b['unavailableStateCounters'] == [] and b['workloadSHA256'] == sha(workload_bytes)
            assert [g['id'] for g in a['loaded']] == workload['sourceOrder']
            loaded = {k: sum(g[k] for g in a['loaded']) for k in ['retained', 'mappedView', 'trigrams', 'loadedFromPersistence']}
        if kind == 'case':
            assert (a['phase'], a['index'], a['id']) == (b['phase'], b['index'], b['id'])
            assert a['index'] == phases[a['phase']] and a['id'] == workload['cases'][a['index']]['id']
            phases[a['phase']] += 1
            main_cases.append(a)
            outcomes['success' if 'canonical' in a else 'error'] += 1
            for field in ['columns', 'rows', 'canonical', 'error', 'message']:
                if (field in a, a.get(field)) != (field in b, b.get(field)):
                    public_differences.append(dict(index=a['index'], field=field))
            if 'error' in a:
                assert a['index'] == 821 and a['id'] == 'four-or-graph-id-targeted'
                assert a['error'] == 'IllegalStateException' and a['message'] == gate
            if a.get('errorClass') != b.get('errorClass'):
                observer_differences.append(dict(index=a['index'], main=a.get('errorClass'), native=b.get('errorClass')))
        for key in {'header': ['loaded'], 'prepared': ['sources'], 'case': ['before', 'after']}.get(kind, []):
            assert len(a[key]) == len(b[key]) == 64
            for x, y in zip(a[key], b[key]):
                observations += 1
                for field in fields:
                    if x[field] != y[field]:
                        state_differences[field] += 1
                for field in ['rawMatchCount', 'rawProjectionCount']:
                    raw_nonzero[field] += x[field] != 0
                    raw_maximum[field] = max(raw_maximum[field], x[field])
    phase = 'warmup' if state == 'warm' else 'replay'
    assert phases == {phase: 1267} and outcomes == {'success': 1266, 'error': 1}
    assert observations == (162240 if state == 'warm' else 162304)
    assert not public_differences and not state_differences
    assert observer_differences == [dict(index=821, main='java.lang.IllegalStateException', native=None)]
    assert kinds['prepared'] == (0 if state == 'warm' else 1)
    native_log = gzip.decompress(read(HERE / 'evidence' / ('native-' + state + '-process.log.gz'))).decode()
    assert native_log.rstrip().endswith(phase + ' had 1 failed cases')
    digests = 0
    if state != 'warm':
        rows = list(csv.DictReader(read(main / 'main-observations.tsv').decode().splitlines(), delimiter='\t'))
        correctness = [line.split('|') for line in read(main / 'main-correctness.tsv').decode().splitlines()]
        assert len(rows) == len(correctness) == len(main_cases) == 1267
        for row, original, case in zip(rows, correctness, main_cases):
            assert row['id'] == original[0] == case['id']
            if 'canonical' in case:
                data = case['canonical'].encode()
                assert row['digest'] == original[13] == sha(data)
                assert int(row['rowCount']) == int(original[11]) == len(case['rows'])
                assert int(row['responseBytes']) == int(original[12]) == len(data)
                digests += 1
        assert digests == 1266
    comparison = load(HERE / 'evidence' / (state + '-comparison.json'))
    assert comparison['phaseCaseCounts'] == phases and comparison['graphStateObservations'] == observations
    assert comparison['publicDifferences'] == [] and comparison['stateDifferenceCounts'] == {}
    assert comparison['mainRawCacheNonzeroObservationCounts'] == raw_nonzero
    assert comparison['mainRawCacheMaximumCounts'] == raw_maximum
    state_reports.append(dict(state=state, phase=phase, cases=1267, outcomes=dict(outcomes),
                              graphStateObservations=observations, loadedCapabilities=loaded,
                              publicDifferences=[], stateDifferences={}, comparedStateFields=fields,
                              rawCacheNonzeroObservations=dict(raw_nonzero), rawCacheMaximum=dict(raw_maximum),
                              errorClassObserverDifferences=observer_differences,
                              mainSuccessfulManifestDigestsVerified=digests,
                              runtimeExitCode=1, verifierExitCode=0, originalAllSuccessGatePassed=False,
                              replayCompleted=state != 'warm', originalFilesUnchangedPerRuntimeReceipt=1152,
                              compiledInputsVerified=len(compiled), binarySHA256=process['binarySHA256']))

matrix = load(CHECKS / 'matrix201/comparison.json')
assert matrix['cases'] == 201 and matrix['sourceUnchanged'] and not matrix['fixtureOriginalChanged']
assert [len(c['differences']) for c in matrix['comparisons']] == [16, 16]
assert all(c['matches'] == {'public': 166, 'providerWrapper': 19} for c in matrix['comparisons'])
commands = load(CHECKS / 'matrix201/commands.json')
assert [c['exitCode'] for c in commands] == [0, 0]
prior_matrix = ROOT / 'docs/go-server-baseline/native-leading-work-accounting/checks/matrix201/go.json'
new_matrix = load(CHECKS / 'matrix201/go.json'); old_matrix = load(prior_matrix)
# Compare the complete captured Go outputs, independently of main comparison.
matrix_go_equal = new_matrix == old_matrix

report = dict(scope='Declared public and graph-state compatibility of original real64 phases, not all-success/P95 acceptance',
              stateReports=state_reports, totalCaseExecutions=3801, totalGraphStateObservations=486848,
              declaredPublicAndStateComparisonPassed=True, originalAllSuccessGatePassed=False,
              formalWarmReplayExecuted=False, p95SamplesCollected=0, p95AcceptancePassed=False,
              overallAcceptancePassed=False,
              sourceBinding=dict(moduleFiles=2568, completeManifestEquality=True,
                                 boundTo=['scheduler-focused-v1', 'probe-v5', 'scheduler-full-checks', 'real64-source-v3', 'matrix201'],
                                 goModSumFilesIndependentlyRehashedInBothWorkspaces=source_files_rehashed,
                                 fullFileHashProof='../scheduler-source-verification.json'),
              fullChecks=dict(inputCount=3388, contextExitCode=0, raceExitCode=0, vetExitCode=0),
              publicWorkOracle=dict(cases=111, operations=482, probeExitCode=0),
              diagnosticMatrix201=dict(publicMatches=166, publicCases=181, providerWrapperMatches=19,
                                       providerWrapperCases=20, differencesEachReference=16,
                                       compileExitCode=0, captureExitCode=0, completeGoOutputEqualPrevious=matrix_go_equal,
                                       originalModuleFilesUnchanged=2568, addedDiagnosticSources=matrix_added_sources,
                                       allComparedFieldsEqual=False),
              limitations=['One qualified error-class observer difference remains per state.',
                           'Warm stops after failed warmup; no prepared invocation or formal warm replay.',
                           'No P95 or new performance evidence; the 201-case matrix retains known differences.',
                           '1152-file unchanged claims are verified run receipts; graph bytes were not rehashed again by this audit.',
                           'No claim of unrelated private counters, JVM stack/FQCN parity, configured worker overrides, or global memory-budget parity.'],
              archiveFiles=len(copied), archiveBytes=sum(c['archiveBytes'] for c in copied),
              engineProcessesStartedByAudit=0, performanceMeasurements=0, inspectedInputHashes=observed)
put(HERE / 'archive-manifest.json', (json.dumps(dict(files=copied, originalsUnchanged=True), indent=2) + '\n').encode())
put(HERE / 'independent-audit.json', (json.dumps(report, indent=2) + '\n').encode())
print(json.dumps({k: v for k, v in report.items() if k != 'inspectedInputHashes'}, indent=2))
