#!/usr/bin/env python3
"""Bind actual JVM35 audits and existing corrected64 proofs to the shared schedule.

No graph, JVM, or HTTP execution occurs here. Auditing the upstream evidence can
be substantial and always happens outside every measured request boundary.
"""
import argparse
from pathlib import Path
import re
import sys

import audit_native_pressure_artifacts as artifacts
import assemble_native_pressure_producers as assembled
import jvm_pressure_oracles as model
import jvm_query_correctness as queries
import multigraph_pressure as common

SCHEMA = 'graphite.jvm-pressure.producers.v1'
STATUS = assembled.CORRECTED_STATUS
PENDING_STATUS = 'JVM35_RESPONSES_AUDITED_CORE_COMPARABILITY_PENDING'
MODE = 'jvm35-before-core-v1'
CATALOG = Path(__file__).resolve().parent/'fixtures/jvm-multigraph-pressure-cases.json'
# The pressure server's own heap, matched across arms and within the 8g ceiling; the
# correctness server that produced the audited plan runs under queries.CORRECTNESS_HEAP.
PRESSURE_HEAP = '-Xmx4g'
PRESSURE_HEAP_BYTES = 4*1024**3
require = common.require
merge_pins = assembled.merge_pins


def audited_arm(directory, revision, role):
    root = Path(directory).resolve()
    observed = queries.audit(root)
    return _arm_from_audit(root, revision, role, observed)


def _arm_from_audit(root, revision, role, observed):
    require(observed['schema'] == 'graphite.jvm-query-correctness-audit.v1' and
            observed['status'] == queries.AUDIT and observed['revision'] == revision and observed['role'] == role and
            all(observed[k] is True for k in ('independentRawExpectationsVerified', 'sourceRuleApplicabilityVerified',
                                             'all35ActualResponsesVerified', 'oracleAuthorityVerified')) and
            all(observed[k] is False for k in ('fresh64Acceptance', 'performanceAcceptance', 'completeSemanticEquivalence')),
            'complete actual independently audited JVM35 evidence')
    audit_ref = artifacts.ref(root/'audit.json')
    require(common.typed(common.read(audit_ref['path'])) == common.typed(observed), 'stored JVM35 audit differs from raw replay')
    pins = dict(observed['pins']); merge_pins(pins, {audit_ref['path']: audit_ref['sha256']})
    plan = common.pinned_authority_metadata({'pins': pins}, observed['plan'])
    raw_root = Path(plan['rawDerivationRoot'])
    raw = common.pinned_authority_metadata({'pins': pins}, artifacts.ref(raw_root/'plan.json'))
    producer = Path(raw['producerRoot'])
    artifact_ref = artifacts.ref(producer/'artifact-audit.json')
    artifact = common.pinned_authority_metadata({'pins': pins}, artifact_ref)
    runtime = common.pinned_authority_metadata({'pins': pins}, observed['runtimeManifest'])
    require(raw['revision'] == artifact['revision'] == revision and artifact['role'] == role and
            all(observed[k] == raw[k] == artifact[k] for k in ('sourceManifest', 'runtimeManifest', 'fixtureManifest')),
            'same actual JVM writer source/runtime/fixture')
    require(plan['graphs'] == artifact['graphs'] and len(plan['graphs']) == 64 and
            [g['id'] for g in plan['graphs']] == list(model.FIXTURE_GRAPH_IDS), 'complete same ordered64 graph roots')
    variant = plan['expectationVariant']
    require(variant == ('C' if role == 'accepted-baseline' else 'B'), 'actual JVM expectation arm')
    require(plan['rawDerivationEvidence'] == queries._evidence(observed['rawDerivationReplay']),
            'same neutral derivation refs and independent replay')
    cases = []
    require(len(plan['cases']) == len(observed['cases']) == 35, 'complete JVM35 case inventory')
    for definition, case, receipt in zip(model.cases(), plan['cases'], observed['cases']):
        require(all(common.typed(case[k]) == common.typed(definition[k]) for k in definition) and
                receipt['case'] == case['id'] and receipt['targetGraphIds'] == case['targetGraphIds'],
                'actual complete JVM request and graph scope')
        value_ref = plan['rawDerivationEvidence']['universes'][case['id']]
        oracle = _pressure_oracle(case, variant, value_ref, audit_ref, pins)
        cases.append({'id': case['id'], 'request': case['request'], 'targetGraphIds': case['targetGraphIds'],
                      'oracle': oracle, 'observed': receipt})
    jar = plan['serverJar']
    argv = [raw['java'], PRESSURE_HEAP, '-XX:ActiveProcessorCount=4', '-cp', jar['path'],
            'io.johnsonlee.graphite.cli.MainKt', 'serve']
    # The shared runner owns data/port/concurrency/graph flags; retain all other
    # actual correctness-server options rather than inventing a different path. The
    # correctness server alone runs under its own heap (queries.CORRECTNESS_HEAP).
    expected_actual = [argv[0], queries.CORRECTNESS_HEAP, *argv[2:7], '--data', plan['data'], '--port', str(plan['port']),
        '--load-mode', 'MAPPED', '--max-concurrent-cypher', '4', '--cypher-max-timeout-ms', '240000',
        *common.graph_arguments(plan['graphs'])]
    require(plan['argv'] == expected_actual and runtime['files'].get(jar['path']) == jar['sha256'] == pins.get(jar['path']),
            'exact sealed source-built JVM server entry and options')
    arm = {'revision': revision, 'artifactRole': role, 'role': role, 'patchSha256': None,
        'sourceManifest': observed['sourceManifest'], 'runtimeManifest': observed['runtimeManifest'],
        'fixtureManifest': observed['fixtureManifest'], 'artifactAudit': artifact_ref, 'queryAudit': audit_ref,
        'runtimeFiles': runtime['files'], 'runtimeRoots': [str(producer/'runtime')], 'serverArgv': argv,
        'maxHeapBytes': PRESSURE_HEAP_BYTES, 'graphs': plan['graphs'],
        'readiness': {**plan['readiness'], 'path': '/api/graphs'}, 'cases': cases}
    if raw.get('coreComparabilityPending') is True:
        arm['rawExportAudit'] = raw['upstreamRawExportAudit']
        return arm,pins,None
    return arm, pins, Path(raw['coreRoot'])/'audit.json'


