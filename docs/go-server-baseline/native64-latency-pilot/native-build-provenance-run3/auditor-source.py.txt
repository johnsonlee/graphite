"""Post-run build provenance: reconstruct measured Go binaries from fully hashed inputs.

Run only after every timed runtime is terminal. This does not measure performance.
It establishes retrospective byte identity; it cannot invent historical embedded-file
pre/post hashes that the original runtime runner did not record.
"""
import argparse
import datetime
import gzip
import hashlib
import io
import json
import re
from pathlib import Path
import subprocess
import tarfile

BASE = Path(__file__).resolve().parent
ROOT = BASE.parents[2]
MODULE = ROOT / 'graphite-server'
COMMAND = './cmd/graphite-benchmark-latency'
FILE_FIELDS = ('GoFiles', 'CgoFiles', 'CFiles', 'CXXFiles', 'MFiles', 'HFiles',
               'FFiles', 'SFiles', 'SwigFiles', 'SwigCXXFiles', 'SysoFiles', 'EmbedFiles')
ENV_KEYS = ('GOARCH', 'GOOS', 'GOAMD64', 'GOARM', 'GOARM64', 'GOROOT', 'GOPATH',
            'GOTOOLDIR', 'GOVERSION', 'GOEXPERIMENT', 'GOFLAGS', 'GOWORK', 'GOENV',
            'GOTOOLCHAIN', 'CGO_ENABLED', 'CC', 'CXX', 'CGO_CFLAGS', 'CGO_CPPFLAGS',
            'CGO_CXXFLAGS', 'CGO_FFLAGS', 'CGO_LDFLAGS')


def require(condition, message):
    if not condition:
        raise ValueError(message)


def read(path):
    return json.loads(path.read_text())


def write(path, value):
    path.write_text(json.dumps(value, indent=2) + '\n')


