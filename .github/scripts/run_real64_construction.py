#!/usr/bin/env python3
"""Six sequential usable-save measurements using the already sealed CI writers.

No compilation, query or single-graph timing. The extra --verify is untimed.
Fresh outputs retain their own validity evidence, not a claim of byte identity
or complete semantic equivalence with the producer's earlier graph files.
"""
import argparse
import json
from pathlib import Path
import shutil
import signal
import subprocess
import sys

sys.dont_write_bytecode = True
import assemble_native_pressure_producers as bundle
import audit_native_pressure_artifacts as artifacts
import multigraph_pressure as common
import produce_native_pressure_artifacts as producer
from prepare_native_pressure_plan import preparation_control_pins, native_build_identity

SCHEMA = 'graphite.real64-construction.plan.v1'
RESULT = 'graphite.real64-construction.execution.v1'
PAIRS = {'parent': [[1, 2], [4, 3]], 'acceptedBaseline': [[0, 2], [5, 3]]}
SCOPE = producer.CONSTRUCTION_SCOPE
FALSE = {'completeSemanticEquivalence': False, 'strictEquivalence': False,
         'sourceToDeclarationCompletenessClaim': False, 'performanceAcceptance': False,
         'otherOperationsEligible': False}
require = common.require


def save_progress(path, value):
    path = Path(path)
    temporary = path.with_name(path.name + '.tmp')
    temporary.write_text(json.dumps(value, indent=2, allow_nan=False) + '\n')
    temporary.replace(path)


def prepare(packet_path, base, candidate):
    packet_path = Path(packet_path).resolve()
    packet = bundle.verify_bundle(common.read(packet_path), base, candidate)
    require(packet['status'] == bundle.CORRECTED_STATUS and not packet['missingAuthority'],
            'complete corrected writer comparability is required')
    bundle.corrected_fixture_bindings(packet)
    inputs, input_pins = producer.verify_inputs(packet['sourceInputs']['path'])
    pins = dict(packet['pins'])
    bundle.merge_pins(pins, preparation_control_pins())
    bundle.merge_pins(pins, input_pins)
    bundle.merge_pins(pins, {str(packet_path): common.sha(packet_path)})
    arms = {}
    for label, source in packet['arms'].items():
        runtime = common.pinned_authority_metadata(packet, source['runtimeManifest'])
        manifest = common.pinned_authority_metadata(packet, source['sourceManifest'])
        audit = common.pinned_authority_metadata(packet, source['artifactAudit'])
        writer = str(Path(source['runtimeManifest']['path']).parent / 'runtime/writer.jar')
        require(runtime['sourceFiles'] == manifest['files'] == audit['sourceBefore'] == audit['sourceAfter']
                and runtime['sourceManifestSha256'] == source['sourceManifest']['sha256']
                and runtime['revision'] == manifest['revision'] == source['revision'],
                'same actual writer source and build closure')
        require(writer in runtime['files'] and pins.get(writer) == runtime['files'][writer],
                'sealed source-built writer bytes')
        java = native_build_identity(runtime)['java']
        require(java in pins and audit.get('constructionResources', {}).get('heapMaxBytes') == 4 * 1024**3,
                'audited direct JDK and bounded producer construction')
        argv = [java, '-Xmx4g', '-XX:ActiveProcessorCount=4',
                *['-D' + key + '=' + str(Path(item['path']).resolve())
                  for key, item in zip(producer.PROPERTIES, inputs['jars'])],
                '-cp', writer, 'io.johnsonlee.graphite.webgraph.Fixture64GraphPreparation']
        arms[label] = {'revision': source['revision'], 'role': source['role'],
                       'sourceManifest': source['sourceManifest'], 'runtimeManifest': source['runtimeManifest'],
                       'artifactAudit': source['artifactAudit'], 'writer': {'path': writer, 'sha256': pins[writer]},
                       'argv': argv}
    require(len({a['argv'][0] for a in arms.values()}) == 1, 'same JDK across all writers')
    require('/usr/bin/time' in pins, 'audited GNU time binary')
    return {'schema': SCHEMA, 'engine': 'jvm', 'operation': 'construction', 'graphCount': 64,
            'scope': SCOPE, 'comparisonModel': bundle.CORRECTED_MODEL,
            'producers': artifacts.ref(packet_path), 'sourceInputs': packet['sourceInputs'],
            'arms': arms, 'cells': [{'id': f'{i+1:02}-{arm}', 'arm': arm} for i, arm in enumerate('CABBAC')],
            'comparisonPairs': PAIRS, 'maxHeapBytes': 4 * 1024**3, 'activeProcessorCount': 4,
            'cachePolicy': 'Identical untimed source-JAR and writer reads before every sample; no disk-cold claim',
            'timeouts': {'prepare-real64': 14400, 'verify-real64': 7200}, 'pins': pins, **FALSE}


