#!/usr/bin/env python3
"""Derive all34 JVM row universes from one audited own-writer64 arm.

This correctness entry point reuses completed core/string/producer evidence and
original GSE01 edges. It never queries a server or learns an expected response.
Its output still requires independent JVM source-rule and execution replay;
core migration scope is not promoted to complete semantic equivalence.
"""
import argparse
import json
import os
from pathlib import Path
import signal
import sys

sys.dont_write_bytecode = True
import audit_native_pressure_artifacts as artifacts
from assemble_native_pressure_producers import merge_pins
import jvm_pressure_derivation as derivation
import jvm_pressure_distinct as distinct
import jvm_pressure_inputs as inputs
import jvm_pressure_oracles as model
import jvm_primitive_facts as primitive
import multigraph_pressure as common
import produce_native_pressure_artifacts as producer
import run_native_core_equivalence as core

SOURCE = Path(__file__).resolve().with_name('JvmPrimitiveFacts.java')
PASS = 'COMPLETE_ALL34_RAW_DERIVATION_REQUIRES_INDEPENDENT_SOURCE_RULE_AUDIT'
FALSE_CLAIMS = ('oracleAuthorityVerified', 'completeSemanticEquivalence', 'fresh64Acceptance', 'performanceAcceptance')
require = common.require


def bind(producer_root, core_root, arm, output):
    """Replay upstream once, before either per-graph traversal, never per graph."""
    require(arm in ('C', 'B'), 'actual core reference/actual arm required')
    root, proof, out = (Path(p).resolve() for p in (producer_root, core_root, output))
    stored = common.read(proof/'audit.json')
    audited = core.audit(proof)
    require(common.typed(stored) == common.typed(audited), 'core audit differs from actual owned evidence')
    require(audited['status'] == core.AUDIT_PASS and audited['completeCoreTopologyIndexComparison'] is True,
            'complete original core/topology execution evidence')
    plan = common.pinned_authority_metadata(audited, artifacts.ref(proof/'plan.json'))
    require(plan.get('rawEdgeExports') is True, 'actual raw edge export mode required')
    require(str(root) == plan['referenceRoot' if arm == 'C' else 'actualRoot'], 'selected producer belongs to core arm')
    export_ref = plan['upstream'][arm+'Strings']
    exports = common.pinned_authority_metadata(audited, export_ref)
    require(export_ref == artifacts.ref(root/'core-string-exports/audit.json') and
            exports['artifactAudit'] == artifacts.ref(root/'artifact-audit.json'), 'exact arm string/producer evidence')
    artifact = common.pinned_authority_metadata(audited, exports['artifactAudit'])
    fixture = common.pinned_authority_metadata(audited, artifact['fixtureManifest'])
    source = common.pinned_authority_metadata(audited, artifact['sourceManifest'])
    export_plan = common.pinned_authority_metadata(audited, exports['plan'])
    require(artifact['revision'] == fixture['writerRevision'] == plan['revisions'][arm] and
            artifact['fixtureManifest'] == plan['fixtureManifests'][arm], 'same writer revision and fixture manifest')
    require(not any(out.is_relative_to(Path(p)) for p in (source['root'], root/'graphs', root/'runtime', proof)),
            'derivation output outside source/runtime/graph/core proof roots')
    ids = [r['id'] for r in plan['graphs']]
    require(ids == [r['id'] for r in audited['graphs']] == [r['id'] for r in fixture['graphs']],
            'complete ordered same-arm64 evidence')
    model.cases(ids)
    pins = dict(audited['pins']); merge_pins(pins, {str(proof/'audit.json'): common.sha(proof/'audit.json')})
    modules = (Path(__file__), SOURCE, Path(derivation.__file__), Path(distinct.__file__),
               Path(inputs.__file__), Path(model.__file__), Path(primitive.__file__))
    merge_pins(pins, {str(p.resolve()): common.sha(p) for p in modules})
    java, javac, writer = (export_plan[k] for k in ('java', 'javac', 'writerJar'))
    require(all(p in pins for p in (java, javac, writer)), 'actual writer and JDK executable pins')
    home = Path(java).resolve().parent.parent
    jdk = {'home': str(home), 'files': {name: artifacts.ref(home/name) for name in primitive.JDK_FILES}}
    require(all(pins.get(ref['path']) == ref['sha256'] for ref in jdk['files'].values()), 'same audited JDK image')
    graphs = []
    for row, checked in zip(plan['graphs'], audited['graphs']):
        gid = row['id']; spec = inputs.graph_inputs(fixture, exports, gid)
        require(spec['root'] == row[arm], 'same arm graph path')
        top = common.pinned_authority_metadata(audited, checked['topology'])
        receipt = top['rawEdgeExports']['reference' if arm == 'C' else 'actual']
        require(receipt['graphRoot'] == spec['root'], 'same actual topology export arm')
        require(all(pins.get(ref['path']) == ref['sha256'] for ref in
                    (receipt['output'], receipt['helperSource'], receipt['helperClass'])), 'audited raw edge/helper bytes')
        graphs.append({'id': gid, 'spec': spec, 'edges': receipt, 'topology': checked['topology']})
    return {'schema': 'graphite.jvm-raw-oracle-plan.v1', 'producerRoot': str(root), 'coreRoot': str(proof),
            'arm': arm, 'output': str(out), 'revision': artifact['revision'], 'role': artifact['role'],
            'sourceManifest': artifact['sourceManifest'], 'runtimeManifest': artifact['runtimeManifest'],
            'fixtureManifest': artifact['fixtureManifest'], 'upstreamCoreAudit': artifacts.ref(proof/'audit.json'),
            'graphs': graphs, 'java': java, 'javac': javac, 'writerJar': writer, 'jdkImage': jdk,
            'helperSource': artifacts.ref(SOURCE), 'pins': pins, **{key: False for key in FALSE_CLAIMS}}