def _pressure_oracle(case, variant, value_ref, audit_ref, pins):
    """Verify one independent payload; retain only its sealed reference."""
    oracle = dict(case['oracleByArm'][variant])
    require(('value' in oracle) != ('valueRef' in oracle), 'one JVM oracle representation')
    if 'valueRef' in oracle:
        require(oracle['valueRef'] == value_ref, 'same independent raw universe reference')
    require('digestKind' not in oracle or ('valueRef' in oracle and
            oracle['digestKind'] == common.JVM_RAW_UNIVERSE_DIGEST), 'known referenced JVM universe identity')
    referenced = {key: value for key, value in oracle.items() if key != 'value'}
    referenced.update(valueRef=value_ref, proof=audit_ref, valueProof=value_ref)
    if 'value' in oracle:
        value = common.jvm_oracle_value(referenced, pins=pins)
        require(common.typed(oracle['value']) == common.typed(value) and
                oracle['digest'] == model.digest(value) == model.digest(oracle['value']),
                'oracle is independent raw universe, not observed response')
        del value
    # Validate the original identity before emitting the explicit new identity.
    # Legacy references still require their original typed digest; new ones use
    # exact raw bytes. Both retain the complete original semantic compiler.
    common.compile_response_validator([{**case, 'oracleByArm': {variant: referenced}}], variant, pins=pins)
    return dict(referenced, digestKind=common.JVM_RAW_UNIVERSE_DIGEST, digest=value_ref['sha256'])


