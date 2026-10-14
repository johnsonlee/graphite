#!/usr/bin/env python3
"""JVM35 correctness from replayed raw derivation, never learned HTTP output.

Reuse the owned Native correctness lifecycle. This stage runs one complete
response per case and cannot claim sustained load or performance acceptance.
"""
import argparse
import json
from pathlib import Path
import sys

sys.dont_write_bytecode = True
import audit_native_pressure_artifacts as artifacts
from audit_native_query_correctness import audit_response_journal
from assemble_native_pressure_producers import merge_pins
import derive_jvm_pressure_oracles as derive
import jvm_pressure_oracles as model
import multigraph_pressure as common
import run_native_query_correctness as lifecycle
from run_real64_loading import entrypoint

# Deriving and checking complete JVM responses on every real64 writer corpus ran a 4g heap out
# of memory (Attempt277); 8g is the ceiling the performance rules allow.
CORRECTNESS_HEAP = '-Xmx8g'

require = common.require
PLAN = 'graphite.jvm-query-correctness-plan.v1'
AUDIT = 'PASS_ALL35_JVM_RESPONSES_INDEPENDENT_RAW_AUDIT'


def source_rules(plan):
    # Kept separate from raw derivation: a complete byte traversal alone does
    # not establish the JVM property's or query executor's interpretation.
    import jvm_pressure_source_rules
    return jvm_pressure_source_rules.audit(plan)


def _prepare_replayed(raw_derivation_dir, output, port):
    root, output = Path(raw_derivation_dir).resolve(), Path(output).resolve()
    require(type(port) is int and 1024 <= port <= 65535, 'owned JVM port')
    replayed = derive.replay(root)
    require(replayed['status'] == derive.PASS and replayed['actualPrimitiveExecutionVerified'] is True and
            replayed['rawDerivationBoundByDigest'] is True and
            all(replayed[key] is False for key in derive.FALSE_CLAIMS), 'independent raw derivation replay required')
    return _prepare_evidence(root, output, port, _evidence(replayed)), replayed


def _evidence(replayed):
    return {key: replayed[key] for key in ('record', 'universes')}


def prepare(raw_derivation_dir, output, port):
    return _prepare_replayed(raw_derivation_dir, output, port)[0]


