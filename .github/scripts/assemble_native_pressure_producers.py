#!/usr/bin/env python3
"""Collect fresh CI artifact and query audits without inventing fixture equivalence.

The resulting bundle is a portable handoff of completed evidence, not a pressure
plan or performance pass. Missing cross-arm semantic authority remains explicit.
No compiler, writer, server or query is launched here.
"""
import argparse
from pathlib import Path
import re
import sys

sys.dont_write_bytecode = True
import audit_native_pressure_artifacts as artifacts
import audit_native_query_correctness as queries
import multigraph_pressure as common
from prepare_native_pressure_plan import native_build_identity, normalize_source_files

SCHEMA = 'graphite.native-pressure.producer-bundle.v1'
STATUS = 'ARTIFACTS_AND39_QUERIES_AUDITED_EQUIVALENCE_PENDING'
CORRECTED_STATUS = 'ARTIFACTS_QUERIES_AND_CORRECTED_CORE_COMPARABILITY_AUDITED'
CORRECTED_MODEL = 'complete-core-topology-index-with-source-corrections-and-additive-declarations-v1'
require = common.require


def merge_pins(target, incoming):
    for path, digest in incoming.items():
        require(Path(path).is_absolute() and str(Path(path).resolve()) == path and
                common.valid_digest(digest), 'canonical complete evidence pin')
        require(path not in target or target[path] == digest, 'conflicting evidence pin: ' + path)
        target[path] = digest


def audited_arm(root, revision, role, source_inputs):
    require(root.is_dir() and not root.is_symlink(), 'actual arm directory')
    artifact_ref = artifacts.ref(root / 'artifact-audit.json')
    query_ref = artifacts.ref(root / 'query-correctness-audit.json')
    artifact = common.read(artifact_ref['path'])
    observed = common.read(query_ref['path'])
    require(artifact['schema'] == 'graphite.native-independent-artifact-audit.v1' and
            artifact['status'] == artifacts.STATUS and artifact['revision'] == revision and
            artifact['writerRevision'] == revision and artifact['role'] == role,
            'exact independently audited writer and runtime')
    require(observed['schema'] == 'graphite.native-query-correctness-audit.v1' and
            observed['status'] == queries.STATUS and observed['revision'] == revision and
            observed['role'] == role and observed['artifactAudit'] == artifact_ref,
            'exact fresh query audit and artifact link')
    require(all(artifact[key] is False for key in
                ('completeSemanticEquivalence', 'performanceAcceptance', 'acceptanceEligible')) and
            observed['completeSemanticEquivalence'] is False and observed['performanceAcceptance'] is False,
            'preserve original partial proof scope')
    # Re-read every response and the saved readiness counts. An audit status
    # string alone cannot stand in for raw current-revision evidence.
    require(common.typed(queries.audit(root / 'query-correctness')) == common.typed(observed),
            'stored audit differs from independently recomputed raw query evidence')
    pins = dict(observed['pins'])
    merge_pins(pins, artifact['pins'])
    merge_pins(pins, {ref['path']: ref['sha256'] for ref in (artifact_ref, query_ref)})
    plan = common.pinned_authority_metadata({'pins': pins}, observed['plan'])
    require(plan['sourceInputs'] == source_inputs, 'all writers use the same source input manifest')
    source = common.pinned_authority_metadata({'pins': pins}, artifact['sourceManifest'])
    runtime = common.pinned_authority_metadata({'pins': pins}, artifact['runtimeManifest'])
    fixture = common.pinned_authority_metadata({'pins': pins}, artifact['fixtureManifest'])
    require(source['revision'] == runtime['revision'] == revision and
            source['files'] == runtime['sourceFiles'] == artifact['sourceBefore'] == artifact['sourceAfter'],
            'complete exact source closure')
    for relative, digest in normalize_source_files(source).items():
        require(pins.get(str((Path(source['root']) / relative).resolve())) == digest,
                'full source closure pin')
    require(runtime['sourceManifestSha256'] == fixture['sourceManifestSha256'] ==
            artifact['sourceManifest']['sha256'] and
            runtime['sha256'] == artifact['binarySha256'] == pins.get(runtime['binary']),
            'exact source, runtime and writer manifests')
    require(len(plan['cases']) == len(observed['cases']) == 39 and
            plan['graphs'] == artifact['graphs'], 'complete fresh case and graph inventory')
    cases = []
    for case, receipt in zip(plan['cases'], observed['cases']):
        require(case['id'] == receipt['case'] and case['targetGraphIds'] == receipt['targetGraphIds'],
                'fresh query case scope')
        cases.append({'id': case['id'], 'request': case['request'],
                      'targetGraphIds': case['targetGraphIds'],
                      'oracle': case['oracleByArm'][plan['expectationVariant']],
                      'observed': receipt})
    arm = {'revision': revision, 'artifactRole': role, 'patchSha256': None,
           'sourceManifest': artifact['sourceManifest'], 'runtimeManifest': artifact['runtimeManifest'],
           'fixtureManifest': artifact['fixtureManifest'], 'artifactAudit': artifact_ref,
           'queryAudit': query_ref, 'runtimeFiles': runtime['files'],
           'runtimeRoots': [str(root / 'runtime')], 'serverArgv': [runtime['binary'], 'serve'],
           'graphs': artifact['graphs'], 'readiness': plan['readiness'], 'cases': cases}
    return arm, pins, native_build_identity(runtime)