def _normalized_packet_oracles(packet, actual):
    """Compare legacy payload identities without changing archived bytes or claims."""
    normalized = dict(packet)
    normalized['arms'] = {}
    for name, arm in packet['arms'].items():
        require(name in actual['arms'], 'JVM producer packet differs from actual evidence')
        expected = actual['arms'][name]['cases']
        require(len(arm['cases']) == len(expected), 'JVM producer packet differs from actual evidence')
        rows = []
        for row, reference in zip(arm['cases'], expected):
            oracle = row['oracle']
            require(('value' in oracle) != ('valueRef' in oracle), 'one JVM oracle representation')
            require('digestKind' not in oracle or ('valueRef' in oracle and
                    oracle['digestKind'] == common.JVM_RAW_UNIVERSE_DIGEST), 'known referenced JVM universe identity')
            if 'digestKind' not in oracle:
                oracle = _normalized_legacy_oracle(oracle, reference['oracle'], actual['pins'])
            rows.append(dict(row, oracle=oracle))
        normalized['arms'][name] = dict(arm, cases=rows)
    return normalized


def _normalized_legacy_oracle(oracle, expected, pins):
    # Exact independently replayed payload bytes precede legacy identity checks.
    if 'valueRef' in oracle:
        require(oracle['valueRef'] == expected['valueRef'],
                'JVM producer packet differs from actual independent payload reference')
    value = common.jvm_oracle_value(expected, pins=pins)
    require(model.digest(value) == oracle['digest'] and
            ('value' not in oracle or (common.typed(oracle['value']) == common.typed(value) and
                                      model.digest(oracle['value']) == oracle['digest'])),
            'JVM producer packet differs from actual independent payload')
    return {**{key: value for key, value in oracle.items() if key != 'value'},
            'valueRef': expected['valueRef'], 'digestKind': common.JVM_RAW_UNIVERSE_DIGEST,
            'digest': expected['valueRef']['sha256']}


def assemble(correctness_roots, base_sha, candidate_sha, *, measurement_only=False):
    return _assemble(correctness_roots, base_sha, candidate_sha, measurement_only, audited_arm)


def _assemble(correctness_roots, base_sha, candidate_sha, measurement_only, arm_reader):
    require(set(correctness_roots) == {'C', 'A', 'B'} and
            all(re.fullmatch('[0-9a-f]{40}', value) for value in (base_sha, candidate_sha)), 'exact C/A/B roots and revisions')
    roots = {key: str(Path(value).resolve()) for key, value in correctness_roots.items()}
    require((roots['A'] == roots['C']) == (base_sha == artifacts.ACCEPTED) and
            roots['B'] not in (roots['C'], roots['A']), 'only actual same4f A/C directory alias')
    arms, pins, comparisons, audited = {}, {}, {}, {}
    for name, revision, role in [('C', artifacts.ACCEPTED, 'accepted-baseline'), ('A', base_sha, 'parent'), ('B', candidate_sha, 'candidate')]:
        if roots[name] not in audited:
            audited[roots[name]] = arm_reader(roots[name], revision, role)
        arm, evidence, core_path = audited[roots[name]]
        require(arm['revision'] == revision, 'aliased actual revision')
        arms[name] = dict(arm, role=role)
        merge_pins(pins, evidence)
        require((core_path is None) == measurement_only, 'explicit JVM raw authority mode')
        if name != 'C' and roots[name] != roots['C']: comparisons[name] = core_path
    packet = {'schema': SCHEMA, 'status': STATUS, 'correctnessRoots': roots,
        'acceptedRevision': artifacts.ACCEPTED, 'baseRevision': base_sha, 'candidateRevision': candidate_sha,
        'arms': arms, 'pins': pins, 'comparisonModel': assembled.CORRECTED_MODEL, 'missingAuthority': [],
        'completeSemanticEquivalence': False, 'strictEquivalence': False, 'sourceToDeclarationCompletenessClaim': False,
        'performanceAcceptance': False, 'acceptanceEligible': False}
    if measurement_only:
        packet.update(status=PENDING_STATUS,measurementMode=MODE,missingAuthority=['complete-matching-core-comparability'])
        packet.pop('comparisonModel')
        return packet
    packet['correctedComparisons'] = {name: assembled.corrected_comparison(packet, name, path)
                                      for name, path in comparisons.items()}
    assembled.corrected_fixture_bindings(packet)
    return packet


