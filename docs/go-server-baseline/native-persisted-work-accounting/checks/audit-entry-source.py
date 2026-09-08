"""Bind the entry correction to its passing checks and failing real64 result."""
import hashlib
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
MODULE = HERE.parents[3] / 'graphite-server'
EXTERNAL = Path('/Users/johnsonlee/.codex/benchmarks/graphite')


def read(path):
    return json.loads(path.read_text())


def inventory(folder):
    return {str(p.relative_to(folder)): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in folder.rglob('*') if p.is_file()}


current = inventory(MODULE)
focused = read(HERE / 'entry-focused-v3/module-inputs.json')
assert current == focused
inputs = read(HERE / 'entry-full-checks/inputs.json')
checked = {str(Path(p).relative_to(MODULE)): h for p, h in inputs.items()
           if Path(p).is_relative_to(MODULE)}
assert current == checked
checks = read(HERE / 'entry-full-checks/receipt.json')
assert len(checks['steps']) == 3
assert all(s['exitCode'] == 0 and s['inputsUnchanged'] for s in checks['steps'])
real = EXTERNAL / 'persisted-work-f0838dda-real64-v4'
source = read(real / 'module-source.json')
assert current == source['files'] == inventory(Path(source['module']))
comparison = read(real / 'cold-comparison.json')
assert comparison['publicDifferences'] == []
assert comparison['stateDifferenceCounts'] == {'mappedRangeCount': 15162}
assert comparison['phaseCaseCounts'] == {'replay': 1267}
assert comparison['originalGraphFilesUnchangedPerRuntime'] == 1152
candidate = read(HERE / 'mapped-entry-capture/candidate-v2/baseline-module-inputs.json')
test_changes = [n for n in sorted(set(candidate) | set(current)) if candidate.get(n) != current.get(n)]
assert test_changes == ['internal/query/lazy_filtered_slot_test.go', 'internal/query/projection_context_test.go']
assert read(HERE / 'entry-symbol-migration.json')['exactChangesAreOnlyTwoObservedFunctionNames']
for name in ['plain.json', 'instrumented.json']:
    assert read(HERE / 'mapped-entry-capture/candidate-v2' / name)['differences'] == 0
repeat = EXTERNAL / 'persisted-work-f0838dda-scheduler-cold-repeat-v1'
old_source = read(repeat / 'module-source.json')
assert old_source['files'] == inventory(Path(old_source['inputModule'])) == inventory(Path(old_source['module']))
old_comparison = read(repeat / 'cold-comparison.json')
assert old_comparison['publicDifferences'] == [] and old_comparison['stateDifferenceCounts'] == {}
assert read(repeat / 'source-verification.json')['controllerExitCode'] == 0
changed = [n for n in sorted(set(current) | set(old_source['files'])) if current.get(n) != old_source['files'].get(n)]
production_changes = [n for n in changed if n.endswith('.go') and not n.endswith('_test.go')]
assert current['internal/query/main_fixed_workers.go'] == old_source['files']['internal/query/main_fixed_workers.go']
result = dict(moduleFiles=len(current), currentSourceBoundToFocusedFullChecksAndFailedCold=True,
              fullCheckInputs=len(inputs), entryOracleComparedDifferences=0,
              entryOracleSourceDifferencesOnlyTestSymbols=test_changes,
              changedFromSchedulerSource=changed, productionChanges=production_changes,
              fixedWorkerImplementationUnchanged=True, real64Cold=comparison,
              previousFrozenSourceRepeatPublicAndStateDifferences=0,
              currentWarmAndStartupPrepared='unexecuted after cold comparison failure',
              currentMatrix201='not rerun for current entry source',
              performanceMeasurements=0, functionalAcceptanceProven=False,
              performanceAcceptanceProven=False)
(HERE / 'entry-source-audit.json').write_text(json.dumps(result, indent=2) + '\n')
print('Bound', len(current), 'module files;', len(production_changes),
      'production changes; retained real64 cold failure and old-source repeat success')
