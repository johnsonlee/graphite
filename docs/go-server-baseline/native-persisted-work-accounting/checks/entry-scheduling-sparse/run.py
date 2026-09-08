#!/usr/bin/env python3
"""Launch only in an exclusive runtime window. Diagnostic, never a latency gate."""
import argparse
import difflib
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import time
import traceback

BASE = Path(__file__).resolve().parent
DOCS = BASE.parents[2]
GO = Path('/opt/homebrew/Cellar/go/1.22.0/libexec/bin/go')
GO_SHA = '8cdbc785275f449b9d2082a1a4929797693c1ec5141d663398c6ac9753d0ccb4'
WORKLOAD_SHA = '378c200c5ab3053c53962f9d87c59924f732d0c012fcaff6009842a58e547023'


def sha(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def write(path, value):
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + '\n')


def files(root):
    answer = []
    for path in sorted(root.rglob('*')):
        assert not path.is_symlink(), f'symlink is not an immutable copy input: {path}'
        if path.is_file():
            answer.append({'path': path.relative_to(root).as_posix(),
                           'bytes': path.stat().st_size, 'sha256': sha(path)})
    return answer


def bundle(root, manifest, target):
    with tarfile.open(target, 'w:gz') as archive:
        for item in manifest:
            archive.add(root / item['path'], arcname=item['path'], recursive=False)


