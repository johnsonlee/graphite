#!/usr/bin/env python3
"""Recheck raw native correctness execution without launching a server."""
import argparse
import json
from pathlib import Path
import struct
import sys

sys.dont_write_bytecode = True
import multigraph_pressure as common
import native_portable_oracles as portable
import audit_native_pressure_artifacts as artifacts
from prepare_native_pressure_plan import preparation_control_pins

require = common.require
STATUS = 'PASS_ALL39_NATIVE_RESPONSES_INDEPENDENT_RAW_AUDIT'


def audit(directory):
    root = Path(directory).resolve()
    plan, record = common.read(root / 'plan.json'), common.read(root / 'record.json')
    require(plan['schema'] == 'graphite.native-query-correctness-plan.v1' and
            record['schema'] == 'graphite.native-query-correctness.v1' and
            record['status'] == 'PASS_ALL39_NATIVE_RESPONSES_FRESH_PRESSURE_PENDING' and
            record['errors'] == [] and record['finalIdentity'] == 'PASS', 'complete successful execution')
    require(record['plan'] == artifacts.ref(root / 'plan.json') and
            record['revision'] == plan['revision'] and record['role'] == plan['role'], 'actual execution plan')
    require(record['performanceAcceptance'] is False and record['completeSemanticEquivalence'] is False and
            plan['performanceAcceptance'] is False, 'correctness-only scope')
    artifact = common.pinned_authority_metadata(plan, plan['artifactAudit'])
    require(artifact['schema'] == 'graphite.native-independent-artifact-audit.v1' and
            artifact['status'] == artifacts.STATUS and artifact['revision'] == plan['revision'] and
            artifact['role'] == plan['role'], 'actual independent artifact authority')
    for key in ('runtimeManifest', 'fixtureManifest'):
        require(plan[key] == artifact[key], 'actual manifest reference')
    runtime = common.pinned_authority_metadata(plan, plan['runtimeManifest'])
    fixture = common.pinned_authority_metadata(plan, plan['fixtureManifest'])
    inputs = common.pinned_authority_metadata(plan, plan['sourceInputs'])
    bundle = portable.load(source_inputs=inputs)
    controls = preparation_control_pins()
    for path, digest in {**artifact['pins'], **bundle['pins'], **controls}.items():
        require(plan['pins'].get(path) == digest, 'complete reviewed execution input closure')
    require(runtime['revision'] == fixture['writerRevision'] == plan['revision'] and
            runtime['sha256'] == artifact['binarySha256'] == plan['pins'].get(runtime['binary']),
            'actual executable and writer revision')
    graphs = [{'id': graph['id'], 'path': graph['path']} for graph in fixture['graphs']]
    require(plan['graphs'] == artifact['graphs'] == graphs and len(graphs) == 64, 'exact loaded graph scope')
    variant = 'C' if plan['revision'] == portable.ACCEPTED else 'B'
    require(plan['expectationVariant'] == variant and
            plan['historicalExpectationRevisions'] == bundle['sourceRevisions'] and
            plan['cases'] == bundle['cases'] and len(bundle['cases']) == 39, 'predetermined full query catalog')
    require(type(plan['port']) is int and 1024 <= plan['port'] <= 65535 and
            plan['data'] == str(root / 'data') and
            plan['limits'] == {'requestSeconds': 240, 'bodyBytes': 64 * 1024 * 1024, 'readinessSeconds': 900},
            'owned bounded server configuration')
    argv = [runtime['binary'], 'serve', '--data', str(root / 'data'), '--port', str(plan['port']),
            '--load-mode', 'MAPPED', '--max-concurrent-cypher', '4', '--cypher-max-timeout-ms', '240000',
            *common.graph_arguments(graphs)]
    owner = common.read(root / 'owner.json')
    require(plan['argv'] == record['argv'] == owner['argv'] == argv, 'actual owned server command')
    cleanup = record['cleanup']
    require(type(owner['group']) is int and owner['group'] > 1 and type(owner['runnerPid']) is int and
            owner['runnerPid'] > 1 and cleanup['group'] == owner['group'] and cleanup['after'] == [] and
            cleanup['errors'] == [] and cleanup['exit'] in (0, 143, -15), 'owned terminal server cleanup')
    raw = (root / 'readiness.body').read_bytes()
    require(record['readinessBody'] == artifacts.ref(root / 'readiness.body') and
            common.parse(raw) == record['readiness'], 'raw readiness response')
    common.validate_readiness_body(raw, {'graphs': graphs, 'readiness': plan['readiness']}, root / 'data')
    # Reconstruct saved statistics here rather than trusting the execution plan's
    # expected envelope or the observed server response.
    expected_graphs = []
    for graph in fixture['graphs']:
        graph_root = Path(graph['path'])
        props = {}
        for line in (graph_root / 'forward.properties').read_text().splitlines():
            if not line or line.startswith(('#', '!')):
                continue
            key, value = line.split('=', 1)
            require(key not in props, 'unique saved graph property')
            props[key] = value
        with (graph_root / 'graph.metadata').open('rb') as stream:
            header = stream.read(8)
        require(len(header) == 8 and header[:3] == b'GRM' and 1 <= header[3] <= 3, 'saved metadata version')
        with (graph_root / 'graph.nodedata').open('rb') as stream:
            node_header = stream.read(8)
        require(len(node_header) == 8 and node_header[:3] == b'GRN' and 1 <= node_header[3] <= 3,
                'saved node data version')
        nodes = struct.unpack('>i', node_header[4:])[0]
        edges, methods = int(props['arcs']), struct.unpack('>i', header[4:])[0]
        require(nodes == graph['nodes'] > 0 and nodes <= int(props['nodes']) and edges >= 0 and methods >= 0,
                'saved counts')
        expected_graphs.append({'id': graph['id'], 'path': graph['path'], 'loadMode': 'MAPPED',
                                'nodes': nodes, 'edges': edges, 'methods': methods, 'callSites': graph['callSites']})
    expected = {'loadMode': 'MAPPED', 'count': 64, 'graphs': sorted(expected_graphs, key=lambda g: g['id']),
                'totals': {key: sum(g[key] for g in expected_graphs)
                           for key in ('nodes', 'edges', 'methods', 'callSites')}}
    require(common.typed(plan['readiness']['expected']) == common.typed(expected), 'independent saved readiness counts')
    journal = [common.parse(line) for line in (root / 'responses.jsonl').read_text().splitlines()]
    require(journal == record['responses'] and len(journal) == len(bundle['cases']) == 39, 'complete response journal')
    validate = common.compile_response_validator(bundle['cases'], variant)
    receipts = []
    evidence = {str(root / name): common.sha(root / name) for name in
                ('plan.json', 'record.json', 'owner.json', 'readiness.body', 'responses.jsonl', 'stdout.log', 'stderr.log')}
    for case, response in zip(bundle['cases'], journal):
        path = root / (case['id'] + '.body')
        require(response['id'] == case['id'] and response['targetGraphIds'] == case['targetGraphIds'] and
                response['status'] == 'PASS' and response['httpStatus'] == 200 and
                response['completeBody'] is True and response['deadlineExpired'] is False and
                response['endpoint'] == case['request']['endpoint'] and
                response['requestBodySha256'] == common.digest_bytes(common.canonical(case['request']['body'])) and
                response['body'] == artifacts.ref(path), 'complete actual request and response bytes')
        result = validate(path.read_bytes(), case)
        require(result == response['validation'], 'independent complete response result')
        evidence[str(path)] = response['body']['sha256']
        receipts.append({'case': case['id'], 'body': response['body'], 'targetGraphIds': case['targetGraphIds'], **result})
    for path in (root / 'readiness-attempts').iterdir():
        require(path.is_file() and not path.is_symlink(), 'readiness attempt evidence')
        evidence[str(path)] = common.sha(path)
    artifacts.verify_pins({**plan['pins'], **evidence})
    require(artifacts.inventory(Path(plan['fixtureManifest']['path']).parent / 'graphs') == fixture['files'] and
            artifacts.inventory(Path(plan['runtimeManifest']['path']).parent / 'runtime') == runtime['files'],
            'final closed graph/runtime inventories')
    return {'schema': 'graphite.native-query-correctness-audit.v1', 'status': STATUS,
            'revision': plan['revision'], 'role': plan['role'], 'artifactAudit': plan['artifactAudit'],
            'plan': artifacts.ref(root / 'plan.json'), 'record': artifacts.ref(root / 'record.json'),
            'readiness': record['readiness'], 'cases': receipts, 'pins': {**plan['pins'], **evidence},
            'performanceAcceptance': False, 'completeSemanticEquivalence': False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', required=True)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    require(not Path(args.output).exists(), 'fresh audit output')
    try:
        result = audit(args.directory)
    except (OSError, ValueError, KeyError, TypeError) as error:
        common.save(args.output, {'status': 'FAIL', 'error': repr(error), 'performanceAcceptance': False})
        return 1
    common.save(args.output, result)
    return 0


if __name__ == '__main__':
    sys.exit(main())
