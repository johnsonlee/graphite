#!/usr/bin/env python3
"""Bind actual CI native identities to fixed pressure controls; missing producers stay UNAVAILABLE.

This command never builds a runtime, creates graphs, starts a server or measures performance.
A complete producer packet is optional; current A/B provenance and candidate-only fixtures cannot
be relabeled as the accepted4f corpus. All published output directories must be fresh.
"""
import argparse
import hashlib
import json
import re
from pathlib import Path
import multigraph_pressure as pressure

ACCEPTED = '4f2ccf33b969e684972e56b5e810034e6e67c1b3'
SCRIPTS = Path(__file__).resolve().parent
CATALOG = SCRIPTS / 'fixtures/multigraph-pressure-cases.json'


class MissingAuthority(ValueError):
    """Valid partial artifacts do not supply an unimplemented or unfinished proof."""


def require(value, message):
    if not value:
        raise ValueError(message)


def inspect_available(provenance, fixture, base_sha, candidate_sha):
    """Inspect actual existing CI outputs without claiming a successful C build or equivalence."""
    result = {'acceptedRevision': ACCEPTED, 'baseRevision': base_sha, 'candidateRevision': candidate_sha,
              'pins': {}, 'available': [], 'missing': []}
    for name, file in [('AB-native-build-provenance', provenance), ('B-fixture64-production', fixture)]:
        if not file.is_file():
            result['missing'].append(name)
            continue
        result['pins'][str(file.resolve())] = pressure.sha(file)
        value = pressure.read(file)
        if name == 'AB-native-build-provenance':
            for folder, expected in [('base', base_sha), ('candidate', candidate_sha)]:
                matches = [c for c in value['commands'] if c['command'] ==
                           ['git', '-C', folder, 'rev-parse', 'HEAD', 'HEAD:backend', 'HEAD:cli']]
                require(len(matches) == 1 and matches[0]['output'].splitlines()[0] == expected,
                        'actual ' + folder + ' revision mismatch')
            for path, digest in value['files'].items():
                if digest is not None:
                    require(pressure.sha(path) == digest, 'actual archived identity changed: ' + path)
                    result['pins'][str(Path(path).resolve())] = digest
        else:
            require(value['schema'] == 'graphite-shared-fixture64-v1' and value['complete'] is True and
                    value['candidateSha'] == candidate_sha, 'actual candidate fixture identity')
            for key, relative in [('manifestSha256', 'graphs/graphs.tsv'),
                                  ('provenanceSha256', 'graphs/fixture-provenance.tsv'),
                                  ('receiptSha256', 'fixture-reproducibility.json')]:
                path = fixture.parent / relative
                require(pressure.sha(path) == value[key], 'candidate fixture provenance changed: ' + relative)
                result['pins'][str(path.resolve())] = value[key]
        result['available'].append(name)
    return result


def normalize_source_files(source):
    root = Path(source['root']).resolve()
    result = {}
    for name, digest in source['files'].items():
        lexical = Path(name)
        path = lexical if lexical.is_absolute() else root / lexical
        require('..' not in lexical.parts and path.resolve().is_relative_to(root) and
                common_digest(digest), 'source path/digest outside actual root')
        relative = str(path.resolve().relative_to(root))
        require(relative not in result, 'source aliases duplicate identity')
        result[relative] = digest
    require(result, 'empty actual source inventory')
    return result


def common_digest(value):
    return pressure.valid_digest(value)


def normalized_readiness(value):
    result = dict(value)
    raw = 'data' in result
    result.pop('data', None)
    result['graphs'] = [dict(graph) for graph in result['graphs']]
    for graph in result['graphs']:
        require(('loadedAt' in graph) == raw, 'consistent raw readiness schema')
        if raw:
            require(type(graph['loadedAt']) is str, 'actual readiness timestamp')
            graph.pop('loadedAt')
    return result


