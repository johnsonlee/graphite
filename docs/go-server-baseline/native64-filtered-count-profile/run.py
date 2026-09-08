"""Attribute case 838 CPU in the full original ordered real64 workload.

Profiles an isolated instrumented copy of the pinned native baseline. This is
diagnostic profiling, never an acceptance latency sample.
"""
import datetime
import difflib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tarfile

BASE = Path(__file__).resolve().parent
ROOT = BASE.parents[2]
PREVIOUS = BASE.parent / 'native64-fullcase-replay'
REVISION = '9237131f'
CASE_ID = 'filtered-count-zero'
spec = importlib.util.spec_from_file_location('fixtures', PREVIOUS / 'audit-fixtures.py')
fixtures = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixtures)


def write(path, value):
    path.write_text(json.dumps(value, indent=2) + '\n')


def clean(a):
    return a['matched'] == 1152 and not any(a[k] for k in ['missing', 'changed', 'added'])


def run():
    out = BASE / 'run1'
    assert not out.exists(), 'Never restart or overwrite a profile run'
    out.mkdir()
    reference = Path(json.loads((PREVIOUS / 'main-cold-preflight.json').read_text())['reference'])
    trial = reference.parents[1] / 'benchmarks/graphite/native64-filtered-count-profile-9237131f-run1'
    trial.mkdir()
    source = trial / 'source'
    source.mkdir()
    archive = trial / 'baseline-source.tar'
    with archive.open('xb') as f:
        subprocess.run(['git', 'archive', '--format=tar', REVISION, 'graphite-server'], cwd=ROOT, stdout=f, check=True)
    with tarfile.open(archive) as tar:
        tar.extractall(source, filter='data')
    module = source / 'graphite-server'
    main = module / 'cmd/graphite-benchmark-latency/main.go'
    original = main.read_text()
    patched = original.replace('"runtime"', '"runtime"\n "runtime/pprof"', 1)
    anchor = '\t\tresponse, elapsed, timedOut, err := r.measure(ctx, input)'
    assert patched.count(anchor) == 1
    instrumentation = '''
        var cpu *os.File
        var before runtime.MemStats
        if c.ID == "filtered-count-zero" {
            var err error
            cpu, err = os.OpenFile(os.Getenv("GRAPHITE_CASE_CPU_PROFILE"), os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
            if err != nil { return records, err }
            runtime.ReadMemStats(&before)
            if err := pprof.StartCPUProfile(cpu); err != nil { cpu.Close(); return records, err }
        }
        response, elapsed, timedOut, err := r.measure(ctx, input)
        if cpu != nil {
            pprof.StopCPUProfile()
            if closeErr := cpu.Close(); closeErr != nil { return records, closeErr }
            var after runtime.MemStats
            runtime.ReadMemStats(&after)
            memory, openErr := os.OpenFile(os.Getenv("GRAPHITE_CASE_MEMORY"), os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
            if openErr != nil { return records, openErr }
            encodeErr := json.NewEncoder(memory).Encode(map[string]any{"case": c.ID, "index": i, "diagnosticOnly": true, "cpuProfiled": true, "before": before, "after": after})
            closeErr := memory.Close()
            if encodeErr != nil { return records, encodeErr }
            if closeErr != nil { return records, closeErr }
        }
'''
    patched = patched.replace(anchor, instrumentation)
    patched = patched.replace('"performanceMeasurement": true,', '"performanceMeasurement": false, "cpuProfiledCase": "filtered-count-zero",', 1)
    main.write_text(patched)
    subprocess.run(['gofmt', '-w', str(main)], check=True)
    (out / 'source.patch').write_text(''.join(difflib.unified_diff(original.splitlines(True), main.read_text().splitlines(True), fromfile='baseline/main.go', tofile='profile/main.go')))
    (out / 'instrumented-main.go.txt').write_bytes(main.read_bytes())
    inputs = {str(p): fixtures.sha(p) for p in module.rglob('*') if p.is_file()}
    inputs[str(Path(__file__).resolve())] = fixtures.sha(Path(__file__).resolve())
    binary = trial / 'graphite-benchmark-profile'
    build = ['go', 'build', '-o', str(binary), './cmd/graphite-benchmark-latency']
    with (out / 'build.log').open('x') as log:
        subprocess.run(build, cwd=module, stdout=log, stderr=subprocess.STDOUT, check=True)
    inputs[str(binary)] = fixtures.sha(binary)
    write(out / 'inputs.json', inputs)
    ref = fixtures.audit(reference)
    write(out / 'reference-preflight.json', ref)
    assert clean(ref)
    clone = trial / 'fixture'
    subprocess.run(['/bin/cp', '-cRp', str(reference), str(clone)], check=True)
    pre = fixtures.audit(clone)
    write(out / 'clone-preflight.json', pre)
    assert clean(pre)
    lines = []
    for line in (clone / 'graphs.tsv').read_text().splitlines():
        if not line.strip() or line.lstrip().startswith('#'):
            lines.append(line)
            continue
        fields = line.split('\t')
        assert len(fields) == 6
        fields[1] = str(clone / fields[0])
        lines.append('\t'.join(fields))
    manifest = clone / 'graphs-relocated.tsv'
    manifest.write_text('\n'.join(lines) + '\n')
    inputs[str(manifest)] = fixtures.sha(manifest)
    write(out / 'inputs.json', inputs)
    command = [str(binary), '--graphs', str(manifest), '--state', 'cold',
               '--workload', str(module / 'internal/benchmarkcase/testdata/main64.json'),
               '--output', str(out / 'observations.jsonl')]
    env = dict(os.environ, GRAPHITE_CASE_CPU_PROFILE=str(out / 'cpu.pprof'), GRAPHITE_CASE_MEMORY=str(out / 'memory.json'))
    record = dict(status='starting', command=command, buildCommand=build, baseRevision=REVISION,
                  sourceArchiveSHA256=fixtures.sha(archive), binarySHA256=fixtures.sha(binary),
                  sourceCopy=str(module), clone=str(clone), caseID=CASE_ID, caseIndex=838,
                  workloadCaseCount=1267, performanceMeasurement=False, cpuProfiling=True,
                  goVersion=subprocess.check_output(['go', 'version'], text=True).strip())
    write(out / 'process.json', record)
    with (out / 'process.log').open('x') as log:
        process = subprocess.Popen(command, cwd=module, env=env, stdout=log, stderr=subprocess.STDOUT)
        record.update(status='running', pid=process.pid, startedAt=datetime.datetime.now(datetime.timezone.utc).isoformat())
        write(out / 'process.json', record)
        print('profile PID', process.pid, flush=True)
        code = process.wait()
    record.update(status='exited', exitCode=code, finishedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                  inputsUnchanged=all(fixtures.sha(Path(p)) == digest for p, digest in inputs.items()))
    write(out / 'process.json', record)
    post = fixtures.audit(clone)
    write(out / 'postrun-fixtures.json', post)
    assert clean(post) and record['inputsUnchanged']
    record['fixtureAuditPassed'] = True
    write(out / 'process.json', record)
    assert code == 1, 'Expected original single-error gate'
    commands = []
    for name, options in [('cpu-flat', ['-top']), ('cpu-cum', ['-top', '-cum']),
                          ('certificate', ['-list=certifyCallSiteCandidates']),
                          ('trigrams', ['-list=certifyCallSiteTrigrams'])]:
        cmd = ['go', 'tool', 'pprof', *options, str(binary), str(out / 'cpu.pprof')]
        result = subprocess.run(cmd, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        (out / (name + '.txt')).write_text(result.stdout)
        commands.append(dict(command=cmd, exitCode=result.returncode))
        result.check_returncode()
    write(out / 'pprof-commands.json', commands)
    print('complete ordered profile replay terminal, original gate exit1', flush=True)


if __name__ == '__main__':
    run()
