"""Run the new actual-main oracle with only the pre-port engine dispatcher restored."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile

BASE = Path(__file__).resolve().parent
ROOT = BASE.parents[2]
MODULE = ROOT / 'graphite-server'
ORACLE = BASE.parent / 'native-filtered-string-aggregation'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run():
    out = BASE / 'baseline-control'
    out.mkdir()
    trial = Path(tempfile.mkdtemp(prefix='graphite-filtered-count-control-'))
    copy = trial / 'graphite-server'
    subprocess.run(['/bin/cp', '-cRp', str(MODULE), str(copy)], check=True)
    oracle = trial / 'oracle'
    subprocess.run(['/bin/cp', '-cRp', str(ORACLE), str(oracle)], check=True)
    before = {str(p):sha(p) for p in MODULE.rglob('*.go')}
    old = subprocess.check_output(['git', 'show', '9237131f:graphite-server/internal/query/engine.go'], cwd=ROOT)
    (copy / 'internal/query/engine.go').write_bytes(old)
    command = ['go', 'test', '-count=1', '-run', '^TestFilteredStringCountMain$', './internal/query']
    env = dict(os.environ, FILTERED_COUNT_ORACLE=str(oracle), FILTERED_COUNT_OUTPUT=str(out / 'responses.json'))
    with (out / 'test.log').open('x') as log:
        code = subprocess.run(command, cwd=copy, env=env, stdout=log, stderr=subprocess.STDOUT).returncode
    unchanged = all(sha(Path(p)) == h for p,h in before.items())
    report = dict(command=command, cwd=str(copy), exitCode=code, rootSourcesUnchanged=unchanged,
                  controlChange='Only engine.go restored from9237131f; new helper/tests kept',
                  controlEngineSHA256=sha(copy / 'internal/query/engine.go'),
                  oracleInputs={p.name:sha(p) for p in oracle.iterdir() if p.is_file()},
                  rootSourceInputs=before, testLogSHA256=sha(out / 'test.log'))
    (out / 'receipt.json').write_text(json.dumps(report, indent=2)+'\n')
    assert unchanged and code != 0, 'Actual-main regression tests must reject the old public path'
    print('Old dispatcher rejected by new actual-main tests; root sources unchanged', flush=True)


if __name__ == '__main__':
    run()
