"""Archive a terminal entry capture without modifying either baseline capture."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import tarfile


def sha(p):
    return hashlib.sha256(p.read_bytes()).hexdigest()


p = argparse.ArgumentParser()
p.add_argument('--source', type=Path, required=True)
p.add_argument('--output', type=Path, required=True)
a = p.parse_args()
source, out = a.source.resolve(), a.output.resolve()
receipt = json.loads((source / 'receipt.json').read_text())
assert receipt['terminal'] and receipt['baselineAndModuleUnchanged']
assert receipt['oracleInputsUnchanged'] and receipt['observerComparisonEqual']
out.mkdir(parents=True, exist_ok=False)
files = []
for f in sorted(source.rglob('*')):
    relative = f.relative_to(source)
    if not f.is_file() or relative.parts[0] == 'module':
        continue
    target = out / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(f, target)
    assert sha(f) == sha(target)
    files.append(dict(source=str(f), file=str(relative), bytes=f.stat().st_size, sha256=sha(f)))
expected = json.loads((out / 'module-with-adapter-inputs.json').read_text())
with tarfile.open(out / 'source-with-adapter.tar.gz') as archive:
    actual = {m.name: hashlib.sha256(archive.extractfile(m).read()).hexdigest()
              for m in archive if m.isfile()}
assert actual == expected
original = (source / 'module/internal/query/execution_context.go').read_text()
instrumentation = json.loads((out / 'instrumentation.json').read_text())
modified = (out / 'execution_context.instrumented.go').read_text()
needle = 'func (c *ExecutionContext) consume(units int64) {\n'
injected = instrumentation['insertedDeclaration'] + needle + instrumentation['insertedObserver']
assert modified.count(injected) == 1 and modified.replace(injected, needle) == original
plain = json.loads((out / 'plain.json').read_text())
instrumented = json.loads((out / 'instrumented.json').read_text())
assert len(plain['cases']) == 7 and len(instrumented['cases']) == 10
summary = dict(files=files, moduleFiles=len(expected),
               plainDifferences=plain['differences'], instrumentedDifferences=instrumented['differences'],
               observerComparisonEqual=True, originalConsumeRestoredExactly=True,
               commands=receipt['commands'], performanceMeasurements=0)
(out / 'archive-verification.json').write_text(json.dumps(summary, indent=2) + '\n')
print('Archived', len(files), 'artifacts; differences:', plain['differences'], instrumented['differences'])
