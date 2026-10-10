#!/usr/bin/env python3
"""Execute the versioned native query catalog on an audited, exact-revision server.

One full response per case is a correctness check, not a latency distribution or
load test. Keep this stage separate from the continuous pressure measurement.
"""
import argparse
from dataclasses import dataclass
import os
from pathlib import Path
import signal
import socket
import struct
import subprocess
import sys

sys.dont_write_bytecode = True
import multigraph_pressure as common
import native_portable_oracles as portable
import audit_native_pressure_artifacts as artifacts
from prepare_native_pressure_plan import preparation_control_pins

SCHEMA = 'graphite.native-query-correctness.v1'
PASS = 'PASS_ALL39_NATIVE_RESPONSES_FRESH_PRESSURE_PENDING'
JVM_SCHEMA = 'graphite.jvm-query-correctness.v1'
JVM_PASS = 'PASS_ALL34_JVM_RESPONSES_FRESH_PRESSURE_PENDING'
require = common.require


def persisted_readiness(fixture):
    """Read saved counts before starting the server; never learn them from HTTP."""
    graphs = []
    for graph in fixture['graphs']:
        root = Path(graph['path'])
        properties = {}
        for line in (root / 'forward.properties').read_text().splitlines():
            if not line or line.startswith(('#', '!')):
                continue
            key, value = line.split('=', 1)
            require(key not in properties, 'duplicate persisted property')
            properties[key] = value
        capacity, edges = int(properties['nodes']), int(properties['arcs'])
        with (root / 'graph.nodedata').open('rb') as stream:
            node_header = stream.read(8)
        require(len(node_header) == 8 and node_header[:3] == b'GRN' and node_header[3] in (1, 2, 3),
                'persisted actual-node-count header')
        # BVGraph nodes is maxNodeId + 1, including gaps between shards. The
        # server reports the number of actual records from graph.nodedata.
        nodes = struct.unpack('>i', node_header[4:])[0]
        with (root / 'graph.metadata').open('rb') as stream:
            header = stream.read(8)
        require(len(header) == 8 and header[:3] == b'GRM' and header[3] in (1, 2, 3),
                'persisted method-count header')
        methods = struct.unpack('>i', header[4:])[0]
        require(nodes == graph['nodes'] and 0 < nodes <= capacity and edges >= 0 and methods >= 0 and
                type(graph['callSites']) is int and graph['callSites'] >= 0, 'persisted readiness counts')
        graphs.append({'id': graph['id'], 'path': str(root), 'loadMode': 'MAPPED',
                       'nodes': nodes, 'edges': edges, 'methods': methods, 'callSites': graph['callSites']})
    require(len(graphs) == len({graph['id'] for graph in graphs}) == 64, 'complete readiness scope')
    return {'loadMode': 'MAPPED', 'count': 64, 'graphs': sorted(graphs, key=lambda g: g['id']),
            'totals': {key: sum(graph[key] for graph in graphs)
                       for key in ('nodes', 'edges', 'methods', 'callSites')}}


def verify_closure(plan):
    artifacts.verify_pins(plan['pins'])
    fixture = common.read(plan['fixtureManifest']['path'])
    require(artifacts.inventory(Path(plan['fixtureManifest']['path']).parent / 'graphs') == fixture['files'],
            'closed current graph directory')
    require(artifacts.inventory(Path(plan['runtimeManifest']['path']).parent / 'runtime') ==
            common.read(plan['runtimeManifest']['path'])['files'], 'closed current runtime directory')


