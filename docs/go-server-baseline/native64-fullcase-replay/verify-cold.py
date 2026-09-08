"""Audit original captures without rewriting results or masking state differences."""
import collections
import argparse
import hashlib
import json
from pathlib import Path
from capture_io import records, capture_sha

BASE = Path(__file__).resolve().parent
ROOT = BASE.parents[2]


def read(path):
    return json.loads(path.read_text())


parser = argparse.ArgumentParser()
parser.add_argument('--main', default='main-cold')
parser.add_argument('--native', default='native-cold')
parser.add_argument('--output', default='cold-comparison.json')
args = parser.parse_args()
main, native = records(args.main), records(args.native)
mc = [r for r in main if r['kind'] == 'case']
nc = [r for r in native if r['kind'] == 'case']
workload_path = ROOT / 'graphite-server/internal/benchmarkcase/testdata/main64.json'
workload = read(workload_path)
actual_cases = read(BASE / args.main / 'actual-cases.json')
expected_order = [(i, c['id']) for i, c in enumerate(actual_cases)]
assert len(mc) == len(nc) == len(actual_cases) == 1267
assert workload['cases'] == actual_cases
assert [(c['index'], c['id']) for c in mc] == expected_order
assert [(c['index'], c['id']) for c in nc] == expected_order
assert all(c['phase'] == 'replay' for c in mc + nc)

# canonical includes original main numeric classes and preserves result row order.
# errorClass is a main-only observer field and is reported separately below.
public_fields = ['columns', 'rows', 'canonical', 'error', 'message']
public_differences = []
state_counts = collections.Counter()
state_first = {}
extra_observer_fields = []
for m, n in zip(mc, nc):
    changed = [k for k in public_fields if (k in m, m.get(k)) != (k in n, n.get(k))]
    if changed:
        public_differences.append({'index': m['index'], 'id': m['id'], 'fields': changed})
    if m.get('errorClass') != n.get('errorClass'):
        extra_observer_fields.append({'index': m['index'], 'id': m['id'],
                                      'field': 'errorClass', 'main': m.get('errorClass'),
                                      'native': n.get('errorClass')})
    for boundary in ['before', 'after']:
        assert len(m[boundary]) == len(n[boundary]) == 64
        for ms, ns in zip(m[boundary], n[boundary]):
            for field in ['id', 'retained', 'mappedView', 'trigrams',
                          'loadedFromPersistence', 'mappedRangeCount']:
                if ms[field] != ns[field]:
                    state_counts[field] += 1
                    state_first.setdefault(field, {'index': m['index'], 'id': m['id'],
                        'boundary': boundary, 'graph': ms['id'],
                        'main': ms[field], 'native': ns[field]})

report = {
    'scope': 'One complete real64 cold correctness replay per runtime; no latency evidence',
    'mainRevision': workload['mainRevision'],
    'workloadSHA256': hashlib.sha256(workload_path.read_bytes()).hexdigest(),
    'caseDefinitionsExactlyEqual': True,
    'caseCount': len(mc),
    'executionOrderExactlyEqual': True,
    'successfulMainCases': sum('canonical' in r for r in mc),
    'successfulNativeCases': sum('canonical' in r for r in nc),
    'comparedPublicFields': public_fields,
    'publicDifferences': public_differences,
    'errors': [{k: v for k, v in r.items() if k not in ('before', 'after')}
               for r in mc if 'canonical' not in r],
    'observerFieldDifferences': extra_observer_fields,
    'stateDifferenceUnit': 'Per graph, field, and before/after case observation; not unique cases',
    'stateDifferenceCounts': dict(state_counts),
    'firstStateDifferences': state_first,
    'nativeUnavailableStateCounters': native[0]['unavailableStateCounters'],
    'processReceipts': {name: read(BASE / (name + '-process.json'))['exitCode']
                        for name in [args.main, args.native]},
    'limitations': [
        ('The initial main capture could not write its correctness manifest because of a missing classpath entry; the corrected repeat is recorded separately.'
         if args.main == 'main-cold' else
         'Main wrote its original correctness and observations manifests, then failed its original all-success gate.'),
        'Both runtimes recorded one query error; an all-success correctness gate cannot pass.',
        'Index/cache state differs; complete benchmark lifecycle parity is not established.',
        'Warm and startup-prepared full replays remain unverified.',
        'This observed replay is not a timing run; no per-case P95 or speedup is established.'
    ],
    'captureSHA256': {name: capture_sha(name)
                      for name in [args.main, args.native]},
}
(BASE / args.output).write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps({k: report[k] for k in ['caseCount', 'caseDefinitionsExactlyEqual',
    'successfulMainCases', 'successfulNativeCases', 'publicDifferences', 'stateDifferenceCounts']}, indent=2))
