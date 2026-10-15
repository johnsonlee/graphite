"""Identity of a cached baseline or parent producer arm: everything its products depend on.

The accepted baseline (4f2ccf3) and the PR's parent (main) do not change between runs of one PR,
yet every run rebuilt both: the revision's gradle and cargo builds, its 64 graphs, the
query-correctness run, 15–20 minutes per arm. The user asked (2026-10-14) for the products to be
cached under `${name}-v${n}-${ref}`, the baseline by its short commit sha and main by its branch
name. A branch moves and the candidate's scripts change, so a restored product is trusted only when
this identity matches what the run would produce: the revision and role the arm's packet names;
the code that can touch the products, which is the producer-arm job's own definition in the
workflow and the import closure of the scripts that job runs (not the whole reviewed controls
manifest: that names every gate's comparator and test, and a gate-rule or test change invalidated
products it could not have changed, so the cache never hit across the day's pushes); the common
producer inputs of the run (`source-inputs.json`: source JARs and provenance;
`portable-expectations.json`: what the correctness run compares against; `producer-common.json`:
the toolchain paths); the versions those toolchain paths answer with; and the workspace, because
the products record absolute paths. A mismatch is a miss: the workflow deletes the stale entry,
rebuilds the arm and saves it again. The cache saves machine time, not wall time: the candidate's
own arm builds alongside and is never cached.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys

import ci_digest_cache as digest_cache

SCHEMA = 'graphite.fixture64-producer-arm-cache-identity.v2'
COMMON_INPUTS = ('source-inputs.json', 'portable-expectations.json', 'producer-common.json')
WORKFLOW = '.github/workflows/benchmark.yml'
JOB = 'rust-producer-arm'
SCRIPTS = '.github/scripts'
ROLES = {'C': 'accepted-baseline', 'A': 'parent'}
IMPORT = re.compile(r'^\s*(?:from\s+([\w.]+)\s+import\b|import\s+([\w.]+(?:\s*,\s*[\w.]+)*))', re.M)


def require(condition, message):
    if not condition:
        raise ValueError(message)


def read(path):
    return json.loads(Path(path).read_text())


def job_definition(workflow_text, job=JOB):
    """The producer-arm job's block of the workflow: from its key to the next job's, as written."""
    lines = workflow_text.splitlines(keepends=True)
    start = next(i for i, line in enumerate(lines) if line.rstrip('\n') == '  ' + job + ':')
    end = next((i for i in range(start + 1, len(lines)) if re.fullmatch(r'  [A-Za-z0-9_-]+:\n?', lines[i])), len(lines))
    return ''.join(lines[start:end])


def job_scripts(workflow_text, job=JOB):
    """The scripts under .github/scripts the job's steps name, by their path under the checkout."""
    names = re.findall(r'(?:candidate/)?\.github/scripts/([A-Za-z0-9_./-]+\.py)', job_definition(workflow_text, job))
    return sorted(set(names))


def is_test(path):
    return path.name.startswith('test_') or path.name.endswith('_test.py')


def import_closure(scripts_dir, entries):
    """Every module under `scripts_dir` the entry scripts import, transitively; a package counts whole, its tests aside."""
    scripts_dir = Path(scripts_dir)
    modules = {path.stem: path for path in scripts_dir.glob('*.py')}
    packages = {path.name: path for path in scripts_dir.iterdir() if (path/'__init__.py').is_file()}
    def imports(path):
        found = set()
        for match in IMPORT.finditer(path.read_text()):
            names = [match.group(1)] if match.group(1) else [n.strip() for n in match.group(2).split(',')]
            for name in names:
                top = name.split('.')[0]
                if top in modules:
                    found.add(modules[top])
                elif top in packages:
                    found.update(path for path in sorted(packages[top].rglob('*.py')) if not is_test(path))
        return found
    seen = set()
    todo = [scripts_dir/name for name in entries]
    while todo:
        path = todo.pop()
        if path in seen or not path.is_file():
            continue
        seen.add(path)
        todo.extend(imports(path))
    return sorted(path.relative_to(scripts_dir).as_posix() for path in seen)


def producer_code(checkout):
    """What can touch an arm's products: the job definition and the scripts it can run, by digest."""
    checkout = Path(checkout)
    workflow = (checkout/WORKFLOW).read_text()
    scripts = import_closure(checkout/SCRIPTS, job_scripts(workflow))
    return {'jobDefinitionSha256': hashlib.sha256(job_definition(workflow).encode()).hexdigest(),
            'scripts': {name: digest_cache.sha(checkout/SCRIPTS/name) for name in scripts}}


