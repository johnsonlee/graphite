"""Check complete main repeat and the original benchmark's output digests."""
import csv
import hashlib
import json
from pathlib import Path
from capture_io import records

BASE = Path(__file__).resolve().parent
initial, repeated = records('main-cold'), records('main-cold-complete')
assert initial == repeated, 'Main public output or any observed state changed'
cases = [r for r in repeated if r['kind'] == 'case']
observations = list(csv.DictReader((BASE / 'main-cold-complete/main-observations.tsv').open(), delimiter='\t'))
correctness = [line.split('|') for line in (BASE / 'main-cold-complete/main-correctness.tsv').read_text().splitlines()]
assert len(cases) == len(observations) == len(correctness) == 1267
for case, observed, recorded in zip(cases, observations, correctness):
    assert case['id'] == observed['id'] == recorded[0]
    if 'canonical' in case:
        data = case['canonical'].encode('utf-8')
        digest = hashlib.sha256(data).hexdigest()
        assert observed['outcome'] == recorded[10] == 'success'
        assert observed['digest'] == recorded[13] == digest
        assert int(observed['rowCount']) == int(recorded[11]) == len(case['rows'])
        assert int(observed['responseBytes']) == int(recorded[12]) == len(data)
    else:
        assert case['id'] == 'four-or-graph-id-targeted'
        assert observed['outcome'] == recorded[10] == 'failed'
receipt = json.loads((BASE / 'main-cold-complete-process.json').read_text())
assert receipt['exitCode'] == 1 and receipt['classpathUnchanged']
post = json.loads((BASE / 'main-cold-complete-postrun-fixtures.json').read_text())
assert post['matched'] == 1152 and not post['added']
report = {'initialCount': len(cases), 'completeClasspathCount': len(observations),
          'allCaptureRecordsExactlyEqual': True, 'publicDifferences': [], 'stateDifferenceCounts': {},
          'originalManifestDigestsVerified': 1266, 'originalManifestFailures': 1,
          'classpathUnchanged': True, 'originalAllSuccessGatePassed': False,
          'performanceMeasurement': False}
(BASE / 'main-repeat-comparison.json').write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps(report, indent=2))
