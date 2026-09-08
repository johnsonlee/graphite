"""Independent cursor evidence audit; reads artifacts, never starts Go or Java."""
from pathlib import Path
import gzip
import hashlib
import itertools
import json
import tarfile

HERE = Path(__file__).resolve().parent
CHECKS = HERE.parent
ORACLE = CHECKS.parent
ROOT = ORACLE.parents[2]
MODULE = ROOT / 'graphite-server'
observed = {}


def digest(data):
    return hashlib.sha256(data).hexdigest()


def read(path):
    data = path.read_bytes()
    observed[str(path)] = digest(data)
    return data


def load(path):
    return json.loads(read(path))


def archive_members(path):
    read(path)
    with tarfile.open(path) as archive:
        result = {}
        for member in archive:
            if not member.isfile():
                continue
            assert member.name not in result
            result[member.name] = digest(archive.extractfile(member).read())
        return result


focused = []
modules = []
for version, expected_exit in [('v1', 1), ('v2', 0), ('v3', 0)]:
    folder = CHECKS / ('cursor-focused-' + version)
    verification = load(folder / 'archive-verification.json')
    receipt = load(folder / 'receipt.json')
    assert receipt['exitCode'] == expected_exit and receipt['inputsUnchanged']
    external = Path(verification['externalSource'])
    for item in verification['verified']:
        data = read(folder / item['file'])
        if 'uncompressedSha256' in item:
            data = gzip.decompress(data)
            assert digest(data) == item['uncompressedSha256']
            assert data == read(external / 'test.log')
        else:
            assert digest(data) == item['sha256']
            assert data == read(external / item['file'])
    manifest = load(folder / 'module-inputs.json')
    archive = Path(verification['sourceArchive'])
    assert digest(read(archive)) == receipt['sourceArchiveSha256'] == verification['sourceArchiveSha256']
    assert archive_members(archive) == manifest
    assert len(manifest) == receipt['moduleFiles'] == 2563
    modules.append(manifest)
    log = gzip.decompress(read(folder / 'test.log.gz')).decode()
    failures = [line for line in log.splitlines() if line.startswith('--- FAIL: ')]
    if expected_exit:
        assert len(failures) == 2
        assert any('TestMainMappedWarmCursorReadsEachOrderOnlyOnAdvance' in line for line in failures)
        assert any('TestMainMappedMatchingRetainedFallback' in line for line in failures)
    else:
        assert 'FAIL' not in log and log.count('\nPASS\n') == 2
    if version == 'v3':
        assert sum('--- PASS: TestMainStringMappedColdWarmDemandAndCancellation/' in line
                   for line in log.splitlines()) == 8
    focused.append(dict(version=version, exitCode=expected_exit, moduleFiles=len(manifest),
                        sourceArchiveSha256=digest(read(archive)), failures=failures,
                        archivedAndExternalArtifactsEqual=True, completeSourceArchiveMatches=True))

changed = sorted(k for k in modules[0].keys() | modules[1].keys() if modules[0].get(k) != modules[1].get(k))
assert changed == ['internal/query/main_mapped_iteration_test.go', 'internal/store/main_mapped_cursor_test.go']
migration_changed = sorted(k for k in modules[1].keys() | modules[2].keys() if modules[1].get(k) != modules[2].get(k))
assert migration_changed == ['internal/query/main_string_source_test.go']

folder = CHECKS / 'probe-v4'
verification = load(folder / 'archive-verification.json')
receipt = load(folder / 'receipt.json')
assert receipt['exitCode'] == 0 and receipt['inputsUnchanged']
for name, expected in verification['copiedFiles'].items():
    assert digest(read(folder / name)) == expected
    if not name.endswith('.gz'):
        assert read(folder / name) == read(Path(verification['source']) / name)
