#!/usr/bin/env python3
"""Original-main cold repeatability diagnostic; correctness only, no compilation.

A fresh output directory is mandatory. The original Java all-success gate is
retained (expected runtime exit 1); comparison differences are always archived.
"""
import argparse
import collections
import csv
import datetime
import gzip
import hashlib
import itertools
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import traceback

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
PRIOR = ROOT / 'docs/go-server-baseline/native64-fullcase-replay'
FIXTURE_MANIFEST = ROOT / 'docs/go-server-baseline/native64-profile-a7de0bec/fixture-files.json'
WORKLOAD = ROOT / 'graphite-server/internal/benchmarkcase/testdata/main64.json'
JDK = Path('/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home')
JAVA = JDK / 'bin/java'
MAIN = '4e328b0109e13c896b74004823fb049fcb19251a'
WORKLOAD_SHA = '378c200c5ab3053c53962f9d87c59924f732d0c012fcaff6009842a58e547023'
FIXTURE_SHA = '3084fd51040494ea79d72022fb46b004b15ac6475b4b4ff748adf8a443edf9cf'


def dump(path, value):
    path.write_text(json.dumps(value, indent=2, ensure_ascii=True) + '\n')


def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def inventory(path):
    paths = sorted(path.rglob('*')) if path.is_dir() else [path]
    return {str(p): digest(p) for p in paths if p.is_file()}


def unchanged(inputs):
    return all(Path(p).is_file() and digest(Path(p)) == h for p, h in inputs.items())


def fixture_audit(root, manifest):
    expected = {f"{f['graphId']}/{f['file']}": f for f in manifest}
    matched, changed, missing, added = 0, [], [], []
    for name, expected_file in expected.items():
        p = root / name
        if not p.is_file():
            missing.append(name)
        elif p.stat().st_size == expected_file['bytes'] and digest(p) == expected_file['sha256']:
            matched += 1
        else:
            changed.append({'path': name, 'bytes': p.stat().st_size, 'sha256': digest(p),
                            'expected': expected_file})
    for graph in sorted({x['graphId'] for x in manifest}):
        for p in sorted((root / graph).rglob('*')):
            if p.is_file() and str(p.relative_to(root)) not in expected:
                added.append({'path': str(p.relative_to(root)), 'bytes': p.stat().st_size, 'sha256': digest(p)})
    return {'root': str(root), 'originalFiles': len(expected), 'matched': matched,
            'changed': changed, 'missing': missing, 'added': added}


def read_records(path):
    records, issues = [], []
    if not path.exists():
        return records, [{'file': str(path), 'issue': 'missing capture'}]
    opener = gzip.open if path.suffix == '.gz' else open
    with opener(path, 'rb') as stream:
        for line_number, line in enumerate(stream, 1):
            try:
                records.append(json.loads(line))
            except Exception as error:
                issues.append({'file': str(path), 'line': line_number, 'issue': str(error)})
    return records, issues


