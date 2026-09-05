import calendar
import hashlib
import json
from collections import Counter
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parent
RAW = 'io.johnsonlee.graphite.webgraph.MappedWebGraphBackedGraph.parallelRawDistinctCallSiteStringProjection'
NODE = RAW + '$lambda$32$lambda$31$lambda$30('
APP = ('broad-query-pressure-worker [', 'graphite-cypher-scan-', 'graphite-callsite-scan-', 'graphite-callsite-segment-')


def ns(s):
    sec, fraction = s.removesuffix('Z').split('.')
    return calendar.timegm(datetime.strptime(sec, '%Y-%m-%dT%H:%M:%S').timetuple()) * 10**9 + int(fraction.ljust(9, '0'))


def distribution(frames):
    counts = Counter((f['method'], f['lineNumber'], f['bytecodeIndex'], f['frameType']) for f in frames)
    return [{'method': key[0], 'lineNumber': key[1], 'bytecodeIndex': key[2], 'frameType': key[3], 'samples': count}
            for key, count in sorted(counts.items(), key=lambda item: (-item[1], item[0]))]


rows = []
for phase in (1, 2, 3):
    detail_path = Path(f'/private/tmp/graphite-distinct-phase-details/phase-{phase}.json')
    original = json.loads(detail_path.read_text())
    exported = json.loads((ROOT / f'phase-{phase}.json').read_text())
    for query in original['queries']:
        if not query['id'].endswith(('distinct-targeted', 'distinct-dense')):
            continue
        for stage in ('initial', 'provenance'):
            intervals = [(ns(call['start']), ns(call['end'])) for call in query['calls'][stage]]
            if not intervals:
                continue
            events = [e for e in exported['events'] if e['thread'].startswith(APP) and any(a <= ns(e['timestamp']) < b for a, b in intervals)]
            assert not any(e['truncated'] for e in events)
            expected = Counter()
            metric = query['metrics'][stage]['cpuSamples']
            for thread, stacks in metric['threadStacks'].items():
                if not thread.startswith(APP):
                    continue
                for stack, count in stacks.items():
                    if any(frame.startswith(RAW) for frame in stack.split(';')):
                        expected[(thread, stack)] += count
            actual = Counter((e['thread'], ';'.join(f['method'].replace(';', ':') for f in reversed(e['framesLeafFirst']))) for e in events)
            assert expected == actual, (phase, query['id'], stage)
            leaf_frames = [e['framesLeafFirst'][0] for e in events]
            node_leaves = [f for f in leaf_frames if f['method'].startswith(NODE)]
            node_frames = [f for e in events for f in e['framesLeafFirst'] if f['method'].startswith(NODE)]
            row = {'phase': phase, 'id': query['id'], 'stage': stage,
                   'originalDetailSha256': hashlib.sha256(detail_path.read_bytes()).hexdigest(),
                   'rawApplicationCpuSamples': len(events), 'fullMethodThreadStacksExactlyMatchOriginal': True,
                   'nodeLeafSamples': len(node_leaves),
                   'nodeLeafKnownLineSamples': sum(f['lineNumber'] > 0 for f in node_leaves),
                   'nodeLeafKnownBciSamples': sum(f['bytecodeIndex'] >= 0 for f in node_leaves),
                   'nodeLeafDistribution': distribution(node_leaves),
                   'nodeFrameDistribution': distribution(node_frames),
                   'allRawLeafDistribution': distribution(leaf_frames)}
            rows.append(row)
(ROOT / 'summary.json').write_text(json.dumps({'rows': rows, 'scope': 'Existing JFR CPU events re-exported; full method/thread stack counts exactly match earlier serialization. No new recording.'}, ensure_ascii=False, indent=2) + '\n')
for row in rows:
    print(row['phase'], row['id'], row['stage'], 'raw', row['rawApplicationCpuSamples'], 'nodeLeaf', row['nodeLeafSamples'], 'lineKnown', row['nodeLeafKnownLineSamples'], 'bciKnown', row['nodeLeafKnownBciSamples'])
    print(json.dumps(row['nodeLeafDistribution']))
