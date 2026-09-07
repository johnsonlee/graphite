from decimal import Decimal
import hashlib
import json
import os
from pathlib import Path
import socket

here = Path(__file__).resolve().parent
root = here.parents[3]
read = lambda p: json.loads(p.read_text())
sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
identity = read(here / 'source-identity.json')
inputs = read(here / 'source-inputs.json')
cfg = read(here / 'config.json')

def canonical(value):
    if isinstance(value, dict):
        return ('object', tuple(sorted((k, canonical(v)) for k, v in value.items())))
    if isinstance(value, list):
        return ('array', tuple(canonical(v) for v in value))
    if isinstance(value, bool):
        return ('boolean', value)
    if isinstance(value, Decimal):
        return ('number', value)
    if value is None:
        return ('null',)
    assert isinstance(value, str)
    return ('string', value)

def body(raw):
    return canonical(json.loads(raw, parse_float=Decimal, parse_int=Decimal))

assert sha(Path(identity['binary'])) == identity['binarySHA256']
assert sha(here / 'replay-http.py') == identity['runnerSHA256']
assert sha(here / 'config.json') == identity['configSHA256']
for name, want in identity['nativeSourcesAndEmbeds'].items():
    assert sha(Path(identity['worktree']) / name) == want, name
for name, want in inputs['testedFiles'].items():
    assert sha(Path(identity['worktree']) / 'graphite-server' / name) == want, name
assert [{'case': r['case'], 'baseline': r['response']} for r in read(here.parent / 'main-http/observations.json')] == read(here / 'pagination-main.json')
assert sha(root / 'docs/go-server-baseline/native64-preflight/queries/observations.json') == inputs['original42SHA256']
checks = []
for phase, count, port in [('pagination21', 21, 18877), ('regression42', 42, 18878)]:
    p = here / phase
    source = read(p / 'identity.json')
    assert source['binarySHA256'] == identity['binarySHA256']
    assert source['sourceIdentitySHA256'] == sha(here / 'source-identity.json')
    assert source['frozenMainResponsesSHA256'] == sha(Path(source['frozenMainResponses']))
    expected = read(Path(source['frozenMainResponses']))
    actual = read(p / 'observations.json')
    summary = read(p / 'summary.json')
    assert summary['complete'] and not summary['mismatches']
    assert summary['cases'] == summary['required'] == len(actual) == len(expected) == count
    assert source['environment'] == identity['executionEnvironment']
    catalog = read(p / 'catalog.json')
    assert catalog['count'] == len(catalog['graphs']) == len(cfg['graphs']) == 64
    assert catalog['totals'] == cfg['totals']
    for graph, want in zip(catalog['graphs'], cfg['graphs']):
        for key in ['id', 'nodes', 'edges', 'methods', 'callSites']:
            assert graph[key] == want[key], (phase, graph['id'], key)
    for got, want in zip(actual, expected):
        assert got['case'] == want['case'] and got['baseline'] == want['baseline']
        assert not got['case'].get('dynamicPointers')
        a, b = got['candidate'], want['baseline']
        assert a['status'] == b['status'] == 200
        assert body(a['body']) == body(b['body']), (phase, got['case']['name'])
        for key in ['Content-Type', 'Retry-After']:
            assert a['headers'].get(key) == b['headers'].get(key)
        if 'Content-Length' in a['headers']:
            assert int(a['headers']['Content-Length']) == len(a['body'].encode())
        checks.append({'phase': phase, 'case': got['case']['name'], 'fullTypedBodyEqual': True, 'status': 200})
    exit_record = read(p / 'server-exit.json')
    assert exit_record['returnCode'] == 143 and not exit_record['forcedKill']
    try:
        os.kill(exit_record['pid'], 0)
    except ProcessLookupError:
        pass
    else:
        raise AssertionError(('server still alive', phase, exit_record['pid']))
    s = socket.socket()
    s.bind(('127.0.0.1', port))
    s.close()
assert len(checks) == 63
result = {'passed': True, 'completeHTTP200': 63, 'phaseCounts': [21, 42], 'allFullTypedBodiesHeadersAndCatalogsEqual': True, 'compilerAndEmbedInputsVerified': len(identity['nativeSourcesAndEmbeds']), 'fullModuleInputsVerified': len(inputs['testedFiles']), 'sourceComparisonWorktree': identity['worktree'], 'binarySHA256': identity['binarySHA256'], 'allTwoServersExited143': True, 'allPortsFree': True, 'noDynamicMasks': True, 'checks': checks, 'scope': 'Independent complete-record verification; no performance/P95 claim.'}
(here / 'verification.json').write_text(json.dumps(result, indent=2) + '\n')
print('PASS: 63 full typed HTTP responses, headers,64 catalogs, sources/binary and two process exits')