def compare(out, reference_path, actual_path, workload):
    original, issues = read_records(reference_path)
    actual, actual_issues = read_records(actual_path)
    issues.extend(actual_issues)
    public_diffs, record_diffs, state_diffs = [], [], []
    state_counts = collections.Counter()
    state_observations = 0
    public_fields = ['columns', 'rows', 'canonical', 'error', 'errorClass', 'message']
    public_pairs = 0

    def compare_states(a, b, label):
        nonlocal state_observations
        if not isinstance(a, list) or not isinstance(b, list):
            if a != b:
                state_diffs.append({'observation': label, 'field': 'wholeState', 'original': a, 'repeat': b})
                state_counts['wholeState'] += 1
            return
        for graph_index, (left, right) in enumerate(itertools.zip_longest(a, b)):
            state_observations += 1
            if not isinstance(left, dict) or not isinstance(right, dict):
                state_diffs.append({'observation': label, 'graphIndex': graph_index,
                                    'field': 'missingGraph', 'original': left, 'repeat': right})
                state_counts['missingGraph'] += 1
                continue
            for key in sorted(set(left) | set(right)):
                if (key in left, left.get(key)) != (key in right, right.get(key)):
                    state_counts[key] += 1
                    state_diffs.append({'observation': label, 'graphIndex': graph_index,
                                        'graph': left.get('id'), 'field': key,
                                        'originalPresent': key in left, 'repeatPresent': key in right,
                                        'original': left.get(key), 'repeat': right.get(key)})

    for index, (left, right) in enumerate(itertools.zip_longest(original, actual)):
        if left is None or right is None:
            record_diffs.append({'recordIndex': index, 'original': left, 'repeat': right})
            continue
        kind = left.get('kind')
        if kind != right.get('kind'):
            record_diffs.append({'recordIndex': index, 'original': left, 'repeat': right})
            continue
        state_fields = {'header': ['loaded'], 'prepared': ['sources'], 'case': ['before', 'after']}.get(kind, [])
        if kind == 'case':
            public_pairs += 1
            changed = [k for k in public_fields if (k in left, left.get(k)) != (k in right, right.get(k))]
            if changed:
                public_diffs.append({'recordIndex': index, 'index': left.get('index'), 'id': left.get('id'),
                                     'fields': changed, 'original': {k: left[k] for k in public_fields if k in left},
                                     'repeat': {k: right[k] for k in public_fields if k in right}})
        for key in sorted(set(left) | set(right)):
            if key in state_fields:
                if (key in left) != (key in right):
                    record_diffs.append({'recordIndex': index, 'field': key, 'originalPresent': key in left, 'repeatPresent': key in right})
                compare_states(left.get(key), right.get(key), f"record/{index}/{key}")
            elif kind != 'case' or key not in public_fields:
                if (key in left, left.get(key)) != (key in right, right.get(key)):
                    record_diffs.append({'recordIndex': index, 'field': key, 'originalPresent': key in left,
                                         'repeatPresent': key in right, 'original': left.get(key), 'repeat': right.get(key)})
    cases = [r for r in actual if r.get('kind') == 'case']
    expected_ids = [(i, c['id'], 'replay') for i, c in enumerate(workload['cases'])]
    actual_ids = [(r.get('index'), r.get('id'), r.get('phase')) for r in cases]
    if actual_ids != expected_ids:
        issues.append({'issue': 'case order/coverage differs', 'expected': expected_ids, 'actual': actual_ids})
    case_definitions = out / 'main-cold/actual-cases.json'
    definitions_equal = case_definitions.exists() and json.loads(case_definitions.read_text()) == workload['cases']
    if not definitions_equal:
        issues.append({'issue': 'captured testcase definitions do not equal frozen original workload'})
    failures = [{k: r[k] for k in ('index', 'id', 'error', 'errorClass', 'message') if k in r}
                for r in cases if 'error' in r]
    original_failures = [{k: r[k] for k in ('index', 'id', 'error', 'errorClass', 'message') if k in r}
                         for r in original if r.get('kind') == 'case' and 'error' in r]
    manifest_checked = 0
    try:
        observations = list(csv.DictReader((out / 'main-cold/main-observations.tsv').open(), delimiter='\t'))
        correctness = [line.split('|') for line in (out / 'main-cold/main-correctness.tsv').read_text().splitlines()]
        if len(observations) != 1267 or len(correctness) != 1267:
            issues.append({'issue': 'original main manifest coverage', 'observations': len(observations), 'correctness': len(correctness)})
        for obs, case, line in zip(observations, cases, correctness):
            ok = obs['id'] == case['id'] == line[0]
            if 'canonical' in case:
                canonical = case['canonical'].encode('utf-8')
                ok = ok and obs['outcome'] == line[10] == 'success'
                ok = ok and obs['digest'] == line[13] == hashlib.sha256(canonical).hexdigest()
                ok = ok and int(obs['responseBytes']) == int(line[12]) == len(canonical)
                ok = ok and int(obs['rowCount']) == int(line[11]) == len(case['rows'])
                manifest_checked += int(ok)
            else:
                ok = ok and obs['outcome'] == line[10] == 'failed'
            if not ok:
                issues.append({'issue': 'original manifest mismatch', 'index': case.get('index'), 'id': case.get('id')})
    except Exception as error:
        issues.append({'issue': 'original manifest could not be verified', 'error': str(error)})
    with gzip.open(out / 'state-differences.json.gz', 'wt', encoding='utf-8') as stream:
        json.dump(state_diffs, stream, ensure_ascii=True)
    dump(out / 'public-differences.json', public_diffs)
    dump(out / 'record-differences.json', record_diffs)
    report = {'performanceMeasurement': False, 'originalRecords': len(original), 'repeatRecords': len(actual),
              'caseDefinitionsExactlyEqual': definitions_equal, 'caseCount': len(cases),
              'publicCasePairsCompared': public_pairs, 'publicDifferences': len(public_diffs),
              'recordDifferences': len(record_diffs), 'stateDifferenceCounts': dict(state_counts),
              'stateDifferences': len(state_diffs), 'graphStateObservations': state_observations,
              'originalErrors': original_failures, 'repeatErrors': failures,
              'errorSequenceExactlyEqual': failures == original_failures,
              'originalManifestSuccessfulDigestsVerified': manifest_checked,
              'issues': issues, 'allCaptureRecordsExactlyEqual': not issues and original == actual}
    dump(out / 'comparison.json', report)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--suffix', required=True)
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]*', args.suffix):
        parser.error('suffix must be a safe single path component')
    reference = Path(json.loads((PRIOR / 'main-cold-preflight.json').read_text())['reference']).resolve()
    out = args.output.resolve()
    if out == reference or reference in out.parents:
        parser.error('output must be outside the immutable graph reference')
    out.mkdir(parents=True, exist_ok=False)
    record = {'state': 'cold', 'runtime': 'original-main', 'mainRevision': MAIN,
              'performanceMeasurement': False, 'suffix': args.suffix, 'status': 'preparing',
              'expectedOriginalAllSuccessGateExitCode': 1, 'originalAllSuccessGatePassed': False}
    dump(out / 'process.json', record)
    try:
        assert digest(WORKLOAD) == WORKLOAD_SHA and digest(FIXTURE_MANIFEST) == FIXTURE_SHA
        workload = json.loads(WORKLOAD.read_text())
        manifest_data = json.loads(FIXTURE_MANIFEST.read_text())
        frozen_cp = json.loads((PRIOR / 'main-cold-complete-classpath-inputs.json').read_text())
        classpath = (PRIOR / 'capture-classpath-complete.txt').read_text().strip()
        current_cp = {}
        for component in classpath.split(os.pathsep):
            current_cp.update(inventory(Path(component)))
        assert current_cp == frozen_cp, 'Original classpath changed; refuse to replay a different runtime'
        assert 'JAVA_VERSION="17.0.18"' in (JDK / 'release').read_text()
        jdk_inputs = inventory(JDK)
        injected = {key: os.environ[key] for key in ('JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS') if os.environ.get(key)}
        assert not injected, 'Unexpected injected JVM options; preserve and inspect environment before proceeding'
        version = subprocess.run([str(JAVA), '-version'], capture_output=True, text=True)
        (out / 'java-version.stdout').write_text(version.stdout)
        (out / 'java-version.stderr').write_text(version.stderr)
        assert version.returncode == 0 and '17.0.18' in version.stderr
        reference_capture = PRIOR / 'main-cold-complete/responses.jsonl'
        if not reference_capture.exists():
            reference_capture = Path(str(reference_capture) + '.gz')
        input_paths = [Path(__file__).resolve(), PRIOR / 'capture-classpath-complete.txt',
                       PRIOR / 'main-cold-complete-classpath-inputs.json', PRIOR / 'MainReplayCapture.java',
                       PRIOR / 'main-cold-preflight.json', WORKLOAD, FIXTURE_MANIFEST, reference_capture,
                       PRIOR / 'main-cold-complete/actual-cases.json']
        inputs = {str(p): digest(p) for p in input_paths}
        dump(out / 'inputs.json', inputs)
        dump(out / 'original-classpath-inputs.json', current_cp)
        dump(out / 'jdk-inputs.json', jdk_inputs)
        # Copy the validated classpath so this invocation uses frozen private bytes.
        frozen_components = []
        classpath_mapping = []
        cp_root = out / 'classpath'
        cp_root.mkdir()
        for index, component in enumerate(classpath.split(os.pathsep)):
            source = Path(component)
            target = cp_root / (f'{index:03d}-' + source.name)
            if source.is_dir():
                shutil.copytree(source, target)
            else:
                shutil.copy2(source, target)
            frozen_components.append(str(target))
            source_items = sorted(source.rglob('*')) if source.is_dir() else [source]
            for item in source_items:
                if item.is_file():
                    copied = target / item.relative_to(source) if source.is_dir() else target
                    assert digest(copied) == current_cp[str(item)]
                    classpath_mapping.append({'original': str(item), 'frozen': str(copied), 'sha256': current_cp[str(item)]})
        dump(out / 'classpath-mapping.json', classpath_mapping)
        frozen_inputs = inventory(cp_root)
        dump(out / 'frozen-classpath-inputs.json', frozen_inputs)
        clone = out / ('fixture-' + args.suffix)
        subprocess.run(['/bin/cp', '-cRp', str(reference), str(clone)], check=True)
        assert clone.resolve() != reference and reference not in clone.resolve().parents
        preflight = fixture_audit(clone, manifest_data)
        dump(out / 'fixture-before.json', preflight)
        assert preflight['matched'] == 1152 and not preflight['changed'] and not preflight['missing'] and not preflight['added']
        # Preserve every original byte outside the second TSV field, including line endings.
        for entry in manifest_data:
            resolved = (clone / entry['graphId'] / entry['file']).resolve()
            assert clone.resolve() in resolved.parents and reference not in resolved.parents
        original_tsv = (clone / 'graphs.tsv').read_bytes()
        relocated_lines, source_order = [], []
        for line in original_tsv.splitlines(keepends=True):
            body = line.rstrip(b'\r\n')
            ending = line[len(body):]
            if not body.strip() or body.lstrip().startswith(b'#'):
                relocated_lines.append(line)
                continue
            fields = body.split(b'\t')
            assert len(fields) == 6
            graph_id = fields[0].decode('utf-8')
            assert graph_id and Path(graph_id).name == graph_id and graph_id not in ('.', '..')
            source_order.append(graph_id)
            fields[1] = str(clone / graph_id).encode('utf-8')
            relocated_lines.append(b'\t'.join(fields) + ending)
        assert source_order == workload['sourceOrder'] and len(source_order) == 64
        graph_tsv = clone / 'graphs-relocated.tsv'
        graph_tsv.write_bytes(b''.join(relocated_lines))
        assert (clone / 'graphs.tsv').read_bytes() == original_tsv
        dump(out / 'graphs-relocation.json', {'sourceOrder': source_order, 'originalSHA256': hashlib.sha256(original_tsv).hexdigest(),
                                             'relocatedSHA256': digest(graph_tsv), 'onlyFieldChanged': 1,
                                             'referenceNeverOpenedByRuntime': True, 'clone': str(clone)})
        command = [str(JAVA), '-Xmx8g', '-cp', os.pathsep.join(frozen_components), 'MainReplayCapture',
                   str(graph_tsv), 'cold', str(out / 'main-cold')]
        record.update(command=command, status='starting', classpathMatchesFrozenOriginal=True, javaHome=str(JDK))
        dump(out / 'process.json', record)
        with (out / 'process.log').open('x') as log:
            process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT)
            record.update(status='running', pid=process.pid, startedAt=datetime.datetime.now(datetime.timezone.utc).isoformat())
            dump(out / 'process.json', record)
            print('Original main cold correctness PID', process.pid, flush=True)
            code = process.wait()
        record.update(status='exited', exitCode=code, finishedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                      originalAllSuccessGatePassed=code == 0)
        dump(out / 'process.json', record)
        # Do not abort on different output/state: produce complete comparison artifacts first.
        comparison = compare(out, reference_capture, out / 'main-cold/responses.jsonl', workload)
        post = fixture_audit(clone, manifest_data)
        dump(out / 'fixture-after.json', post)
        record.update(explicitInputsUnchanged=unchanged(inputs), originalClasspathUnchanged=unchanged(current_cp),
                      frozenClasspathUnchanged=unchanged(frozen_inputs), jdkUnchanged=unchanged(jdk_inputs),
                      originalGraphTSVUnchanged=(clone / 'graphs.tsv').read_bytes() == original_tsv)
        dump(out / 'process.json', record)
        valid_inputs = all(record[k] for k in ('explicitInputsUnchanged', 'originalClasspathUnchanged',
                                               'frozenClasspathUnchanged', 'jdkUnchanged', 'originalGraphTSVUnchanged'))
        fixture_ok = post['matched'] == 1152 and not post['changed'] and not post['missing'] and not post['added']
        passed = code == 1 and valid_inputs and fixture_ok and comparison['allCaptureRecordsExactlyEqual']
        dump(out / 'controller.json', {'exitCode': 0 if passed else 1, 'runtimeExitCode': code,
                                      'expectedRuntimeExitCode': 1, 'strictRepeatabilityPassed': passed,
                                      'fixtureStrictlyUnchanged': fixture_ok, 'inputsUnchanged': valid_inputs,
                                      'performanceMeasurement': False})
        print(json.dumps({'runtimeExitCode': code, 'publicDifferences': comparison['publicDifferences'],
                          'stateDifferenceCounts': comparison['stateDifferenceCounts'], 'strictRepeatabilityPassed': passed}), flush=True)
        return 0 if passed else 1
    except Exception as error:
        dump(out / 'controller-error.json', {'error': str(error), 'stack': traceback.format_exc(),
                                             'performanceMeasurement': False, 'capturedArtifactsRetained': True})
        raise


if __name__ == '__main__':
    raise SystemExit(main())