def native_build_identity(runtime):
    identity = runtime['toolchainIdentity']
    require(type(identity) is dict and set(identity) ==
            {'java','cargo','rustc','target','rustcVersion','cargoVersion'}, 'actual structured toolchain identity')
    require(all(type(v) is str and v for v in identity.values()), 'complete toolchain identity')
    require(all(Path(identity[k]).is_absolute() for k in ('java','cargo','rustc')) and
            Path(identity['cargo']).parent == Path(identity['rustc']).parent, 'actual direct tool paths')
    expected = [identity['cargo'],'build','--release','--locked','--jobs','2','-p','graphite-cli',
                '--target',identity['target']]
    require(runtime['buildArgv'] == expected, 'actual bounded release argv')
    require('host: '+identity['target'] in identity['rustcVersion'].replace('\\n', '\n').splitlines(), 'actual Rust host/target')
    # Compare structured evidence; retain the original manifest and argv unchanged.
    return identity


def runtime_binding(packet, arm_id, expected_revision):
    arm = packet['arms'][arm_id]
    require(arm['revision'] == expected_revision and arm.get('patchSha256') is None,
            'CI source revision/patch mismatch for ' + arm_id)
    refs = [arm['sourceManifest'], arm['runtimeManifest'], packet['buildAudits'][arm_id]]
    values = []
    for ref in refs:
        require(packet['pins'].get(ref['path']) == ref['sha256'] == pressure.sha(ref['path']),
                'actual source/runtime/build audit changed')
        values.append(pressure.read(ref['path']))
    source, runtime, audit = values
    require(source['revision'] == runtime['revision'] == expected_revision,
            'source/runtime payload revision')
    require(source['files'] and source['files'] == runtime['sourceFiles'], 'full source map binding')
    relative_files = normalize_source_files(source)
    for relative, digest in relative_files.items():
        path = Path(source['root']).resolve() / relative
        require(not path.is_symlink() and packet['pins'].get(str(path.resolve())) == digest,
                'actual source closure pin')
    require(runtime['sourceManifestSha256'] == refs[0]['sha256'] and
            runtime['sha256'] == arm['runtimeFiles'].get(runtime['binary']) and
            arm['serverArgv'] == [runtime['binary'], 'serve'], 'actual executable binding')
    if arm_id == 'B' and audit['status'] == 'VERIFIED_ARTIFACTS_SCHEMA_ORACLE_PENDING':
        pressure.candidate_authority(packet)
    else:
        require(audit['status'] == 'PASS', 'completed independent native build audit')
    require(audit['sourceManifestSha256'] == refs[0]['sha256'] and
            audit['runtimeManifestSha256'] == refs[1]['sha256'] and audit['binarySha256'] == runtime['sha256'] and
            audit['revision'] == expected_revision and audit['sourceBefore'] == audit['sourceAfter'] == source['files'],
            'completed independent native build audit')
    matches = [p for p in packet['proofs'] if p['role'] == 'source-runtime' and p.get('arm') == arm_id]
    require(len(matches) == 1 and matches[0]['upstream'].get(refs[2]['path']) == refs[2]['sha256'],
            'source proof must bind actual build audit')
    return runtime


def oracle_side(arm, base_sha):
    # Equal source and identical audited artifacts retain the accepted schema oracle.
    require(arm in 'CAB' and len(arm) == 1, 'known arm')
    return 'base' if arm == 'C' or (arm == 'A' and base_sha == ACCEPTED) else 'candidate'


