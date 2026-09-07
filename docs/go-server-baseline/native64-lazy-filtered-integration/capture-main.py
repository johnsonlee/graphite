#!/usr/bin/env python3
"""Fresh pinned-main all64 HTTP oracle, with the complete fixed query history."""
import datetime
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import socket
import subprocess
import time
import urllib.request

here = Path(__file__).resolve().parent
root = here.parents[2]
out = here / 'main-http'
out.mkdir()
cfg = json.loads((here / 'config.pending-main.json').read_text())
prep = json.loads((here / 'preparation.json').read_text())
clone = Path(prep['clones']['main'])
jar = Path('/tmp/graphite-go-main-baseline-clone-4e328b0/graphite-explore/build/libs/graphite-explore.jar')
port = 18871

def digest(p):
    h = hashlib.sha256()
    with p.open('rb') as stream:
        for b in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(b)
    return h.hexdigest()

def save(name, value):
    (out / name).write_text(json.dumps(value, indent=2) + '\n')

assert digest(jar) == '91c3a1d154ca96004c55df195d9f752e077cab3e33ca1570b2c88b872d9bc34d'
frozen = here.parent / 'native64-profile-a7de0bec/fixture-files.json'
files = json.loads(frozen.read_text())
for f in files:
    p = clone / f['graphId'] / f['file']
    assert p.stat().st_size == f['bytes'] and digest(p) == f['sha256'], str(p)
save('fixture-before.json', {'files': len(files), 'allHashesMatch': True, 'manifestSHA256': digest(frozen)})
spec = importlib.util.spec_from_file_location('parity', root / 'graphite-server/scripts/http_parity.py')
parity = importlib.util.module_from_spec(spec)
spec.loader.exec_module(parity)
env = os.environ.copy()
for k in ['JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS']:
    env.pop(k, None)
command = ['java', '-Xmx8g', '-jar', str(jar), '--data', str(clone), '--port', str(port), '--max-concurrent-cypher', '4']
for g in cfg['graphs']:
    command += ['--graph', g['id'] + ':' + str(clone / g['id'])]
save('identity.json', {'mainRevision': '4e328b0109e13c896b74004823fb049fcb19251a', 'command': command,
                      'jarSHA256': digest(jar), 'harnessSHA256': digest(Path(__file__)),
                      'parityFetchSHA256': digest(root / 'graphite-server/scripts/http_parity.py'),
                      'javaVersion': subprocess.run(['java', '-version'], capture_output=True, text=True, env=env).stderr,
                      'purpose': 'Fresh complete HTTP oracle; not a latency or P95 acceptance run.',
                      'environment': {k: env.get(k) for k in ['JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS']}})
observations = []
with (out / 'server.log').open('w') as log:
    server = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT, env=env)
    save('process.json', {'pid': server.pid, 'startedUTC': datetime.datetime.now(datetime.timezone.utc).isoformat()})
    try:
        for _ in range(300):
            if server.poll() is not None:
                raise RuntimeError('Main exited before ready')
            try:
                with urllib.request.urlopen(f'http://127.0.0.1:{port}/api/graphs', timeout=3) as response:
                    catalog = json.load(response)
                break
            except (OSError, TimeoutError):
                time.sleep(1)
        else:
            raise RuntimeError('Main did not become ready')
        assert catalog['count'] == len(catalog['graphs']) == len(cfg['graphs']) == 64
        assert catalog['totals'] == cfg['totals']
        for actual, want in zip(catalog['graphs'], cfg['graphs']):
            for key in ['id', 'nodes', 'edges', 'methods', 'callSites']:
                assert actual[key] == want[key], (key, actual['id'])
        save('catalog.json', catalog)
        for q in cfg['queries']:
            case = {'name': q['name'], 'method': 'POST', 'path': '/api/cypher', 'body': {'query': q['query']}}
            response = parity.fetch(f'http://127.0.0.1:{port}', case, '')
            record = {'case': case, 'response': response}
            observations.append(record)
            save('observations.partial.json', observations)
            assert response['status'] == 200, record
            body = json.loads(response['body'])
            assert body['graphCount'] == 64
            if 'expected' in q:
                assert body == q['expected'], q['name']
            else:
                assert body['rowCount'] == len(body['rows'])
                q['expected'] = body
            print(q['name'], response['status'], body['rowCount'], flush=True)
        assert len(observations) == 13
        save('observations.json', observations)
    finally:
        server.terminate()
        forced = False
        try:
            server.wait(timeout=30)
        except subprocess.TimeoutExpired:
            forced = True
            server.kill()
            server.wait()
        save('server-exit.json', {'pid': server.pid, 'returnCode': server.returncode, 'forcedKill': forced})
assert not forced
assert digest(jar) == '91c3a1d154ca96004c55df195d9f752e077cab3e33ca1570b2c88b872d9bc34d'
for f in files:
    assert digest(clone / f['graphId'] / f['file']) == f['sha256'], f
save('fixture-after.json', {'files': len(files), 'allHashesMatch': True})
save('completion.json', {'complete': True, 'cases': 13, 'oldFiveFullBodiesEqual': True,
                         'newLazyConsumerBodies': 8, 'serverExited': True, 'forcedKill': False})
(here / 'config.json').write_text(json.dumps(cfg, indent=2) + '\n')
print('Complete: 13 full HTTP bodies; original fixed five preserved; main exited', flush=True)