def prepare(audit_path, inputs_path, revision, role, output, port):
    audit_path, inputs_path, output = (Path(p).resolve() for p in (audit_path, inputs_path, output))
    audit = common.read(audit_path)
    require(audit['schema'] == 'graphite.native-independent-artifact-audit.v1' and
            audit['status'] == artifacts.STATUS and audit['revision'] == revision and audit['role'] == role,
            'completed independent artifact audit for requested revision/role')
    require(type(port) is int and 1024 <= port <= 65535, 'server port')
    bundle = portable.load(source_inputs=common.read(inputs_path))
    pins = dict(audit['pins'])
    for path, digest in {**bundle['pins'], **preparation_control_pins(),
                         str(audit_path): common.sha(audit_path),
                         str(inputs_path): common.sha(inputs_path)}.items():
        require(path not in pins or pins[path] == digest, 'conflicting actual input identity')
        pins[path] = digest
    runtime_ref, fixture_ref = audit['runtimeManifest'], audit['fixtureManifest']
    for ref in (runtime_ref, fixture_ref):
        require(pins.get(ref['path']) == ref['sha256'] == common.sha(ref['path']), 'actual artifact manifest')
    runtime, fixture = common.read(runtime_ref['path']), common.read(fixture_ref['path'])
    require(runtime['revision'] == fixture['writerRevision'] == revision and
            audit['binarySha256'] == runtime['sha256'] == pins.get(runtime['binary']), 'exact runtime/writer revision')
    graph_scope = [{'id': graph['id'], 'path': graph['path']} for graph in fixture['graphs']]
    require(graph_scope == audit['graphs'], 'exact audited graph scope')
    require([graph['id'] for graph in graph_scope] == common.read(portable.CATALOG)['engines']['native']['graphIds'],
            'catalog registry order')
    side = 'C' if revision == portable.ACCEPTED else 'B'
    # The expectation variant does not assign its historical source revision to
    # the runtime. A fresh response must pass against these existing expectations.
    plan = {'schema': 'graphite.native-query-correctness-plan.v1', 'revision': revision, 'role': role,
            'artifactAudit': artifacts.ref(audit_path), 'runtimeManifest': runtime_ref,
            'sourceInputs': artifacts.ref(inputs_path),
            'fixtureManifest': fixture_ref, 'expectationVariant': side,
            'historicalExpectationRevisions': bundle['sourceRevisions'],
            'pins': pins, 'graphs': graph_scope, 'cases': bundle['cases'],
            'port': port, 'data': str(output / 'data'),
            'limits': {'requestSeconds': 240, 'bodyBytes': 64 * 1024 * 1024, 'readinessSeconds': 900},
            'performanceAcceptance': False}
    verify_closure(plan)
    plan['readiness'] = {'expected': persisted_readiness(fixture)}
    plan['argv'] = [runtime['binary'], 'serve', '--data', plan['data'], '--port', str(port),
                    '--load-mode', 'MAPPED', '--max-concurrent-cypher', '4',
                    '--cypher-max-timeout-ms', '240000', *common.graph_arguments(graph_scope)]
    return plan



@dataclass(frozen=True)
class _ExecutionContract:
    """Trusted preparation supplies authority; this contract fixes execution scope.

    A contract is not an oracle-authority receipt. A future JVM wrapper must
    independently bind its own raw derivation and source/runtime applicability
    before invoking the shared lifecycle; no JVM public entry point exists here.
    """
    engine: str
    plan_schema: str
    record_schema: str
    pass_status: str
    expected_case_ids: tuple
    verify_closure: object


def _check_execution_contract(plan, contract):
    if contract.engine == 'native':
        catalog = common.read(portable.CATALOG)['engines']['native']
        definitions = catalog['cases']; graph_ids = catalog['graphIds']
        expected = ('graphite.native-query-correctness-plan.v1', SCHEMA, PASS, 39)
        kinds = {'native-full-json-sha256-v1', 'native-complete-legal-limit-multiset-v1'}
    elif contract.engine == 'jvm':
        import jvm_pressure_oracles as jvm
        graph_ids = [graph['id'] for graph in plan['graphs']]
        definitions = jvm.cases(graph_ids)
        expected = ('graphite.jvm-query-correctness-plan.v1', JVM_SCHEMA, JVM_PASS, 34)
        kinds = {'jvm-complete-legal-limit-multiset-v1'}
    else:
        raise ValueError('known correctness execution engine required')
    require((contract.plan_schema, contract.record_schema, contract.pass_status,
             len(contract.expected_case_ids)) == expected and
            contract.expected_case_ids == tuple(case['id'] for case in definitions),
            'fixed engine schema/status and exact complete case IDs')
    require(callable(contract.verify_closure), 'explicit trusted closure verifier')
    require(plan['schema'] == contract.plan_schema and
            [graph['id'] for graph in plan['graphs']] == graph_ids and
            plan['performanceAcceptance'] is False, 'validated plan engine/scope')
    require(plan['limits'] == {'requestSeconds': 240, 'bodyBytes': 64 * 1024 * 1024,
                               'readinessSeconds': 900}, 'unchanged correctness resource limits')
    require(tuple(case['id'] for case in plan['cases']) == contract.expected_case_ids,
            'exact complete ordered correctness cases')
    for case, definition in zip(plan['cases'], definitions):
        require(all(common.typed(case[key]) == common.typed(definition[key])
                    for key in ('request', 'targetGraphIds')), 'exact correctness request/scope')
        require(case['oracleByArm'][plan['expectationVariant']]['kind'] in kinds,
                'same-engine correctness oracle required')


def execute(plan, output):
    """The public entry remains Native39 only, with its existing closure check."""
    ids = tuple(case['id'] for case in common.read(portable.CATALOG)['engines']['native']['cases'])
    contract = _ExecutionContract('native', 'graphite.native-query-correctness-plan.v1',
                                  SCHEMA, PASS, ids, verify_closure)
    return _execute_validated(plan, output, contract)


