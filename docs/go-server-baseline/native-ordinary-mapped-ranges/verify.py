import hashlib
import json
from pathlib import Path

BASE = Path(__file__).resolve().parent
main = json.loads((BASE / 'main.json').read_text())
baseline = json.loads((BASE / 'native-baseline.json').read_text())
candidate = json.loads((BASE / 'native-candidate-2.json').read_text())
assert len(main) == len(baseline) == len(candidate) == 6
public_differences = []
queries = successes = failures = observations = 0
for m, n in zip(main, baseline):
    assert len(m['steps']) == len(n['steps']) == 7
    states = [m['loaded']] + [s[k] for s in m['steps'] for k in ['before', 'after']]
    observations += len(states)
    for state in states:
        for field in ['rawMatchCount', 'rawProjectionCount']:
            assert state[field] == 0, 'Raw cache activity requires a corresponding native observer'
            del state[field]
    for i, (ms, ns) in enumerate(zip(m['steps'], n['steps'])):
        if ms['query'] == 'clear':
            continue
        queries += 1
        successes += 'rows' in ms
        failures += 'error' in ms
        fields = [k for k in ['columns', 'rows', 'error', 'message']
                  if (k in ms, ms.get(k)) != (k in ns, ns.get(k))]
        if fields:
            public_differences.append({'fixture': m['fixture'], 'step': i, 'fields': fields})
assert main == candidate, 'Full response or observed first-source state differs'
assert len(public_differences) == 7
expanded = json.loads((BASE / 'main-expanded.json').read_text())
final = json.loads((BASE / 'native-candidate-4.json').read_text())
reset_control = json.loads((BASE / 'native-reset-control.json').read_text())
for record in expanded:
    assert len(record['steps']) == 8
    for state in [record['loaded']] + [s[k] for s in record['steps'] for k in ['before', 'after']]:
        for field in ['rawMatchCount', 'rawProjectionCount']:
            assert state[field] == 0
            del state[field]
assert expanded == final
# The isolated negative control omits only the mapped-view preservation change.
# These four histories also preserve public outputs; their cache reset is the failure.
for expected, control in zip(expanded[:4], reset_control[:4]):
    assert expected['steps'][3]['after']['mappedRangeCount'] == 1
    assert control['steps'][3]['after']['mappedRangeCount'] == 0
annotation = json.loads((BASE / 'annotation-positive-main.json').read_text())
annotation_native = json.loads((BASE / 'annotation-native.json').read_text())
for step in annotation:
    for when in ['before', 'after']:
        for state in step[when]:
            for field in ['rawMatchCount', 'rawProjectionCount']:
                assert state[field] == 0
                del state[field]
assert annotation == annotation_native
for step in annotation[1:]:
    assert step['rows'] == [{'x': 'example.Other', 'y': None,
                            '$metadata': {'graphIds': ['g00', 'g63']}}]
before = json.loads((BASE / 'fixture-before.json').read_text())
post = None
if (BASE / 'fixtures').exists():
    changed = [name for name, digest in before.items()
               if hashlib.sha256((BASE / 'fixtures' / name).read_bytes()).hexdigest() != digest]
    added = [str(p.relative_to(BASE / 'fixtures')) for p in (BASE / 'fixtures').rglob('*')
             if p.is_file() and str(p.relative_to(BASE / 'fixtures')) not in before]
    assert not changed and not added
    post = {'originalFilesUnchanged': len(before), 'added': added, 'changed': changed}
    (BASE / 'fixture-postrun.json').write_text(json.dumps(post, indent=2) + '\n')
report = {'histories': 6, 'operations': 48, 'publicQueries': 42, 'successes': 28,
          'expectedErrors': 14, 'firstSourceStructuralAndFileObservations': 102,
          'baselinePublicDifferences': public_differences, 'candidateExactlyEqualsMain': True,
          'annotationHistoryQueries': 3, 'annotationEndpointStateObservations': 12,
          'annotationProvenanceExactlyEqualsMain': True, 'mappedViewResetNegativeControlFailed': True,
          'performanceMeasurement': False,
          'scope': 'All public values and first-source structural/file state; other source state not compared'}
(BASE / 'verification.json').write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps(report, indent=2))