def environment(plan):
    env = common.clean_env()
    for key in ('JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS', 'JDK_JAVAC_OPTIONS', 'CLASSPATH'):
        env.pop(key, None)
    env.update(JAVA_HOME=plan['jdkImage']['home'], LC_ALL='C')
    return env


def commands(plan):
    out = Path(plan['output'])
    return [
        ('compile-primitives', [plan['javac'], '-J-Xmx4g', '-J-XX:ActiveProcessorCount=4', '-proc:none',
                               '-cp', plan['writerJar'], '-d', str(out/'classes'), plan['helperSource']['path']], 180),
        ('primitive-facts', [plan['java'], '-Xmx4g', '-XX:ActiveProcessorCount=4', '-cp',
                             str(out/'classes')+os.pathsep+plan['writerJar'], 'JvmPrimitiveFacts',
                             str(out/'requests.json'), str(out/'primitive-facts.json'), plan['helperSource']['path']], 1800)]


def collect_requests(plan):
    request = primitive.RequestCollector([row['id'] for row in plan['graphs']])
    for row in plan['graphs']:
        graph = inputs.read_graph(row['spec'])
        request.add_graph(graph)
        del graph  # No all64 node/metadata/type tables retained.
    classes = Path(plan['output'])/'classes'
    require({p.name for p in classes.iterdir()} == {'JvmPrimitiveFacts.class'}, 'exact primitive compiled helper closure')
    return request.finish(plan['helperSource'], artifacts.ref(classes/'JvmPrimitiveFacts.class'), plan['jdkImage'])


def consume_facts(plan):
    out = Path(plan['output'])
    facts = primitive.PrimitiveFacts((out/'requests.json').read_bytes(), str(out/'requests.json'),
                                     (out/'primitive-facts.json').read_bytes())
    require(facts.request['helperSource'] == plan['helperSource'] and facts.request['jdkImage'] == plan['jdkImage'],
            'actual owned primitive source/runtime identity')
    require(facts.request['helperClass'] == artifacts.ref(out/'classes/JvmPrimitiveFacts.class'), 'actual compiled primitive bytes')
    require([g['id'] for g in facts.request['sourceGraphs']] == [g['id'] for g in plan['graphs']], 'actual primitive arm scope')
    return facts


def derive(plan, facts):
    """Pure source-model derivation; consumes every original node and raw edge."""
    collector = derivation.Collector([row['id'] for row in plan['graphs']], primitive_facts=facts)
    for row in plan['graphs']:
        graph = inputs.read_graph(row['spec'])
        raw = inputs.edge_export_input(row['edges'], row['spec']['root'], plan['pins'], plan['pins'])
        require(row['edges']['nodeSlots'] == graph['nodeSlots'], 'raw edge/node slot identity')
        collector.add_graph(graph, raw, row['edges']['labeledEdges'])
        del graph, raw
    result = collector.finish()
    if plan['arm'] == 'B':
        require(all(count > 0 for counts in result['fullProjectionBindings'].values() for count in counts.values()),
                'candidate full projections must exercise actual declared bindings in both target graphs for every case')
    return result


def verify_phases(plan):
    out = Path(plan['output']); refs = []
    for name, argv, timeout in commands(plan):
        path = out/name/'record.json'; phase = artifacts.check_phase(path, argv, out)
        require(phase['name'] == name and phase['timeoutSeconds'] == timeout, 'exact primitive owned phase')
        refs.append(artifacts.ref(path))
    return refs