def toolchain_versions(common):
    """What the common producer's toolchain paths answer: a path can keep its name across versions."""
    tools = common['toolchainIdentity']
    versions = {}
    for name, argv in (('java', [tools['java'], '-version']),
                       ('cargo', [tools['cargo'], '--version', '--verbose']),
                       ('rustc', [tools['rustc'], '--version', '--verbose'])):
        result = subprocess.run(argv, capture_output=True, text=True, timeout=60, check=True)
        versions[name] = (result.stdout + result.stderr).strip()
    return versions


def identity(workspace, arm, revision, role, checkout):
    workspace = Path(workspace).resolve()
    require(arm in ROLES and ROLES[arm] == role, 'only the accepted baseline and parent arms are cached')
    artifacts = workspace/'native-pressure-artifacts'
    inputs = {name: digest_cache.sha(artifacts/name) for name in COMMON_INPUTS}
    return {'schema': SCHEMA, 'arm': arm, 'revision': revision, 'role': role, 'checkout': checkout,
            'workspace': str(workspace),
            'producerCode': producer_code(workspace/'candidate'),
            'commonInputs': inputs,
            'toolchainVersions': toolchain_versions(read(artifacts/'producer-common.json'))}


def products_present(workspace, arm, revision, role, checkout):
    """The restored products name this revision and role, and the checkout's built jars are back."""
    workspace = Path(workspace).resolve()
    root = workspace/'native-pressure-artifacts'/arm
    problems = []
    for name in ('packet.json', 'runtime-manifest.json', 'artifact-audit.json', 'query-correctness-audit.json', 'fixture-manifest.json'):
        if not (root/name).is_file():
            problems.append('missing ' + name)
    if not problems:
        packet = read(root/'packet.json')
        if packet.get('revision') != revision or packet.get('role') != role:
            problems.append('packet names revision %s role %s' % (packet.get('revision'), packet.get('role')))
        if read(root/'runtime-manifest.json').get('revision') != revision:
            problems.append('runtime manifest names another revision')
    for tree in ('runtime', 'graphs', 'query-correctness'):
        if not (root/tree).is_dir():
            problems.append('missing ' + tree)
    libs = workspace/checkout/'frontend/jvm'
    if not list((libs/'webgraph/build/libs').glob('*-jmh.jar')):
        problems.append('missing ' + checkout + ' writer jar')
    if not (libs/'query/build/libs/graphite.jar').is_file():
        problems.append('missing ' + checkout + ' graphite.jar')
    return problems


def differences(recorded, expected):
    """The identity keys whose recorded value differs from the expected one, nested keys dotted."""
    changed = []
    for key in sorted(set(recorded) | set(expected)):
        a, b = recorded.get(key), expected.get(key)
        if isinstance(a, dict) and isinstance(b, dict):
            changed.extend(key + '.' + inner for inner in differences(a, b))
        elif a != b:
            changed.append(key)
    return changed


def check(identity_file, workspace, arm, revision, role, checkout):
    """Why a restored arm cannot stand in for this run's build: an empty list means it can."""
    path = Path(identity_file)
    if not path.is_file():
        return ['no recorded identity']
    recorded = read(path)
    problems = ['identity.' + key for key in differences(recorded, identity(workspace, arm, revision, role, checkout))]
    return problems + products_present(workspace, arm, revision, role, checkout)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest='command', required=True)
    for name in ('identity', 'check'):
        p = sub.add_parser(name)
        p.add_argument('--workspace', required=True)
        p.add_argument('--arm', required=True, choices=sorted(ROLES))
        p.add_argument('--revision', required=True)
        p.add_argument('--role', required=True)
        p.add_argument('--checkout', required=True)
        p.add_argument('--identity' if name == 'check' else '--output', required=True)
    args = parser.parse_args(argv)
    if args.command == 'identity':
        value = identity(args.workspace, args.arm, args.revision, args.role, args.checkout)
        Path(args.output).write_text(json.dumps(value, indent=2, sort_keys=True) + '\n')
        print(json.dumps({'identity': args.output, 'arm': args.arm, 'revision': args.revision}))
        return 0
    problems = check(args.identity, args.workspace, args.arm, args.revision, args.role, args.checkout)
    print(json.dumps({'arm': args.arm, 'revision': args.revision, 'valid': not problems, 'problems': problems}))
    return 0 if not problems else 1


if __name__ == '__main__':
    sys.exit(main())