def assemble(artifacts_root, base_sha, candidate_sha):
    root = Path(artifacts_root).resolve()
    require(all(re.fullmatch('[0-9a-f]{40}', value) for value in (base_sha, candidate_sha)),
            'full actual revisions')
    directories_ref = artifacts.ref(root / 'arm-directories.json')
    source_inputs = artifacts.ref(root / 'source-inputs.json')
    directories = common.read(directories_ref['path'])
    expected = {'C': 'C', 'A': 'C' if base_sha == artifacts.ACCEPTED else 'A', 'B': 'B'}
    require(directories['schema'] == 'graphite.native-artifact-arm-directories.v1' and
            directories['directories'] == expected and directories['acceptedRevision'] == artifacts.ACCEPTED and
            directories['baseRevision'] == base_sha and directories['candidateRevision'] == candidate_sha and
            directories['status'] == 'ARTIFACTS_ONLY_INDEPENDENT_PROOFS_REQUIRED' and
            directories['performanceAcceptance'] is False, 'exact arm directories and permitted A/C alias')
    pins = {ref['path']: ref['sha256'] for ref in (directories_ref, source_inputs)}
    arms, identities, audited = {}, [], {}
    for name, revision, role in [('C', artifacts.ACCEPTED, 'accepted-baseline'),
                                 ('A', base_sha, 'parent'), ('B', candidate_sha, 'candidate')]:
        directory = expected[name]
        if directory not in audited:
            audited[directory] = audited_arm(root / directory, revision, role, source_inputs)
        arm, evidence, identity = audited[directory]
        require(arm['revision'] == revision, 'aliased artifacts must have the exact same revision')
        arms[name] = dict(arm, role=role)
        merge_pins(pins, evidence)
        identities.append(identity)
    require(all(identity == identities[0] for identity in identities), 'matched native build toolchains')
    artifacts.verify_pins(pins)
    packet = {'schema': SCHEMA, 'status': STATUS, 'artifactsRoot': str(root),
            'acceptedRevision': artifacts.ACCEPTED, 'baseRevision': base_sha, 'candidateRevision': candidate_sha,
            'armDirectories': directories_ref, 'sourceInputs': source_inputs, 'arms': arms, 'pins': pins,
            'completeSemanticEquivalence': False, 'performanceAcceptance': False, 'acceptanceEligible': False,
            'missingAuthority': ['Complete C/A/B core, topology and index semantic equivalence or independently '
                                 'proven source corrections; fresh39 response correctness does not establish this.']}
    required = [name for name in ('A', 'B') if expected[name] != 'C']
    paths = {name: root / expected[name] / 'core-equivalence/audit.json' for name in required}
    if all(path.is_file() for path in paths.values()):
        comparisons = {name: corrected_comparison(packet, name, path) for name, path in paths.items()}
        packet.update(status=CORRECTED_STATUS, comparisonModel=CORRECTED_MODEL,
                      correctedComparisons=comparisons, missingAuthority=[],
                      strictEquivalence=False, sourceToDeclarationCompletenessClaim=False)
    return packet