def _prepare_evidence(root, output, port, evidence):
    root, output = Path(root).resolve(), Path(output).resolve()
    require(type(port) is int and 1024 <= port <= 65535, 'owned JVM port')
    raw_plan, raw_record = common.read(root/'plan.json'), common.read(root/'record.json')
    require(evidence['record'] == artifacts.ref(root/'record.json') and
            raw_record['plan'] == artifacts.ref(root/'plan.json'), 'same actual derivation plan/record')
    rules = source_rules(raw_plan)
    require(rules['schema'] == 'graphite.jvm-source-rule-applicability.v1' and
            rules['status'] == 'PASS_REVIEWED_JVM_SOURCE_RULE_APPLICABILITY' and
            rules['sourceRuleApplicabilityVerified'] is True and rules['upstreamExecutionReplayRequired'] is True and
            all(rules[key] is False for key in ('oracleAuthorityVerified', 'fresh64Acceptance', 'performanceAcceptance')),
            'independent scoped JVM source-rule applicability')
    require(rules['revision'] == raw_plan['revision'] and
            all(rules[key] == raw_plan[key] for key in ('sourceManifest', 'runtimeManifest')),
            'same source/runtime revision for derivation and JVM rules')
    pins = dict(raw_plan['pins']); merge_pins(pins, rules['pins'])
    merge_pins(pins, raw_record['outputFiles'])
    for name in ('plan.json', 'record.json'):
        ref = artifacts.ref(root/name); merge_pins(pins, {ref['path']: ref['sha256']})
    # New orchestration code is part of the immutable execution input closure.
    for module in (Path(__file__), Path(lifecycle.__file__)):
        merge_pins(pins, {str(module.resolve()): common.sha(module)})
    packet = {'pins': pins}
    source = common.pinned_authority_metadata(packet, raw_plan['sourceManifest'])
    runtime = common.pinned_authority_metadata(packet, raw_plan['runtimeManifest'])
    fixture = common.pinned_authority_metadata(packet, raw_plan['fixtureManifest'])
    require(source['revision'] == runtime['revision'] == fixture['writerRevision'] == raw_plan['revision'],
            'same actual source/runtime/saved writer')
    producer = Path(raw_plan['producerRoot'])
    require(not any(output.is_relative_to(p) for p in
                    (root, Path(source['root']), producer/'runtime', producer/'graphs')),
            'correctness output outside immutable input roots')
    jars = rules['packagedRuntime']
    for key, name in (('graphiteJar', 'graphite.jar'), ('writerJar', 'writer.jar')):
        ref = jars[key]; expected_path = str(producer/'runtime'/name)
        require(ref['path'] == expected_path and
                ref['sha256'] == pins.get(expected_path) == runtime['files'].get(expected_path),
                'actual sealed source-built JVM package')
    require(jars['writerJar']['path'] == raw_plan['writerJar'] and
            runtime['toolchainIdentity']['java'] == raw_plan['java'] and raw_plan['java'] in pins,
            'same writer dependency and audited JVM executable')
    graphs = [{'id': graph['id'], 'path': graph['path']} for graph in fixture['graphs']]
    require([g['id'] for g in graphs] == [g['id'] for g in raw_plan['graphs']], 'same full64 graph scope')
    cases = model.cases([g['id'] for g in graphs])
    contracts = [{key: case[key] for key in ('id', 'requestSha256', 'querySha256',
                                            'requestedGraphIds', 'targetGraphIds')} for case in cases]
    require(common.typed(rules['requestContracts']) == common.typed(contracts), 'same complete35 source-bound requests')
    require(set(evidence['universes']) == {case['id'] for case in cases}, 'complete35 independent raw universes')
    variant = raw_plan['arm']
    require(variant in ('C', 'B'), 'actual reference/candidate derivation arm')
    for case in cases:
        ref = evidence['universes'][case['id']]
        oracle = {'kind': 'jvm-complete-legal-limit-multiset-v1', 'valueRef': dict(ref),
                  'digestKind': common.JVM_RAW_UNIVERSE_DIGEST, 'digest': ref['sha256']}
        universe = common.jvm_oracle_value(oracle, pins=pins)
        validator = model.CompiledLegalLimitOracle(universe, case)
        oracle['rows'] = min(validator.total, validator.limit)
        case['oracleByArm'] = {variant: oracle}
        del universe, validator
    jar = jars['graphiteJar']['path']
    plan = {'schema': PLAN, 'revision': raw_plan['revision'], 'role': raw_plan['role'],
            'rawDerivationRoot': str(root), 'rawDerivationEvidence': evidence, 'sourceRuleAudit': rules,
            'sourceManifest': raw_plan['sourceManifest'], 'runtimeManifest': raw_plan['runtimeManifest'],
            'fixtureManifest': raw_plan['fixtureManifest'], 'expectationVariant': variant,
            'serverJar': jars['graphiteJar'], 'packagedEntry': entrypoint(jar),
            'graphs': graphs, 'cases': cases, 'port': port, 'data': str(output/'data'), 'pins': pins,
            'readiness': {'expected': lifecycle.persisted_readiness(fixture)},
            'limits': {'requestSeconds': 240, 'bodyBytes': 64*1024*1024, 'readinessSeconds': 900},
            'performanceAcceptance': False, 'completeSemanticEquivalence': False}
    plan['argv'] = [raw_plan['java'], CORRECTNESS_HEAP, '-XX:ActiveProcessorCount=4', '-cp', jar,
                    'io.johnsonlee.graphite.cli.MainKt', 'serve', '--data', plan['data'], '--port', str(port),
                    '--load-mode', 'MAPPED', '--max-concurrent-cypher', '4', '--cypher-max-timeout-ms', '240000',
                    *common.graph_arguments(graphs)]
    lifecycle.verify_closure(plan)
    return plan


