"""Reproduce the actual-main oracle in a new directory, never a post-run fixture."""
import hashlib
import json
from pathlib import Path
import shutil
import struct
import subprocess
import sys

BASE = Path(__file__).resolve().parent
ROOT = BASE.parents[2]
out = Path(sys.argv[1]).resolve()
out.mkdir()
(out / 'classes').mkdir()
before = {}
for name in ['clean', 'bad-matched', 'valid-tag', 'unknown-tag', 'offset-negative-90', 'offset-negative-2']:
    for i in range(64):
        directory = out / 'fixtures' / name / f'g{i:02d}'
        source = ROOT / 'graphite-server/internal/query/testdata/candidate-index' / (
            'bad-matched' if name == 'bad-matched' and i == 0 else 'clean')
        shutil.copytree(source, directory)
        if i == 0 and name in ['valid-tag', 'unknown-tag']:
            path = directory / 'graph.nodedata'
            data = bytearray(path.read_bytes())
            data[69] = 0 if name == 'valid-tag' else 127
            path.write_bytes(data)
        if i == 0 and name.startswith('offset-negative'):
            path = directory / 'graph.nodeoffsets'
            data = bytearray(path.read_bytes())
            struct.pack_into('>q', data, 8 + int(name.split('-')[-1]) * 8, -1)
            path.write_bytes(data)
        for path in directory.iterdir():
            before[str(path.relative_to(out / 'fixtures'))] = hashlib.sha256(path.read_bytes()).hexdigest()
(out / 'fixture-before.json').write_text(json.dumps(before, indent=2) + '\n')
jar = '/tmp/graphite-go-main-baseline-clone-4e328b0/graphite-explore/build/libs/graphite-explore.jar'
assert hashlib.sha256(Path(jar).read_bytes()).hexdigest() == '91c3a1d154ca96004c55df195d9f752e077cab3e33ca1570b2c88b872d9bc34d'
commands = []
for i, command in enumerate([
    ['javac', '-cp', jar, '-d', str(out / 'classes'),
     str(BASE.parent / 'native-index-lifecycle/IndexLifecycleOracle.java'), str(BASE / 'OrdinaryMappedRangeOracle.java')],
    ['java', '-Xmx512m', '-cp', str(out / 'classes') + ':' + jar, 'OrdinaryMappedRangeOracle',
     str(out / 'fixtures'), str(out / 'main.json')]
]):
    result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    (out / f'command-{i}.log').write_text(result.stdout)
    commands.append({'command': command, 'exitCode': result.returncode})
    (out / 'commands.json').write_text(json.dumps(commands, indent=2) + '\n')
    result.check_returncode()