def sha(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def json_stream(text):
    decoder = json.JSONDecoder()
    records, position = [], 0
    while position < len(text):
        if text[position].isspace():
            position += 1
            continue
        record, position = decoder.raw_decode(text, position)
        records.append(record)
    return records


def run_command(command, name, output, commands):
    result = subprocess.run(command, cwd=MODULE, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    (output / (name + '.stdout')).write_text(result.stdout)
    (output / (name + '.stderr')).write_text(result.stderr)
    commands.append(dict(command=command, cwd=str(MODULE), exitCode=result.returncode,
                         stdout=name + '.stdout', stderr=name + '.stderr'))
    write(output / 'commands.json', commands)
    result.check_returncode()
    return result.stdout


def native_receipts(directories):
    require(len(directories) == 2, 'exactly cold and startup-prepared receipts required')
    records = []
    for directory in directories:
        receipt = read(directory / 'process.json')
        require(receipt['runtime'] == 'native' and receipt['status'] == 'exited'
                and receipt['exitCode'] == 1 and receipt['inputsUnchanged'] is True
                and receipt['binaryUnchanged'] is True and receipt['fixtureAuditPassed'] is True,
                'native runtime must be terminal with clean audits')
        require(receipt['buildCommand'][-1] == COMMAND, 'unexpected captured build target')
        require(receipt['command'][0] == receipt['buildCommand'][3], 'captured build/run binary differs')
        records.append(receipt)
    require({r['state'] for r in records} == {'cold', 'startup-prepared'}, 'both cache states required')
    require(len({r['binarySHA256'] for r in records}) == 1, 'measured Go binaries differ')
    return records


def build_inputs(packages, environment, go, original_inputs):
    """Selected source files plus toolchain/includes and frozen trial input union."""
    paths = {}

    def add(path, reason):
        path = Path(path)
        require(path.is_file(), 'missing build input: ' + str(path))
        paths.setdefault(str(path), set()).add(reason)

    for package in packages:
        require(not package.get('Error') and not package.get('DepsErrors') and not package.get('Incomplete'),
                'incomplete go list package')
        # This pilot is a pure Go binary. A future cgo target needs compiler,
        # SDK/system header and linker dependency discovery before this audit applies.
        require(not any(package.get(k) for k in ('CgoFiles', 'SwigFiles', 'SwigCXXFiles')),
                'cgo/SWIG dependency needs an expanded host-toolchain audit: ' + package['ImportPath'])
        if package['ImportPath'] == 'unsafe':
            continue
        directory = Path(package['Dir'])
        for field in FILE_FIELDS:
            for name in package.get(field, []):
                add(directory / name, package['ImportPath'] + ':' + field)
        module = package.get('Module', {})
        if module.get('GoMod'):
            add(module['GoMod'], 'module metadata:' + module.get('Path', ''))
        replacement = module.get('Replace', {})
        if replacement.get('GoMod'):
            add(replacement['GoMod'], 'replacement module metadata')
    add(Path(__file__).resolve(), 'audit implementation')
    for name in ('go.mod', 'go.sum'):
        add(MODULE / name, 'main module metadata')
    goroot = Path(environment['GOROOT'])
    add(go, 'Go command binary')
    add(goroot / 'VERSION', 'Go toolchain version')
    for directory in (goroot / 'pkg/tool', goroot / 'pkg/include'):
        require(directory.is_dir(), 'missing toolchain directory: ' + str(directory))
        for path in sorted(directory.rglob('*')):
            if path.is_file():
                add(path, 'toolchain binary or assembly include')
    for key in ('GOENV', 'GOWORK'):
        value = environment.get(key)
        if value and value != 'off' and Path(value).is_file():
            add(value, key + ' configuration')
            if key == 'GOWORK' and Path(value + '.sum').is_file():
                add(value + '.sum', 'workspace module sums')
    for path in original_inputs:
        add(path, 'original runtime frozen input')
    return {path: dict(sha256=sha(Path(path)), bytes=Path(path).stat().st_size,
                       reasons=sorted(reasons)) for path, reasons in sorted(paths.items())}


def stable_environment(environment):
    result = dict(environment)
    # go env creates a new temporary build directory on every invocation, even
    # for this pure-Go target. Preserve all flags except that generated path.
    result['GOGCCFLAGS'] = re.sub(r'-ffile-prefix-map=\S*/go-build\d+=/tmp/go-build',
                                   '-ffile-prefix-map=<go-temporary-build-dir>=/tmp/go-build',
                                   result.get('GOGCCFLAGS', ''))
    return result


def package_signature(packages):
    # go list Stale/StaleReason/BuildID can vary after a forced rebuild.
    return [{k: p[k] for k in ('ImportPath', 'Dir', 'Module', 'Imports', 'ImportMap', *FILE_FIELDS) if k in p}
            for p in packages]


def project_archive(manifest, output):
    members = {str(Path(path).relative_to(ROOT)): entry for path, entry in manifest.items()
               if Path(path).is_relative_to(MODULE)}
    require(bool(members), 'empty project snapshot')
    archive = output / 'project-build-inputs.tar.gz'
    with archive.open('xb') as raw:
        with gzip.GzipFile(fileobj=raw, mode='wb', filename='', mtime=0) as zipped:
            with tarfile.open(fileobj=zipped, mode='w') as tar:
                for name, entry in sorted(members.items()):
                    contents = (ROOT / name).read_bytes()
                    require(hashlib.sha256(contents).hexdigest() == entry['sha256'], 'input changed while archiving')
                    info = tarfile.TarInfo(name)
                    info.size, info.mode, info.mtime = len(contents), 0o644, 0
                    tar.addfile(info, io.BytesIO(contents))
    with tarfile.open(archive, 'r:gz') as tar:
        require(tar.getnames() == sorted(members), 'snapshot member inventory differs')
        for name, entry in members.items():
            contents = tar.extractfile(name).read()
            require(len(contents) == entry['bytes'] and hashlib.sha256(contents).hexdigest() == entry['sha256'],
                    'snapshot bytes differ: ' + name)
    write(output / 'project-build-inputs.json', members)
    return dict(path=str(archive), sha256=sha(archive), bytes=archive.stat().st_size,
                fileCount=len(members), extractedBytesVerified=True)


def audit(args):
    # The explicit flag is an operator assertion of the cross-runtime condition;
    # native terminal receipts are independently checked below.
    require(args.all_timed_runtimes_terminal, 'wait for all timed runtimes, then pass --all-timed-runtimes-terminal')
    receipts = native_receipts(args.native)
    require(not args.output.exists() and not args.rebuild_directory.exists(), 'use fresh output and build directories')
    require(not args.rebuild_directory.is_relative_to(ROOT), 'rebuild output must be outside the worktree')
    args.output.mkdir(parents=True)
    args.rebuild_directory.mkdir(parents=True)
    (args.output / 'auditor-source.py.txt').write_bytes(Path(__file__).read_bytes())
    commands = []
    status = dict(status='running', performanceMeasurement=False,
                  startedAt=datetime.datetime.now(datetime.timezone.utc).isoformat())
    write(args.output / 'status.json', status)
    try:
        go = str(Path(receipts[0]['buildCommand'][0]).resolve())
        require(all(str(Path(r['buildCommand'][0]).resolve()) == go for r in receipts), 'different Go tool binaries')
        environment = json.loads(run_command([go, 'env', '-json'], 'go-env-before', args.output, commands))
        for r in receipts:
            require(all(environment.get(k) == r['goEnvironment'].get(k) for k in ENV_KEYS),
                    'current build environment differs from measured binary')
        original_inputs = {}
        for directory, receipt in zip(args.native, receipts):
            frozen = read(directory / 'inputs.json')
            for path, digest in frozen.items():
                require(path not in original_inputs or original_inputs[path] == digest,
                        'shared frozen input differs across runs: ' + path)
                original_inputs[path] = digest
            require(sha(Path(receipt['command'][0])) == receipt['binarySHA256'], 'measured binary changed')
        for path, digest in original_inputs.items():
            require(sha(Path(path)) == digest, 'original frozen input changed: ' + path)
        deps_command = [go, 'list', '-deps', '-json', COMMAND]
        packages = json_stream(run_command(deps_command, 'go-list-before', args.output, commands))
        require(sum(p.get('Name') == 'main' for p in packages) == 1, 'unexpected executable package inventory')
        before = build_inputs(packages, environment, go, original_inputs)
        write(args.output / 'build-inputs-before.json', before)
        embedded = {p['ImportPath']: p['EmbedFiles'] for p in packages if p.get('EmbedFiles')}
        require('github.com/johnsonlee/graphite/graphite-server/internal/javaregex' in embedded,
                'Java embedded inputs not discovered')
        archive = project_archive(before, args.output)
        rebuilt = args.rebuild_directory / 'graphite-benchmark-latency'
        build_command = [go, 'build', '-a', '-o', str(rebuilt), COMMAND]
        run_command(build_command, 'forced-rebuild', args.output, commands)
        after_packages = json_stream(run_command(deps_command, 'go-list-after', args.output, commands))
        require(package_signature(packages) == package_signature(after_packages), 'transitive package/file set changed')
        after_env = json.loads(run_command([go, 'env', '-json'], 'go-env-after', args.output, commands))
        require(stable_environment(environment) == stable_environment(after_env), 'Go environment changed during audit')
        after = build_inputs(after_packages, after_env, go, original_inputs)
        write(args.output / 'build-inputs-after.json', after)
        require(before == after, 'transitive source, embedded data, or toolchain changed during rebuild')
        digest = sha(rebuilt)
        require(all(digest == r['binarySHA256'] == sha(Path(r['command'][0])) for r in receipts),
                'forced rebuilt binary is not byte-identical to both measured binaries')
        for label, binary in [('rebuilt', rebuilt), *[(r['state'], Path(r['command'][0])) for r in receipts]]:
            run_command([go, 'version', '-m', str(binary)], 'binary-build-info-' + label, args.output, commands)
        report = dict(performanceMeasurement=False, measuredStates=[r['state'] for r in receipts],
                      provenanceMethod='post-run forced reproducible-build byte identity',
                      historicalEmbeddedPrePostHashesAvailable=False,
                      conclusion='Both measured binaries are byte-identical to a forced rebuild from the enumerated, pre/post hashed transitive build inputs',
                      limitations=['This retrospective audit does not claim original timing runs recorded embedded-file pre/post hashes',
                                   'No cgo/SWIG host compiler or SDK closure is supported; such a dependency fails this audit'],
                      binarySHA256=digest, binaryBytes=rebuilt.stat().st_size, rebuiltBinary=str(rebuilt),
                      originalBinaries=[r['command'][0] for r in receipts],
                      packages=len(packages), buildInputs=len(before), buildInputBytes=sum(v['bytes'] for v in before.values()),
                      stdlibPackages=sum(p.get('Standard', False) for p in packages),
                      embeddedInputs=embedded, environmentNormalization='Only Go-generated GOGCCFLAGS temporary build directory', buildInputsUnchanged=True, originalRuntimeInputsUnchanged=True,
                      projectSnapshot=archive, commands=commands)
        report['evidenceSHA256'] = {str(path): sha(path) for path in sorted(args.output.iterdir())
                                   if path.is_file() and path.name != 'status.json'}
        report['evidenceSHA256'][str(Path(__file__).resolve())] = sha(Path(__file__).resolve())
        for directory in args.native:
            for name in ('process.json', 'inputs.json'):
                report['evidenceSHA256'][str(directory / name)] = sha(directory / name)
        write(args.output / 'verification.json', report)
        status.update(status='complete', forcedRebuildExitCode=0, binaryIdentityVerified=True)
        print(json.dumps({k: v for k, v in report.items() if k not in ('commands', 'evidenceSHA256')}, indent=2))
    except BaseException as error:
        status.update(status='failed', errorClass=type(error).__name__, error=str(error))
        raise
    finally:
        status['finishedAt'] = datetime.datetime.now(datetime.timezone.utc).isoformat()
        write(args.output / 'status.json', status)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--native', type=Path, action='append', required=True, help='native trial directory; repeat for both states')
    parser.add_argument('--output', type=Path, required=True, help='new audit evidence directory')
    parser.add_argument('--rebuild-directory', type=Path, required=True, help='new external directory for rebuilt executable')
    parser.add_argument('--all-timed-runtimes-terminal', action='store_true')
    args = parser.parse_args()
    args.native = [p.resolve() for p in args.native]
    args.output, args.rebuild_directory = args.output.resolve(), args.rebuild_directory.resolve()
    audit(args)
