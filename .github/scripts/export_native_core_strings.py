#!/usr/bin/env python3
"""Export actual own-writer64 dictionaries for independent core verification.

Runs the retained dependency-only Java bridge with existing owned phases. These
exports are correctness inputs, not schema or cross-arm equivalence proofs.
"""
import argparse
import hashlib
import os
from pathlib import Path
import signal
import sys
import zipfile

sys.dont_write_bytecode = True
import audit_native_pressure_artifacts as artifacts
import multigraph_pressure as common
import produce_native_pressure_artifacts as producer
from prepare_native_pressure_plan import preparation_control_pins
from native_core_proof import wire_gty05 as wire

SOURCE = Path(__file__).resolve().parent / 'native_core_proof/ExportStrings.java'
SOURCE_SHA = '2957d2ddbcd32dd595e4496dfa12a597ef5dd88a07118a4e89fea611c9358835'
CLASSES = ('ExportStrings$CountedInput.class', 'ExportStrings.class')
DEPENDENCY = 'it/unimi/dsi/util/FrontCodedStringList.class'
PASS = 'PASS_ALL64_ACTUAL_STRING_EXPORTS_NOT_SEMANTIC_EQUIVALENCE'
AUDIT_PASS = 'PASS_ALL64_STRING_EXPORTS_INDEPENDENT_RAW_AUDIT'
require = common.require


def environment(java):
    removed = {'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', 'JDK_JAVAC_OPTIONS', '_JAVA_OPTIONS',
               'JAVA_OPTS', 'GRADLE_OPTS', 'CLASSPATH', 'JAVA', 'JAVA_HOME'}
    env = {key: value for key, value in common.clean_env().items() if key not in removed and
           not key.startswith(('PYTHON', 'DYLD_', 'LD_', 'GRAPHITE_', 'ORG_GRADLE_', 'CARGO_', 'RUSTUP_'))}
    env.update(JAVA_HOME=str(Path(java).parent.parent), JAVA_TOOL_OPTIONS='-Xmx4g -XX:ActiveProcessorCount=4')
    return env


def bind(audit_path, inputs_path, output):
    audit_path, inputs_path, output = (Path(p).resolve() for p in (audit_path, inputs_path, output))
    artifact = common.read(audit_path)
    require(artifact['schema'] == 'graphite.native-independent-artifact-audit.v1' and
            artifact['status'] == artifacts.STATUS, 'actual independent artifact audit')
    source = common.pinned_authority_metadata(artifact, artifact['sourceManifest'])
    runtime = common.pinned_authority_metadata(artifact, artifact['runtimeManifest'])
    fixture = common.pinned_authority_metadata(artifact, artifact['fixtureManifest'])
    tools = {key: runtime['toolchainIdentity'][key] for key in ('java', 'cargo', 'rustc')}
    actual = artifacts.audit(artifact['producerPacket']['path'], source['root'], artifact['revision'],
                            artifact['role'], inputs_path, tools)
    require(common.typed(actual) == common.typed(artifact), 'stored artifact audit differs from raw evidence')
    root = Path(artifact['producerPacket']['path']).parent
    jar = root / 'runtime/writer.jar'
    require(fixture['writerJarSha256'] == runtime['files'][str(jar)], 'actual writer.jar identity')
    require(len(fixture['graphs']) == 64 and
            [{'id': row['id'], 'path': row['path']} for row in fixture['graphs']] == artifact['graphs'],
            'complete ordered own-writer64 scope')
    require(not any(output.is_relative_to(Path(p)) for p in
                    (source['root'], root/'graphs', root/'runtime')), 'exports outside source/graph/runtime roots')
    require(common.sha(SOURCE) == SOURCE_SHA, 'retained export helper source identity')
    with zipfile.ZipFile(jar) as archive:
        require(archive.namelist().count(DEPENDENCY) == 1, 'unique actual serialized-string dependency')
        dependency_sha = hashlib.sha256(archive.read(DEPENDENCY)).hexdigest()
    pins = dict(artifact['pins'])
    for path, digest in {**preparation_control_pins(), str(audit_path): common.sha(audit_path),
                         str(inputs_path): common.sha(inputs_path), str(SOURCE): SOURCE_SHA}.items():
        require(path not in pins or pins[path] == digest, 'conflicting export input pin')
        pins[path] = digest
    java = tools['java']; javac = str(Path(java).parent / 'javac')
    require(java in pins and javac in pins and str(jar) in pins, 'actual JDK and writer pins')
    graphs = []
    for row in fixture['graphs']:
        strings = str(Path(row['path'])/'graph.strings')
        require(pins.get(strings) == fixture['files'].get(strings) and strings in pins,
                'actual serialized dictionary pin')
        graphs.append({'id': row['id'], 'input': {'path': strings, 'sha256': pins[strings]}})
    return {'schema': 'graphite.native-core-string-export-plan.v1', 'artifactAudit': artifacts.ref(audit_path),
            'sourceInputs': artifacts.ref(inputs_path), 'revision': artifact['revision'], 'role': artifact['role'],
            'java': java, 'javac': javac, 'writerJar': str(jar), 'output': str(output),
            'helperSource': artifacts.ref(SOURCE), 'dependencyClassSha256': dependency_sha,
            'graphs': graphs, 'pins': pins, 'environment': {k: environment(java)[k] for k in
                ('JAVA_HOME', 'JAVA_TOOL_OPTIONS', 'LC_ALL', 'LANG')},
            'performanceAcceptance': False, 'completeSemanticEquivalence': False}


