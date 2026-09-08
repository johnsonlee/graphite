"""Freeze a private iterator diagnostic. Only fresh reflink clones enter Store.Open."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[4]
REFERENCE = Path('/Users/johnsonlee/.codex/fixtures/graphite-real64-1152-20260908')
GO = Path('/opt/homebrew/Cellar/go/1.22.0/libexec/bin/go')
IDS = ['fixture-android-%02d' % i for i in range(2, 8)]


def sha(p):
    h = hashlib.sha256()
    with p.open('rb') as f:
        for b in iter(lambda: f.read(1024 * 1024), b''):
            h.update(b)
    return h.hexdigest()


def dump(p, value):
    p.write_text(json.dumps(value, indent=2) + '\n')


def files(folder):
    result = []
    for p in sorted(folder.rglob('*')):
        assert not p.is_symlink(), ('symlink', str(p))
        if p.is_file():
            result.append(dict(file=str(p.relative_to(folder)), bytes=p.stat().st_size, sha256=sha(p)))
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--module-source', type=Path, required=True)
    parser.add_argument('--v2-responses', type=Path, required=True)
    a = parser.parse_args()
    out = a.output.resolve()
    out.mkdir(parents=True, exist_ok=False)
    reference = REFERENCE.resolve(strict=True)
    manifest = reference / 'graphs.tsv'
    original_manifest = sha(manifest)
    rows = {}
    for line in manifest.read_text().splitlines():
        if not line.strip() or line.lstrip().startswith('#'):
            continue
        fields = line.split('\t')
        assert len(fields) == 6
        rows[fields[0]] = fields
    assert len(rows) == 64 and all(i in rows for i in IDS)
    frozen_manifest = ROOT / 'docs/go-server-baseline/native64-profile-a7de0bec/fixture-files.json'
    known = json.loads(frozen_manifest.read_text())
    workload = ROOT / 'graphite-server/internal/benchmarkcase/testdata/main64.json'
    case = json.loads(workload.read_text())['cases'][3]
    assert case['id'] == 'single-contains-unlabeled-dense' and case['limit'] == 200
    v2_case = None
    with a.v2_responses.open() as f:
        for line in f:
            record = json.loads(line)
            if record.get('kind') == 'case' and record.get('index') == 3:
                v2_case = record
                break
    assert v2_case is not None and v2_case['id'] == case['id']
    dump(out / 'v2-case3.json', v2_case)
    before = {r['id']: r for r in v2_case['before']}
    graphs, original_files, clone_files = [], {}, {}
    for graph_id in IDS:
        # Never trust the old absolute path in graphs.tsv as a load target.
        source = (reference / rows[graph_id][0]).resolve(strict=True)
        assert source.parent == reference
        original_files[graph_id] = files(source)
        expected = [dict(file=r['file'], bytes=r['bytes'], sha256=r['sha256'])
                    for r in known if r['graphId'] == graph_id]
        assert original_files[graph_id] == sorted(expected, key=lambda x: x['file'])
        clone = out / 'fixtures' / graph_id
        clone.parent.mkdir(exist_ok=True)
        subprocess.run(['/bin/cp', '-cRp', str(source), str(clone)], check=True)
        clone_files[graph_id] = files(clone)
        assert clone_files[graph_id] == original_files[graph_id]
        graphs.append(dict(ID=graph_id, Path=str(clone), V2Before=before[graph_id]))
    dump(out / 'reference-selected-before.json', original_files)
    dump(out / 'clones-before.json', clone_files)
    source = a.module_source.resolve(strict=True)
    source_files = files(source)
    module = out / 'module'
    shutil.copytree(source, module)
    assert files(module) == source_files
    dump(out / 'original-module-files.json', source_files)
    template = HERE / 'diagnostic_test.go.txt'
    overlay = module / 'internal/query/cursor_real64_diagnostic_test.go'
    assert not overlay.exists()
    shutil.copyfile(template, overlay)
    executable_files = files(module)
    dump(out / 'executable-module-files.json', executable_files)
    dump(out / 'input.json', dict(Query=case['query'], Graphs=graphs))
    shutil.copyfile(__file__, out / 'runner.py')
    cmd = [str(GO), 'test', '-count=1', '-v', '-timeout=30m', '-run', '^TestCursorReal64Diagnostic$', './internal/query']
    env = dict(os.environ, PATH=str(GO.parent) + os.pathsep + os.environ['PATH'], GOTOOLCHAIN='local',
               CURSOR_DIAGNOSTIC_INPUT=str(out / 'input.json'), CURSOR_DIAGNOSTIC_OUTPUT=str(out / 'result.json'))
    preflight = dict(command=cmd, moduleSource=str(source), cwd=str(module), originalGraphsManifestSHA256=original_manifest,
                     diagnosticTemplateSHA256=sha(template), runnerSHA256=sha(Path(__file__)), goSHA256=sha(GO),
                     fixtureManifestSHA256=sha(frozen_manifest), workloadSHA256=sha(workload),
                     v2CaseRecordSHA256=sha(out / 'v2-case3.json'), performanceMeasurement=False,
                     publicQuery=False, referenceHashScope='All files in the six selected graphs; graphs.tsv also checked')
    dump(out / 'preflight.json', preflight)
    with (out / 'test.log').open('x') as log:
        process = subprocess.Popen(cmd, cwd=module, env=env, stdout=log, stderr=subprocess.STDOUT)
        dump(out / 'process.json', dict(pid=process.pid, command=cmd))
        code = process.wait()
    post = {i: files(out / 'fixtures' / i) for i in IDS}
    ref_post = {i: files(reference / i) for i in IDS}
    dump(out / 'clones-after.json', post)
    dump(out / 'reference-selected-after.json', ref_post)
    unchanged = post == clone_files and ref_post == original_files and sha(manifest) == original_manifest
    comparison = []
    if (out / 'result.json').exists():
        for record in json.loads((out / 'result.json').read_text())['records']:
            prepared = record.get('prepared', {})
            original = before[record['id']]
            differences = {k: dict(v2=v, diagnostic=prepared.get(k)) for k, v in original.items()
                           if k != 'id' and prepared.get(k) != v}
            comparison.append(dict(id=record['id'], preparedVersusV2BeforeDifferences=differences))
    dump(out / 'prepared-state-comparison.json', comparison)
    receipt = dict(preflight, exitCode=code, fixtureFilesUnchanged=unchanged,
                   moduleUnchanged=files(module) == executable_files, moduleSourceUnchanged=files(source) == source_files,
                   observationsHaveNoExpectedRangeCount=True)
    dump(out / 'receipt.json', receipt)
    print(json.dumps(receipt, indent=2), flush=True)
    assert unchanged and receipt['moduleUnchanged'] and receipt['moduleSourceUnchanged']
    raise SystemExit(code)


if __name__ == '__main__':
    main()