def _execute_validated(plan, output, contract):
    _check_execution_contract(plan, contract)
    output = Path(output).resolve()
    require(output.is_dir() and not list(output.iterdir()), 'fresh empty correctness output')
    require(Path(plan['data']) == output / 'data', 'owned data path')
    common.save(output / 'plan.json', plan)
    record = {'schema': contract.record_schema, 'status': 'RUNNING', 'revision': plan['revision'], 'role': plan['role'],
              'plan': artifacts.ref(output / 'plan.json'), 'argv': plan['argv'], 'responses': [], 'errors': [],
              'performanceAcceptance': False, 'completeSemanticEquivalence': False}
    proc = transport = None
    handlers = {}
    def interrupted(signum, frame):
        raise InterruptedError('signal ' + str(signum))
    try:
        for sig in (signal.SIGINT, signal.SIGTERM):
            handlers[sig] = signal.signal(sig, interrupted)
        contract.verify_closure(plan)
        validator = common.compile_response_validator(plan['cases'], plan['expectationVariant'])
        with socket.socket() as probe:
            probe.bind(('127.0.0.1', plan['port']))
        with common.deferred_signals():
            with (output / 'stdout.log').open('x') as stdout, (output / 'stderr.log').open('x') as stderr:
                proc = subprocess.Popen(plan['argv'], env=common.clean_env(), stdout=stdout,
                                        stderr=stderr, start_new_session=True)
            common.save(output / 'owner.json', {'runnerPid': os.getpid(), 'group': proc.pid, 'argv': plan['argv']})
        arm = {'graphs': plan['graphs'], 'readiness': plan['readiness']}
        attempts = output / 'readiness-attempts'
        attempts.mkdir()
        raw = common.readiness(plan['port'], arm, output / 'data', plan['limits']['readinessSeconds'], proc, attempts)
        (output / 'readiness.body').write_bytes(raw)
        record['readinessBody'] = artifacts.ref(output / 'readiness.body')
        common.validate_readiness_body(raw, arm, output / 'data')
        record['readiness'] = common.parse(raw)
        transport = common.HTTPTransport(plan['port'], plan['limits'])
        with (output / 'responses.jsonl').open('x') as journal:
            for case in plan['cases']:
                require(proc.poll() is None, 'server exited before query')
                response = {'id': case['id'], 'targetGraphIds': case['targetGraphIds']}
                body = output / (case['id'] + '.body')
                try:
                    transport.fetch(case, body, response)
                    response['validation'] = validator(body.read_bytes(), case)
                    response['status'] = 'PASS'
                except BaseException as error:
                    response.update(status='FAIL', error=repr(error))
                    raise
                finally:
                    response['body'] = artifacts.ref(body) if body.exists() else None
                    record['responses'].append(response)
                    journal.write(common.canonical(response).decode() + '\n')
                    journal.flush()
        require(proc.poll() is None, 'server exited after final query')
    except BaseException as error:
        record['errors'].append(repr(error))
    finally:
        for sig in handlers:
            signal.signal(sig, signal.SIG_IGN)
        if transport is not None:
            try:
                transport.close_all()
            except BaseException as error:
                record['errors'].append('transport cleanup: ' + repr(error))
        try:
            record['cleanup'] = common.stop_owned(proc, None, None)
            require(not record['cleanup']['after'] and not record['cleanup']['errors'], 'owned server cleanup')
        except BaseException as error:
            record['errors'].append('cleanup: ' + repr(error))
        try:
            contract.verify_closure(plan)
            require(common.sha(output / 'plan.json') == record['plan']['sha256'], 'execution plan changed')
            record['finalIdentity'] = 'PASS'
        except BaseException as error:
            record['finalIdentity'] = 'FAIL'
            record['errors'].append('final identity: ' + repr(error))
        complete = (tuple(row['id'] for row in record['responses']) == contract.expected_case_ids and
                    all(row['status'] == 'PASS' for row in record['responses']))
        record['status'] = contract.pass_status if complete and not record['errors'] else 'FAIL'
        common.save(output / 'record.json', record)
        for sig, handler in handlers.items():
            signal.signal(sig, handler)
    return record


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for key in ('artifact-audit', 'source-inputs', 'revision', 'role', 'output'):
        parser.add_argument('--' + key, required=True)
    parser.add_argument('--port', type=int, required=True)
    args = parser.parse_args()
    output = Path(args.output).resolve()
    output.mkdir(parents=True, exist_ok=False)
    try:
        plan = prepare(args.artifact_audit, args.source_inputs, args.revision, args.role, output, args.port)
    except (OSError, ValueError, KeyError, TypeError) as error:
        common.save(output / 'preparation-failure.json', {'status': 'FAIL', 'error': repr(error)})
        return 1
    return 0 if execute(plan, output)['status'] == PASS else 1


if __name__ == '__main__':
    sys.exit(main())
