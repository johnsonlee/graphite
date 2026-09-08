"""Serial fresh-process whole-workload sampling; never overwrite a trial."""
import argparse
import datetime
import fcntl
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import time

from verify_pair import verify, references, WORKLOAD, FIXTURES

BASE = Path(__file__).resolve().parent
ROOT = BASE.parents[2]
STATES = ['cold', 'startup-prepared', 'warm-after-failed-prewarm']
DATA = Path('/Users/johnsonlee/.codex/benchmarks/graphite')
spec = importlib.util.spec_from_file_location('real_fixtures', BASE.parent / 'native64-fullcase-replay/audit-fixtures.py')
fixtures = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixtures)

def sha(path): return fixtures.sha(path)
def read(path): return json.loads(path.read_text())
def write(path, value):
    temporary = path.with_name(path.name + '.pending')
    temporary.write_text(json.dumps(value, indent=2) + '\n')
    temporary.replace(path)
def clean(audit): return audit['matched'] == 1152 and not any(audit[k] for k in ['added','changed','missing'])
def verify_inputs(inputs):
    for name, digest in inputs.items():
        assert Path(name).is_file() and sha(Path(name)) == digest, 'Frozen input changed: ' + name

def processes():
    return subprocess.check_output(['ps','-axo','pid,pcpu,pmem,comm'],text=True)