def independent_case_oracle(packet, case, arm_id, expected):
    """Bind fresh persisted-graph oracle and actual HTTP proof to this exact arm."""
    origin = 'C' if arm_id == 'A' and pressure.same_accepted_artifacts(packet, 'A') else arm_id
    refs = packet.get('independentCaseOracles', {}).get(origin, {}).get(case['id'])
    if refs is None:
        raise MissingAuthority(origin + '/' + case['id'] + ' requires an independent persisted-graph oracle and actual response audit')
    require(set(refs) == {'plan', 'audit', 'expected', 'body'}, 'complete independent case references')
    plan, audit, value, body = [pressure.pinned_authority_metadata(packet, refs[k])
                              for k in ('plan', 'audit', 'expected', 'body')]
    actual = plan['arms'][origin]
    arm = packet['arms'][arm_id]
    require(actual['revision'] == arm['revision'] and actual['serverArgv'] == arm['serverArgv'] and
            actual['graphs'] == arm['graphs'] and
            plan['pins'].get(actual['binary']) == arm['runtimeFiles'].get(actual['binary']),
            'independent oracle actual revision/runtime/fixture scope')
    require(all(packet['pins'].get(path) == digest for path, digest in plan['pins'].items()),
            'independent derivation and execution input closure')
    executed = plan['cases'][case['id']]
    require(executed['request'] == case['request'] and executed['querySha256'] ==
            pressure.digest_bytes(case['request']['body']['query'].encode()), 'independent exact request')
    targets = pressure.declared_query_scope(case, plan['graphIds'])
    require(len(executed['targetGraphIds']) == len(targets) and set(executed['targetGraphIds']) == set(targets),
            'independent actual query graph membership')
    require(all(executed['expected'][origin][k] == refs['expected'][k] for k in ('path', 'sha256')),
            'expected payload used by actual execution')
    statuses = {'PASS_FOUR_COMPLETE_NATIVE_FEATURE_RESPONSES_INDEPENDENT_RAW_AUDIT',
                'PASS_TWENTY_COMPLETE_NATIVE_DISCOVERY_ROUTING_RESPONSES_INDEPENDENT_RAW_AUDIT',
                'PASS_SIXTEEN_COMPLETE_NATIVE_SLOW_NODE_RESPONSES_INDEPENDENT_RAW_AUDIT',
                'PASS_EIGHT_COMPLETE_NATIVE_DATAFLOW_LEGAL_RESPONSES_INDEPENDENT_RAW_AUDIT'}
    require(audit['status'] in statuses and audit['performanceClaim'] is False,
            'completed independently audited actual responses')
    require(all(packet['pins'].get(path) == digest for path, digest in audit['pins'].items()),
            'complete actual independent audit input closure')
    observed = [proof for proof in audit['proofs'] if proof['arm'] == origin and proof['case'] == case['id']]
    require(len(observed) == 1 and observed[0]['expectedSha256'] == refs['expected']['sha256'] and
            observed[0]['bodySha256'] == refs['body']['sha256'] and observed[0]['rows'] == expected['rows'],
            'independent expected and captured complete response linkage')
    require(pressure.digest_bytes(pressure.canonical(value)) == expected['digest'],
            'trusted catalog independent expected payload')
    oracle = {'kind': case['oracleKind'], **expected, 'proof': refs['audit']}
    if case['oracleKind'] == 'native-complete-legal-limit-multiset-v1':
        oracle.update(value=value, valueProof=refs['expected'])
    else:
        require(case['oracleKind'] == 'native-full-json-sha256-v1', 'known independent native policy')
        require(pressure.typed(value) == pressure.typed(body), 'complete independently expected typed response')
    prepared = dict(case, oracleByArm={arm_id: oracle})
    pressure.compile_response_validator([prepared], arm_id)(pressure.canonical(body), prepared)
    return oracle


