"""Build the thin launcher or run one original-main real64 diagnostic trial."""
import argparse
import datetime
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess

BASE = Path(__file__).resolve().parent
ROOT = BASE.parents[3]
PREVIOUS = BASE.parents[1] / 'native64-fullcase-replay'
MAIN = Path('/tmp/graphite-go-main-baseline-clone-4e328b0')
spec = importlib.util.spec_from_file_location('fixtures', PREVIOUS / 'audit-fixtures.py')
fixtures = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixtures)


def write(path, value):
    path.write_text(json.dumps(value, indent=2) + '\n')


def classpath_inputs(classpath):
    inputs = {}
    for component in classpath.split(os.pathsep):
        path = Path(component)
        assert path.exists(), path
        for item in sorted(path.rglob('*')) if path.is_dir() else [path]:
            if item.is_file():
                inputs[str(item)] = fixtures.sha(item)
    return inputs


def prepare():
    original_cp = (PREVIOUS / 'capture-classpath-complete.txt').read_text().strip()
    original_inputs = classpath_inputs(original_cp)
    frozen = json.loads((PREVIOUS / 'main-cold-complete-classpath-inputs.json').read_text())
    assert original_inputs == frozen, 'Main classpath differs from verified full replay'
    classes = BASE / 'classes'
    classes.mkdir(exist_ok=True)
    command = ['javac', '-cp', original_cp, '-d', str(classes), str(BASE / 'MainLatencyPilot.java')]
    result = subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    (BASE / 'compile.log').write_text(result.stdout)
    result.check_returncode()
    cp = str(classes) + os.pathsep + original_cp
    bytecode = subprocess.check_output(['javap', '-c', '-p', '-classpath', cp, 'MainLatencyPilot'], text=True)
    (BASE / 'launcher-bytecode.txt').write_text(bytecode)
    forbidden = ['java/lang/reflect/Field.set:', 'MainReplayCapture', 'AbstractExecutorService',
                 'ExecutorService.submit:', 'java/util/concurrent/Future.get:']
    assert not any(token in bytecode for token in forbidden), 'Unexpected executor mutation/observer in launcher'
    calls = ['setupTrial:()V', 'setupInvocation:()V', 'replayBroadQueries:', 'tearDownTrial:()V']
    assert all(token in bytecode for token in calls)
    assert 'java/lang/reflect/Field.get:' in bytecode
    write(BASE / 'build-verification.json', {
        'compileCommand': command, 'compileExitCode': result.returncode,
        'originalClasspathMatchesVerifiedCapture': True,
        'originalClasspathInputCount': len(original_inputs),
        'launcherSourceSHA256': fixtures.sha(BASE / 'MainLatencyPilot.java'),
        'launcherClassSHA256': fixtures.sha(classes / 'MainLatencyPilot.class'),
        'bytecodeSHA256': fixtures.sha(BASE / 'launcher-bytecode.txt'),
        'forbiddenBytecodeTokensAbsent': forbidden,
        'originalLifecycleCallsPresent': calls,
        'runtimeLaunched': False,
    })
    (BASE / 'classpath.txt').write_text(cp + '\n')
    return cp


def clean(audit):
    return audit['matched'] == 1152 and not any(audit[k] for k in ['added', 'changed', 'missing'])


