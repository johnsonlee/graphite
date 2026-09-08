"""Archive the completed diagnostic without executing query or fixture code."""
import hashlib
import json
from pathlib import Path
import shutil
import sys

HERE = Path(__file__).resolve().parent


def sha(path):
    digest = hashlib.sha256()
    with path.open('rb') as file:
        for block in iter(lambda: file.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def inventory(folder):
    result = []
    for path in sorted(folder.rglob('*')):
        assert not path.is_symlink(), str(path)
        if path.is_file():
            result.append(dict(file=str(path.relative_to(folder)), bytes=path.stat().st_size, sha256=sha(path)))
    return result


def read(path):
    return json.loads(path.read_text())


source = Path(sys.argv[1]).resolve(strict=True)
receipt = read(source / 'receipt.json')
assert receipt['exitCode'] == 0
assert all(receipt[k] for k in ['fixtureFilesUnchanged', 'moduleUnchanged', 'moduleSourceUnchanged'])
original = read(source / 'original-module-files.json')
executed = read(source / 'executable-module-files.json')
assert inventory(Path(receipt['moduleSource'])) == original
assert inventory(Path(receipt['cwd'])) == executed
original_map = {r['file']: r for r in original}
executed_map = {r['file']: r for r in executed}
overlay = 'internal/query/cursor_real64_diagnostic_test.go'
assert set(executed_map) - set(original_map) == {overlay}
assert set(original_map) - set(executed_map) == set()
assert all(executed_map[name] == value for name, value in original_map.items())
actual_test = Path(receipt['cwd']) / overlay
assert sha(actual_test) == sha(HERE / 'diagnostic_test.go.txt') == receipt['diagnosticTemplateSHA256']
assert sha(source / 'runner.py') == sha(HERE / 'run.py') == receipt['runnerSHA256']
raw_files = sorted(path for path in source.iterdir() if path.is_file())
assert len(raw_files) == 15
destination = HERE / 'evidence'
destination.mkdir(exist_ok=False)
copies = []
for path in raw_files:
    copy = destination / path.name
    shutil.copyfile(path, copy)
    assert sha(copy) == sha(path)
    copies.append(dict(file=path.name, bytes=path.stat().st_size, sha256=sha(path)))
copied_test = HERE / 'executed-diagnostic_test.go.txt'
shutil.copyfile(actual_test, copied_test)
assert sha(copied_test) == receipt['diagnosticTemplateSHA256']
before = read(destination / 'clones-before.json')
assert before == read(destination / 'clones-after.json')
assert before == read(destination / 'reference-selected-before.json') == read(destination / 'reference-selected-after.json')
comparison = read(destination / 'prepared-state-comparison.json')
assert len(comparison) == 6 and all(not r['preparedVersusV2BeforeDifferences'] for r in comparison)
affected_path = HERE.parent / 'cursor-case3-affected-states.json'
affected = read(affected_path)
main_before = {r['id']: r for r in affected['main']['before']}
native_before = {r['id']: r for r in affected['native']['before']}
main_after = {r['id']: r for r in affected['main']['after']}
native_after = {r['id']: r for r in affected['native']['after']}
result = read(destination / 'result.json')
observations = []
for record in result['records']:
    graph_id = record['id']
    assert record['stage'] == 'complete' and record['eof']
    assert not record.get('failure') and record['closeError'] is None
    assert record['v2Case3Before'] == main_before[graph_id] == native_before[graph_id]
    assert all(record['prepared'][k] == v for k, v in main_before[graph_id].items() if k != 'id')
    count = record['afterIteratorConstruction']['mappedRangeCount']
    assert count == record['afterIteration']['mappedRangeCount'] == main_after[graph_id]['mappedRangeCount']
    observations.append(dict(id=graph_id, returnedIDs=len(record['ids']), exactMatchingStrings=len(record['exactMatchingStrings']),
                             diagnosticRangeCount=count, originalMainAfterRangeCount=main_after[graph_id]['mappedRangeCount'],
                             originalNativeAfterRangeCount=native_after[graph_id]['mappedRangeCount']))
verification = dict(externalSource=str(source), copiedRawFiles=copies, rawFileCount=len(copies),
                    originalModuleFiles=len(original), executedModuleFiles=len(executed),
                    onlyModuleDifference=overlay, executedTestSHA256=sha(copied_test),
                    actualModuleInventoriesRehashed=True,
                    allSelectedFixtureFileCount=sum(map(len, before.values())),
                    recordedReferenceAndCloneBeforeAfterIdentical=True,
                    affectedStateEvidenceSHA256=sha(affected_path), observations=observations,
                    scope='Same observed state, fresh independently opened graphs, private iterator without local cancellation; not full-history or public-route parity.',
                    runtimeExecutions=0)
(HERE / 'archive-verification.json').write_text(json.dumps(verification, indent=2) + '\n')
print(json.dumps({k: v for k, v in verification.items() if k != 'copiedRawFiles'}, indent=2))
