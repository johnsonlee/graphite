"""Compare every ordered pilot case; single samples never establish P95."""
import csv
import hashlib
import json
from pathlib import Path

BASE = Path(__file__).resolve().parent
OLD = BASE.parent / 'native64-latency-pilot'


def read(path):
    with path.open() as stream:
        return list(csv.DictReader(stream, delimiter='\t'))


reports = {}
inputs = {}
for state in ['cold', 'startup-prepared']:
    paths = [OLD / (state + '-comparison.tsv'), BASE / 'latency' / (state + '-comparison.tsv')]
    before, after = map(read, paths)
    assert len(before) == len(after) == 1267
    inputs.update({str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in paths})
    rows = []
    for old, new in zip(before, after):
        for field in ['index', 'id', 'family', 'shape', 'outcome', 'rowCount', 'responseBytes', 'digest']:
            assert old[field] == new[field], (state, new['id'], field)
        assert old['samplesPerRuntime'] == new['samplesPerRuntime'] == '1'
        rows.append(dict(index=int(new['index']), id=new['id'], outcome=new['outcome'],
                         oldMainMs=int(old['mainLatencyNanos']) / 1e6,
                         oldGoMs=int(old['goLatencyNanos']) / 1e6,
                         mainMs=int(new['mainLatencyNanos']) / 1e6,
                         goMs=int(new['goLatencyNanos']) / 1e6,
                         goDeltaMs=(int(new['goLatencyNanos']) - int(old['goLatencyNanos'])) / 1e6))
    successes = [r for r in rows if r['outcome'] == 'success']
    counts = [r for r in successes if r['id'].startswith(('filtered-count-', 'filtered-distinct-count-'))]
    assert len(successes) == 1266 and len(counts) == 6
    report = dict(samplesPerCasePerRuntime=1, perCaseP95Available=False,
                  successfulCasesSlowerThanFreshMain=sum(r['goMs'] > r['mainMs'] for r in successes),
                  successfulCasesAtLeastTenfoldSingleSample=sum(r['mainMs'] >= 10*r['goMs'] for r in successes),
                  selectedCountCases=counts,
                  largestGoIncreases=sorted(successes, key=lambda r: r['goDeltaMs'], reverse=True)[:15],
                  largestGoReductions=sorted(successes, key=lambda r: r['goDeltaMs'])[:15],
                  sumOfSuccessfulCaseTimesMs={key: sum(r[key] for r in successes) for key in ['oldMainMs', 'oldGoMs', 'mainMs', 'goMs']},
                  sumInterpretation='Sum of this one ordered invocation, excluding the original failure; neither P95 nor a causal estimate from repeated trials')
    reports[state] = report
    with (BASE / 'latency' / (state + '-before-after.tsv')).open('w') as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]), delimiter='\t', lineterminator='\n')
        writer.writeheader()
        writer.writerows(rows)
result = dict(states=reports, inputsSHA256=inputs, acceptanceProven=False)
(BASE / 'latency/analysis.json').write_text(json.dumps(result, indent=2)+'\n')
print(json.dumps(result, indent=2))
