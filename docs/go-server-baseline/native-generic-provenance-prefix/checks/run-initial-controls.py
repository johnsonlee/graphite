"""Retain Go comparisons for the original capability-absence observations."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
MODULE = ROOT / 'graphite-server'
ORACLE = HERE.parent / 'initial-missing-callsite-control'
GO = Path('/opt/homebrew/Cellar/go/1.22.0/libexec/bin/go')
BASELINE = '6d061b525a91d844f5c22a7231af6e18c61c0c3a'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write(path, value):
    path.write_text(json.dumps(value, indent=2) + '\n')


p = argparse.ArgumentParser()
p.add_argument('--output', type=Path, required=True)
out = p.parse_args().output.resolve()
out.mkdir(parents=True, exist_ok=False)
test = MODULE / 'internal/query/generic_provenance_prefix_test.go'
text = test.read_text()
assert text.count('"../../../docs/go-server-baseline/native-generic-provenance-prefix"') == 1
assert text.count('"fixtures.tar.gz"), 457)') == 1
text = text.replace('"../../../docs/go-server-baseline/native-generic-provenance-prefix"', json.dumps(str(ORACLE)))
text = text.replace('"fixtures.tar.gz"), 457)', '"fixtures.tar.gz"), 451)')
overlay_test = out / 'initial-control_test.go'
overlay_test.write_text(text)
production = MODULE / 'internal/query/indexed_distinct.go'
old = out / 'baseline-indexed_distinct.go'
old.write_bytes(subprocess.check_output(['git', 'show', BASELINE + ':' + str(production.relative_to(ROOT))], cwd=ROOT))
inputs = {str(f): sha(f) for f in MODULE.rglob('*') if f.is_file()}
inputs.update({str(f): sha(f) for f in [ORACLE / 'main.json', ORACLE / 'fixtures.tar.gz', GO, Path(__file__), overlay_test, old]})
write(out / 'inputs.json', inputs)
main = json.loads((ORACLE / 'main.json').read_text())['cases']
steps, comparisons = [], {}
for label in ['baseline', 'candidate']:
    replace = {str(test): str(overlay_test)}
    if label == 'baseline':
        replace[str(production)] = str(old)
    overlay = out / (label + '-overlay.json')
    write(overlay, {'Replace': replace})
    results = out / (label + '-outputs')
    command = [str(GO), 'test', '-overlay', str(overlay), '-count=1', '-v', '-run', '^TestGenericProvenanceMainPrefix$', './internal/query']
    env = dict(os.environ, PATH=str(GO.parent) + os.pathsep + os.environ['PATH'], GOTOOLCHAIN='local', INDEXED_DISTINCT_OUTPUT=str(results))
    with (out / (label + '.log')).open('x') as log:
        result = subprocess.run(command, cwd=MODULE, env=env, stdout=log, stderr=subprocess.STDOUT)
    steps.append(dict(runtime=label, command=command, exitCode=result.returncode))
    write(out / 'commands.json', steps)
    actual = json.loads((results / 'generic-provenance-prefix.json').read_text())
    assert len(actual) == len(main) == 13
    differences = []
    for g, j in zip(actual, main):
        assert g['name'] == j['name']
        expected = {'name': j['name']}
        expected.update({k: j[k] for k in (['columns', 'rows'] if j['outcome'] == 'SUCCESS' else ['error', 'message'])})
        if g != expected:
            differences.append(dict(name=j['name'], main=expected, go=g))
    comparisons[label] = dict(cases=13, matches=13-len(differences), differences=differences)
    assert result.returncode == (1 if differences else 0)
    unchanged = all(Path(f).is_file() and sha(Path(f)) == h for f, h in inputs.items())
    write(out / 'comparison.json', dict(performanceMeasurements=0, inputsUnchanged=unchanged, comparisons=comparisons))
    assert unchanged
    print(label, 'matches', 13-len(differences), '/13; original observations retained', flush=True)
write(out / 'artifacts.json', {str(f.relative_to(out)): sha(f) for f in sorted(out.rglob('*')) if f.is_file()})
