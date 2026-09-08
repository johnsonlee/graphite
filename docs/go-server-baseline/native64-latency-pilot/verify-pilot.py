"""Independently verify one serial real64 main/Go pair; never estimate P95 from n=1."""
import argparse
import copy
import csv
import datetime
import gzip
import hashlib
import json
from pathlib import Path
import statistics

BASE = Path(__file__).resolve().parent
ROOT = BASE.parents[2]
WORKLOAD = ROOT / 'graphite-server/internal/benchmarkcase/testdata/main64.json'
WORKLOAD_SHA = '378c200c5ab3053c53962f9d87c59924f732d0c012fcaff6009842a58e547023'
MAIN_REVISION = '4e328b0109e13c896b74004823fb049fcb19251a'
ERROR_INDEX = 821
ERROR_ID = 'four-or-graph-id-targeted'
ERROR_CLASS = 'java.lang.IllegalStateException'
ERROR_MESSAGE = 'Unsafe expression reached parallel string projection'
REFERENCES = {
    'cold': ('native64-fullcase-replay/main-cold-complete',
             'ec5a289455443d1c09a8c33abf4ebda9acadf958532c17e3fbf373e970a0777f'),
    'startup-prepared': ('native64-index-states/main-startup-prepared',
                         'a0c4159689383a4096f0fe33eb05a2ee839dc3c5811b495febced33722deac65'),
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def read(path):
    return json.loads(path.read_text())


def sha(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def check_audit(audit):
    require(audit['originalFiles'] == audit['matched'] == 1152, 'incomplete 1152-file audit')
    require(not any(audit[k] for k in ('changed', 'missing', 'added')), 'fixture changed')


def check_receipt(directory, runtime, state):
    receipt = read(directory / 'process.json')
    require(receipt['runtime'] == runtime and receipt['state'] == state, 'wrong runtime/state receipt')
    require(receipt['status'] == 'exited' and receipt['exitCode'] == 1, 'runtime not terminal exit 1')
    require(receipt['inputsUnchanged'] is True and receipt['fixtureAuditPassed'] is True,
            'input or fixture audit not passed')
    require(receipt['performanceMeasurement'] is True and receipt['diagnosticOnly'] is True
            and receipt['samplesPerCase'] == 1, 'wrong receipt measurement scope')
    if runtime == 'native':
        require(receipt['binaryUnchanged'] is True, 'native binary changed')
        require(sha(Path(receipt['command'][0])) == receipt['binarySHA256'], 'native binary hash differs')
    else:
        require(receipt['originalClasspathMatchesVerifiedCapture'] is True
                and receipt['queryExecutorReplaced'] is False, 'main baseline/observer receipt invalid')
    inputs = read(directory / 'inputs.json')
    require(bool(inputs) and str(WORKLOAD) in inputs, 'missing frozen workload input')
    for path, expected in inputs.items():
        require(sha(Path(path)) == expected, 'current input differs: ' + path)
    audits = {name: read(directory / (name + '.json')) for name in
              ('reference-preflight', 'clone-preflight', 'postrun-fixtures')}
    for audit in audits.values():
        check_audit(audit)
    require(audits['clone-preflight']['root'] == audits['postrun-fixtures']['root'] == receipt['clone'],
            'pre/post audits refer to different clone')
    require(Path(receipt['clone']).resolve() != Path(audits['reference-preflight']['root']).resolve(),
            'runtime opened immutable reference')
    manifest = Path(receipt['clone']) / 'graphs-relocated.tsv'
    require(str(manifest) in inputs, 'manifest not frozen')
    rows = [line.split('\t') for line in manifest.read_text().splitlines()
            if line.strip() and not line.lstrip().startswith('#')]
    require(len(rows) == 64 and all(len(row) == 6 for row in rows), 'manifest not 64 six-column rows')
    require(len({Path(row[1]).resolve() for row in rows}) == 64, 'duplicate physical graph directories')
    return receipt, [row[0] for row in rows], len(inputs)


def reference_cases(state, workload):
    relative, expected = REFERENCES[state]
    directory = BASE.parent / relative
    archive = directory / 'responses.jsonl.gz'
    require(sha(archive) == expected, 'full canonical reference archive changed')
    require(read(directory / 'actual-cases.json') == workload['cases'], 'original actual cases differ')
    cases = []
    with gzip.open(archive, 'rt') as stream:
        for line in stream:
            record = json.loads(line)
            if record['kind'] != 'case':
                continue
            i = len(cases)
            require(record['phase'] == 'replay' and record['index'] == i
                    and record['id'] == workload['cases'][i]['id'], 'reference coverage/order mismatch')
            if 'canonical' in record:
                canonical = record['canonical'].encode('utf-8')
                cases.append(dict(id=record['id'], outcome='success', rowCount=len(record['rows']),
                                  responseBytes=len(canonical), digest=hashlib.sha256(canonical).hexdigest()))
            else:
                require(i == ERROR_INDEX and record['id'] == ERROR_ID
                        and record['errorClass'] == ERROR_CLASS and record['error'] == 'IllegalStateException'
                        and record['message'] == ERROR_MESSAGE, 'unexpected original reference failure')
                cases.append(dict(id=record['id'], outcome='failed', rowCount=0, responseBytes=0,
                                  digest=ERROR_CLASS, error=record['error'], message=record['message']))
    require(len(cases) == 1267, 'incomplete full canonical reference')
    return cases, archive


def check_main_metadata(row, case):
    for field in ('id', 'family', 'shape', 'selectivity', 'operator', 'boundary', 'projection', 'limit'):
        require(row[field] == str(case[field]), 'main metadata differs: ' + field)
    for field in ('targetGraphId', 'workloadIdentity', 'fixtureDistributionId'):
        require(row[field] == (case[field] or ''), 'main metadata differs: ' + field)
    require(row['targetGraphIds'] == ','.join(case['targetGraphIds']), 'main target graph IDs differ')
    require(int(row['selectedGraphCount']) == len(case['targetGraphIds']), 'main target count differs')


def compare_cases(workload, reference, main, native):
    require(len(main) == len(native) == len(reference) == len(workload['cases']) == 1267,
            'missing/extra testcase')
    results = []
    for index, (case, ref, m, n) in enumerate(zip(workload['cases'], reference, main, native)):
        label = f'{index}/{case["id"]}'
        require(m['id'] == n['id'] == ref['id'] == case['id'], 'case order/id differs: ' + label)
        require(n['kind'] == 'case' and n['phase'] == 'replay' and n['index'] == index, 'Go case index differs')
        check_main_metadata(m, case)
        outcome = ref['outcome']
        require(m['outcome'] == n['outcome'].lower() == outcome, 'outcome differs: ' + label)
        require(n['censoredTimeout'] is False and not n.get('validationError'), 'timeout/validation failure: ' + label)
        for field in ('rowCount', 'responseBytes'):
            require(int(m[field]) == n[field] == ref[field], field + ' differs: ' + label)
        require(m['digest'] == n['digest'] == ref['digest'], 'digest differs: ' + label)
        scoped = case['requestGraphIds'] is not None
        input_count = len(case['requestGraphIds']) if scoped else 64
        require(int(m['inputSourceCount']) == n['inputSourceCount'] == input_count, 'input source count differs')
        require(n['sourceScopeApplied'] is scoped, 'request scope differs')
        timeout = case['configuredTimeoutMillis'] or 60000
        require(n['timeoutMillis'] == timeout, 'effective timeout differs')
        if outcome == 'failed':
            require(index == ERROR_INDEX and n['error'] == ref['error'] and n['message'] == ref['message'],
                    'native failure differs from original full canonical capture')
        else:
            require('error' not in n and n['message'] is None, 'successful case has error')
            require(not case['expectZeroRows'] or n['rowCount'] == 0, 'zero-row assertion failed')
            bounds = case['expectedRowCountRange']
            require(bounds is None or bounds['first'] <= n['rowCount'] <= bounds['last'], 'row bounds failed')
        m_ns, n_ns = int(m['latencyNanos']), n['latencyNanos']
        require(type(n_ns) is int and m_ns > 0 and n_ns > 0, 'nonpositive/invalid latency')
        results.append(dict(index=index, id=case['id'], family=case['family'], shape=case['shape'],
                            outcome=outcome, samplesPerRuntime=1,
                            measurementKind='successful-latency' if outcome == 'success' else 'time-to-failure',
                            mainLatencyNanos=m_ns, goLatencyNanos=n_ns,
                            mainOverGoSingleSampleRatio=m_ns / n_ns,
                            rowCount=ref['rowCount'], responseBytes=ref['responseBytes'], digest=ref['digest'],
                            mainErrorClass=m['digest'] if outcome == 'failed' else '',
                            mainReferenceErrorMessage=ref.get('message', ''),
                            goErrorClass=n.get('error', ''), goErrorMessage=n['message'] or ''))
    require(sum(r['outcome'] == 'success' for r in results) == 1266, 'unexpected success count')
    return results


def verify(args):
    require(sha(WORKLOAD) == WORKLOAD_SHA, 'pinned workload changed')
    workload = read(WORKLOAD)
    require(workload['mainRevision'] == MAIN_REVISION, 'wrong main revision')
    receipts, input_counts = {}, {}
    for runtime, directory in [('main', args.main), ('native', args.native)]:
        receipt, order, count = check_receipt(directory, runtime, args.state)
        require(order == workload['sourceOrder'], 'physical manifest source order differs')
        receipts[runtime], input_counts[runtime] = receipt, count
    require(Path(receipts['main']['clone']).resolve() != Path(receipts['native']['clone']).resolve(), 'shared clone')
    intervals = [(datetime.datetime.fromisoformat(r['startedAt']), datetime.datetime.fromisoformat(r['finishedAt']))
                 for r in receipts.values()]
    require(all(start < end for start, end in intervals), 'invalid runtime interval')
    require(intervals[0][1] <= intervals[1][0] or intervals[1][1] <= intervals[0][0], 'benchmark runtimes overlapped')
    mh = read(args.main / 'capture/header.json')
    for key, value in dict(state=args.state, graphCount=64, caseCount=1267, sourceOrder=workload['sourceOrder'],
                           mainRevision=MAIN_REVISION, timeoutMillis=60000, coverageFamily='all',
                           performanceMeasurement=True, diagnosticOnly=True, samplesPerCase=1,
                           queryExecutorReplaced=False).items():
        require(mh[key] == value, 'main header differs: ' + key)
    mc = read(args.main / 'capture/completion.json')
    require(mc['stage'] == 'replayBroadQueries' and mc['originalGatePassed'] is False
            and mc['launcherSucceeded'] is False and mc['queryExecutorIdentityUnchanged'] is True
            and mc['correctnessManifestWritten'] is True and mc['observationsWritten'] is True,
            'main lifecycle did not reach original gate')
    require(mc['exceptionClass'] == ERROR_CLASS and mc['exceptionMessage'] ==
            'A correctness oracle requires every query to succeed; incomplete results: ' + ERROR_ID + '=failed',
            'main original gate failed for an unexpected reason')
    require(read(args.main / 'capture/actual-cases.json') == workload['cases'], 'actual main cases not exact')
    native_path = args.native / 'capture/observations.jsonl'
    nr = [json.loads(line) for line in native_path.read_text().splitlines()]
    require(len(nr) == 1269 and nr[0]['kind'] == 'header' and nr[-1]['kind'] == 'completion', 'Go framing differs')
    nh = nr[0]
    for key, value in dict(protocol='real64-dispatch-inclusive-pilot-v1', state=args.state, graphCount=64,
                           caseCount=1267, sourceOrder=workload['sourceOrder'], mainRevision=MAIN_REVISION,
                           workloadSHA256=WORKLOAD_SHA, performanceMeasurement=True, diagnosticOnly=True,
                           sampleCountPerCase=1, workTrackingEnabled=True, workBudget='unlimited',
                           cancellationGraceMillis=5000).items():
        require(nh[key] == value, 'Go header differs: ' + key)
    nc = nr[-1]
    require(nc['caseCount'] == nc['expectedCaseCount'] == 1267 and nc['completeReplay'] is True
            and nc['originalGatePassed'] is False and nc['error'] == 'replay had 1 failed cases', 'Go gate differs')
    with (args.main / 'capture/main-observations.tsv').open() as stream:
        main = list(csv.DictReader(stream, delimiter='\t'))
    reference, archive = reference_cases(args.state, workload)
    rows = compare_cases(workload, reference, main, nr[1:-1])
    correctness = [line.split('|') for line in (args.main / 'capture/main-correctness.tsv').read_text().splitlines()]
    require(len(correctness) == 1267, 'incomplete main correctness manifest')
    for record, row in zip(correctness, rows):
        require(len(record) == 14 and record[0] == row['id'] and record[10] == row['outcome']
                and int(record[11]) == row['rowCount'] and int(record[12]) == row['responseBytes']
                and record[13] == row['digest'], 'main manifest differs from full canonical reference')
    ratios = [r['mainOverGoSingleSampleRatio'] for r in rows if r['outcome'] == 'success']
    report = dict(state=args.state, protocol=nh['protocol'], diagnosticOnly=True, performanceMeasurement=True,
                  perCaseP95Available=False, tenfoldP95AcceptanceProven=False, samplesPerCasePerRuntime=1,
                  caseDefinitionsExactlyEqual=True, caseCount=1267, successCount=1266, failureCount=1,
                  timeoutCount=0, originalAllSuccessGatePassed=False, successfulCanonicalDigestsVerified=1266,
                  originalFilesUnchangedPerRuntime=1152, currentInputHashesVerified=input_counts,
                  benchmarkRuntimesOverlap=False, runtimeExitCodes={k: r['exitCode'] for k, r in receipts.items()},
                  singleSampleSuccessfulCaseRatios=dict(min=min(ratios), median=statistics.median(ratios), max=max(ratios),
                        belowOne=sum(r < 1 for r in ratios), atLeastTen=sum(r >= 10 for r in ratios),
                        interpretation='Distribution across different cases of one main/Go latency ratio each; not a latency percentile or acceptance gate'),
                  failure=rows[ERROR_INDEX], unavailableGoParity=nh['unavailableParity'],
                  caveats=['n=1 per case; no P95 estimate', 'The original main all-success gate remains failed',
                           'Failure latency is time-to-failure and excluded from successful-case ratio summaries',
                           'Main per-query error message is from the archived full canonical capture; this observer-free trial reports its class only',
                           'Main resource sampling and work/diagnostic accounting do not yet have full Go parity'],
                  mainDirectory=str(args.main), nativeDirectory=str(args.native), referenceArchive=str(archive))
    files = [WORKLOAD, archive, Path(__file__).resolve()]
    for directory in (args.main, args.native):
        files.extend(p for p in directory.rglob('*') if p.is_file())
    report['evidenceSHA256'] = {str(path): sha(path) for path in sorted(set(files))}
    prefix = args.output_prefix
    prefix.parent.mkdir(parents=True, exist_ok=True)
    with Path(str(prefix) + '.tsv').open('w') as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]), delimiter='\t', lineterminator='\n')
        writer.writeheader()
        writer.writerows(rows)
    Path(str(prefix) + '.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({k: v for k, v in report.items() if k != 'evidenceSHA256'}, indent=2))


def self_test():
    """Correctness-only mutations; fabricated clocks never become benchmark evidence."""
    workload = read(WORKLOAD)
    reference, _ = reference_cases('cold', workload)
    directory = BASE.parent / REFERENCES['cold'][0]
    with (directory / 'main-observations.tsv').open() as stream:
        main = list(csv.DictReader(stream, delimiter='\t'))
    native = []
    for i, (c, ref) in enumerate(zip(workload['cases'], reference)):
        n = dict(ref, kind='case', phase='replay', index=i, outcome=ref['outcome'].upper(),
                 censoredTimeout=False, latencyNanos=1, timeoutMillis=c['configuredTimeoutMillis'] or 60000,
                 inputSourceCount=len(c['requestGraphIds']) if c['requestGraphIds'] is not None else 64,
                 sourceScopeApplied=c['requestGraphIds'] is not None, message=ref.get('message'))
        native.append(n)
    compare_cases(workload, reference, main, native)
    mutations = {
        'dropped-case': lambda n: n.pop(),
        'duplicate-case': lambda n: n.insert(0, copy.deepcopy(n[0])),
        'wrong-order': lambda n: n.reverse(),
        'wrong-digest': lambda n: n[0].update(digest='0' * 64),
        'wrong-row-count': lambda n: n[0].update(rowCount=1),
        'wrong-byte-count': lambda n: n[0].update(responseBytes=0),
        'wrong-error-class': lambda n: n[ERROR_INDEX].update(error='IndexOutOfBoundsException'),
        'wrong-error-message': lambda n: n[ERROR_INDEX].update(message='changed'),
        'new-timeout': lambda n: n[0].update(outcome='TIMEOUT', censoredTimeout=True),
        'wrong-source-count': lambda n: n[0].update(inputSourceCount=1),
        'wrong-scope': lambda n: n[0].update(sourceScopeApplied=False),
        'wrong-timeout': lambda n: n[0].update(timeoutMillis=1),
        'zero-latency': lambda n: n[0].update(latencyNanos=0),
        'validation-error': lambda n: n[0].update(validationError='invalid'),
    }
    rejected = []
    for name, mutate in mutations.items():
        altered = copy.deepcopy(native)
        mutate(altered)
        try:
            compare_cases(workload, reference, main, altered)
        except ValueError:
            rejected.append(name)
        else:
            raise ValueError('mutation was accepted: ' + name)
    for field, value in [('matched', 1151), ('changed', ['x']), ('missing', ['x']), ('added', ['x'])]:
        audit = dict(originalFiles=1152, matched=1152, changed=[], missing=[], added=[])
        audit[field] = value
        try:
            check_audit(audit)
        except ValueError:
            rejected.append('fixture-' + field)
        else:
            raise ValueError('invalid fixture audit accepted')
    print(json.dumps(dict(correctnessOnly=True, performanceMeasurement=False,
                          validArchivedCaseSignaturesAccepted=1267, mutationsRejected=rejected), indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--state', choices=list(REFERENCES))
    parser.add_argument('--main', type=Path)
    parser.add_argument('--native', type=Path)
    parser.add_argument('--output-prefix', type=Path)
    parser.add_argument('--self-test', action='store_true')
    args = parser.parse_args()
    if args.self_test:
        self_test()
    else:
        require(all((args.state, args.main, args.native, args.output_prefix)), 'state/main/native/output-prefix required')
        args.main, args.native = args.main.resolve(), args.native.resolve()
        verify(args)
