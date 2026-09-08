"""Verify every original result and retain both complete phase ledgers."""
import csv
import hashlib
import importlib.util
import json
from pathlib import Path

BASE = Path(__file__).resolve().parent
ROOT = BASE.parents[2]
path = BASE.parent / 'native64-latency-pilot/verify-pilot.py'
spec = importlib.util.spec_from_file_location('original_pair_verifier', path)
original = importlib.util.module_from_spec(spec)
spec.loader.exec_module(original)
WORKLOAD = ROOT / 'graphite-server/internal/benchmarkcase/testdata/main64.json'
FIXTURES = BASE.parent / 'native64-profile-a7de0bec/fixture-files.json'

def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def read(path): return json.loads(path.read_text())
def require(condition, message):
    if not condition: raise ValueError(message)

def tsv(path):
    with path.open() as stream: return list(csv.DictReader(stream, delimiter='\t'))

def references(workload):
    cold, _ = original.reference_cases('cold', workload)
    startup, _ = original.reference_cases('startup-prepared', workload)
    require(cold == startup, 'cold/startup reference signatures differ')
    return cold

def verify(directory, trial_id, state, workload, reference):
    main, native = directory / 'main', directory / 'go'
    mh = read(main / 'capture/header.json')
    mc = read(main / 'capture/completion.json')
    nr = [json.loads(line) for line in (native / 'capture/observations.jsonl').read_text().splitlines()]
    require(nr and nr[0].get('kind') == 'header' and nr[-1].get('kind') == 'completion', 'native framing')
    nh, nc = nr[0], nr[-1]
    for h in [mh, nh]:
        require(h['state'] == state and h['caseCount'] == 1267 and h['graphCount'] == 64, 'runtime coverage/state header')
        require(h['sourceOrder'] == workload['sourceOrder'], 'source order header')
        require(h['mainRevision'] == workload['mainRevision'] and h['diagnosticOnly'] is True, 'runtime revision/measurement scope')
    require(nh['protocol'] == 'real64-dispatch-inclusive-sampling-v1', 'native protocol')
    require(nh['workloadSHA256'] == original.WORKLOAD_SHA and nh['workTrackingEnabled'] is True, 'native workload/context')
    require(nh['sampleCountPerCase'] == mh['samplesPerCase'] == 1, 'sample count')
    require(mh['queryExecutorReplaced'] is False and mc['queryExecutorIdentityUnchanged'] is True, 'main executor changed')
    require(nc['completeReplay'] is True and nc['caseCount'] == nc['expectedCaseCount'] == 1267, 'native completion')
    require(mc['originalGatePassed'] is False and nc['originalGatePassed'] is False, 'original gate was bypassed')
    require(mc['exceptionClass'] == original.ERROR_CLASS, 'main failure class')
    require(mc['exceptionMessage'] == 'A correctness oracle requires every query to succeed; incomplete results: ' + original.ERROR_ID + '=failed', 'main gate failure changed')
    require(read(main / 'capture/actual-cases.json') == workload['cases'], 'main testcase definitions changed')
    require(len(nr) == 1269, 'unexpected phase record count')
    timed = [r for r in nr[1:-1] if r.get('phase') == 'replay']
    main_rows = tsv(main / 'capture/main-observations.tsv')
    original.compare_cases(workload, reference, main_rows, timed)
    for filename, records in [('main-correctness.tsv', main_rows)]:
        lines = [line.split('|') for line in (main / 'capture' / filename).read_text().splitlines()]
        require(len(lines) == 1267, 'main signature count')
        for sig, rec in zip(lines, records):
            require(len(sig) == 14 and sig[0] == rec['id'] and sig[10] == rec['outcome'] and int(sig[11]) == int(rec['rowCount']) and int(sig[12]) == int(rec['responseBytes']) and sig[13] == rec['digest'], 'main signature disagrees with timing ledger')
    if state == 'warm-after-failed-prewarm':
        require(nh['diagnosticWarmupContinued'] is True and nh['formalWarmPrepared'] is False, 'native warm continuation scope')
        warm_native = [json.loads(line) for line in (native / 'capture/warmup-observations.jsonl').read_text().splitlines()]
        require(len(warm_native) == 1267 and all(r.get('phase') == 'warmup' for r in warm_native), 'incomplete native prewarm ledger')
        warm_native = [{**r, 'phase': 'replay'} for r in warm_native]
        warm_main = tsv(main / 'capture/warmup-observations.tsv')
        original.compare_cases(workload, reference, warm_main, warm_native)
        require(mh['formalWarmPrepared'] is False and mh['diagnosticWarmupContinued'] is True, 'main warm continuation scope')
    intervals = []
    for runtime in ['main', 'go']:
        receipt = read(directory / runtime / 'process.json')
        require(receipt['status'] == 'exited' and receipt['exitCode'] == 1, 'runtime not terminal original failure')
        require(receipt['inputsUnchanged'] and receipt['fixtureAuditPassed'], 'immutable runtime inputs/fixtures changed')
        manifest = directory / runtime / 'graphs-relocated.tsv'
        require(sha(manifest) == receipt['manifestSHA256'], 'runtime manifest changed')
        rows = [line.split('\t') for line in manifest.read_text().splitlines() if line.strip() and not line.lstrip().startswith('#')]
        require(len(rows) == 64 and all(len(row) == 6 for row in rows), 'manifest shape')
        require([row[0] for row in rows] == workload['sourceOrder'], 'manifest source order')
        paths = [Path(row[1]).resolve(strict=True) for row in rows]
        clone = Path(receipt['clone']).resolve(strict=True)
        require(len(set(paths)) == 64 and all(p == clone / row[0] for p, row in zip(paths, rows)), 'manifest source paths escaped clone or aliased')
        intervals.append((receipt['startedAtNanos'], receipt['finishedAtNanos']))
    require(read(main / 'process.json')['clone'] != read(native / 'process.json')['clone'], 'runtimes shared a clone')
    require(all(a < b for a,b in intervals) and (intervals[0][1] <= intervals[1][0] or intervals[1][1] <= intervals[0][0]), 'timed runtimes overlapped')
    records = []
    for i, (case, main_rec, go_rec) in enumerate(zip(workload['cases'], main_rows, timed)):
        def observation(row, is_main):
            outcome = row['outcome'].lower()
            result = dict(outcome=outcome, latencyNanos=int(row['latencyNanos']), censoredTimeout=False,
                validationError=None, digest=row['digest'], rowCount=int(row['rowCount']), responseBytes=int(row['responseBytes']),
                error=reference[i].get('error'), message=reference[i].get('message'),
                messageSource='reference-derived' if is_main and outcome != 'success' else 'observed')
            if not is_main:
                result['message'] = row['message']
                result['error'] = row.get('error')
            return result
        records.append(dict(index=i, id=case['id'], querySHA256=hashlib.sha256(case['query'].encode()).hexdigest(),
                            main=observation(main_rec, True), go=observation(go_rec, False)))
    return dict(schemaVersion=1, trialId=trial_id, state=state, workloadSHA256=sha(WORKLOAD), graphManifestSHA256=sha(FIXTURES), records=records)
