#!/usr/bin/env python3
"""Freeze and profile a native revision on the complete 64 real persisted graphs."""
import argparse
import datetime
import hashlib
import json
import os
import pathlib
import shutil
import subprocess
import tempfile

p = argparse.ArgumentParser()
p.add_argument('--worktree', type=pathlib.Path, required=True)
p.add_argument('--out', type=pathlib.Path, required=True)
a = p.parse_args()
here = pathlib.Path(__file__).resolve().parent
out = a.out.resolve()
out.mkdir()  # Evidence from an earlier run must never be overwritten.
module = a.worktree.resolve() / 'graphite-server'
cfg = json.loads((here / 'config.json').read_text())
assert len(cfg['graphs']) == 64
paths = {g['id']: pathlib.Path(g['path']) for g in cfg['graphs']}
frozen = here.parent / 'native64-profile-a7de0bec/fixture-files.json'

def save(name, value):
    (out / name).write_text(json.dumps(value, indent=2) + '\n')

def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as f:
        for b in iter(lambda: f.read(1024 * 1024), b''):
            h.update(b)
    return h.hexdigest()

files = json.loads(frozen.read_text())
for item in files:
    path = paths[item['graphId']] / item['file']
    assert path.stat().st_size == item['bytes'], str(path)
    assert digest(path) == item['sha256'], str(path)
save('fixture-verification.json', {
    'verifiedAtUTC': datetime.datetime.now(datetime.timezone.utc).isoformat(),
    'manifest': str(frozen), 'manifestSHA256': digest(frozen),
    'files': len(files), 'bytes': sum(f['bytes'] for f in files),
    'allHashesMatch': True,
})
print('All 64 real fixture hashes verified', flush=True)
harness = module / 'cmd/profile-query/main.go'
harness.parent.mkdir(exist_ok=True)
if harness.exists():
    assert digest(harness) == digest(here / 'profile-query.go'), 'Different existing profiling harness'
else:
    shutil.copyfile(here / 'profile-query.go', harness)
helper = module / 'internal/server/profile_export.go'
if helper.exists():
    assert digest(helper) == digest(here / 'server-profile-export.go'), 'Different existing serializer bridge'
else:
    shutil.copyfile(here / 'server-profile-export.go', helper)
state_helper = module / 'internal/store/profile_state.go'
if state_helper.exists():
    assert digest(state_helper) == digest(here / 'store-profile-state.go'), 'Different existing state observer'
else:
    shutil.copyfile(here / 'store-profile-state.go', state_helper)
source = []
for path in sorted(module.rglob('*')):
    if path.is_file() and '.git' not in path.parts:
        source.append({'path': str(path.relative_to(module)),
                       'bytes': path.stat().st_size, 'sha256': digest(path)})
save('source-manifest.json', source)
untracked = subprocess.check_output(
    ['git', 'ls-files', '--others', '--exclude-standard', '-z', '--', 'graphite-server'],
    cwd=a.worktree).decode().split('\0')
for name in filter(None, untracked):
    target = out / 'new-source' / name
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(a.worktree / name, target)
binary = pathlib.Path(tempfile.mkdtemp(prefix='graphite-slot-profile-')) / 'profile-query'
build = ['go', 'build', '-o', str(binary), './cmd/profile-query']
subprocess.run(build, cwd=module, check=True)
(out / 'binary-build-info.txt').write_bytes(subprocess.check_output(['go', 'version', '-m', str(binary)]))
(out / 'source.patch').write_bytes(subprocess.check_output(['git', 'diff', 'HEAD', '--', 'graphite-server'], cwd=a.worktree))
command = [str(binary), '--config', str(here / 'config.json'), '--out', str(out / 'profiles')]
save('identity.json', {
    'baseRevision': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=a.worktree, text=True).strip(),
    'binarySHA256': digest(binary), 'harnessSHA256': digest(harness),
    'sourceManifestSHA256': digest(out / 'source-manifest.json'),
    'sourcePatchSHA256': digest(out / 'source.patch'),
    'buildCommand': build, 'command': command,
    'environment': {k: os.getenv(k) for k in ['GOGC', 'GOMEMLIMIT', 'GOMAXPROCS', 'GODEBUG']},
    'purpose': 'Allocation and CPU diagnostic on all 64 graphs; not HTTP or P95.',
})
with (out / 'profile.log').open('w') as log:
    result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT)
save('completion.json', {'exitCode': result.returncode,
                         'finishedAtUTC': datetime.datetime.now(datetime.timezone.utc).isoformat()})
print('Profile exit', result.returncode, flush=True)
raise SystemExit(result.returncode)
