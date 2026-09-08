import hashlib
import json
from pathlib import Path

BASE = Path(__file__).resolve().parent
main = json.loads((BASE / 'main.json').read_text())
candidate = json.loads((BASE / 'native-candidate.json').read_text())
baseline = json.loads((BASE / 'native-baseline.json').read_text())
assert len(main) == 3 and sum(len(r['steps']) for r in main) == 21
observations = queries = 0
for record in main:
    states = [record['loaded']] + [s[k] for s in record['steps'] for k in ['before', 'after']]
    observations += len(states)
    queries += sum(s['action'] == 'query' for s in record['steps'])
    for state in states:
        for counter in ['rawMatchCount', 'rawProjectionCount']:
            assert state[counter] == 0, 'Native counter implementation required for nonzero activity'
            del state[counter]
assert candidate == main, 'Full response, supported state, or index-file digest differs'
baseline_failures = [m['scenario'] for m, b in zip(main, baseline) if m != b]
assert baseline_failures == ['persisted-scoped', 'built-prepared']
original = json.loads((BASE / 'fixture-before.json').read_text())
changed = [relative for relative, digest in original.items()
           if hashlib.sha256((BASE / 'fixtures' / relative).read_bytes()).hexdigest() != digest]
assert not changed
added = [str(p.relative_to(BASE / 'fixtures')) for p in (BASE / 'fixtures').rglob('*')
         if p.is_file() and str(p.relative_to(BASE / 'fixtures')) not in original]
assert added == ['built-prepared/graph.callsite-string-index']
report = {'scenarios': 3, 'operations': 21, 'stateAndFileObservations': observations,
          'publicQueryResponses': queries, 'candidateExactlyEqualsMain': True,
          'baselineFailingScenarios': baseline_failures, 'originalFixtureFilesUnchanged': len(original),
          'newMainFiles': added, 'performanceMeasurement': False,
          'scope': 'Preferred persisted and built-trigram release lifecycle; not full server parity'}
(BASE / 'verification.json').write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps(report, indent=2))