def commands(plan):
    out = Path(plan['output']); classes = out/'classes'
    yield ('compile-exporter', [plan['javac'], '-J-Xmx4g', '-J-XX:ActiveProcessorCount=4', '-proc:none',
                               '-cp', plan['writerJar'], '-d', str(classes), plan['helperSource']['path']], 120)
    for row in plan['graphs']:
        yield (row['id']+'-export', [plan['java'], '-Xmx4g', '-XX:ActiveProcessorCount=4', '-cp',
               str(classes)+os.pathsep+plan['writerJar'], 'ExportStrings', row['input']['path'],
               row['input']['sha256'], str(out/'exports'/row['id']), plan['helperSource']['path']], 600)


def classes(plan):
    directory = Path(plan['output'])/'classes'
    actual = artifacts.inventory(directory)
    require(set(actual) == {str(directory/name) for name in CLASSES}, 'exact compiled helper closure')
    return actual


def export_row(plan, row, compiled):
    directory = Path(plan['output'])/'exports'/row['id']
    require({p.name for p in directory.iterdir()} == {'receipt.json', 'strings.bin'} and
            all(p.is_file() and not p.is_symlink() for p in directory.iterdir()), 'exact export output closure')
    receipt = common.read(directory/'receipt.json')
    require(receipt['frontCodedStringListClassSha256'] == plan['dependencyClassSha256'],
            'actual writer serialized-string dependency bytes')
    wire.verified_strings_export((directory/'strings.bin').read_bytes(), receipt, row['input']['sha256'],
        compiled[str(Path(plan['output'])/'classes/ExportStrings.class')], SOURCE_SHA)
    return {'id': row['id'], 'input': row['input'], 'stringsExport': artifacts.ref(directory/'strings.bin'),
            'stringsReceipt': artifacts.ref(directory/'receipt.json'),
            'helperClassSha256': compiled[str(Path(plan['output'])/'classes/ExportStrings.class')]}


def execute(plan):
    out = Path(plan['output'])
    expected = bind(plan['artifactAudit']['path'], plan['sourceInputs']['path'], out)
    require(common.typed(plan) == common.typed(expected), 'execution plan differs from actual artifact inputs')
    out.mkdir(parents=True, exist_ok=False)
    (out/'classes').mkdir(); (out/'exports').mkdir()
    common.save(out/'plan.json', plan)
    record = {'status': 'FAIL', 'plan': artifacts.ref(out/'plan.json'), 'phases': [], 'exports': [],
              'errors': [], 'performanceAcceptance': False, 'completeSemanticEquivalence': False}
    previous = {}; compiled = {}
    def interrupted(signum, frame):
        raise InterruptedError('signal '+str(signum))
    try:
        for sig in (signal.SIGINT, signal.SIGTERM):
            previous[sig] = signal.signal(sig, interrupted)
        artifacts.verify_pins(plan['pins'])
        for index, (name, argv, timeout) in enumerate(commands(plan)):
            producer.phase(name, argv, out, environment(plan['java']), out, timeout)
            record['phases'].append(artifacts.ref(out/name/'record.json'))
            if index == 0:
                compiled = classes(plan)
            else:
                record['exports'].append(export_row(plan, plan['graphs'][index-1], compiled))
        require(len(record['exports']) == 64, 'all64 completed exports')
        common.save(out/'exports.json', {'schema': 'graphite.string-exports.v1', 'graphs': record['exports']})
        record['status'] = PASS
    except BaseException as error:
        record['errors'].append(repr(error))
    finally:
        for sig in previous: signal.signal(sig, signal.SIG_IGN)
        try:
            artifacts.verify_pins(plan['pins'])
            if compiled:
                require(classes(plan) == compiled, 'helper changed during exports')
            for row in record['exports']:
                for key in ('stringsExport', 'stringsReceipt'):
                    require(artifacts.ref(row[key]['path']) == row[key], 'export changed during execution')
            record['finalIdentity'] = 'PASS'
        except BaseException as error:
            record['errors'].append('final verification: '+repr(error)); record['finalIdentity'] = 'FAIL'
        if record['errors']: record['status'] = 'FAIL'
        record['compiledHelper'] = compiled
        common.save(out/'record.json', record)
        for sig, handler in previous.items(): signal.signal(sig, handler)
    return record


