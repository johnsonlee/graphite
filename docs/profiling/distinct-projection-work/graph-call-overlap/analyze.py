import calendar
import hashlib
import json
from collections import Counter, defaultdict
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SOURCE = Path('/private/tmp/graphite-distinct-phase-details')


def nanos(value):
    whole, fractional = value.removesuffix('Z').split('.')
    return calendar.timegm(datetime.strptime(whole, '%Y-%m-%dT%H:%M:%S').timetuple()) * 10**9 + int(fractional.ljust(9, '0'))


def inspect(calls, expected_union):
    events = defaultdict(int)
    thread_intervals = defaultdict(list)
    intervals = []
    for call in calls:
        start, end = nanos(call['start']), nanos(call['end'])
        assert end - start == call['durationNanos']
        assert start <= end
        events[start] += 1
        events[end] -= 1
        thread_intervals[call['thread']].append((start, end))
        intervals.append((start, end))
    for spans in thread_intervals.values():
        spans.sort()
        assert all(a[1] <= b[0] for a, b in zip(spans, spans[1:])), 'Same-thread overlapping stage calls'
    active = 0
    previous = None
    duration_by_concurrency = Counter()
    for timestamp, delta in sorted(events.items()):
        if previous is not None:
            duration_by_concurrency[active] += timestamp - previous
        active += delta
        assert active >= 0
        previous = timestamp
    assert active == 0
    union = sum(value for concurrency, value in duration_by_concurrency.items() if concurrency)
    total = sum(call['durationNanos'] for call in calls)
    assert union == expected_union
    assert total == sum(concurrency * value for concurrency, value in duration_by_concurrency.items())
    longest_two = sorted(intervals, key=lambda span: span[1] - span[0], reverse=True)[:2]
    overlap = max(0, min(span[1] for span in longest_two) - max(span[0] for span in longest_two)) if len(longest_two) == 2 else None
    return {
        'callCount': len(calls), 'unionNanos': union,
        'sumCallDurationNanos': total,
        'durationByActiveGraphCallsNanos': dict(sorted(duration_by_concurrency.items())),
        'meanActiveCallsDuringUnion': total / union if union else None,
        'longestCallNanos': max((call['durationNanos'] for call in calls), default=0),
        'twoLongestCallOverlapNanos': overlap,
        'threadCallCounts': dict(Counter(call['thread'] for call in calls)),
    }


result = {'scope': 'Offline analysis of existing traced frozen-main graph-stage intervals; no new recording or candidate measurement.', 'inputs': [], 'queries': []}
for phase in (1, 2, 3):
    source = SOURCE / f'phase-{phase}.json'
    data = source.read_bytes()
    result['inputs'].append({'path': str(source), 'sha256': hashlib.sha256(data).hexdigest()})
    for query in json.loads(data)['queries']:
        if 'distinct' not in query['id']:
            continue
        assert query['outerDurationNanos'] == query['initialUnionNanos'] + query['provenanceUnionNanos'] + query['otherNanos']
        assert query['tsvLatencyNanos'] == query['outerDurationNanos'] + query['untracedTsvNanos']
        result['queries'].append({
            'phase': phase, 'id': query['id'],
            'tsvLatencyNanos': query['tsvLatencyNanos'],
            'outerDurationNanos': query['outerDurationNanos'],
            'otherNanos': query['otherNanos'],
            'untracedTsvNanos': query['untracedTsvNanos'],
            **{stage: inspect(query['calls'][stage], query[stage + 'UnionNanos']) for stage in ('initial', 'provenance')}
        })
(ROOT / 'summary.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
for q in result['queries']:
    for stage in ('initial', 'provenance'):
        s = q[stage]
        if s['callCount']:
            print(q['phase'], q['id'], stage, json.dumps(s))
