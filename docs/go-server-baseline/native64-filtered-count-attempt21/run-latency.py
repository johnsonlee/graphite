"""Run one complete native real64 latency pilot; preserve failed correctness gate."""
import argparse
import datetime
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess

BASE = Path(__file__).resolve().parent
ROOT = BASE.parents[2]
MODULE = ROOT / 'graphite-server'
PREVIOUS = BASE.parent / 'native64-fullcase-replay'
spec = importlib.util.spec_from_file_location('fixtures', PREVIOUS / 'audit-fixtures.py')
fixtures = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixtures)


def write(path, value):
    path.write_text(json.dumps(value, indent=2) + '\n')


def clean(audit):
    return audit['matched'] == 1152 and not any(audit[k] for k in ['added', 'changed', 'missing'])


def run(state, out):
    assert not out.exists(), 'Never overwrite trial evidence'
    out.mkdir(parents=True)
    reference = Path(json.loads((PREVIOUS / 'main-cold-preflight.json').read_text())['reference'])
    trial = reference.parents[1] / 'benchmarks/graphite' / ('filtered-count-attempt21-' + out.name)
    trial.mkdir()
    ref = fixtures.audit(reference)
    write(out / 'reference-preflight.json', ref)
    assert clean(ref), 'Immutable reference changed'
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
    go = Path(shutil.which('go')).resolve()
    go_env = json.loads(subprocess.check_output([str(go), 'env', '-json'], cwd=MODULE, text=True))
    go_root = Path(go_env['GOROOT'])
    paths = [*MODULE.rglob('*.go'), MODULE / 'go.mod', MODULE / 'go.sum',
             MODULE / 'internal/benchmarkcase/testdata/main64.json',
             Path(__file__).resolve(), PREVIOUS / 'audit-fixtures.py', fixtures.MANIFEST,
             reference / 'graphs.tsv', clone / 'graphs.tsv', manifest, go, go_root / 'VERSION']
    paths.extend(p for p in (go_root / 'pkg/tool').rglob('*') if p.is_file())
    # Freeze the actual transitive build closure, including embedded regex data.
    deps = subprocess.check_output([str(go), 'list', '-deps', '-json', './cmd/graphite-benchmark-latency'], cwd=MODULE, text=True)
    (out / 'build-dependencies.jsonstream').write_text(deps)
    decoder, position, packages = json.JSONDecoder(), 0, []
    while position < len(deps):
        if deps[position].isspace():
            position += 1
            continue
        package, position = decoder.raw_decode(deps, position)
        packages.append(package)
        assert not package.get('CgoFiles') and not package.get('SwigFiles') and not package.get('SwigCXXFiles'), 'Expand host compiler audit before using cgo'
        if package['ImportPath'] == 'unsafe':
            continue
        for field in ['GoFiles', 'SFiles', 'HFiles', 'CFiles', 'SysoFiles', 'EmbedFiles']:
            paths.extend(Path(package['Dir']) / name for name in package.get(field, []))
        if package.get('Module', {}).get('GoMod'):
            paths.append(Path(package['Module']['GoMod']))
    paths.extend(p for p in (go_root / 'pkg/include').rglob('*') if p.is_file())
    inputs = {str(p): fixtures.sha(p) for p in sorted(set(paths)) if p.is_file()}
    binary = trial / 'graphite-benchmark-latency'
    build = [str(go), 'build', '-o', str(binary), './cmd/graphite-benchmark-latency']
    with (out / 'build.log').open('x') as log:
        subprocess.run(build, cwd=MODULE, stdout=log, stderr=subprocess.STDOUT, check=True)
    assert all(fixtures.sha(Path(p)) == digest for p, digest in inputs.items()), 'Inputs changed during build'
    inputs[str(binary)] = fixtures.sha(binary)
    write(out / 'inputs.json', inputs)
    capture = out / 'capture'
    capture.mkdir()
    command = [str(binary), '--graphs', str(manifest), '--state', state,
               '--workload', str(MODULE / 'internal/benchmarkcase/testdata/main64.json'),
               '--output', str(capture / 'observations.jsonl')]
    record = dict(runtime='native', state=state, command=command, buildCommand=build,
                  binarySHA256=inputs[str(binary)], status='starting',
                  candidateRevision=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
                  candidateInputsIncludeUncommittedTimingDriver=True,
                  mainRevision='4e328b0109e13c896b74004823fb049fcb19251a',
                  performanceMeasurement=True, diagnosticOnly=True, samplesPerCase=1,
                  goEnvironment=go_env,
                  runtimeEnvironment={k: os.environ.get(k) for k in ['GOGC', 'GOMEMLIMIT', 'GOMAXPROCS', 'GODEBUG']},
                  sourceOrder=graph_ids, clone=str(clone))
    write(out / 'process.json', record)
    with (out / 'process.log').open('x') as log:
        process = subprocess.Popen(command, cwd=MODULE, stdout=log, stderr=subprocess.STDOUT)
        record.update(status='running', pid=process.pid,
                      startedAt=datetime.datetime.now(datetime.timezone.utc).isoformat())
        write(out / 'process.json', record)
        print('native', state, 'PID', process.pid, 'receipt', out / 'process.json', flush=True)
        code = process.wait()
    record.update(status='exited', exitCode=code,
                  finishedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                  inputsUnchanged=all(Path(p).is_file() and fixtures.sha(Path(p)) == sha for p, sha in inputs.items()),
                  binaryUnchanged=fixtures.sha(binary) == record['binarySHA256'])
    write(out / 'process.json', record)
    post = fixtures.audit(clone)
    write(out / 'postrun-fixtures.json', post)
    record['fixtureAuditPassed'] = clean(post)
    write(out / 'process.json', record)
    assert record['inputsUnchanged'] and record['binaryUnchanged'], 'Runtime/source input changed'
    assert clean(post), 'Fixture changed'
    print('native', state, 'terminal exit', code, 'fixture/input hashes verified', flush=True)
    return code


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--state', required=True, choices=['cold', 'startup-prepared'])
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    raise SystemExit(run(args.state, args.output.resolve()))