def corrected_comparison(packet, arm_id, path):
    """Rebind the actual complete pair; no partial upstream claim is promoted."""
    import run_native_core_equivalence as core
    ref = artifacts.ref(path)
    actual = core.audit(path.parent)
    require(common.typed(common.read(path)) == common.typed(actual), 'raw complete comparison audit changed')
    require(actual['schema'] == 'graphite.native-core-equivalence-audit.v1' and
            actual['status'] == core.AUDIT_PASS and actual['completeCoreTopologyIndexComparison'] is True and
            actual['productionFormatterTestsVerified'] is True and
            actual['proofModel'] == 'EXPLICIT_CLASSFILE_AND_FORMATTER_SOURCE_CORRECTIONS_WITH_ADDITIVE_DECLARATION_VALIDITY' and
            all(actual[k] is False for k in core.FALSE_CLAIMS), 'scoped complete corrected comparison')
    arms = packet['arms']
    require(actual['revisions'] == {'C': arms['C']['revision'], 'B': arms[arm_id]['revision']} and
            actual['fixtureManifests'] == {'C': arms['C']['fixtureManifest'], 'B': arms[arm_id]['fixtureManifest']},
            'comparison exact arm revisions and manifests')
    record = common.pinned_authority_metadata(actual, actual['record'])
    plan = common.pinned_authority_metadata(actual, record['plan'])
    require('rawLocalExports' in plan and len(plan['rawLocalExports']) == 8 and
            actual['phases'] == plan['maxOwnedPhases'] == 139, 'complete actual raw Local phases required')
    ids = [g['id'] for g in arms['C']['graphs']]
    require(len(ids) == len(set(ids)) == 64 and ids == [g['id'] for g in arms[arm_id]['graphs']] ==
            [g['id'] for g in plan['graphs']] == [g['id'] for g in actual['graphs']], 'complete ordered64 comparison')
    raw_refs = []
    for c, b, row, receipt in zip(arms['C']['graphs'], arms[arm_id]['graphs'], plan['graphs'], actual['graphs']):
        require(row['C'] == c['path'] and row['B'] == b['path'], 'comparison actual graph roots')
        raw = artifacts.ref(path.parent / 'graphs' / row['id'] / 'core/raw-local-type-proof.json')
        report = common.pinned_authority_metadata(actual, raw)
        require(report['status'] == 'PASS_ALL_PERSISTED_ARRAY_LOCALS_RAW_TYPE' and
                report['completeNodeInventory'] is True and report['unprovedCount'] == 0 and
                report['strictEquivalence'] is report['completeSemanticEquivalence'] is
                report['performanceAcceptance'] is False, 'complete scoped raw array authority')
        require(report['localCount'] == len(report['occurrences']) and
                report['arrayCount'] == sum(r['status'] == 'PASS_ARRAY' for r in report['occurrences']) and
                all(r['graphId'] == row['id'] and r['status'] in ('PASS_ARRAY', 'NON_ARRAY') and
                    r['issues'] == [] for r in report['occurrences']), 'no missing conflicting or unproved raw locals')
        require(len(report['inputs']) == 2 and all(actual['pins'].get(p) == h for p, h in report['inputs'].items()),
                'both actual raw export input closure')
        require(all(actual['pins'].get(p) == h for p, h in receipt['files'].items()), 'complete raw graph proof closure')
        raw_refs.append(raw)
    for name in ('C', arm_id):
        for key in ('artifactAudit', 'fixtureManifest', 'sourceManifest', 'runtimeManifest'):
            upstream = arms[name][key]
            require(actual['pins'].get(upstream['path']) == upstream['sha256'], 'comparison actual producer upstream')
    merge_pins(packet['pins'], actual['pins'])
    merge_pins(packet['pins'], {ref['path']: ref['sha256']})
    return {'audit': ref, 'rawArrayProofs': raw_refs, 'revisions': actual['revisions'],
            'fixtureManifests': actual['fixtureManifests'],
            'correctionCounts': {key: actual[key] for key in core.COUNTS}}


def verify_bundle(packet, base_sha, candidate_sha):
    require(packet['schema'] == SCHEMA and packet['baseRevision'] == base_sha and
            packet['candidateRevision'] == candidate_sha, 'actual producer bundle identity')
    actual = assemble(packet['artifactsRoot'], base_sha, candidate_sha)
    require(common.typed(packet) == common.typed(actual), 'producer bundle differs from current raw evidence')
    return actual


def corrected_fixture_bindings(packet):
    require(packet['status'] == CORRECTED_STATUS and packet['comparisonModel'] == CORRECTED_MODEL and
            packet['missingAuthority'] == [] and packet['completeSemanticEquivalence'] is
            packet['strictEquivalence'] is packet['sourceToDeclarationCompletenessClaim'] is
            packet['performanceAcceptance'] is packet['acceptanceEligible'] is False,
            'explicit corrected scope without promoted claims')
    expected = {'B'} | (set() if common.same_accepted_artifacts(packet, 'A') else {'A'})
    require(set(packet['correctedComparisons']) == expected, 'all nonaccepted arms have actual comparisons')
    roots = {Path(g['path']) for arm in packet['arms'].values() for g in arm['graphs']}
    return {'graphsByArm': {key: arm['graphs'] for key, arm in packet['arms'].items()},
            'fixtureFiles': {path: digest for path, digest in packet['pins'].items()
                             if any(Path(path).is_relative_to(root) for root in roots)},
            'realPersistedGraphs': True, 'completeSemanticEquivalence': False,
            'strictEquivalence': False, 'sourceToDeclarationCompletenessClaim': False,
            'comparisonModel': CORRECTED_MODEL, 'correctedComparisons': packet['correctedComparisons']}


def pressure_arms(packet):
    # The fresh query auditor checks the raw /api/graphs body and independently
    # recomputes these saved counts; only the older pressure shape adds the path.
    return {name: dict(arm, readiness={**arm['readiness'], 'path': '/api/graphs'})
            for name, arm in packet['arms'].items()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--artifacts', type=Path, required=True)
    parser.add_argument('--base-sha', required=True)
    parser.add_argument('--candidate-sha', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    try:
        result = assemble(args.artifacts, args.base_sha, args.candidate_sha)
    except (OSError, ValueError, KeyError, TypeError) as error:
        common.save(args.output / 'assembly-status.json',
                    {'status': 'FAIL', 'error': repr(error), 'performanceAcceptance': False})
        return 1
    common.save(args.output / 'packet.json', result)
    common.save(args.output / 'assembly-status.json',
                {'status': result['status'], 'packet': artifacts.ref(args.output / 'packet.json'),
                 'missingAuthority': result['missingAuthority'], 'performanceAcceptance': False})
    return 0


if __name__ == '__main__':
    sys.exit(main())
