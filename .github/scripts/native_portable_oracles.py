#!/usr/bin/env python3
"""Load versioned expectations without consulting a local execution directory.

These are historical correctness authorities, not evidence that a newly built
runtime passed. Fresh graph/build audits and HTTP comparisons remain required.
"""
import argparse
from pathlib import Path
import sys

sys.dont_write_bytecode = True
import multigraph_pressure as pressure
from native_legal_response import CompiledDataflowOracle

SCRIPTS = Path(__file__).resolve().parent
DEFAULT_INDEX = SCRIPTS / 'fixtures/native-pressure-oracles/index.json'
CATALOG = SCRIPTS / 'fixtures/multigraph-pressure-cases.json'
ACCEPTED = '4f2ccf33b969e684972e56b5e810034e6e67c1b3'
require = pressure.require


def jar_identity(jars):
    require(type(jars) is list and len(jars) == 4, 'four source JARs required')
    identity = {item['corpus']: item['sha256'] for item in jars}
    require(set(identity) == {'android', 'tika', 'hive', 'kotlin-compiler'} and
            all(pressure.valid_digest(value) for value in identity.values()), 'source JAR identities')
    return identity


def load(index_path=DEFAULT_INDEX, catalog_path=CATALOG, source_inputs=None):
    index_path, catalog_path = Path(index_path), Path(catalog_path)
    root = index_path.resolve().parent
    index = pressure.read(index_path)
    catalog = pressure.read(catalog_path)['engines']['native']
    pins = {str(index_path.resolve()): pressure.sha(index_path),
            str(catalog_path.resolve()): pressure.sha(catalog_path)}
    require(index['schema'] == 'graphite.native-portable-oracles.v1', 'portable oracle schema')
    require(index['sourceRevisions']['C'] == ACCEPTED and
            set(index['sourceRevisions']) == {'C', 'B'}, 'historical source revisions')
    require(all(index[key] is False for key in
                ('performanceAcceptance', 'completeSemanticEquivalence', 'ciAcceptance')),
            'historical expectations cannot declare acceptance')
    require(index['graphIds'] == catalog['graphIds'], 'loaded graph identity/order')
    if source_inputs is not None:
        require(source_inputs['schema'] == 'graphite.fixture64-source-inputs.v1', 'source input schema')
        require(jar_identity(source_inputs['jars']) == jar_identity(index['sourceJars']),
                'portable expectations require the same source JARs')
    else:
        jar_identity(index['sourceJars'])

    def blob(ref):
        require(set(ref) == {'path', 'sha256'} and pressure.valid_digest(ref['sha256']),
                'complete portable blob reference')
        require(ref['path'] == 'blobs/' + ref['sha256'] + '.json', 'content-addressed blob path')
        path = root / ref['path']
        require(not path.is_symlink() and path.resolve().is_relative_to(root), 'portable blob containment')
        require(pressure.sha(path) == ref['sha256'], 'portable blob bytes changed')
        pins[str(path.resolve())] = ref['sha256']
        return pressure.read(path)

    controls = {case['id']: case for case in catalog['cases']}
    require(len(controls) == len(catalog['cases']), 'unique catalog cases')
    require(len(index['cases']) == len(controls) and
            {case['id'] for case in index['cases']} == set(controls), 'complete unique oracle catalog')
    runtime_cases = []
    for case in index['cases']:
        control = controls[case['id']]
        require(all(case[key] == control[key] for key in
                    ('request', 'querySha256', 'targetGraphIds', 'oracleKind')), 'exact query and scope')
        require(case['querySha256'] == pressure.digest_bytes(case['request']['body']['query'].encode()),
                'query byte identity')
        require(set(case['expected']) == {'base', 'candidate'}, 'both expected response variants')
        runtime = dict(control, oracleByArm={})
        for arm, side in [('C', 'base'), ('B', 'candidate')]:
            expected = case['expected'][side]
            value, audit = blob(expected['payload']), blob(expected['authority']['audit'])
            authority = expected['authority']
            require(authority['revision'] == index['sourceRevisions'][arm], 'historical arm identity')
            require(type(expected['rows']) is int and expected['rows'] >= 0, 'exact expected row count')
            require(pressure.digest_bytes(pressure.canonical(value)) == expected['digest'],
                    'full expected payload digest')
            shape = {key: expected[key] for key in ('digest', 'rows')}
            if authority['kind'] == 'independent-completed-schema-oracle-v1':
                require(case['id'] == 'schema-key-histogram' and arm == 'B' and
                        audit['status'] == 'PASS_INDEPENDENT_COMPLETED_SCHEMA_OUTPUT_AUDIT' and
                        audit['expectedCanonicalSha256'] == expected['digest'] and
                        audit['completeOrderedResponseAndProvenanceEqual'] is True and
                        audit['completeRows'] == expected['rows'], 'explicit independently completed schema authority')
            else:
                require(shape == control['expected'][side], 'predetermined catalog expectation')
                kind = ('independent-persisted-graph-oracle-v1' if 'sourceKind' in control
                        else 'original73-predetermined-digest-v1')
                require(authority['kind'] == kind, 'expected authority kind')
                if kind == 'independent-persisted-graph-oracle-v1':
                    statuses = {
                        'PASS_FOUR_COMPLETE_NATIVE_FEATURE_RESPONSES_INDEPENDENT_RAW_AUDIT',
                        'PASS_TWENTY_COMPLETE_NATIVE_DISCOVERY_ROUTING_RESPONSES_INDEPENDENT_RAW_AUDIT',
                        'PASS_SIXTEEN_COMPLETE_NATIVE_SLOW_NODE_RESPONSES_INDEPENDENT_RAW_AUDIT',
                        'PASS_EIGHT_COMPLETE_NATIVE_DATAFLOW_LEGAL_RESPONSES_INDEPENDENT_RAW_AUDIT',
                    }
                    require(audit['status'] in statuses and audit['performanceClaim'] is False,
                            'historical independent response audit scope')
                    proofs = [proof for proof in audit['proofs']
                              if proof['arm'] == arm and proof['case'] == case['id']]
                    require(len(proofs) == 1 and proofs[0]['rows'] == expected['rows'] and
                            proofs[0]['expectedSha256'] == expected['payload']['sha256'] and
                            proofs[0]['bodySha256'] == authority['observedBodySha256'],
                            'historical independent expected/response binding')
            oracle = dict(shape, kind=case['oracleKind'], proof={
                'path': str((root / expected['payload']['path']).resolve()),
                'sha256': expected['payload']['sha256']})
            if case['oracleKind'] == 'native-complete-legal-limit-multiset-v1':
                compiled = CompiledDataflowOracle(value, control)
                require(type(expected['rows']) is int and expected['rows'] == min(compiled.total, 50),
                        'legal LIMIT count')
                oracle.update(value=value, valueProof=oracle['proof'])
            else:
                require(case['oracleKind'] == 'native-full-json-sha256-v1', 'known oracle kind')
                pressure.validate_response(pressure.canonical(value),
                                           {'oracleByArm': {arm: oracle}}, arm)
            runtime['oracleByArm'][arm] = oracle
        runtime_cases.append(runtime)
    return {'cases': runtime_cases, 'pins': pins, 'sourceRevisions': index['sourceRevisions'],
            'limitations': index['limitations']}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-inputs', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    bundle = load(source_inputs=pressure.read(args.source_inputs))
    bundle['pins'][str(args.source_inputs.resolve())] = pressure.sha(args.source_inputs)
    pressure.save(args.output, {
        'schema': 'graphite.native-portable-oracle-validation.v1',
        'status': 'PASS_VERSIONED_EXPECTATIONS_FRESH_EXECUTION_PENDING',
        'caseCount': len(bundle['cases']), 'pins': bundle['pins'],
        'sourceRevisions': bundle['sourceRevisions'], 'limitations': bundle['limitations'],
        'performanceAcceptance': False, 'completeSemanticEquivalence': False, 'ciAcceptance': False,
    })


if __name__ == '__main__':
    main()