def load_script(path):
    namespace = {'__file__': str(path), '__name__': 'diagnostic_import_without_bytecode'}
    exec(compile(path.read_text(), str(path), 'exec'), namespace)
    return namespace


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', required=True, type=Path,
                        help='Explicit frozen lazy-core-v1 or old v4 Go module, never the workspace')
    parser.add_argument('--output', required=True, type=Path, help='New external output directory')
    args = parser.parse_args()
    source, output = args.source.resolve(), args.output.resolve()
    assert source.is_dir() and (source / 'go.mod').is_file()
    assert not output.exists(), 'Never overwrite an earlier attempt'
    assert source not in output.parents and output not in source.parents
    output.mkdir(parents=True)
    receipt = {'diagnosticOnly': True, 'performanceMeasurement': False,
               'source': str(source), 'output': str(output), 'controllerPID': os.getpid(),
               'startedUnixSeconds': time.time(), 'steps': [], 'status': 'preparing'}
    write(output / 'receipt.json', receipt)
    audit, reference, clone, original, modified = None, None, None, None, None
    try:
        preparation = files(BASE)
        write(output / 'runner-inputs.json', preparation)
        bundle(BASE, preparation, output / 'runner-source.tar.gz')
        original = files(source)
        write(output / 'source-before.json', original)
        mapping = {item['path']: item['sha256'] for item in original}
        allowed = json.loads((BASE / 'supported-sources.json').read_text())
        matching = [name for name, touched in allowed.items()
                    if all(mapping.get(path) == digest for path, digest in touched.items())]
        assert len(matching) == 1, 'Touched source does not exactly match frozen lazy-core-v1 or old v4'
        receipt['supportedFrozenVersion'] = matching[0]
        assert sha(GO) == GO_SHA, 'Go toolchain bytes changed'
        receipt['go'] = {'path': str(GO), 'sha256': GO_SHA}
        module = output / 'module'
        shutil.copytree(source, module)
        assert files(module) == original, 'Frozen module copy differs'
        bundle(module, original, output / 'source-original.tar.gz')
        instrument = load_script(BASE / 'instrument.py')['instrument']
        changes, edits = instrument(module)
        write(output / 'instrumentation-edits.json', edits)
        patch = []
        for relative, changed in changes.items():
            path = module / relative
            before = path.read_text() if path.exists() else ''
            patch.extend(difflib.unified_diff(before.splitlines(True), changed.splitlines(True),
                         fromfile='original/' + relative, tofile='instrumented/' + relative))
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(changed)
        (output / 'instrumentation.patch').write_text(''.join(patch))
        modified = files(module)
        write(output / 'source-instrumented.json', modified)
        bundle(module, modified, output / 'source-instrumented.tar.gz')
        workload = module / 'internal/benchmarkcase/testdata/main64.json'
        assert sha(workload) == WORKLOAD_SHA
        workload_data = json.loads(workload.read_text())
        assert len(workload_data['cases']) == 1267
        write(output / 'prefix-case-identities.json', workload_data['cases'][:4])
        receipt['workloadSHA256'] = WORKLOAD_SHA
        receipt['validatedWorkloadCaseCount'] = 1267
        receipt['executedCaseIndices'] = [0, 1, 2, 3]
        audit_path = DOCS / 'native64-fullcase-replay/audit-fixtures.py'
        audit = load_script(audit_path)['audit']
        reference = Path(json.loads((DOCS / 'native64-fullcase-replay/main-cold-preflight.json').read_text())['reference'])
        receipt['fixtureAuditSHA256'] = sha(audit_path)
        receipt['fixtureManifestSHA256'] = sha(DOCS / 'native64-profile-a7de0bec/fixture-files.json')
        (output / 'fixture-audit.py').write_bytes(audit_path.read_bytes())
        (output / 'fixture-files.json').write_bytes((DOCS / 'native64-profile-a7de0bec/fixture-files.json').read_bytes())
        def checked_audit(root, name):
            result = audit(root); write(output / name, result)
            assert result['matched'] == 1152 and not result['changed'] and not result['missing'] and not result['added'], result
        checked_audit(reference, 'reference-before.json')
        clone = output / 'fixture'
        subprocess.run(['/bin/cp', '-cRp', str(reference), str(clone)], check=True)
        checked_audit(clone, 'fixture-before.json')
        manifest_before = sha(reference / 'graphs.tsv')
        receipt['originalGraphsManifestSHA256'] = manifest_before
        rows = []
        for line in (reference / 'graphs.tsv').read_text().splitlines():
            if line.strip() and not line.startswith('#'):
                fields = line.split('\t'); assert len(fields) == 6
                fields[1] = str(clone / fields[0]); line = '\t'.join(fields)
            rows.append(line)
        graphs = output / 'graphs-relocated.tsv'
        graphs.write_text('\n'.join(rows) + '\n')
        receipt['relocatedGraphsManifestSHA256'] = sha(graphs)
        env = dict(os.environ, GOTOOLCHAIN='local', GOWORK='off', GOFLAGS='',
                   GRAPHITE_ENTRY_TRACE=str(output / 'trace.jsonl'))
        receipt['inheritedRuntimeEnvironment'] = {key: env.get(key) for key in
             ['GOMAXPROCS', 'GOGC', 'GOMEMLIMIT', 'GODEBUG', 'GOOS', 'GOARCH', 'CGO_ENABLED']}
        def step(name, command):
            item = {'name': name, 'command': command, 'cwd': str(module), 'startedUnixSeconds': time.time()}
            receipt['steps'].append(item)
            with (output / (name + '.stdout')).open('wb') as stdout, (output / (name + '.stderr')).open('wb') as stderr:
                proc = subprocess.Popen(command, cwd=module, env=env, stdout=stdout, stderr=stderr)
                item['pid'] = proc.pid; receipt['status'] = name; write(output / 'receipt.json', receipt)
                print(name, 'PID', proc.pid, flush=True)
                try:
                    item['exitCode'] = proc.wait()
                except BaseException:
                    proc.terminate()
                    try:
                        item['exitCode'] = proc.wait(timeout=30)
                    except subprocess.TimeoutExpired:
                        proc.kill(); item['exitCode'] = proc.wait()
                    write(output / 'receipt.json', receipt)
                    raise
            item['finishedUnixSeconds'] = time.time(); write(output / 'receipt.json', receipt)
            return item['exitCode']
        binary = output / 'diagnostic-replay'
        assert step('build', [str(GO), 'build', '-o', str(binary), './cmd/graphite-benchmark-replay']) == 0
        receipt['binarySHA256'] = sha(binary)
        code = step('replay', [str(binary), '--workload', str(workload), '--graphs', str(graphs),
                              '--state', 'cold', '--output', str(output / 'responses.jsonl')])
        records = [json.loads(line) for line in (output / 'responses.jsonl').read_text().splitlines()]
        cases = [r for r in records if r.get('kind') == 'case']
        receipt['actualCaseIndices'] = [r['index'] for r in cases]
        receipt['publicErrors'] = [{key: r.get(key) for key in ['index', 'id', 'error', 'message']}
                                   for r in cases if 'error' in r]
        trace = [json.loads(line) for line in (output / 'trace.jsonl').read_text().splitlines()]
        summary = trace[-1]; assert summary['kind'] == 'trace_summary'
        receipt['traceSummary'] = summary
        counts = {}
        for event in trace[:-1]:
            key = str(event['queryIndex']) + ':' + event['kind']
            counts[key] = counts.get(key, 0) + 1
        write(output / 'event-counts.json', counts)
        assert code == 0 and receipt['actualCaseIndices'] == [0, 1, 2, 3]
        assert not receipt['publicErrors'] and not summary['dropped'] and not summary['unpublished']
        assert sha(reference / 'graphs.tsv') == manifest_before
        receipt['status'] = 'complete'
    except BaseException as exc:
        receipt['status'] = 'failed'
        receipt['failure'] = {'class': type(exc).__name__, 'message': str(exc), 'traceback': traceback.format_exc()}
    finally:
        try:
            if original is not None:
                after = files(source); write(output / 'source-after.json', after)
                assert after == original, 'Explicit frozen source changed during capture'
            if modified is not None:
                after = files(output / 'module'); write(output / 'instrumented-after.json', after)
                assert after == modified, 'Instrumented module mutated during capture'
            if audit is not None and reference is not None:
                for name, root in [('reference-after', reference), ('fixture-after', clone)]:
                    if root is not None and root.exists():
                        result = audit(root); write(output / (name + '.json'), result)
                        assert result['matched'] == 1152 and not result['changed'] and not result['missing'] and not result['added'], result
        except BaseException as exc:
            receipt['status'] = 'failed'; receipt['postflightFailure'] = traceback.format_exc()
        receipt['finishedUnixSeconds'] = time.time()
        write(output / 'receipt.json', receipt)
        artifacts = []
        for path in sorted(output.iterdir()):
            if path.is_file() and path.name != 'artifact-manifest.json':
                artifacts.append({'path': path.name, 'bytes': path.stat().st_size, 'sha256': sha(path)})
        write(output / 'artifact-manifest.json', artifacts)
    print(json.dumps({'status': receipt['status'], 'output': str(output)}), flush=True)
    return 0 if receipt['status'] == 'complete' else 1


if __name__ == '__main__':
    sys.exit(main())
