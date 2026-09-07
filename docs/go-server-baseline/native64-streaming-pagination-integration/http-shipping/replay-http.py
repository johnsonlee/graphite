#!/usr/bin/env python3
"""Complete fixed-response correctness replay on all 64 graphs; no latency claim."""
import argparse
import datetime
import hashlib
import importlib.util
import json
import os
import pathlib
import subprocess
import time
import urllib.request

p = argparse.ArgumentParser()
p.add_argument('--binary', type=pathlib.Path, required=True)
p.add_argument('--out', type=pathlib.Path, required=True)
p.add_argument('--source-identity', type=pathlib.Path, required=True)
p.add_argument('--port', type=int, required=True)
p.add_argument('--frozen', type=pathlib.Path, required=True)
p.add_argument('--required', type=int, required=True)
a = p.parse_args()
here = pathlib.Path(__file__).resolve().parent
root = pathlib.Path('/Users/johnsonlee/.codex/worktrees/112399f5-4ea0-42da-af34-5ab6ef682c3d/graphite')
out = a.out.resolve()
out.mkdir()
spec = importlib.util.spec_from_file_location('parity', root / 'graphite-server/scripts/http_parity.py')
parity = importlib.util.module_from_spec(spec)
spec.loader.exec_module(parity)
cfg = json.loads((here / 'config.json').read_text())
source = a.frozen.resolve()
frozen = json.loads(source.read_text())
assert len(cfg['graphs']) == 64 and len(frozen) == a.required
binary = a.binary.resolve()
command = [str(binary), '--data', '/tmp/graphite-pagination-http-real64-62b92d20', '--port', str(a.port), '--max-concurrent-cypher', '4']
for g in cfg['graphs']:
    command += ['--graph', g['id'] + ':' + g['path']]

def save(name, value):
    (out / name).write_text(json.dumps(value, indent=2) + '\n')

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

save('identity.json', {
    'command': command, 'binarySHA256': digest(binary),
    'sourceIdentity': str(a.source_identity.resolve()), 'sourceIdentitySHA256': digest(a.source_identity),
    'frozenMainResponses': str(source), 'frozenMainResponsesSHA256': digest(source),
    'environment': {k: os.getenv(k) for k in ['GOGC', 'GODEBUG', 'GOMEMLIMIT', 'GOMAXPROCS']},
    'purpose': 'Complete HTTP correctness replay. Default timeout and admission configuration. No P95.',
})
records = []
started = datetime.datetime.now(datetime.timezone.utc).isoformat()
with (out / 'server.log').open('w') as log:
    server = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT)
    try:
        for _ in range(300):
            if server.poll() is not None:
                raise RuntimeError('server stopped before ready')
            try:
                with urllib.request.urlopen(f'http://127.0.0.1:{a.port}/api/graphs', timeout=3) as f:
                    catalog = json.load(f)
                break
            except (OSError, TimeoutError):
                time.sleep(1)
        else:
            raise RuntimeError('server not ready')
        assert catalog['count'] == 64 and catalog['totals'] == cfg['totals'], catalog
        for actual, want in zip(catalog['graphs'], cfg['graphs']):
            for k in ['id', 'nodes', 'edges', 'methods', 'callSites']:
                assert actual[k] == want[k], (actual, want, k)
        save('catalog.json', catalog)
        for old in frozen:
            case = old['case']
            request_started = time.perf_counter()
            actual = parity.fetch(f'http://127.0.0.1:{a.port}', case, '')
            elapsed = time.perf_counter() - request_started
            equal = parity.signature(old['baseline'], case) == parity.signature(actual, case)
            headers = {k: {'expected': old['baseline']['headers'].get(k), 'actual': actual['headers'].get(k)}
                       for k in ['Content-Type', 'Retry-After']
                       if old['baseline']['headers'].get(k) != actual['headers'].get(k)}
            records.append({'case': case, 'baseline': old['baseline'], 'candidate': actual,
                            'equal': equal, 'headerDifferences': headers,
                            'elapsedSecondsDiagnostic': elapsed})
            save('observations.partial.json', records)
            print(case['name'], actual['status'], equal, headers, flush=True)
        save('observations.json', records)
        save('summary.json', {
            'cases': len(records), 'required': a.required, 'complete': len(records) == a.required,
            'mismatches': [r['case']['name'] for r in records if not r['equal'] or r['headerDifferences']],
            'started': started, 'finished': datetime.datetime.now(datetime.timezone.utc).isoformat(),
            'purpose': 'Correctness only; no P95 result',
        })
    finally:
        server.terminate()
        forced = False
        try:
            server.wait(timeout=15)
        except subprocess.TimeoutExpired:
            forced = True
            server.kill()
            server.wait()

        save('server-exit.json', {'pid': server.pid, 'returnCode': server.returncode, 'forcedKill': forced})