def execute(raw_derivation_dir, output, port):
    output = Path(output).resolve()
    require(output.is_dir() and not list(output.iterdir()), 'fresh empty correctness output')
    # Prepare once here; callers cannot supply an unaudited serialized plan.
    plan = prepare(raw_derivation_dir, output, port)
    return _execute_plan(plan, output, lifecycle.verify_closure)


def _execute_plan(plan, output, verify_closure):
    contract = lifecycle._ExecutionContract('jvm', PLAN, lifecycle.JVM_SCHEMA, lifecycle.JVM_PASS,
        tuple(case['id'] for case in model.cases([g['id'] for g in plan['graphs']])), verify_closure)
    return lifecycle._execute_validated(plan, output, contract)


def derive_and_execute(producer_root, raw_export_root, arm, raw_derivation_dir, output, port):
    output, raw = Path(output).resolve(), Path(raw_derivation_dir).resolve()
    require(output.is_dir() and not list(output.iterdir()), 'fresh empty correctness output')
    require(not output.is_relative_to(raw) and not raw.is_relative_to(output), 'separate derivation and HTTP output')
    controls = {str(module.resolve()): common.sha(module) for module in (Path(__file__), Path(lifecycle.__file__))}
    def consume(evidence, check):
        artifacts.verify_pins(controls)
        plan = _prepare_evidence(raw, output, port, evidence)
        def verify(actual):
            lifecycle.verify_closure(actual)
            check()
        return _execute_plan(plan, output, verify)
    return derive._run_and_consume_raw(producer_root, raw_export_root, arm, raw, consume)



def _same_oracle_plan(plan, expected):
    """Retain every legacy typed claim while comparing against fresh raw identities."""
    if set(plan) != set(expected): return False
    if common.typed({k: v for k, v in plan.items() if k != 'cases'}) != common.typed(
            {k: v for k, v in expected.items() if k != 'cases'}): return False
    if len(plan['cases']) != len(expected['cases']): return False
    for actual_case, expected_case in zip(plan['cases'], expected['cases']):
        if set(actual_case) != set(expected_case): return False
        if common.typed({k: v for k, v in actual_case.items() if k != 'oracleByArm'}) != common.typed(
                {k: v for k, v in expected_case.items() if k != 'oracleByArm'}): return False
        if set(actual_case['oracleByArm']) != set(expected_case['oracleByArm']): return False
        for arm, wanted in expected_case['oracleByArm'].items():
            actual = actual_case['oracleByArm'][arm]
            if 'digestKind' in actual:
                # Modern raw identity is an exact representation, not a marker
                # that can relabel an old typed digest or authorize inline data.
                if actual['digestKind'] != common.JVM_RAW_UNIVERSE_DIGEST: return False
                if common.typed(actual) != common.typed(wanted): return False
                continue
            # Archived plans did not declare digestKind. Both old representations
            # retain their full typed-digest claim and independently checked bytes.
            payload = 'valueRef' if 'valueRef' in actual else 'value'
            if set(actual) != {'kind', payload, 'digest', 'rows'}: return False
            if common.typed({k: actual[k] for k in ('kind', 'rows')}) != common.typed(
                    {k: wanted[k] for k in ('kind', 'rows')}): return False
            if payload == 'valueRef' and common.typed(actual['valueRef']) != common.typed(wanted['valueRef']):
                return False
            universe = common.jvm_oracle_value(wanted, pins=expected['pins'])
            legacy = universe if payload == 'valueRef' else common.jvm_oracle_value(actual, pins=plan['pins'])
            authoritative_digest = model.digest(universe)
            equal = (derive._same_typed_json(legacy, universe) and authoritative_digest == actual['digest'] and
                     (payload == 'valueRef' or model.digest(legacy) == actual['digest']))
            del universe, legacy
            if not equal: return False
    return True