def audit(output):
    out = Path(output).resolve(); plan = common.read(out/'plan.json'); record = common.read(out/'record.json')
    require(plan['output'] == str(out) and record['status'] == PASS and record['errors'] == [] and
            record['finalIdentity'] == 'PASS' and record['plan'] == artifacts.ref(out/'plan.json') and
            all(record[k] is False for k in ('performanceAcceptance','completeSemanticEquivalence')),
            'completed actual export execution')
    expected = bind(plan['artifactAudit']['path'], plan['sourceInputs']['path'], out)
    require(common.typed(plan) == common.typed(expected), 'export plan differs from actual artifact inputs')
    compiled = classes(plan)
    require(record['compiledHelper'] == compiled, 'compiled helper receipt')
    phases = list(commands(plan)); require(len(record['phases']) == len(phases) == 65, 'exact65 owned phases')
    pins = {**plan['pins'], **compiled}; rows = []
    for actual_ref, (name, argv, timeout) in zip(record['phases'], phases):
        path = out/name/'record.json'; require(actual_ref == artifacts.ref(path), 'actual phase receipt')
        phase = artifacts.check_phase(path, argv, out)
        require(phase['timeoutSeconds'] == timeout and phase['name'] == name, 'exact bounded phase')
        for leaf in ('record.json','owner.json','stdout.log','stderr.log'):
            file = path.parent/leaf; pins[str(file)] = common.sha(file)
    require({p.name for p in (out/'exports').iterdir()} == {r['id'] for r in plan['graphs']},
            'closed all64 export directory')
    for row in plan['graphs']:
        value = export_row(plan, row, compiled); rows.append(value)
        for key in ('stringsExport', 'stringsReceipt'): pins[value[key]['path']] = value[key]['sha256']
    require(record['exports'] == rows and common.read(out/'exports.json') ==
            {'schema': 'graphite.string-exports.v1', 'graphs': rows}, 'complete exact export index')
    for name in ('plan.json','record.json','exports.json'): pins[str(out/name)] = common.sha(out/name)
    artifacts.verify_pins(pins)
    return {'schema': 'graphite.native-core-string-export-audit.v1', 'status': AUDIT_PASS,
            'revision': plan['revision'], 'role': plan['role'], 'artifactAudit': plan['artifactAudit'],
            'plan': artifacts.ref(out/'plan.json'), 'record': artifacts.ref(out/'record.json'),
            'exports': artifacts.ref(out/'exports.json'), 'graphs': rows, 'phases': 65, 'pins': pins,
            'performanceAcceptance': False, 'completeSemanticEquivalence': False,
            'sourceToDeclarationCompletenessClaim': False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--artifact-audit', type=Path)
    parser.add_argument('--source-inputs', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--audit-only', action='store_true')
    args = parser.parse_args()
    if not args.audit_only:
        require(args.artifact_audit is not None and args.source_inputs is not None, 'actual producer audit and inputs required')
        plan = bind(args.artifact_audit, args.source_inputs, args.output)
        result = execute(plan)
        if result['status'] != PASS: return 1
    common.save(args.output/'audit.json', audit(args.output))
    return 0


if __name__ == '__main__':
    sys.exit(main())