def verify_bundle(packet, base_sha, candidate_sha):
    require(packet['schema'] == SCHEMA and packet['baseRevision'] == base_sha and packet['candidateRevision'] == candidate_sha,
            'actual JVM producer packet identity')
    actual = assemble(packet['correctnessRoots'], base_sha, candidate_sha,measurement_only=packet.get('measurementMode')==MODE)
    require(common.typed(actual) == common.typed(_normalized_packet_oracles(packet, actual)),
            'JVM producer packet differs from actual evidence')
    # Legacy callers compare the exact archived arm shape against their plan.
    # Return that already verified shape; fresh packets contain references only.
    return packet


def pressure_arms(packet):
    return packet['arms']


def fixture_bindings(packet):
    require(packet['schema'] == SCHEMA, 'explicit JVM producer authority')
    if packet.get('measurementMode') == MODE and packet['status'] == PENDING_STATUS:
        require(packet['missingAuthority']==['complete-matching-core-comparability'] and 'correctedComparisons' not in packet,'explicit pending JVM inventory')
        roots={Path(g['path']) for arm in packet['arms'].values() for g in arm['graphs']}
        return {'graphsByArm':{key:arm['graphs'] for key,arm in packet['arms'].items()},
                'fixtureFiles':{path:digest for path,digest in packet['pins'].items() if any(Path(path).is_relative_to(root) for root in roots)},
                'realPersistedGraphs':True,'completeSemanticEquivalence':False,'coreComparabilityPending':True}
    return assembled.corrected_fixture_bindings(packet)