def run(state, out):
    assert not out.exists(), 'Output must be a new directory'
    cp = (BASE / 'classpath.txt').read_text().strip()
    build = json.loads((BASE / 'build-verification.json').read_text())
    assert fixtures.sha(BASE / 'MainLatencyPilot.java') == build['launcherSourceSHA256']
    assert fixtures.sha(BASE / 'classes/MainLatencyPilot.class') == build['launcherClassSHA256']
    original_cp = (PREVIOUS / 'capture-classpath-complete.txt').read_text().strip()
    assert cp == str(BASE / 'classes') + os.pathsep + original_cp
    assert classpath_inputs(original_cp) == json.loads((PREVIOUS / 'main-cold-complete-classpath-inputs.json').read_text())
    out.mkdir(parents=True)
    reference = Path(json.loads((PREVIOUS / 'main-cold-preflight.json').read_text())['reference'])
    # A unique independently cloned trial; graph data stay outside evidence/git.
    trial = reference.parents[1] / 'benchmarks/graphite' / ('latency-pilot-' + out.name)
    trial.mkdir()
    ref_audit = fixtures.audit(reference)
    write(out / 'reference-preflight.json', ref_audit)
    assert clean(ref_audit), 'Immutable reference changed'
    clone = trial / 'fixture'
    subprocess.run(['/bin/cp', '-cRp', str(reference), str(clone)], check=True)
    pre = fixtures.audit(clone)
    write(out / 'clone-preflight.json', pre)
    assert clean(pre), 'Clone mismatch'
    lines, graph_ids = [], []
    for line in (clone / 'graphs.tsv').read_text().splitlines():
        if not line.strip() or line.lstrip().startswith('#'):
            lines.append(line)
            continue
        fields = line.split('\t')
        assert len(fields) == 6
        graph_ids.append(fields[0])
        fields[1] = str(clone / fields[0])
        lines.append('\t'.join(fields))
    assert len(graph_ids) == len(set(graph_ids)) == 64
    manifest = clone / 'graphs-relocated.tsv'
    manifest.write_text('\n'.join(lines) + '\n')
    inputs = classpath_inputs(cp)
    source_paths = [*MAIN.glob('*/src/**/*.kt'), *BASE.glob('*.py'), BASE / 'MainLatencyPilot.java',
                    BASE / 'classpath.txt', BASE / 'build-verification.json',
                    ROOT / 'graphite-server/internal/benchmarkcase/testdata/main64.json',
                    PREVIOUS / 'capture-classpath-complete.txt',
                    PREVIOUS / 'main-cold-complete-classpath-inputs.json',
                    PREVIOUS / 'audit-fixtures.py', fixtures.MANIFEST,
                    reference / 'graphs.tsv', clone / 'graphs.tsv', manifest]
    java_settings = subprocess.run([shutil.which('java'), '-XshowSettings:properties', '-version'],
                                   text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=True).stdout
    java_home_lines = [line.split('=', 1)[1].strip() for line in java_settings.splitlines()
                       if line.strip().startswith('java.home =')]
    assert len(java_home_lines) == 1, 'Cannot resolve actual Java runtime'
    java_home = Path(java_home_lines[0])
    java = (java_home / 'bin/java').resolve()
    for path in [java, java_home / 'lib/modules', java_home / 'release', java_home / 'lib/server/libjvm.dylib']:
        assert path.is_file(), path
        inputs[str(path)] = fixtures.sha(path)
    for path in source_paths:
        assert path.is_file(), path
        inputs[str(path)] = fixtures.sha(path)
    write(out / 'inputs.json', inputs)
    command = [str(java), '-Xmx8g', '-cp', cp, 'MainLatencyPilot', str(manifest), state, str(out / 'capture')]
    record = dict(runtime='main', state=state, command=command, status='starting',
                  mainRevision='4e328b0109e13c896b74004823fb049fcb19251a',
                  originalClasspathMatchesVerifiedCapture=True, performanceMeasurement=True,
                  diagnosticOnly=True, samplesPerCase=1, queryExecutorReplaced=False,
                  javaVersion=subprocess.run([str(java), '-version'], text=True, stdout=subprocess.PIPE,
                                             stderr=subprocess.STDOUT, check=True).stdout,
                  sourceOrder=graph_ids, clone=str(clone))
    write(out / 'process.json', record)
    with (out / 'process.log').open('x') as log:
        process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT)
        record.update(status='running', pid=process.pid, startedAt=datetime.datetime.now(datetime.timezone.utc).isoformat())
        write(out / 'process.json', record)
        print('main', state, 'PID', process.pid, 'receipt', out / 'process.json', flush=True)
        code = process.wait()
    record.update(status='exited', exitCode=code, finishedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                  inputsUnchanged=all(Path(p).is_file() and fixtures.sha(Path(p)) == sha for p, sha in inputs.items()))
    write(out / 'process.json', record)
    post = fixtures.audit(clone)
    write(out / 'postrun-fixtures.json', post)
    record['fixtureAuditPassed'] = clean(post)
    write(out / 'process.json', record)
    assert record['inputsUnchanged'], 'Runtime/source input changed during trial'
    assert clean(post), 'Fixture changed during trial'
    print('main', state, 'terminal exit', code, 'fixture/input hashes verified', flush=True)
    return code


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--prepare-only', action='store_true')
    parser.add_argument('--state', choices=['cold', 'startup-prepared'])
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    if args.prepare_only:
        assert args.state is None and args.output is None
        prepare()
        print('Compiled and audited; no graph runtime launched', flush=True)
    else:
        assert args.state is not None and args.output is not None
        raise SystemExit(run(args.state, args.output.resolve()))