def audit(directory):
    root = Path(directory).resolve()
    plan, record = common.read(root/'plan.json'), common.read(root/'record.json')
    require(plan['schema'] == PLAN and record['schema'] == lifecycle.JVM_SCHEMA and
            record['status'] == lifecycle.JVM_PASS and record['errors'] == [] and record['finalIdentity'] == 'PASS',
            'complete successful JVM35 execution')
    expected, replayed = _prepare_replayed(plan['rawDerivationRoot'], root, plan['port'])
    require(_same_oracle_plan(plan, expected), 'independent JVM plan differs from actual raw/source evidence')
    require(record['plan'] == artifacts.ref(root/'plan.json') and record['revision'] == plan['revision'] and
            record['role'] == plan['role'], 'same actual JVM execution plan')
    require(record['performanceAcceptance'] is False and record['completeSemanticEquivalence'] is False,
            'JVM correctness-only evidence')
    owner = common.read(root/'owner.json'); cleanup = record['cleanup']
    require(plan['argv'] == record['argv'] == owner['argv'], 'actual sealed MainKt server launch')
    require(type(owner['group']) is int and owner['group'] > 1 and type(owner['runnerPid']) is int and
            owner['runnerPid'] > 1 and cleanup['group'] == owner['group'] and cleanup['after'] == [] and
            cleanup['errors'] == [] and cleanup['exit'] in (0, 143, -15), 'owned terminal JVM cleanup')
    raw = (root/'readiness.body').read_bytes()
    require(record['readinessBody'] == artifacts.ref(root/'readiness.body') and
            common.parse(raw) == record['readiness'], 'complete actual JVM readiness body')
    common.validate_readiness_body(raw, {'graphs': plan['graphs'], 'readiness': plan['readiness']}, root/'data')
    receipts, evidence = audit_response_journal(root, record, plan['cases'], plan['expectationVariant'], pins=plan['pins'])
    lifecycle.verify_closure(plan); artifacts.verify_pins(evidence)
    pins = dict(plan['pins']); merge_pins(pins, evidence)
    return {'schema': 'graphite.jvm-query-correctness-audit.v1', 'status': AUDIT,
            'revision': plan['revision'], 'role': plan['role'], 'plan': artifacts.ref(root/'plan.json'),
            'record': artifacts.ref(root/'record.json'), 'sourceManifest': plan['sourceManifest'],
            'runtimeManifest': plan['runtimeManifest'], 'fixtureManifest': plan['fixtureManifest'],
            'rawDerivationReplay': replayed, 'sourceRuleAudit': plan['sourceRuleAudit'],
            'readiness': record['readiness'], 'cases': receipts, 'pins': pins,
            'independentRawExpectationsVerified': True, 'sourceRuleApplicabilityVerified': True,
            'all35ActualResponsesVerified': True, 'oracleAuthorityVerified': True,
            'fresh64Acceptance': False, 'performanceAcceptance': False, 'completeSemanticEquivalence': False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--raw-derivation', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--port', type=int)
    parser.add_argument('--audit-only', action='store_true')
    parser.add_argument('--producer-root', type=Path)
    parser.add_argument('--raw-export-root', type=Path)
    parser.add_argument('--arm', choices=('C', 'B'))
    args = parser.parse_args()
    fresh = any(value is not None for value in (args.producer_root, args.raw_export_root, args.arm))
    require(not fresh or (not args.audit_only and all(value is not None for value in
            (args.producer_root, args.raw_export_root, args.arm))), 'complete fresh derivation arguments')
    if args.audit_only:
        result = audit(args.output); print(json.dumps(result)); return 0
    require(args.raw_derivation is not None and args.port is not None, 'actual raw derivation and server port required')
    args.output.mkdir(parents=True, exist_ok=False)
    try:
        result = (derive_and_execute(args.producer_root, args.raw_export_root, args.arm,
                                    args.raw_derivation, args.output, args.port) if fresh else
                  execute(args.raw_derivation, args.output, args.port))
    except (OSError, ValueError, KeyError, TypeError) as error:
        common.save(args.output/'preparation-failure.json', {'status': 'FAIL', 'error': repr(error)})
        return 1
    print(json.dumps({'status': result['status'], 'errors': result['errors']}))
    return 0 if result['status'] == lifecycle.JVM_PASS else 1


if __name__ == '__main__': raise SystemExit(main())