def validate(plan):
    require(plan['schema'] == SCHEMA, 'construction plan schema')
    expected = prepare(plan['producers']['path'], plan['arms']['A']['revision'], plan['arms']['B']['revision'])
    require(common.typed(plan) == common.typed(expected), 'exact predeclared construction plan')
    return plan


def environment():
    env = common.clean_env()
    for key in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'JAVA_OPTS', 'CLASSPATH'):
        env.pop(key, None)
    env['LC_ALL'] = 'C'
    return env


def verify_writer(plan, arm):
    source = common.pinned_authority_metadata(plan, arm['sourceManifest'])
    require(artifacts.source_inventory(Path(source['root']), arm['revision']) == source['files'],
            'actual writer source changed')
    runtime = common.pinned_authority_metadata(plan, arm['runtimeManifest'])
    artifacts.verify_pins(runtime['files'])
    artifacts.verify_pins(runtime['originalArtifacts'])
    require(producer.inventory(Path(arm['writer']['path']).parent) == runtime['files'], 'closed runtime unchanged')
    inputs, pins = producer.verify_inputs(plan['sourceInputs']['path'])
    require(artifacts.ref(plan['sourceInputs']['path']) == plan['sourceInputs'], 'source input manifest unchanged')
    artifacts.verify_pins({arm['argv'][0]: plan['pins'][arm['argv'][0]],
                          '/usr/bin/time': plan['pins']['/usr/bin/time']})
    return inputs, pins


