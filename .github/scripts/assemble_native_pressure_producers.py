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
    return {'schema': SCHEMA, 'status': STATUS, 'artifactsRoot': str(root),
            'acceptedRevision': artifacts.ACCEPTED, 'baseRevision': base_sha, 'candidateRevision': candidate_sha,
            'armDirectories': directories_ref, 'sourceInputs': source_inputs, 'arms': arms, 'pins': pins,
            'completeSemanticEquivalence': False, 'performanceAcceptance': False, 'acceptanceEligible': False,
            'missingAuthority': ['Complete C/A/B core, topology and index semantic equivalence or independently '
                                 'proven source corrections; fresh39 response correctness does not establish this.']}


def verify_bundle(packet, base_sha, candidate_sha):
    require(packet['schema'] == SCHEMA and packet['baseRevision'] == base_sha and
            packet['candidateRevision'] == candidate_sha, 'actual producer bundle identity')
    actual = assemble(packet['artifactsRoot'], base_sha, candidate_sha)
    require(common.typed(packet) == common.typed(actual), 'producer bundle differs from current raw evidence')
    return actual


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
                {'status': STATUS, 'packet': artifacts.ref(args.output / 'packet.json'),
                 'missingAuthority': result['missingAuthority'], 'performanceAcceptance': False})
    return 0


if __name__ == '__main__':
    sys.exit(main())
