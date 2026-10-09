#!/usr/bin/env python3
"""Build one exact native runtime and its own real64 persisted graph corpus.

Artifacts are not an independent audit, query oracle or performance verdict.
Each arm must be built from its clean ordinary Git checkout with the same tools
and pinned input JARs. No candidate graph is reused as an accepted-baseline graph.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys

# Keep the clean candidate checkout clean when this controller lives inside it.
sys.dont_write_bytecode = True
import multigraph_pressure as common

ACCEPTED = '4f2ccf33b969e684972e56b5e810034e6e67c1b3'
CORPORA = ('android', 'tika', 'hive', 'kotlin-compiler')
PROPERTIES = ('android.jar.path', 'tika.jar.path', 'hive.jar.path', 'kotlin.compiler.jar.path')
SCHEMA = 'graphite.native-artifact-producer.v1'
ARTIFACTS_READY = 'ARTIFACTS_COMPLETE_INDEPENDENT_PROOFS_PENDING'


def require(condition, message):
    common.require(condition, message)


def inventory(root):
    root = Path(root).resolve()
    require(root.is_dir(), 'inventory directory missing')
    result = {}
    for path in sorted(root.rglob('*')):
        require(not path.is_symlink(), 'inventory symlink: ' + str(path))
        if path.is_file():
            result[str(path.resolve())] = common.sha(path)
    require(result, 'empty inventory')
    return result


def git(checkout, *args):
    return subprocess.check_output(['git', '-C', str(checkout), *args], timeout=30,
                                   env=common.clean_env()).decode().strip()


def source_inventory(checkout, revision):
    require((checkout / '.git').is_dir() and not (checkout / '.git').is_symlink(),
            'ordinary Git checkout required by the JVM publishing plugin; worktrees are unsupported')
    require(git(checkout, 'rev-parse', 'HEAD') == revision, 'exact source revision mismatch')
    require(not git(checkout, 'status', '--porcelain', '--untracked-files=normal'), 'source checkout must be clean')
    result = {}
    for name in git(checkout, 'ls-files').splitlines():
        path = checkout / name
        require(not path.is_symlink() and path.is_file(), 'tracked source is not a regular file')
        result[str(path.resolve())] = common.sha(path)
    return result


def read_provenance(path):
    lines = Path(path).read_text().splitlines()
    require(len(lines) == 65, 'exact64 provenance rows required')
    rows = [line.split('\t') for line in lines[1:]]
    require(all(len(row) == 19 for row in rows), 'provenance columns')
    expected = [f'fixture-{kind}-{index:02d}' for kind in CORPORA for index in range(16)]
    require([row[0] for row in rows] == expected, 'exact ordered64 membership')
    return rows


def verify_inputs(path):
    document = common.read(path)
    require(document.get('schema') == 'graphite.fixture64-source-inputs.v1', 'input schema')
    require([item['corpus'] for item in document['jars']] == list(CORPORA), 'four ordered real JARs')
    reference = document['referenceProvenance']
    require(common.sha(reference['path']) == reference['sha256'], 'comparison provenance pin')
    rows = read_provenance(reference['path'])
    pins = {str(Path(path).resolve()): common.sha(path), str(Path(reference['path']).resolve()): reference['sha256']}
    for item in document['jars']:
        jar = Path(item['path'])
        require(not jar.is_symlink() and jar.is_file(), 'source JAR missing/symlink')
        require(common.valid_digest(item['sha256']) and common.sha(jar) == item['sha256'], 'source JAR bytes')
        matching = [row for row in rows if row[1] == item['corpus']]
        require(len(matching) == 16 and all(row[4] == item['sha256'] for row in matching), 'different source JAR corpus')
        pins[str(jar.resolve())] = item['sha256']
    return document, pins


def cleanup_process(proc, expected_live=False):
    previous = {sig: signal.signal(sig, signal.SIG_IGN) for sig in (signal.SIGINT, signal.SIGTERM)}
    try:
        if proc is None:
            return {'after': [], 'errors': []}
        probe_error = None
        try:
            needs_cleanup = expected_live or proc.poll() is None or bool(common.group_members(proc.pid))
        except BaseException as error:
            probe_error = repr(error)
            needs_cleanup = True
        if needs_cleanup:
            proof = common.stop_owned(proc, None, None)
            if probe_error is not None:
                proof['errors'].append('pre-cleanup probe failed: ' + probe_error)
            return proof
        return {'group': proc.pid, 'after': [], 'errors': [], 'exit': proc.returncode}
    finally:
        for sig, handler in previous.items():
            signal.signal(sig, handler)


def phase(name, argv, cwd, env, out, timeout):
    directory = out / name
    directory.mkdir()
    record = {'name': name, 'argv': argv, 'cwd': str(cwd), 'timeoutSeconds': timeout, 'errors': []}
    proc = None
    try:
        with common.deferred_signals():
            with (directory / 'stdout.log').open('x') as stdout, (directory / 'stderr.log').open('x') as stderr:
                proc = subprocess.Popen(argv, cwd=cwd, env=env, stdout=stdout, stderr=stderr, start_new_session=True)
            common.save(directory / 'owner.json', {'runnerPid': os.getpid(), 'group': proc.pid, 'argv': argv})
        record['exit'] = proc.wait(timeout=timeout)
        require(record['exit'] == 0, name + ' command failed')
    except BaseException as error:
        record['errors'].append(repr(error))
    finally:
        try:
            record['cleanup'] = cleanup_process(proc)
            require(not record['cleanup']['after'] and not record['cleanup']['errors'], 'phase cleanup')
        except BaseException as error:
            record['errors'].append('cleanup: ' + repr(error))
        record['status'] = 'FAIL' if record['errors'] else 'PASS'
        record['logs'] = {str(p): common.sha(p) for p in directory.glob('*.log')}
        common.save(directory / 'record.json', record)
    require(record['status'] == 'PASS', name + ': ' + repr(record['errors']))
    return record


def fixture_records(root, inputs):
    rows = read_provenance(root / 'fixture-provenance.tsv')
    lines = [line for line in (root / 'graphs.tsv').read_text().splitlines() if not line.startswith('#')]
    manifest = [line.split('\t') for line in lines]
    require(len(manifest) == 64 and all(len(row) == 6 for row in manifest), 'graph manifest exact64')
    require([row[0] for row in manifest] == [row[0] for row in rows], 'manifest/provenance order')
    jar_hashes = {item['corpus']: item['sha256'] for item in inputs['jars']}
    graphs = []
    for entry, row in zip(manifest, rows):
        directory = (root / row[0]).resolve()
        require(Path(entry[1]).resolve() == Path(row[18]).resolve() == directory, 'graph path not own output')
        require(row[4] == jar_hashes[row[1]], 'writer used different JAR')
        require(int(row[6]) > 0 and int(row[7]) > 0 and int(row[8]) > 0, 'empty fixture graph')
        require(entry[5] == row[15], 'workload identity differs')
        graphs.append({'id': row[0], 'path': str(directory), 'nodes': int(row[7]), 'callSites': int(row[8]),
                       'workloadIdentitySha256': row[15], 'querySemanticSha256': row[12]})
    require(len({row['querySemanticSha256'] for row in graphs}) == 64, 'non-distinct real64 graphs')
    return graphs


def build_environment(checkout, out, tools):
    # Direct toolchain executables avoid rustup's user override/settings discovery.
    for name in ('cargo', 'rustc'):
        path = Path(tools[name])
        require(path.name == name and path.resolve().name == name and path.is_file(),
                'provide direct toolchain executable, not rustup proxy: ' + name)
    require(Path(tools['cargo']).parent == Path(tools['rustc']).parent, 'matched Rust toolchain bin')
    homes = {name: out / name for name in ('user-home', 'cargo-home', 'gradle-home')}
    for path in homes.values():
        path.mkdir()
    removed = {'HOME', 'JAVA_HOME', 'GRADLE_HOME', 'GRADLE_USER_HOME', 'JAVA_OPTS', 'GRADLE_OPTS',
               'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'RUSTFLAGS',
               'RUSTC', 'RUSTDOC', 'RUSTDOCFLAGS', 'RUSTC_WRAPPER', 'RUSTC_WORKSPACE_WRAPPER',
               'RUST_TEST_THREADS', 'RUST_TEST_NOCAPTURE', 'LDFLAGS', 'CFLAGS', 'CXXFLAGS', 'CPPFLAGS',
               'MALLOC_CONF', '_RJEM_MALLOC_CONF', 'CLASSPATH'}
    prefixes = ('CARGO_', 'RUSTUP_', 'ORG_GRADLE_PROJECT_', 'DYLD_', 'LD_', 'GRAPHITE_', 'RAYON_')
    env = {k: v for k, v in common.clean_env().items() if k not in removed and not k.startswith(prefixes)}
    env.update(HOME=str(homes['user-home']), CARGO_HOME=str(homes['cargo-home']),
               GRADLE_USER_HOME=str(homes['gradle-home']), JAVA_HOME=str(Path(tools['java']).parent.parent),
               RUSTC=tools['rustc'], CARGO_TARGET_DIR=str(out / 'native-target'),
               JAVA_TOOL_OPTIONS='-Xmx4g -XX:ActiveProcessorCount=4',
               PATH=str(Path(tools['cargo']).parent) + os.pathsep + env.get('PATH', ''))
    return env


def configuration_inventory(checkout, env):
    # Run cwd is canonical; retain every searched ancestor, including absent files.
    paths = set()
    for ancestor in (checkout, *checkout.parents):
        paths.update(ancestor / '.cargo' / n for n in ('config', 'config.toml'))
        paths.update(ancestor / n for n in ('rust-toolchain', 'rust-toolchain.toml'))
    paths.update(Path(env['CARGO_HOME']) / n for n in ('config', 'config.toml'))
    gradle_roots = {Path(env['GRADLE_USER_HOME']), Path(env['HOME']) / '.gradle'}
    for root in gradle_roots:
        paths.update(root / n for n in ('gradle.properties', 'init.gradle', 'init.gradle.kts'))
    result = {}
    for path in sorted(paths):
        require(not path.is_symlink() and (not path.exists() or path.is_file()), 'config not regular: ' + str(path))
        # Accepted tracked project config is allowed; no external/home config is inherited.
        require(not path.exists() or path.is_relative_to(checkout), 'unexpected external config: ' + str(path))
        result[str(path)] = common.sha(path) if path.exists() else None
    for root in gradle_roots:
        init = root / 'init.d'
        require(not init.is_symlink() and (not init.exists() or init.is_dir()), 'Gradle init directory type')
        require(not init.exists() or not list(init.iterdir()), 'unexpected Gradle init scripts')
        result[str(init)] = 'EMPTY_DIRECTORY' if init.exists() else None
    return result


def produce(args):
    checkout, out = Path(args.checkout).resolve(), Path(args.output).resolve()
    revision, role = args.revision, args.role
    require(len(revision) == 40 and all(c in '0123456789abcdef' for c in revision), 'full source revision required')
    require(role in ('accepted-baseline', 'parent', 'candidate'), 'known producer role')
    require(role != 'accepted-baseline' or revision == ACCEPTED, 'fixed accepted baseline revision')
    require(not out.exists() and checkout.is_dir(), 'fresh output and existing checkout required')
    require(not out.is_relative_to(checkout), 'output must be outside source checkout')
    out.mkdir(parents=True)
    record = {'schema': SCHEMA, 'revision': revision, 'role': role, 'status': 'RUNNING', 'phases': [], 'errors': [],
              'independentlyAudited': False, 'acceptanceEligible': False,
              'unavailable': ['independent producer audit', 'complete cross-arm semantic-equivalence authority',
                              'complete native readiness and independent query-response oracles'],
              'performanceMeasurement': False}
    before = source_before = generated = runtime_pins = config_before = env = None
    original_handlers = {}
    def interrupted(signum, frame):
        raise InterruptedError('signal ' + str(signum))
    try:
        for sig in (signal.SIGINT, signal.SIGTERM):
            original_handlers[sig] = signal.signal(sig, interrupted)
        inputs, before = verify_inputs(args.fixture_manifest)
        source_before = source_inventory(checkout, revision)
        common.save(out / 'source-manifest.json', {'revision': revision, 'root': str(checkout), 'files': source_before})
        tools = {name: str(Path(getattr(args, name)).resolve()) for name in ('java', 'cargo', 'rustc')}
        require(all(Path(value).is_file() for value in tools.values()), 'tool executable missing')
        controls = {str(Path(__file__).resolve()): common.sha(__file__),
                    str(Path(common.__file__).resolve()): common.sha(common.__file__),
                    str(Path(common.__file__).with_name('native_legal_response.py').resolve()): common.sha(Path(common.__file__).with_name('native_legal_response.py')),
                    **{value: common.sha(value) for value in tools.values()}}
        jdk = Path(tools['java']).parent.parent
        for relative in ('bin/javac', 'release', 'lib/modules'):
            path = jdk / relative
            require(path.is_file() and not path.is_symlink(), 'JDK identity file missing: ' + relative)
            controls[str(path)] = common.sha(path)
        env = build_environment(checkout, out, tools)
        config_before = configuration_inventory(checkout, env)
        record['configurationBefore'] = config_before
        record['buildEnvironment'] = {key: env[key] for key in ('HOME', 'CARGO_HOME', 'GRADLE_USER_HOME', 'JAVA_HOME', 'RUSTC', 'CARGO_TARGET_DIR', 'PATH', 'JAVA_TOOL_OPTIONS')}
        before = {**before, **controls}
        common.save(out / 'inputs-before.json', before)
        def run(name, argv, timeout=1800):
            result = phase(name, argv, checkout, env, out, timeout)
            record['phases'].append(result)
            temporary = out / 'progress.json.tmp'
            temporary.write_text(json.dumps(record, indent=2) + '\n')
            temporary.replace(out / 'progress.json')
            return result
        run('java-version', [tools['java'], '-Xmx512m', '-XX:ActiveProcessorCount=4', '-version'], 30)
        run('rustc-version', [tools['rustc'], '--version', '--verbose'], 30)
        run('cargo-version', [tools['cargo'], '--version', '--verbose'], 30)
        host_lines = [line.split(': ', 1)[1] for line in (out / 'rustc-version/stdout.log').read_text().splitlines()
                      if line.startswith('host: ')]
        require(len(host_lines) == 1 and '/' not in host_lines[0], 'rustc host target')
        target = host_lines[0]
        run('build-jvm', [str(checkout / 'gradlew'), '--no-daemon', '--max-workers=2',
                         '-Dorg.gradle.jvmargs=-Xmx4g -XX:ActiveProcessorCount=4',
                         '-Pkotlin.compiler.execution.strategy=in-process', ':webgraph:jmhJar', ':query:shadowJar'], 7200)
        native_argv = [tools['cargo'], 'build', '--release', '--locked', '--jobs', '2', '-p', 'graphite-cli', '--target', target]
        run('build-native', native_argv, 7200)
        writer_jars = list((checkout / 'frontend/jvm/webgraph/build/libs').glob('*-jmh.jar'))
        require(len(writer_jars) == 1, 'exactly one source-built writer JAR required')
        originals = {'writer.jar': writer_jars[0], 'graphite.jar': checkout / 'frontend/jvm/query/build/libs/graphite.jar',
                     'graphite': out / 'native-target' / target / 'release/graphite'}
        runtime = out / 'runtime'
        runtime.mkdir()
        for name, path in originals.items():
            require(path.is_file() and not path.is_symlink(), 'actual built artifact missing/symlink')
            shutil.copy2(path, runtime / name)
            require(common.sha(path) == common.sha(runtime / name), 'runtime copy changed')
        runtime_pins = inventory(runtime)
        record['runtimeManifest'] = {'revision': revision, 'sourceFiles': source_before,
                                     'sourceManifestSha256': common.sha(out / 'source-manifest.json'),
                                     'files': runtime_pins, 'binary': str(runtime / 'graphite'),
                                     'sha256': common.sha(runtime / 'graphite'), 'buildArgv': native_argv,
                                     'toolchainIdentity': {**tools, 'target': target,
                                         'rustcVersion': (out / 'rustc-version/stdout.log').read_text(),
                                         'cargoVersion': (out / 'cargo-version/stdout.log').read_text()},
                                     'originalArtifacts': {str(path): common.sha(path) for path in originals.values()}}
        common.save(out / 'runtime-manifest.json', record['runtimeManifest'])
        java = [tools['java'], '-Xmx4g', '-XX:ActiveProcessorCount=4']
        properties = ['-D' + name + '=' + str(Path(item['path']).resolve())
                      for name, item in zip(PROPERTIES, inputs['jars'])]
        writer = [*java, *properties, '-cp', str(runtime / 'writer.jar'),
                  'io.johnsonlee.graphite.webgraph.Fixture64GraphPreparation']
        graphs_root = out / 'graphs'
        run('prepare-real64', [*writer, str(graphs_root)], 14400)
        run('verify-real64', [*writer, '--verify', str(graphs_root / 'graphs.tsv'),
                              str(graphs_root / 'fixture-provenance.tsv')], 7200)
        graphs = fixture_records(graphs_root, inputs)
        generated = inventory(graphs_root)
        common.save(out / 'fixture-manifest.json', {'writerRevision': revision, 'graphs': graphs,
                    'files': generated, 'inputJars': inputs['jars'],
                    'sourceManifestSha256': common.sha(out / 'source-manifest.json'),
                    'writerJarSha256': common.sha(runtime / 'writer.jar')})
        record['graphs'] = graphs
        record['status'] = ARTIFACTS_READY
    except BaseException as error:
        record['errors'].append(repr(error))
        record['status'] = 'FAIL'
    finally:
        for sig in original_handlers:
            signal.signal(sig, signal.SIG_IGN)
        checks = {}
        for name, check in [('source', lambda: source_inventory(checkout, revision) == source_before),
                            ('inputs', lambda: all(common.sha(path) == digest for path, digest in before.items())),
                            ('runtime', lambda: inventory(out / 'runtime') == runtime_pins),
                            ('fixtures', lambda: inventory(out / 'graphs') == generated)]:
            required = {'source': source_before, 'inputs': before, 'runtime': runtime_pins, 'fixtures': generated}[name]
            if required is None:
                checks[name] = 'NOT_REACHED'
                continue
            try:
                require(check(), 'final ' + name + ' identity changed')
                checks[name] = 'PASS'
            except BaseException as error:
                checks[name] = 'FAIL'
                record['errors'].append(repr(error))
        if 'runtimeManifest' in record:
            try:
                require(all(common.sha(path) == digest for path, digest in record['runtimeManifest']['originalArtifacts'].items()), 'original build artifacts changed')
                checks['originalArtifacts'] = 'PASS'
            except BaseException as error:
                checks['originalArtifacts'] = 'FAIL'
                record['errors'].append(repr(error))
        if config_before is not None:
            try:
                record['configurationAfter'] = configuration_inventory(checkout, env)
                require(record['configurationAfter'] == config_before, 'configuration presence/content changed')
                checks['configuration'] = 'PASS'
            except BaseException as error:
                checks['configuration'] = 'FAIL'
                record['errors'].append(repr(error))
        record['finalIdentity'] = checks
        record['phaseReceipts'] = {str(p): common.sha(p) for p in sorted(out.glob('*/record.json'))}
        if record['errors']:
            record['status'] = 'FAIL'
        common.save(out / 'packet.json', record)
        for sig, handler in original_handlers.items():
            signal.signal(sig, handler)
    return record


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('checkout', 'fixture-manifest', 'java', 'cargo', 'rustc', 'output'):
        parser.add_argument('--' + name, required=True)
    parser.add_argument('--revision', required=True)
    parser.add_argument('--role', choices=('accepted-baseline', 'parent', 'candidate'), required=True)
    args = parser.parse_args()
    value = produce(args)
    print(json.dumps({'status': value['status'], 'output': args.output, 'acceptanceEligible': False,
                      'independentProofsRequired': value['unavailable']}))
    return 0 if value['status'] == ARTIFACTS_READY else 1


if __name__ == '__main__':
    sys.exit(main())