def oracle_bindings(packet, cases, candidate):
    bindings = {}
    for name in ('A', 'B'):
        arm = packet['arms'][name]
        if name == 'A' and pressure.same_accepted_artifacts(packet, name):
            continue
        if name == 'A' or candidate is None or name in packet.get('oracleBindings', {}):
            if name not in packet.get('oracleBindings', {}):
                raise MissingAuthority(name + ' requires its own revision-specific independent full-response oracle')
            bindings[name] = packet['oracleBindings'][name]
            continue
        artifact = pressure.pinned_authority_metadata(packet, packet['candidateAuthority']['artifactAudit'])
        observed = {c['id']: c for c in artifact['caseEvidence']}
        expected = {}
        for case in cases:
            if case['id'] not in observed:
                if case.get('sourceKind') == 'independent-persisted-graph-oracle-v1':
                    independent_case_oracle(packet, case, name, case['expected']['candidate'])
                    expected[case['id']] = case['expected']['candidate']
                    continue
                raise MissingAuthority(name + '/' + case['id'] + ' requires revision-specific historical replay authority')
            row = observed[case['id']]
            if case['id'] == 'schema-key-histogram':
                require(candidate['query'] == case['request']['body']['query'], 'exact schema query authority')
                expected[case['id']] = {k: candidate[k] for k in ('digest', 'rows')}
            else:
                value = {'digest': row['validation']['canonicalSha256'], 'rows': row['validation']['rows']}
                require(row['status'] == 'PASS' and value == case['expected']['candidate'],
                        'unchanged complete native case authority')
                expected[case['id']] = value
        bindings[name] = {'revision': arm['revision'], 'graphs': arm['graphs'],
                          'sourceManifestSha256': arm['sourceManifest']['sha256'],
                          'runtimeManifestSha256': arm['runtimeManifest']['sha256'], 'cases': expected}
    return bindings


