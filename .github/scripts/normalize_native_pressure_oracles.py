#!/usr/bin/env python3
"""Bind historical persisted-graph expectations to freshly audited HTTP bodies.

This adapts evidence shape only. It neither re-derives historical expectations
from fresh graphs nor establishes whole-graph semantic equivalence.
"""
import argparse
from pathlib import Path
import sys

sys.dont_write_bytecode = True
import audit_native_pressure_artifacts as artifacts
import audit_native_query_correctness as queries
import multigraph_pressure as common
import native_portable_oracles as portable

STATUS = 'PASS_NORMALIZED_PORTABLE_EXPECTATIONS_AND_FRESH39_RESPONSES'
SCOPE = 'historical-independent-persisted-oracle-and-fresh-response'
require = common.require


def build(directory, origin):
    require(origin in ('C', 'A', 'B'), 'exact native arm')
    root = Path(directory).resolve()
    fresh_ref = artifacts.ref(root.parent / 'query-correctness-audit.json')
    fresh = queries.audit(root)
    require(common.typed(common.read(fresh_ref['path'])) == common.typed(fresh),
            'fresh stored audit differs from raw evidence')
    plan = common.pinned_authority_metadata({'pins': fresh['pins']}, fresh['plan'])
    runtime = common.pinned_authority_metadata(plan, plan['runtimeManifest'])
    source_inputs = common.pinned_authority_metadata(plan, plan['sourceInputs'])
    bundle = portable.load(source_inputs=source_inputs)
    require(plan['cases'] == bundle['cases'] and len(plan['cases']) == 39 and
            len(fresh['cases']) == 39, 'complete predetermined39 scope')
    variant = plan['expectationVariant']
    require(variant == ('C' if plan['revision'] == portable.ACCEPTED else 'B'),
            'historical expectation variant')
    require(origin != 'C' or plan['revision'] == portable.ACCEPTED, 'accepted origin revision')
    require(plan['role'] == {'C': 'accepted-baseline', 'A': 'parent', 'B': 'candidate'}[origin],
            'actual execution role matches normalized origin')
    pins = dict(fresh['pins'])
    for path, digest in {**bundle['pins'], fresh_ref['path']: fresh_ref['sha256']}.items():
        require(path not in pins or pins[path] == digest, 'conflicting oracle input pin')
        pins[path] = digest
    cases, proofs, refs = {}, [], {}
    for case, response in zip(plan['cases'], fresh['cases']):
        require(case['id'] == response['case'] and case['id'] not in cases,
                'complete ordered unique responses')
        oracle = case['oracleByArm'][variant]
        expected = oracle.get('valueProof', oracle['proof'])
        require(pins.get(expected['path']) == expected['sha256'] and
                pins.get(response['body']['path']) == response['body']['sha256'],
                'actual expected payload and complete body pins')
        require(response['rows'] == oracle['rows'], 'complete expected row count')
        cases[case['id']] = {'request': case['request'], 'querySha256': case['querySha256'],
                            'targetGraphIds': case['targetGraphIds'], 'expected': {origin: expected}}
        proofs.append({'arm': origin, 'case': case['id'], 'expectedSha256': expected['sha256'],
                       'bodySha256': response['body']['sha256'], 'rows': oracle['rows']})
        refs[case['id']] = {'expected': expected, 'body': response['body']}
    normalized_plan = {'arms': {origin: {'revision': plan['revision'],
                         'serverArgv': [runtime['binary'], 'serve'], 'binary': runtime['binary'],
                         'graphs': plan['graphs']}}, 'graphIds': [g['id'] for g in plan['graphs']],
                       'cases': cases, 'pins': pins, 'freshPlan': fresh['plan'],
                       'freshAudit': fresh_ref, 'historicalExpectationRevisions': bundle['sourceRevisions'],
                       'authorityScope': SCOPE, 'completeSemanticEquivalence': False,
                       'performanceClaim': False}
    normalized_audit = {'status': STATUS, 'proofs': proofs, 'pins': pins,
                        'freshDirectory': str(root), 'origin': origin, 'freshAudit': fresh_ref,
                        'authorityScope': SCOPE, 'historicalExpectationRevisions': bundle['sourceRevisions'],
                        'completeSemanticEquivalence': False, 'performanceClaim': False}
    return normalized_plan, normalized_audit, refs


def normalize(directory, origin, output):
    plan, audit, references = build(directory, origin)
    output = Path(output).resolve()
    output.mkdir(parents=True, exist_ok=False)
    common.save(output / 'plan.json', plan)
    common.save(output / 'audit.json', audit)
    plan_ref, audit_ref = artifacts.ref(output / 'plan.json'), artifacts.ref(output / 'audit.json')
    cases = {key: dict(value, plan=plan_ref, audit=audit_ref) for key, value in references.items()}
    result = {'independentCaseOracles': {origin: cases},
              'pins': {**audit['pins'], plan_ref['path']: plan_ref['sha256'],
                       audit_ref['path']: audit_ref['sha256']},
              'completeSemanticEquivalence': False, 'performanceAcceptance': False}
    common.save(output / 'references.json', result)
    return result


def verify(packet, refs, origin):
    """Recompute the new shape from existing raw validators; never trust status alone."""
    plan = common.pinned_authority_metadata(packet, refs['plan'])
    audit = common.pinned_authority_metadata(packet, refs['audit'])
    require(audit['status'] == STATUS and audit['origin'] == origin, 'normalized actual arm')
    actual_plan, actual_audit, cases = build(audit['freshDirectory'], origin)
    require(common.typed(plan) == common.typed(actual_plan) and
            common.typed(audit) == common.typed(actual_audit), 'normalized evidence differs from raw audit')
    require(any(case['expected'] == refs['expected'] and case['body'] == refs['body']
                for case in cases.values()), 'normalized expected/body pair')
    return plan, audit


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', type=Path, required=True)
    parser.add_argument('--origin', choices=['C', 'A', 'B'], required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    normalize(args.directory, args.origin, args.output)


if __name__ == '__main__':
    main()
