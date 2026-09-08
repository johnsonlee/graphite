"""Archive terminal 201-case diagnostics and expose every change from a prior capture."""
import argparse
import gzip
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil

HERE = Path(__file__).resolve().parent
RUNNER = HERE.parents[1] / 'native-generic-string-disjunction/candidate-replay/run.py'
# HERE.parents[1] is go-server-baseline.


def sha(p):
    return hashlib.sha256(p.read_bytes()).hexdigest()


def write(p, value):
    p.write_text(json.dumps(value, indent=2) + '\n')


p = argparse.ArgumentParser()
p.add_argument('--source', type=Path, required=True)
p.add_argument('--output', type=Path, required=True)
p.add_argument('--previous', type=Path, required=True)
a = p.parse_args()
source, out = a.source.resolve(), a.output.resolve()
comparison = json.loads((source / 'comparison.json').read_text())
assert comparison['cases'] == 201 and comparison['sourceUnchanged']
assert comparison['fixtureOriginalChanged'] == []
commands = json.loads((source / 'commands.json').read_text())
assert len(commands) == 2 and all(c['exitCode'] == 0 for c in commands)
spec = importlib.util.spec_from_file_location('matrix_replay', RUNNER)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
old = json.loads(a.previous.read_text())['cases']
new = json.loads((source / 'go.json').read_text())['cases']
assert len(old) == len(new) == 201
assert [r['name'] for r in old] == [r['name'] for r in new]
differences = [n['name'] for o, n in zip(old, new) if module.projected(o) != module.projected(n)]
out.mkdir(parents=True, exist_ok=False)
entries = []
names = ['source-before.json', 'inputs.json', 'input.json', 'compiled-source.json',
         'source.tar.gz', 'compile.stdout', 'compile.stderr', 'capture.stdout',
         'capture.stderr', 'commands.json', 'go.json', 'source-after.json',
         'comparison.json', 'artifact-manifest.json', 'fixtures-before.json',
         'fixtures-after.json', 'diagnostic.test']
for name in names:
    src, dst = source / name, out / name
    compressed = name in ['fixtures-before.json', 'fixtures-after.json', 'diagnostic.test']
    if compressed:
        dst = dst.with_name(dst.name + '.gz')
        with src.open('rb') as input_file, dst.open('xb') as output_file:
            with gzip.GzipFile(filename='', mode='wb', fileobj=output_file, mtime=0) as archive:
                shutil.copyfileobj(input_file, archive)
        assert hashlib.sha256(gzip.decompress(dst.read_bytes())).hexdigest() == sha(src)
    else:
        shutil.copyfile(src, dst)
        assert sha(src) == sha(dst)
    entries.append(dict(source=str(src), sourceSHA256=sha(src), archive=dst.name,
                        archiveSHA256=sha(dst), decompressedBytesEqual=True))
write(out / 'copy-verification.json', dict(files=entries,
    inferredControllerExitCode=0 if comparison['allComparedFieldsEqual'] else 1,
    previousComparedOutputDifferences=differences, cases=201, performanceMeasurements=0,
    diagnosticsScope='The legacy adapter does not pass the new ExecutionContext or compare its diagnostics; full route accounting remains incomplete. Its original comparison wording is retained verbatim.'))
print('Archived all 201 cases; changes from previous:', differences, '; remaining reference mismatches:',
      [len(c['differences']) for c in comparison['comparisons']], 'original mismatches')