def run_runtime(runtime, state, pair, build, inputs, reference, campaign):
    out = pair / runtime
    out.mkdir()
    trial = DATA / ('p95-data-' + campaign + '-' + pair.name + '-' + runtime)
    trial.mkdir()
    clone = trial / 'fixture'
    subprocess.run(['/bin/cp','-cRp',str(reference),str(clone)],check=True)
    pre = fixtures.audit(clone)
    write(out / 'clone-preflight.json', pre)
    assert clean(pre), 'Fixture clone mismatch'
    lines = []
    for line in (clone / 'graphs.tsv').read_text().splitlines():
        if not line.strip() or line.lstrip().startswith('#'): lines.append(line); continue
        fields = line.split('\t'); assert len(fields) == 6
        fields[1] = str(clone / fields[0]); lines.append('\t'.join(fields))
    manifest = out / 'graphs-relocated.tsv'
    manifest.write_text('\n'.join(lines) + '\n')
    verify_inputs(inputs)
    env = {k: os.environ.get(k) for k in build['runtimeEnvironment']}
    assert env == build['runtimeEnvironment'], 'Runtime options changed after build freeze'
    capture = out / 'capture'
    if runtime == 'main':
        command = [build['java'],'-Xmx8g','-cp',build['mainClasspath'],'MainLatencyCapture',str(manifest),state,str(capture),str(BASE / 'main-tooling')]
    else:
        capture.mkdir()
        command = [build['nativeBinary'],'--graphs',str(manifest),'--state',state,'--workload',str(Path(build['nativeModule']) / 'internal/benchmarkcase/testdata/main64.json'),'--output',str(capture / 'observations.jsonl')]
    receipt = dict(runtime=runtime, state=state, command=command, status='starting', clone=str(clone),
                   engineRevision=build['engineRevision'], mainRevision='4e328b0109e13c896b74004823fb049fcb19251a',
                   performanceMeasurement=True, diagnosticOnly=True, samplesPerCase=1,
                   manifestSHA256=sha(manifest), environment=env)
    write(out / 'process.json', receipt)
    (out / 'host-before.txt').write_text(processes())
    failure = None
    with (out / 'process.log').open('x') as log:
        child = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT)
        receipt.update(pid=child.pid, status='running', startedAtNanos=time.time_ns(),
                       startedAt=datetime.datetime.now(datetime.timezone.utc).isoformat())
        write(out / 'process.json', receipt)
        print(pair.name, runtime, 'PID', child.pid, flush=True)
        try:
            code = child.wait()
        except BaseException as error:
            failure = error
            child.terminate()
            try: code = child.wait(timeout=10)
            except subprocess.TimeoutExpired: child.kill(); code = child.wait()
        receipt.update(status='exited', exitCode=code, finishedAtNanos=time.time_ns(),
                       finishedAt=datetime.datetime.now(datetime.timezone.utc).isoformat())
        write(out / 'process.json', receipt)
    (out / 'host-after.txt').write_text(processes())
    verify_inputs(inputs)
    assert sha(manifest) == receipt['manifestSHA256'], 'Manifest changed during runtime'
    post = fixtures.audit(clone)
    write(out / 'postrun-fixtures.json', post)
    receipt.update(inputsUnchanged=True, fixtureAuditPassed=clean(post))
    write(out / 'process.json', receipt)
    assert clean(post), 'Graph files changed during runtime'
    if failure is not None: raise failure
    assert code == 1, 'Original all-success gate exit changed; inspect preserved trial'
    return trial

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--build', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--pairs', type=int, default=200)
    parser.add_argument('--resume', action='store_true')
    args = parser.parse_args()
    assert args.pairs > 0
    DATA.mkdir(parents=True, exist_ok=True)
    with (DATA / 'real64-measurement.lock').open('a+') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        build_dir, out = args.build.resolve(), args.output.resolve()
        build_path = build_dir / 'build.json'
        build, inputs = read(build_path), read(build_dir / 'inputs.json')
        build_sha = sha(build_path)
        inputs[str(build_path)] = build_sha
        assert inputs[build['nativeBinary']] == build['nativeBinarySHA256']
        assert build['mainClasspath'] == (BASE / 'main-tooling/classpath.txt').read_text().strip()
        assert build['java'] in inputs
        verify_inputs(inputs)
        workload = read(WORKLOAD)
        reference_records = references(workload)
        reference = Path(read(BASE.parent / 'native64-fullcase-replay/main-cold-preflight.json')['reference'])
        if not args.resume:
            out.mkdir(parents=True)
            preflight = fixtures.audit(reference)
            write(out / 'reference-preflight.json', preflight)
            assert clean(preflight)
            schedule = []
            for repetition in range(args.pairs):
                for offset in range(len(STATES)):
                    state = STATES[(repetition + offset) % len(STATES)]
                    order = ['main','go'] if (repetition + STATES.index(state)) % 2 == 0 else ['go','main']
                    schedule.append(dict(id=f'{state}-{repetition+1:04}', state=state, runtimeOrder=order))
            controller = dict(schemaVersion=1, workload=dict(path=str(WORKLOAD),sha256=sha(WORKLOAD)),
                graphManifestSHA256=sha(FIXTURES), states=STATES, trials=[], schedule=schedule, plannedPairsPerState=args.pairs,
                measurementAcceptanceEligible=False, limitations=['Original all-success benchmark gate fails on case821',
                'warm-after-failed-prewarm is diagnostic continuation, not formal original warm',
                'Go lacks main-equivalent resource sampler and work metrics; full server fidelity unfinished'],
                protocol='fresh process per runtime/state/whole ordered workload; no discarded process JIT warmup; alternating paired runtime order',
                build=str(build_dir), buildInputsSHA256=sha(build_dir / 'inputs.json'), buildSHA256=build_sha, status='running')
            write(out / 'controller.json', controller)
        else:
            controller = read(out / 'controller.json')
            assert controller['build'] == str(build_dir) and controller['buildInputsSHA256'] == sha(build_dir / 'inputs.json')
            assert controller['plannedPairsPerState'] == args.pairs and controller['buildSHA256'] == build_sha
        campaign = hashlib.sha256(str(out).encode()).hexdigest()[:12]
        completed = {trial['id'] for trial in controller['trials']}
        try:
            for planned in controller['schedule']:
                if planned['id'] in completed: continue
                pair = out / planned['id']
                pair.mkdir()  # Refuse to restart or replace an incomplete trial.
                controller.update(status='running', currentTrial=planned['id'])
                write(out / 'controller.json', controller)
                data = []
                for runtime in planned['runtimeOrder']:
                    data.append(run_runtime(runtime, planned['state'], pair, build, inputs, reference, campaign))
                record = verify(pair, planned['id'], planned['state'], workload, reference_records)
                write(pair / 'records.json', record)
                controller['trials'].append(dict(id=planned['id'], state=planned['state'], recordsPath=str((pair / 'records.json').relative_to(out)),
                    recordsSHA256=sha(pair / 'records.json'), verificationPassed=True, complete=True, serialExecutionVerified=True))
                write(out / 'controller.json', controller)
                # Runtime children are terminal and every original file has been
                # audited. Remove only this controller's generated fixture copies.
                for temporary in data:
                    assert temporary.parent == DATA and temporary.name.startswith('p95-data-' + campaign + '-')
                    shutil.rmtree(temporary)
                write(pair / 'data-cleanup.json', dict(terminalAndVerified=True, removed=[str(p) for p in data], originalReferenceUntouched=str(reference)))
                print('Verified pair', planned['id'], len(controller['trials']), '/', len(controller['schedule']), flush=True)
            controller.update(status='complete', currentTrial=None)
            write(out / 'controller.json', controller)
        except BaseException as error:
            controller.update(status='stopped', error=repr(error))
            write(out / 'controller.json', controller)
            raise
        verify_inputs(inputs)
        print('Complete diagnostic sampling batch; original acceptance gates remain failed', flush=True)

if __name__ == '__main__': main()