def run(producer_root, core_root, arm, output):
    plan = bind(producer_root, core_root, arm, output)
    out = Path(plan['output']); out.mkdir(parents=True, exist_ok=False); (out/'classes').mkdir()
    common.save(out/'plan.json', plan)
    record = {'schema': 'graphite.jvm-raw-oracle-record.v1', 'status': 'FAIL', 'errors': [], 'phases': [],
              'plan': artifacts.ref(out/'plan.json'), 'unissued': [name for name, _, _ in commands(plan)],
              **{key: False for key in FALSE_CLAIMS}}
    handlers = {}
    def interrupted(signum, frame): raise InterruptedError('signal '+str(signum))
    try:
        for sig in (signal.SIGINT, signal.SIGTERM): handlers[sig] = signal.signal(sig, interrupted)
        for name, argv, timeout in commands(plan):
            if name == 'primitive-facts': common.save(out/'requests.json', collect_requests(plan))
            record['unissued'].remove(name)
            producer.phase(name, argv, out, environment(plan), out, timeout)
            record['phases'].append(artifacts.ref(out/name/'record.json'))
        require(verify_phases(plan) == record['phases'], 'actual two-phase primitive execution')
        result = derive(plan, consume_facts(plan))
        (out/'universes').mkdir()
        record['universes'] = {}
        for name, universe in result.pop('universes').items():
            path = out/'universes'/(name+'.json'); common.save(path, universe)
            record['universes'][name] = artifacts.ref(path)
        record['derivation'] = result
        record['request'] = artifacts.ref(out/'requests.json'); record['primitiveFacts'] = artifacts.ref(out/'primitive-facts.json')
        record['status'] = PASS
    except BaseException as error: record['errors'].append(repr(error))
    finally:
        for sig in handlers: signal.signal(sig, signal.SIG_IGN)
        try:
            artifacts.verify_pins(plan['pins'])
            record['finalIdentity'] = 'PASS'
            record['outputFiles'] = producer.inventory(out)
        except BaseException as error:
            record['errors'].append('final input/output verification: '+repr(error)); record['finalIdentity'] = 'FAIL'
        if record['errors']: record['status'] = 'FAIL'
        common.save(out/'record.json', record)
        for sig, handler in handlers.items(): signal.signal(sig, handler)
    return record


def replay(output):
    """Independently replay actual upstream, owned phases and all raw derivation."""
    out = Path(output).resolve(); record = common.read(out/'record.json'); plan = common.read(out/'plan.json')
    require(record['schema'] == 'graphite.jvm-raw-oracle-record.v1' and record['status'] == PASS and
            record['errors'] == [] and record['unissued'] == [] and record['finalIdentity'] == 'PASS' and
            all(record[key] is False for key in FALSE_CLAIMS), 'complete scoped raw derivation record')
    require(plan['output'] == str(out) and record['plan'] == artifacts.ref(out/'plan.json'), 'actual derivation plan bytes')
    actual = bind(plan['producerRoot'], plan['coreRoot'], plan['arm'], out)
    require(common.typed(actual) == common.typed(plan), 'raw derivation plan differs from upstream evidence')
    require(verify_phases(plan) == record['phases'], 'actual owned phase linkage')
    require(record['request'] == artifacts.ref(out/'requests.json') and
            record['primitiveFacts'] == artifacts.ref(out/'primitive-facts.json'), 'primitive request/result bytes')
    # Recollect the complete deduplicated primitive request from all raw inputs;
    # accepting only an arbitrary subset would not establish its source closure.
    require(common.typed(collect_requests(plan)) == common.typed(common.read(out/'requests.json')),
            'complete raw-input primitive requests differ')
    result = derive(plan, consume_facts(plan)); universes = result.pop('universes')
    require(common.typed(result) == common.typed(record['derivation']), 'complete per-graph derivation counts')
    require(set(record['universes']) == set(universes) and
            {p.name for p in (out/'universes').iterdir()} == {name+'.json' for name in universes}, 'closed exact34 outputs')
    for name, expected in universes.items():
        path = out/'universes'/(name+'.json')
        require(record['universes'][name] == artifacts.ref(path) and
                common.typed(common.read(path)) == common.typed(expected), 'actual independently derived universe: '+name)
    inventory = producer.inventory(out); inventory.pop(str(out/'record.json'), None)
    require(inventory == record['outputFiles'], 'closed raw derivation output inventory')
    artifacts.verify_pins(plan['pins'])
    return {'status': PASS, 'record': artifacts.ref(out/'record.json'), 'universes': record['universes'],
            'actualPrimitiveExecutionVerified': True, 'completeRawDerivationReplayed': True,
            'remainingAuthority': 'Independent applicability audit of JVM source-model rules to this exact writer/runtime revision.',
            **{key: False for key in FALSE_CLAIMS}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--producer-root', type=Path); parser.add_argument('--core-root', type=Path)
    parser.add_argument('--arm', choices=('C', 'B')); parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--replay-only', action='store_true')
    args = parser.parse_args()
    if args.replay_only:
        print(json.dumps(replay(args.output))); return 0
    require(all((args.producer_root, args.core_root, args.arm)), 'actual producer/core arm required')
    record = run(args.producer_root, args.core_root, args.arm, args.output)
    print(json.dumps({'status': record['status'], 'errors': record['errors'], 'output': str(args.output.resolve())}))
    return 0 if record['status'] == PASS else 1


if __name__ == '__main__': raise SystemExit(main())
