import hashlib
import json
import os
import pathlib
import subprocess
import time

ROOT = pathlib.Path(__file__).parent
PRIOR = pathlib.Path('/private/tmp/graphite-attempt140._5jztd0a')
MANIFEST = pathlib.Path('/private/tmp/pr113-attempt131-ascii.JqgmHw/fixture64/graphs.tsv')
EXPECTED_JAR = 'a5c2db2b0020798488916ec86902459d1044a7dcef606a73e00055883cdf5abe'

def digest(path):
    h = hashlib.sha256()
    with pathlib.Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()

def write(name, value):
    (ROOT / name).write_text(json.dumps(value, indent=2) + '\n')

def inventory():
    result = []
    for line in MANIFEST.read_text().splitlines():
        if not line or line.startswith('#'):
            continue
        parts = line.split('\t')
        directory = pathlib.Path(parts[1])
        result.append({'id': parts[0], 'files': [
            {'path': str(p.relative_to(directory)), 'size': p.stat().st_size, 'sha256': digest(p)}
            for p in sorted(directory.rglob('*')) if p.is_file()
        ]})
    return result

template = json.loads((PRIOR / 'old34-pairs/base-global-wide-1-command.json').read_text())
jar = pathlib.Path(template[2])
assert digest(jar) == EXPECTED_JAR
assert not any(os.environ.get(key) for key in ['JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS'])
before = inventory()
assert before == json.loads((PRIOR / 'v3-control/graph-content-after.json').read_text())
write('graph-content-before.json', before)
write('plan.json', {
    'purpose': 'Observe frozen-main compiler decisions without method tracing or async-profiler; not an optimization attempt, performance gate, causal profiler comparison, or speedup claim.',
    'base': '4e328b0109e13c896b74004823fb049fcb19251a',
    'jar': str(jar), 'jarSha256': EXPECTED_JAR,
    'template': str(PRIOR / 'old34-pairs/base-global-wide-1-command.json'),
    'templateSha256': digest(PRIOR / 'old34-pairs/base-global-wide-1-command.json'),
    'manifest': str(MANIFEST), 'manifestSha256': digest(MANIFEST),
    'forks': 3, 'changes': ['output paths', '-XX:+UnlockDiagnosticVMOptions', '-XX:+LogCompilation', '-XX:LogFile=<unique fork path>'],
    'unchanged': 'Frozen JAR, real 64 graphs, original 34 workload and oracle, no warmup, cold-on-replay, Java17, four active CPUs, 8GiB heap, GC profiler.',
    'limits': 'Compiler logging can perturb timing. It does not provide per-query timestamp windows; compile stamps cannot automatically be assigned to individual queries. No production changes and no acceptance comparison.'
})
runs = []
for number in range(1, 4):
    prefix = ROOT / f'fork-{number}'
    command = list(template)
    command[command.index('-rff') + 1] = str(prefix) + '.json'
    command[-1] = command[-1].replace(str(PRIOR / 'old34-pairs/base-global-wide-1.tsv'), str(prefix) + '.tsv')
    command[-1] += f' -XX:+UnlockDiagnosticVMOptions -XX:+LogCompilation -XX:LogFile={prefix}.compilation.xml'
    write(f'fork-{number}-command.json', command)
    assert digest(jar) == EXPECTED_JAR
    started = time.time()
    print('START compilation diagnostic', number, flush=True)
    with pathlib.Path(str(prefix) + '.log').open('w') as log:
        completed = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT)
    receipt = {'fork': number, 'exitCode': completed.returncode, 'startedEpoch': started, 'endedEpoch': time.time(), 'jarSha256Before': EXPECTED_JAR, 'jarSha256After': digest(jar)}
    runs.append(receipt)
    write('runs.json', runs)
    assert completed.returncode == 0
    assert receipt['jarSha256After'] == EXPECTED_JAR
    print('DONE compilation diagnostic', number, flush=True)
after = inventory()
write('graph-content-after.json', after)
assert before == after
write('completion.json', {'forks': 3, 'graphCount': len(after), 'graphFiles': sum(len(g['files']) for g in after), 'graphsUnchanged': before == after, 'jarSha256': digest(jar), 'productionModified': False, 'acceptedOptimization': False})
print('COMPLETE compiler diagnostic; all input hashes unchanged', flush=True)