def run(plan_file, output):
    plan_file, output = Path(plan_file).resolve(), Path(output).resolve()
    plan = validate(common.read(plan_file))
    require(sys.platform == 'linux', 'reviewed GNU/Linux construction resource backend')
    output.mkdir(exist_ok=False)
    record = {'schema': RESULT, 'status': 'RUNNING', 'planSha256': common.sha(plan_file),
              'cells': [], 'unissued': [c['id'] for c in plan['cells']], 'errors': [], **FALSE}
    save_progress(output / 'execution.json', record)
    original_handlers = {}
    def interrupted(signum, frame):
        raise InterruptedError('signal ' + str(signum))
    try:
        for sig in (signal.SIGINT, signal.SIGTERM):
            original_handlers[sig] = signal.signal(sig, interrupted)
        artifacts.verify_pins(plan['pins'])
        common.save(output / 'identities-before.json', {'pins': plan['pins']})
        for cell in plan['cells']:
            arm, directory = plan['arms'][cell['arm']], output / cell['id']
            directory.mkdir()
            row = {'cell': cell, 'status': 'RUNNING', 'writer': arm['writer'],
                   'revision': arm['revision'], 'errors': [], **FALSE}
            record['cells'].append(row)
            record['unissued'].remove(cell['id'])
            save_progress(output / 'execution.json', record)
            try:
                inputs, input_pins = verify_writer(plan, arm)
                # Equal untimed reads precede each launch; required writer reads stay timed.
                require(common.sha(arm['writer']['path']) == arm['writer']['sha256'], 'prewarm sealed writer')
                graphs = directory / 'graphs'
                row['construction'] = producer.phase('prepare-real64', [*arm['argv'], str(graphs)],
                    directory, environment(), directory, plan['timeouts']['prepare-real64'], construction_time='/usr/bin/time')
                row['verify'] = producer.phase('verify-real64', [*arm['argv'], '--verify',
                    str(graphs/'graphs.tsv'), str(graphs/'fixture-provenance.tsv')],
                    directory, environment(), directory, plan['timeouts']['verify-real64'])
                fixture = {'writerRevision': arm['revision'], 'writerJarSha256': arm['writer']['sha256'],
                           'sourceManifestSha256': arm['sourceManifest']['sha256'],
                           'graphs': producer.fixture_records(graphs, inputs), 'files': producer.inventory(graphs),
                           'inputJars': inputs['jars'], **FALSE}
                common.save(directory/'fixture-manifest.json', fixture)
                row['fixture'] = artifacts.ref(directory/'fixture-manifest.json')
                artifacts.verify_pins(input_pins)
                verify_writer(plan, arm)
                row['status'] = 'PASS_USABLE_SAVED64'
            except BaseException as error:
                row.update(status='FAIL', errors=[repr(error)])
                raise
            finally:
                common.save(directory/'cell.json', row)
                save_progress(output/'execution.json', record)
        record['status'] = 'PASS_ALL_SIX_USABLE_SAVED64'
    except BaseException as error:
        record.update(status='FAIL', errors=[repr(error)])
    finally:
        for sig in original_handlers:
            signal.signal(sig, signal.SIG_IGN)
        try:
            try:
                artifacts.verify_pins(plan['pins'])
                require(common.sha(plan_file) == record['planSha256'], 'construction plan changed')
                common.save(output/'identities-after.json', {'pins': plan['pins']})
            except BaseException as error:
                record['status'] = 'FAIL'
                record['errors'].append('final identity: ' + repr(error))
            save_progress(output/'execution.json', record)
        finally:
            for sig, handler in original_handlers.items():
                signal.signal(sig, handler)
    return record


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('producers', 'base-sha', 'candidate-sha', 'output', 'prefix'):
        parser.add_argument('--' + name, required=True)
    args = parser.parse_args()
    out, prefix = Path(args.output).resolve(), Path(args.prefix).resolve()
    require(not out.exists(), 'fresh construction output required')
    out.mkdir(parents=True)
    measured = False
    try:
        plan = prepare(args.producers, args.base_sha, args.candidate_sha)
        common.save(out/'plan.json', plan)
        measured = True
        result = run(out/'plan.json', out/'cells')
        require(result['status'] == 'PASS_ALL_SIX_USABLE_SAVED64', 'construction run failed; raw failures retained')
        from audit_real64_construction import audit
        try:
            common.save(out/'audit.json', audit(out/'plan.json', out/'cells'))
        except BaseException as error:
            common.save(out/'audit.json', {'schema': 'graphite.real64-construction.audit.v1',
                        'status': 'FAIL', 'errors': [repr(error)], **FALSE})
            raise
        node = shutil.which('node')
        require(node is not None, 'Node executable missing')
        command = [node, str(Path(__file__).with_name('benchmark-construction.mjs')),
                   str(out/'plan.json'), str(out), str(prefix)]
        common.save(out/'comparison-command.json', command)
        completed = subprocess.run(command, capture_output=True, text=True, timeout=60, check=False)
        (out/'comparison.stdout.log').write_text(completed.stdout)
        (out/'comparison.stderr.log').write_text(completed.stderr)
        verdict = common.read(str(prefix)+'-status.json')
        require(completed.returncode in (0, 1) and verdict['passed'] == (completed.returncode == 0)
                and verdict['planSha256'] == common.sha(out/'plan.json'), 'actual construction comparison verdict')
        artifacts.verify_pins({command[1]: plan['pins'][command[1]]})
        common.save(out/'comparison-exit.json', {'exit': completed.returncode,
                    'statusSha256': common.sha(str(prefix)+'-status.json')})
        return completed.returncode
    except BaseException as error:
        result = {'schema': 'graphite.real64-construction.comparison.v1', 'engine': 'jvm',
                  'operation': 'construction', 'status': 'FAIL' if measured else 'UNAVAILABLE',
                  'passed': False, 'errors': [repr(error)], **FALSE}
        common.save(out/'failure.json', result)
        prefix.parent.mkdir(parents=True, exist_ok=True)
        save_progress(str(prefix)+'-status.json', result)
        Path(str(prefix)+'-report.md').write_text('### JVM real64 construction\n\n'+result['status']+'\n\n'+repr(error)+'\n')
        return 1


if __name__ == '__main__':
    sys.exit(main())
