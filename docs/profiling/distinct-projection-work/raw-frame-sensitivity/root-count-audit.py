from pathlib import Path
from collections import Counter
import json
import calendar
from datetime import datetime

ROOT = Path(__file__).resolve().parent
summary = json.loads((ROOT / 'summary.json').read_text())
NODE = 'io.johnsonlee.graphite.webgraph.MappedWebGraphBackedGraph.parallelRawDistinctCallSiteStringProjection$lambda$32$lambda$31$lambda$30('
APP = ('broad-query-pressure-worker [', 'graphite-cypher-scan-', 'graphite-callsite-scan-', 'graphite-callsite-segment-')


def ns(value):
    whole, frac = value.removesuffix('Z').split('.')
    return calendar.timegm(datetime.strptime(whole, '%Y-%m-%dT%H:%M:%S').timetuple()) * 10**9 + int(frac.ljust(9, '0'))


events_checked = 0
rows = []
for recording in (3, 4, 5):
    export = json.loads((ROOT / f'cpu-{recording}.json').read_text())
    windows = [w for w in summary['windows'] if w['recording'] == recording]
    assert len(windows) == 34
    counts, leaves, raw = {}, Counter(), Counter()
    for event in export['events']:
        owners = [w for w in windows if ns(w['start']) <= ns(event['timestamp']) < ns(w['end'])]
        assert len(owners) == 1 and not event['truncated']
        events_checked += 1
        qid = owners[0]['id']
        if event['thread'].startswith(APP):
            raw[qid] += 1
            frame = event['framesLeafFirst'][0]
            if frame['method'].startswith(NODE):
                assert frame['lineNumber'] > 0 and frame['bytecodeIndex'] >= 0
                leaves[qid] += 1
                counts.setdefault(qid, Counter())[frame['frameType']] += 1
    for row in summary['rows']:
        if row['recording'] != recording: continue
        qid = row['id']
        assert raw[qid] == row['applicationRawInclusiveCpuSamples']
        assert leaves[qid] == row['applicationNodeLeafSamples']
        assert dict(counts[qid]) == row['frameTypeCounts']
        rows.append({'recording': recording, 'id': qid, 'raw': raw[qid], 'nodeLeaf': leaves[qid], 'frameTypes': dict(counts[qid])})
assert events_checked == 523 and len(rows) == 6
(ROOT / 'root-count-audit.json').write_text(json.dumps({'passed': True, 'scope': 'Independent root recomputation of exported event ownership and frame counts using summary windows; does not independently re-decode JFR, authenticate capture-time JAR or repeat full original collapsed-stack audit.', 'eventsChecked': events_checked, 'rows': rows}, indent=2) + '\n')
print('Independent root count check passed: 523 events, 6 query distributions')
