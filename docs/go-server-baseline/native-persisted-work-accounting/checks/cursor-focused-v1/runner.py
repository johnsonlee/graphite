"""Freeze and test the mapped cursor correction; this is not a benchmark."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile

HERE = Path(__file__).resolve().parent
MODULE = HERE.parents[3] / 'graphite-server'
GO = Path('/opt/homebrew/Cellar/go/1.22.0/libexec/bin/go')


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def inventory(folder):
    return {str(f.relative_to(folder)): sha(f)
            for f in sorted(folder.rglob('*')) if f.is_file()}


p = argparse.ArgumentParser()
p.add_argument('--output', type=Path, required=True)
a = p.parse_args()
out = a.output.resolve()
out.mkdir(parents=True, exist_ok=False)
module = out / 'module'
original = inventory(MODULE)
shutil.copytree(MODULE, module)
assert inventory(module) == original
fixture = HERE.parent / 'fixtures.tar.gz'
fixture_copy = out / 'docs/go-server-baseline/native-persisted-work-accounting/fixtures.tar.gz'
fixture_copy.parent.mkdir(parents=True)
shutil.copyfile(fixture, fixture_copy)
assert sha(fixture_copy) == sha(fixture)
(out / 'module-inputs.json').write_text(json.dumps(original, indent=2) + '\n')
with tarfile.open(out / 'source.tar.gz', 'w:gz') as archive:
    for name in original:
        archive.add(module / name, arcname=name, recursive=False)
shutil.copyfile(__file__, out / 'runner.py')
cmd = [str(GO), 'test', '-race', '-count=1', '-v', '-run',
       '^TestMainMapped', './internal/store', './internal/query']
env = dict(os.environ, PATH=str(GO.parent) + os.pathsep + os.environ['PATH'],
           GOTOOLCHAIN='local')
preflight = dict(command=cmd, cwd=str(module), goSha256=sha(GO),
                 runnerSha256=sha(Path(__file__)), moduleFiles=len(original),
                 sourceArchiveSha256=sha(out / 'source.tar.gz'),
                 fixtureSha256=sha(fixture_copy),
                 performanceMeasurements=0)
(out / 'preflight.json').write_text(json.dumps(preflight, indent=2) + '\n')
with (out / 'test.log').open('x') as log:
    process = subprocess.Popen(cmd, cwd=module, env=env, stdout=log,
                               stderr=subprocess.STDOUT)
    (out / 'process.json').write_text(json.dumps(dict(pid=process.pid,
                                                    command=cmd), indent=2) + '\n')
    code = process.wait()
unchanged = inventory(module) == original and sha(fixture_copy) == preflight['fixtureSha256']
receipt = dict(preflight, exitCode=code, inputsUnchanged=unchanged,
               originalModuleStillMatches=inventory(MODULE) == original)
(out / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
(out / 'artifacts.json').write_text(json.dumps({f.name: sha(f)
                                              for f in sorted(out.iterdir())
                                              if f.is_file()}, indent=2) + '\n')
print(json.dumps(receipt, indent=2), flush=True)
assert unchanged
raise SystemExit(code)