def assemble(packet, base_sha, candidate_sha, catalog):
    require(packet['schema'] == 'graphite.native-pressure.producers.v1', 'producer packet schema')
    require(packet['acceptedRevision'] == ACCEPTED and packet['baseRevision'] == base_sha and
            packet['candidateRevision'] == candidate_sha, 'matched C/A/B revisions')
    runtimes = [runtime_binding(packet, arm, revision) for arm, revision in
                [('C', ACCEPTED), ('A', base_sha), ('B', candidate_sha)]]
    identities = [native_build_identity(runtime) for runtime in runtimes]
    require(all(value == identities[0] for value in identities),
            'matched actual native toolchain/release settings')
    # Graph origins must name their real writers. A C record cannot reuse candidate-only preparation.
    for arm_id in 'CAB':
        ref = packet['fixtureAudits'][arm_id]
        require(packet['pins'].get(ref['path']) == ref['sha256'] == pressure.sha(ref['path']), 'fixture producer audit pin')
        audit = pressure.read(ref['path'])
        complete = audit['status'] == 'PASS'
        if arm_id == 'B' and audit['status'] == 'VERIFIED_ARTIFACTS_SCHEMA_ORACLE_PENDING':
            pressure.candidate_authority(packet)
            complete = True
        require(complete and audit['writerRevision'] == packet['arms'][arm_id]['revision'] and
                audit['graphs'] == packet['arms'][arm_id]['graphs'] and normalized_readiness(audit['fullReadiness']) == packet['arms'][arm_id]['readiness']['expected'],
                'actual fixture writer/readiness identity for ' + arm_id)
        equivalence = [p for p in packet['proofs'] if p['role'] == 'fixture-equivalence']
        if not equivalence or not all(p.get('status', '').startswith('PASS') for p in equivalence):
            raise MissingAuthority('Complete C/A/B core/topology/indices equivalence remains pending')
        require(len(equivalence) == 1 and equivalence[0]['upstream'].get(ref['path']) == ref['sha256'],
                'fixture equivalence must include each actual writer audit')
    declared = catalog['engines']['native']
    original = packet['oracleCatalog']
    require(packet['pins'].get(original['path']) == original['sha256'] == pressure.sha(original['path']) ==
            catalog['provenance']['nativeCatalogSha256'], 'original full native oracle catalog')
    rows = pressure.read(original['path'])
    require(len(rows) == 73, 'complete original73 oracle scope')
    candidate = pressure.candidate_authority(packet) if 'candidateAuthority' in packet else None
    bindings = oracle_bindings(packet, declared['cases'], candidate)
    cases = []
    for case in declared['cases']:
        independent = case.get('sourceKind') == 'independent-persisted-graph-oracle-v1'
        if not independent:
            entry = case['sourceCatalogEntry']
            matches = [row for row in rows if row['benchmark'] == entry['benchmark'] and row['params'] == entry['params']]
            require(matches == [entry] and hashlib.sha256(case['request']['body']['query'].encode()).hexdigest() == entry['querySha256'],
                    'original query/full response oracle identity')
        oracles = {}
        for arm in 'CAB':
            expected = case['expected']['base'] if arm == 'C' or arm not in bindings else bindings[arm]['cases'][case['id']]
            if independent:
                oracles[arm] = independent_case_oracle(packet, case, arm, expected)
                continue
            proof = original
            if arm == 'B' and candidate is not None and case['id'] == 'schema-key-histogram':
                proof = candidate['proof']
            elif arm == 'A' and arm in bindings:
                proof = packet['oracleBindings']['A']['proofByCase'][case['id']]
            oracles[arm] = {'kind': case['oracleKind'], **expected, 'proof': proof}
        cases.append({'id': case['id'], 'catalogId': case['id'], 'request': case['request'],
                      'targetGraphIds': pressure.declared_query_scope(case, declared['graphIds']), 'oracleByArm': oracles})
    coverage = {'coveredFamilies': declared['coveredFamilies'], 'requiredFamilies': declared['requiredFamilies'],
                'unavailableFamilies': sorted(set(declared['requiredFamilies']) - set(declared['coveredFamilies'])),
                'unavailableOperations': ['construction', 'loading']}
    plan = {'schema': pressure.PLAN_SCHEMA, 'engine': 'native', 'operation': 'query', 'pins': dict(packet['pins']),
            'catalog': {'path': str(CATALOG), 'sha256': pressure.sha(CATALOG)}, 'arms': packet['arms'],
            'proofs': packet['proofs'], 'cases': cases, 'coverage': coverage,
            'nativeOracleBindings': bindings,
            'cells': [{'id': f'{i}-{arm}', 'arm': arm, 'port': 22840+i} for i, arm in enumerate('CABBAC')],
            'schedule': {'concurrency': 4, 'warmupPerCase': 2, 'measuredPerCase': 20},
            'limits': {'requestSeconds': 240, 'stageSeconds': 3600, 'readinessSeconds': 300,
                       'bodyBytes': 67108864, 'rssIntervalSeconds': .01},
            'comparisonPairs': {'parent': [[1, 2], [4, 3]], 'acceptedBaseline': [[0, 2], [5, 3]]}}
    plan['pins'][str(CATALOG)] = plan['catalog']['sha256']
    if candidate is not None:
        plan['producerAuthority'] = packet['_producerRef']
    pressure.validate_plan(plan, catalog)
    pressure.verify_inputs(plan)
    return plan


def preparation_control_pins():
    """Bind every reviewed runtime dependency, including the legal row compiler."""
    root = SCRIPTS.parent.parent.resolve()
    manifest = SCRIPTS / 'multigraph-pressure-preparation-controls.sha256'
    pins = {str(manifest.resolve()): pressure.sha(manifest)}
    for line in manifest.read_text().splitlines():
        digest, name = line.split('  ', 1)
        relative = Path(name)
        path = root / relative
        require(not relative.is_absolute() and '..' not in relative.parts and
                path.resolve().is_relative_to(root) and not path.is_symlink(), 'control path outside repository')
        path = str(path.resolve())
        require(common_digest(digest) and path not in pins and pressure.sha(path) == digest,
                'reviewed preparation control changed: ' + name)
        pins[path] = digest
    return pins


