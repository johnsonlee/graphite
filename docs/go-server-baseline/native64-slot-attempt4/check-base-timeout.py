#!/usr/bin/env python3
"""Check whether the unchanged native base also misses the HTTP deadline."""
import hashlib, importlib.util, json, pathlib, subprocess, time, urllib.request
here = pathlib.Path(__file__).resolve().parent
out = here / 'base-http-timeout'
out.mkdir()
spec = importlib.util.spec_from_file_location('parity', here.parents[2] / 'graphite-server/scripts/http_parity.py')
parity = importlib.util.module_from_spec(spec)
spec.loader.exec_module(parity)
cfg = json.loads((here / 'config.json').read_text())
old = json.loads((here.parent / 'native64-preflight/queries/observations.json').read_text())[0]
binary = pathlib.Path('/tmp/graphite-go-slot-base-http-4124bfc4')
command = [str(binary), '--data', '/tmp/pr113-exp037-fixture.nXn4fg', '--port', '18857', '--max-concurrent-cypher', '4']
for g in cfg['graphs']:
    command += ['--graph', g['id'] + ':' + g['path']]
def save(name, value):
    (out / name).write_text(json.dumps(value, indent=2) + '\n')
save('identity.json', {'sourceRevision': '4124bfc44bedd7915e238b7a08f2524b48ed47b6',
                      'binarySHA256': hashlib.sha256(binary.read_bytes()).hexdigest(),
                      'command': command, 'purpose': 'One default-deadline HTTP correctness diagnostic on all 64 real graphs; no P95.'})
with (out / 'server.log').open('w') as log:
    server = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT)
    try:
        for _ in range(300):
            if server.poll() is not None:
                raise RuntimeError('server stopped')
            try:
                with urllib.request.urlopen('http://127.0.0.1:18857/api/graphs', timeout=3) as f:
                    catalog = json.load(f)
                break
            except (OSError, TimeoutError):
                time.sleep(1)
        else:
            raise RuntimeError('server not ready')
        assert catalog['count'] == 64 and catalog['totals'] == cfg['totals']
        for actual, want in zip(catalog['graphs'], cfg['graphs']):
            for k in ['id', 'nodes', 'edges', 'methods', 'callSites']:
                assert actual[k] == want[k]
        save('catalog.json', catalog)
        actual = parity.fetch('http://127.0.0.1:18857', old['case'], '')
        save('observation.json', {'case': old['case'], 'main': old['baseline'], 'nativeBase': actual,
                                 'equalToMain': parity.signature(old['baseline'], old['case']) == parity.signature(actual, old['case'])})
        print(old['case']['name'], actual['status'], flush=True)
    finally:
        server.terminate()
        try:
            server.wait(timeout=15)
        except subprocess.TimeoutExpired:
            server.kill()
            server.wait()