log = gzip.decompress(read(folder / 'query.log.gz'))
assert digest(log) == verification['rawLogSHA256']
assert log == read(Path(verification['source']) / 'query.log') and b'FAIL' not in log
inputs = load(folder / 'inputs.json')
probe_module = {str(Path(k).relative_to(MODULE)): h for k, h in inputs.items() if Path(k).is_relative_to(MODULE)}
assert probe_module == modules[1]
archive = Path(verification['externalFullModuleSnapshot']['path'])
assert digest(read(archive)) == verification['externalFullModuleSnapshot']['sha256']
assert archive_members(archive) == probe_module
public_groups = {'TestPersistedWorkAccountingMainOracle': (52, 187),
                 'TestMappedWorkAccountingMainOracle': (44, 220),
                 'TestMappedWorkLargeAccountingMainOracle': (7, 35),
                 'TestBuildTrigramWorkAccountingMainOracle': (8, 40)}
for name in public_groups:
    assert ('--- PASS: ' + name + ' ').encode() in log
current_source_mismatches = [name for name, h in probe_module.items()
                             if name.endswith(('.go', '.mod', '.sum')) and digest(read(MODULE / name)) != h]

folder = ORACLE / 'mapped-cursor-oracle'
verification = load(folder / 'archive-verification.json')
for item in verification['artifacts']:
    data = read(folder / item['file'])
    assert (len(data), digest(data)) == (item['bytes'], item['sha256'])
    assert data == read(Path(item['source']))
first = load(folder / 'main.json')
assert first == load(folder / 'main-capture/main.json') == load(folder / 'repeat-capture/main.json')
assert len(first['cases']) == 11 and sum(len(c['operations']) for c in first['cases']) == 19
fixtures = archive_members(folder / 'fixtures.tar.gz')
for capture in ['main-capture', 'repeat-capture']:
    before = load(folder / capture / 'fixture-before.json')
    assert before == load(folder / capture / 'fixture-after.json')
    assert fixtures == {r['file']: r['sha256'] for r in before} and len(fixtures) == 34
    capture_receipt = load(folder / capture / 'receipt.json')
    assert capture_receipt['inputsUnchanged']

folder = CHECKS / 'main-cold-repeat-audit'
verification = load(folder / 'archive-verification.json')
for item in verification['copied']:
    data = read(folder / item['file'])
    assert (len(data), digest(data)) == (item['archiveBytes'], item['archiveSHA256'])
    raw = gzip.decompress(data) if item['gzipEncoded'] else data
    assert (len(raw), digest(raw)) == (item['rawBytes'], item['rawSHA256'])
    assert raw == read(Path(item['source']))
old = ROOT / 'docs/go-server-baseline/native64-fullcase-replay/main-cold-complete/responses.jsonl.gz'
if not old.exists():
    old = old.with_suffix('')
new = folder / 'evidence/capture/responses.jsonl.gz'


def records(path):
    data = read(path)
    if path.suffix == '.gz':
        data = gzip.decompress(data)
    for line in data.splitlines():
        yield json.loads(line)


case_count = state_count = 0
for a, b in itertools.zip_longest(records(old), records(new)):
    assert a == b
    if a['kind'] == 'case':
        case_count += 1
        state_count += len(a['before']) + len(a['after'])
    elif a['kind'] == 'header':
        state_count += len(a['loaded'])
    elif a['kind'] == 'prepared':
        state_count += len(a['sources'])
assert (case_count, state_count) == (1267, 162304)
comparison = load(folder / 'evidence/comparison.json')
assert comparison['mainRuntimeExitCode'] == 1
assert comparison['originalMainComparison']['stateDifferenceCounts'] == {}
assert comparison['originalMainComparison']['publicDifferences'] == []
for i, capture in enumerate(verification['nativeExternalResponses'], 1):
    native = Path(capture['directory']) / 'native-cold/responses.jsonl'
    data = read(native)
    assert (len(data), digest(data)) == (capture['responsesBytes'], capture['responsesSHA256'])
    mismatches = 0
    for a, b in itertools.zip_longest(records(new), records(native)):
        assert a['kind'] == b['kind']
        if a['kind'] == 'case':
            for field in ['columns', 'rows', 'canonical', 'error', 'message']:
                assert (field in a, a.get(field)) == (field in b, b.get(field))
        for key in {'header': ['loaded'], 'prepared': ['sources'], 'case': ['before', 'after']}.get(a['kind'], []):
            for x, y in zip(a[key], b[key]):
                assert x['id'] == y['id']
                mismatches += x['mappedRangeCount'] != y['mappedRangeCount']
    assert mismatches == 15162