def bind_plan(packet, producer_ref, output, catalog_path):
    output, catalog_path = Path(output).resolve(), Path(catalog_path).resolve()
    catalog = common.read(catalog_path); declared = catalog['engines']['jvm']
    require(declared['graphIds'] == list(model.FIXTURE_GRAPH_IDS) and
            declared['requiredFamilies'] == declared['coveredFamilies'] == list(model.FAMILIES), 'exact JVM64 eight families')
    require(len(declared['cases']) == 35, 'exact catalog35 inventory')
    cases = []
    for definition, source in zip(model.cases(), declared['cases']):
        require(all(common.typed(source[k]) == common.typed(v) for k, v in definition.items()) and
                source['oracleKind'] == 'jvm-complete-legal-limit-multiset-v1', 'exact reviewed JVM catalog definition')
        oracles = {}
        for name, arm in packet['arms'].items():
            observed = arm['cases'][len(cases)]
            require(observed['id'] == definition['id'] and observed['request'] == definition['request'] and
                    observed['targetGraphIds'] == definition['targetGraphIds'], 'same source-bound full JVM cases')
            oracles[name] = observed['oracle']
        cases.append({**definition, 'catalogId': definition['id'], 'oracleByArm': oracles})
    pins = dict(packet['pins'])
    from prepare_native_pressure_plan import preparation_control_pins
    merge_pins(pins, preparation_control_pins())
    for ref in (producer_ref, artifacts.ref(catalog_path), artifacts.ref(__file__)):
        merge_pins(pins, {ref['path']: ref['sha256']})
    proofs = []
    def proof(role, claims, upstream, arm=None):
        path = output/(role+('-'+arm if arm else '')+'.json')
        value = {'status': 'PASS_BOUND_ACTUAL_SCOPED_EVIDENCE', 'upstream': upstream, **claims}
        common.save(path, value); ref = artifacts.ref(path); merge_pins(pins, {ref['path']: ref['sha256']})
        proofs.append({**ref, 'status': value['status'], 'role': role, **({'arm': arm} if arm else {}),
                       'bindings': claims, 'upstream': upstream})
    for name, arm in packet['arms'].items():
        upstream = {arm[k]['path']: arm[k]['sha256'] for k in ('sourceManifest', 'runtimeManifest', 'artifactAudit', 'queryAudit')}
        upstream[producer_ref['path']] = producer_ref['sha256']
        proof('source-runtime', {'revision': arm['revision'], 'patchSha256': None,
            'sourceManifestSha256': arm['sourceManifest']['sha256'], 'runtimeManifestSha256': arm['runtimeManifest']['sha256'],
            'runtimeFiles': arm['runtimeFiles']}, upstream, name)
        proof('independent-correctness', {'graphIds': [g['id'] for g in arm['graphs']],
            'requestDigests': {c['id']: common.digest_bytes(common.canonical(c['request'])) for c in cases},
            'oracleDigests': {c['id']: c['oracleByArm'][name]['digest'] for c in cases},
            **({'oracleDigestKinds': {c['id']: c['oracleByArm'][name]['digestKind'] for c in cases
                                      if 'digestKind' in c['oracleByArm'][name]}}
               if any('digestKind' in c['oracleByArm'][name] for c in cases) else {})}, upstream, name)
    pending=packet.get('measurementMode')==MODE
    proof('fixture-inventory' if pending else 'fixture-equivalence', fixture_bindings(packet), {producer_ref['path']: producer_ref['sha256'],
        **{v['audit']['path']: v['audit']['sha256'] for v in packet.get('correctedComparisons',{}).values()},
        **{a['artifactAudit']['path']: a['artifactAudit']['sha256'] for a in packet['arms'].values()}})
    return {'schema': common.PLAN_SCHEMA, 'engine': 'jvm', 'operation': 'query', 'pins': pins,
        'catalog': artifacts.ref(catalog_path),
        **({'pendingJvmComparabilityAuthority':producer_ref,'comparabilityPending':True} if pending else {'jvmProducerAuthority':producer_ref}),
        'arms': pressure_arms(packet), 'cases': cases, 'proofs': proofs,
        'coverage': {'coveredFamilies': declared['coveredFamilies'], 'requiredFamilies': declared['requiredFamilies'],
                     'unavailableFamilies': [], 'unavailableOperations': ['construction', 'loading']},
        'cells': [{'id': f'{i}-{arm}', 'arm': arm, 'port': 23840+i} for i, arm in enumerate('CABBAC')],
        'schedule': dict(common.SCHEDULE),
        'comparisonPairs': {'parent': [[1, 2], [4, 3]], 'acceptedBaseline': [[0, 2], [5, 3]]},
        'limits': {'requestSeconds': 240, 'stageSeconds': 3600, 'readinessSeconds': 600,
                   'bodyBytes': 67108864, 'rssIntervalSeconds': .01}}


def _reserve_preparation(correctness_roots, output):
    output = Path(output).resolve()
    require(not output.exists(), 'fresh preparation directory')
    # Inspect paths only before reserving output, so failed/missing audit evidence
    # can still produce a terminal diagnostic without writing inside its inputs.
    protected = [Path(p).resolve() for p in correctness_roots.values()]
    preflight_error = None
    try:
        for root in list(protected):
            if (root/'plan.json').is_file():
                query = common.read(root/'plan.json')
                raw_root = Path(query['rawDerivationRoot']).resolve(); protected.append(raw_root)
                if (raw_root/'plan.json').is_file():
                    raw = common.read(raw_root/'plan.json')
                    protected.extend(Path(raw[key]).resolve() for key in ('producerRoot', 'coreRoot','rawExportRoot') if key in raw)
                    source_path = Path(raw['sourceManifest']['path'])
                    if source_path.is_file(): protected.append(Path(common.read(source_path)['root']).resolve())
    except (OSError, ValueError, KeyError, TypeError) as error:
        preflight_error = error
    require(not any(output.is_relative_to(root) for root in protected), 'preparation output outside immutable upstream inputs')
    output.mkdir(parents=True)
    return output, preflight_error


