"""Verify final cold public/state parity and the captured runtime boundaries."""
import json
from pathlib import Path
import subprocess
import sys

BASE = Path(__file__).resolve().parent
PREVIOUS = BASE.parent / 'native64-fullcase-replay'
sys.path.insert(0, str(PREVIOUS))
from capture_io import records

subprocess.run([sys.executable, str(PREVIOUS / 'verify-cold.py'), '--main', 'main-cold-complete',
                '--native', '../native64-ordinary-mapped-ranges/native-cold-final',
                '--output', '../native64-ordinary-mapped-ranges/comparison-final.json'], check=True)
report = json.loads((BASE / 'comparison-final.json').read_text())
assert not report['publicDifferences'] and not report['stateDifferenceCounts']
main = records('main-cold-complete')
native = records('../native64-ordinary-mapped-ranges/native-cold-final')
fields = ['id', 'retained', 'mappedView', 'trigrams', 'loadedFromPersistence', 'mappedRangeCount']
assert main[0]['kind'] == native[0]['kind'] == 'header'
assert main[0]['graphCount'] == native[0]['graphCount'] == 64
assert main[0]['state'] == native[0]['state'] == 'cold'
assert main[1]['kind'] == native[1]['kind'] == 'prepared'
for key, index in [('loaded', 0), ('sources', 1)]:
    assert len(main[index][key]) == len(native[index][key]) == 64
    for m, n in zip(main[index][key], native[index][key]):
        assert {k: m[k] for k in fields} == {k: n[k] for k in fields}
receipt = json.loads((BASE / 'native-cold-final-process.json').read_text())
assert receipt['status'] == 'exited' and receipt['exitCode'] == 1
assert receipt['sourcesUnchanged'] and receipt['binaryUnchanged']
post = json.loads((BASE / 'native-cold-final-postrun-fixtures.json').read_text())
assert post['matched'] == 1152 and not post['changed'] and not post['missing'] and not post['added']
summary = {'cases': 1267, 'successfulCanonicalResultsEqual': 1266, 'matchingQueryErrors': 1,
           'graphStateObservationsEqual': 64 * (2 + 2 * 1267),
           'stateFields': fields[1:], 'stateDifferences': 0,
           'rawCacheCountersAvailableInNative': False, 'originalAllSuccessGatePassed': False,
           'performanceMeasurement': False, 'warmVerified': False, 'startupPreparedVerified': False,
           'binaryAndSourcesUnchanged': True, 'originalFixtureFilesUnchanged': 1152}
(BASE / 'verification-final.json').write_text(json.dumps(summary, indent=2) + '\n')
print(json.dumps(summary, indent=2))