old_checks = load(CHECKS / 'evidence/inputs.json')
old_module = {str(Path(k).relative_to(MODULE)): h for k, h in old_checks.items() if Path(k).is_relative_to(MODULE)}
changed_since_old_checks = sorted(k for k in old_module.keys() | probe_module.keys()
                                  if old_module.get(k) != probe_module.get(k))
assert changed_since_old_checks
full_folder = CHECKS / 'cursor-first-full-check'
full_verification = load(full_folder / 'archive-verification.json')
for item in full_verification['files']:
    data = read(full_folder / item['archive'])
    assert digest(data) == item['archiveSHA256']
    raw = gzip.decompress(data) if item['archive'].endswith('.gz') else data
    assert digest(raw) == item['sourceSHA256'] and raw == read(Path(item['source']))
full_receipt = load(full_folder / 'receipt.json')
assert [(s['name'], s['exitCode']) for s in full_receipt['steps']] == [('context', 0), ('race', 1)]
full_log = gzip.decompress(read(full_folder / 'race.log.gz')).decode()
full_failures = [line for line in full_log.splitlines() if '--- FAIL:' in line]
assert len(full_failures) == 5
assert all('TestMainStringMappedColdWarmDemandAndCancellation' in line for line in full_failures)
workload = load(CHECKS / 'cursor-workload-recheck.json')
native_export = read(ROOT / workload['native']['path'])
assert digest(native_export) == workload['native']['sha256']
for reference in workload['originalFullExports']:
    assert read(ROOT / reference['path']) == native_export
fresh_cases = load(ROOT / workload['freshMainColdCases']['path'])
assert fresh_cases == json.loads(native_export)['cases'] and len(fresh_cases) == 1267
report = dict(scope='Independent artifact audit of the cursor stage, not overall acceptance',
              focused=focused, focusedOnlyChangedFiles=changed, focusedProductionUnchanged=True,
              cancellationMigration=dict(focusedV3ExitCode=0, changedFiles=migration_changed,
                                         productionUnchanged=True, actualCancellationAndRowControls=8),
              probe=dict(exitCode=0, cases=111, operations=482, moduleFiles=2563,
                         fullSourceMatchesFocusedV2=True, currentGoSourceMismatches=current_source_mismatches),
              cursorMethodOracle=dict(cases=11, operations=19, completeRepeatJSONEqual=True,
                                      copiedArtifactsVerified=len(load(ORACLE / 'mapped-cursor-oracle/archive-verification.json')['artifacts']),
                                      unchangedFixtureFiles=34, publicCypherOracle=False),
              originalMainRepeat=dict(cases=case_count, graphStateObservations=state_count,
                                      completeParsedRecordsEqual=True, runtimeExitCode=1,
                                      originalAllSuccessGatePassed=False),
              earlierNativeReal64=dict(repeats=2, mappedRangeCountDifferencesEach=15162,
                                      coveredByNewCursorSource=False, acceptancePassed=False),
              priorFullChecks=dict(sourceMatchesNewCursorSource=False, changedFiles=changed_since_old_checks,
                                   usableAsCurrentFullCheck=False),
              cursorFirstFullChecks=dict(contextExitCode=0, raceExitCode=1, vetExecuted=False,
                                         failures=full_failures, archivedAndExternalArtifactsEqual=True,
                                         failureObserver='The archived test observes runtime.Caller suffix .ProjectionNodeOrder; its four subcases recorded zero such calls.'),
              workloadIdentity=dict(fullExportsByteIdentical=True, freshMainArrayCasesEqual=True, cases=1267),
              pending=['New-source full context/race/vet evidence and source binding',
                       'New-source complete real64 replay and state comparison'],
              performanceMeasurements=0, p95AcceptanceProven=False, overallAcceptancePassed=False,
              inspectedInputHashes=observed)
(HERE / 'audit.json').write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps({k: v for k, v in report.items() if k != 'inspectedInputHashes'}, indent=2))