def _status():
    return {'schema': 'graphite.jvm-pressure.preparation.v1', 'engine': 'jvm', 'operation': 'query',
            'status': 'FAIL', 'errors': [], 'missingProducers': [], 'performanceAcceptance': False}


def prepare(correctness_roots, base_sha, candidate_sha, output, catalog_path=CATALOG, *, measurement_only=False):
    output, preflight_error = _reserve_preparation(correctness_roots, output)
    status = {'schema': 'graphite.jvm-pressure.preparation.v1', 'engine': 'jvm', 'operation': 'query',
              'status': 'FAIL', 'errors': [], 'missingProducers': [], 'performanceAcceptance': False}
    try:
        if preflight_error is not None: raise preflight_error
        packet = assemble(correctness_roots, base_sha, candidate_sha,measurement_only=measurement_only)
        require(not any(output.is_relative_to(Path(p).parent) for p in packet['pins']),
                'preparation output outside immutable upstream inputs')
        common.save(output/'packet.json', packet)
        plan = bind_plan(packet, artifacts.ref(output/'packet.json'), output, catalog_path)
        common.validate_plan(plan); common.verify_inputs(plan)
        common.save(output/'plan.json', plan)
        status.update(status='PLAN_READY_NOT_MEASURED', planSha256=common.sha(output/'plan.json'))
        if measurement_only:status['comparabilityPending']=True
    except FileNotFoundError as error:
        status.update(status='UNAVAILABLE', missingProducers=[str(error)])
    except (OSError, ValueError, KeyError, TypeError) as error:
        status['errors'].append(repr(error))
    common.save(output/'preparation-status.json', status)
    return status


def _stage_candidate(correctness_roots, base_sha, candidate_sha, output, catalog_path=CATALOG):
    """Materialize untrusted saved evidence; only the strict execution factory can approve it.

    Return only small diagnostic metadata so candidate universes/validators are
    released before the independent context factory loads and validates a plan.
    """
    output, preflight_error = _reserve_preparation(correctness_roots, output)
    status = _status()
    status['errors'] = ['Complete independent preparation verification has not finished']
    common.save(output/'preparation-status.json', status)
    try:
        if preflight_error is not None: raise preflight_error
        def stored_arm(directory, revision, role):
            root = Path(directory).resolve()
            return _arm_from_audit(root, revision, role, common.read(root/'audit.json'))
        packet = _assemble(correctness_roots, base_sha, candidate_sha, True, stored_arm)
        require(not any(output.is_relative_to(Path(p).parent) for p in packet['pins']),
                'preparation output outside immutable upstream inputs')
        common.save(output/'packet.json', packet)
        plan = bind_plan(packet, artifacts.ref(output/'packet.json'), output, catalog_path)
        common.save(output/'plan.json', plan)
        status.update(candidatePlanSha256=common.sha(output/'plan.json'))
    except FileNotFoundError as error:
        status.update(status='UNAVAILABLE', missingProducers=[str(error)])
    except (OSError, ValueError, KeyError, TypeError) as error:
        status['errors'].append(repr(error))
    return status


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for arm in 'CAB': parser.add_argument('--'+arm.lower(), type=Path, required=True)
    parser.add_argument('--base-sha', required=True); parser.add_argument('--candidate-sha', required=True)
    parser.add_argument('--output', type=Path, required=True); parser.add_argument('--catalog', type=Path, default=CATALOG)
    parser.add_argument('--measurement-only',action='store_true')
    args = parser.parse_args()
    result = prepare({k: getattr(args, k.lower()) for k in 'CAB'}, args.base_sha, args.candidate_sha, args.output, args.catalog,measurement_only=args.measurement_only)
    print(result); sys.exit(0 if result['status'] == 'PLAN_READY_NOT_MEASURED' else 1)