def prepare(args):
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    status = {'schema': 'graphite.native-pressure.preparation.v1', 'engine': 'native', 'operation': 'query',
              'passed': False, 'status': 'UNAVAILABLE', 'performanceAcceptance': False,
              'acceptedRevision': ACCEPTED, 'baseRevision': args.base_sha, 'candidateRevision': args.candidate_sha,
              'unavailableOperations': ['construction', 'loading']}
    try:
        require(all(re.fullmatch(r'[0-9a-f]{40}', x) for x in [args.base_sha, args.candidate_sha]),
                'exact full git revisions required')
        status['availableInputs'] = inspect_available(args.provenance, args.fixture, args.base_sha, args.candidate_sha)
        catalog = pressure.read(CATALOG)
        declared = catalog['engines']['native']
        status['unavailableFamilies'] = sorted(set(declared['requiredFamilies']) - set(declared['coveredFamilies']))
        if args.producers is None or not args.producers.is_file():
            status['missingProducers'] = ['accepted4f release/source/build audit', 'accepted4f compatible real64 saved graphs',
                'A exact-base compatible real64 graphs', 'complete C/A/B fixture semantic equivalence',
                'per-arm full-readiness and independent oracle bindings', 'closed matched runtime producer packet']
        else:
            packet = pressure.read(args.producers)
            if packet.get('schema') == 'graphite.native-pressure.producer-bundle.v1':
                # Fresh builds and all39 audited responses are concrete evidence,
                # but they cannot manufacture the separate cross-arm core proof.
                from assemble_native_pressure_producers import verify_bundle
                bundle = verify_bundle(packet, args.base_sha, args.candidate_sha)
                status['producerPacket'] = {'path': str(args.producers.resolve()),
                                            'sha256': pressure.sha(args.producers)}
                status['availableInputs']['matchedNativeBundle'] = {
                    'status': bundle['status'],
                    'revisions': {arm: value['revision'] for arm, value in bundle['arms'].items()},
                    'auditedCasesByArm': {arm: len(value['cases']) for arm, value in bundle['arms'].items()}}
                raise MissingAuthority('; '.join(bundle['missingAuthority']))
            # Retain the consumed packet and preparation controls as actual plan inputs.
            packet['pins'] = dict(packet['pins'])
            controls = preparation_control_pins()
            controls[str(args.producers.resolve())] = pressure.sha(args.producers)
            for path, digest in controls.items():
                require(path not in packet['pins'] or packet['pins'][path] == digest,
                        'conflicting preparation input pin')
                packet['pins'][path] = digest
            packet['_producerRef'] = {'path': str(args.producers.resolve()), 'sha256': pressure.sha(args.producers)}
            plan = assemble(packet, args.base_sha, args.candidate_sha, catalog)
            pressure.save(output / 'plan.json', plan)
            status.update(status='PLAN_READY_NOT_MEASURED', planSha256=pressure.sha(output / 'plan.json'),
                          producerPacket={'path': str(args.producers.resolve()), 'sha256': pressure.sha(args.producers)})
    except MissingAuthority as error:
        status.update(status='UNAVAILABLE', missingProducers=[str(error)])
    except (OSError, ValueError, KeyError, TypeError) as error:
        status.update(status='FAIL', errors=[f'{type(error).__name__}: {error}'])
    pressure.save(output / 'preparation-status.json', status)
    pressure.save(output / 'multigraph-native-query-status.json', status)
    (output / 'preparation-report.md').write_text('### Native multi-graph pressure preparation\n\n' +
        status['status'] + '\n\n' + '\n'.join('- ' + x for x in status.get('missingProducers', status.get('errors', []))) +
        '\n\nConstruction/loading and missing query families remain unavailable. No timing or regression acceptance.\n')
    return status


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-sha', required=True)
    parser.add_argument('--candidate-sha', required=True)
    parser.add_argument('--provenance', type=Path, required=True)
    parser.add_argument('--fixture', type=Path, required=True)
    parser.add_argument('--producers', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    options = parser.parse_args()
    result = prepare(options)
    print(result['status'])
    raise SystemExit(0 if result['status'] == 'PLAN_READY_NOT_MEASURED' else 1)
