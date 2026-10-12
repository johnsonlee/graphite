"""Tiny packet contracts, not actual upstream execution or performance evidence.

Only JVM correctness/core audit boundaries are modeled. All metadata files,
ordered35 universes, pins, plan/proof checks and request digests are real.
"""
import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import prepare_jvm_pressure_plan as p
import test_jvm_pressure_oracles as oracle_fixtures


class JvmPreparationTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory(); self.addCleanup(temp.cleanup)
        self.root = Path(temp.name).resolve()
        self.java = self.root/'jdk/bin/java'; self.java.parent.mkdir(parents=True); self.java.write_bytes(b'tiny JVM identity')
        self.observed = {}; self.roots = {}; self.comparison_calls = []
        for name, revision, role in [('C', p.artifacts.ACCEPTED, 'accepted-baseline'),
                                     ('A', 'a'*40, 'parent'), ('B', 'b'*40, 'candidate')]:
            self.roots[name] = str(self.make_arm(name, revision, role))
        self.queries = patch.object(p.queries, 'audit', side_effect=lambda root: copy.deepcopy(self.observed[str(root)]))
        self.audit_mock = self.queries.start(); self.addCleanup(self.queries.stop)
        self.core = patch.object(p.assembled, 'corrected_comparison', side_effect=self.compare)
        self.core.start(); self.addCleanup(self.core.stop)
        self.no_child = patch('subprocess.Popen', side_effect=AssertionError('no actual child or JVM'))
        self.no_child.start(); self.addCleanup(self.no_child.stop)

    def write(self, path, value):
        path.parent.mkdir(parents=True, exist_ok=True); path.write_text(json.dumps(value, indent=2)+'\n')
        return p.artifacts.ref(path)

    def make_arm(self, name, revision, role):
        root = self.root/name; producer = root/'producer'; http = root/'http'; raw_dir = root/'raw'
        pins = {str(self.java): p.common.sha(self.java)}
        runtime_files = {}
        for leaf in ('graphite.jar', 'writer.jar'):
            path = producer/'runtime'/leaf; path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes((name+leaf).encode())
            runtime_files[str(path)] = p.common.sha(path)
        pins.update(runtime_files)
        refs = {}
        refs['sourceManifest'] = self.write(producer/'source.json', {'revision': revision, 'root': str(root/'source')})
        refs['runtimeManifest'] = self.write(producer/'runtime.json', {'revision': revision, 'files': runtime_files})
        graphs = []
        for gid in p.model.FIXTURE_GRAPH_IDS:
            path = producer/'graphs'/gid; path.mkdir(parents=True); (path/'tiny').write_bytes(b'not a real graph')
            pins[str(path/'tiny')] = p.common.sha(path/'tiny'); graphs.append({'id': gid, 'path': str(path)})
        refs['fixtureManifest'] = self.write(producer/'fixture.json', {'writerRevision': revision, 'graphs': graphs})
        artifact_ref = self.write(producer/'artifact-audit.json', {'revision': revision, 'role': role, 'graphs': graphs, **refs})
        for ref in (*refs.values(), artifact_ref): pins[ref['path']] = ref['sha256']
        raw = {'revision': revision, 'role': role, 'producerRoot': str(producer), 'coreRoot': str(root/'core'),
               'java': str(self.java), **refs}
        raw_ref = self.write(raw_dir/'plan.json', raw); pins[raw_ref['path']] = raw_ref['sha256']
        cases = p.model.cases(); universes = {}; variant = 'C' if name == 'C' else 'B'
        model_test = oracle_fixtures.JvmPressureOracleTests(); model_test.setUp()
        for case in cases:
            entries = []
            if case['family'] == 'full-projection':
                entries = [(p.model.projected_row({'value': {'id': 7, 'graphId': gid}}, [gid]), 1)
                           for gid in case['targetGraphIds']]
            value = model_test.universe(entries, case); ref = self.write(raw_dir/'universes'/(case['id']+'.json'), value)
            pins[ref['path']] = ref['sha256']; universes[case['id']] = ref
            case['oracleByArm'] = {variant: {'kind': 'jvm-complete-legal-limit-multiset-v1', 'value': value,
                                            'digest': p.model.digest(value), 'rows': value['totalMatches']}}
        jar = p.artifacts.ref(producer/'runtime/graphite.jar')
        plan = {'rawDerivationRoot': str(raw_dir), 'serverJar': jar, 'graphs': graphs, 'cases': cases,
                'rawDerivationEvidence': {'record': {'tiny': 'raw record'}, 'universes': universes}, 'expectationVariant': variant,
                'data': str(http/'data'), 'port': 23800+ord(name),
                'readiness': {'expected': {'count': 64, 'graphs': graphs}}}
        plan['argv'] = [str(self.java), p.queries.CORRECTNESS_HEAP, '-XX:ActiveProcessorCount=4', '-cp', jar['path'],
            'io.johnsonlee.graphite.cli.MainKt', 'serve', '--data', plan['data'], '--port', str(plan['port']),
            '--load-mode', 'MAPPED', '--max-concurrent-cypher', '4', '--cypher-max-timeout-ms', '240000',
            *p.common.graph_arguments(graphs)]
        plan_ref = self.write(http/'plan.json', plan); pins[plan_ref['path']] = plan_ref['sha256']
        result = {'schema': 'graphite.jvm-query-correctness-audit.v1', 'status': p.queries.AUDIT,
            'revision': revision, 'role': role, **refs, 'plan': plan_ref, 'pins': pins,
            'rawDerivationReplay': dict(plan['rawDerivationEvidence'], completeRawDerivationReplayed=True),
            'cases': [{'case': c['id'], 'targetGraphIds': c['targetGraphIds']} for c in cases],
            'independentRawExpectationsVerified': True, 'sourceRuleApplicabilityVerified': True,
            'all35ActualResponsesVerified': True, 'oracleAuthorityVerified': True,
            'fresh64Acceptance': False, 'performanceAcceptance': False, 'completeSemanticEquivalence': False}
        self.write(http/'audit.json', result); self.observed[str(http)] = result
        self.write(root/'core/audit.json', {'tiny': 'modeled corrected comparison boundary'})
        return http

    def compare(self, packet, arm, path):
        self.comparison_calls.append(arm)
        ref = p.artifacts.ref(path); p.merge_pins(packet['pins'], {ref['path']: ref['sha256']})
        return {'audit': ref, 'rawArrayProofs': [], 'revisions': {'C': p.artifacts.ACCEPTED, 'B': packet['arms'][arm]['revision']}}

    def packet(self, alias=False):
        roots = dict(self.roots)
        if alias: roots['A'] = roots['C']
        return p.assemble(roots, p.artifacts.ACCEPTED if alias else 'a'*40, 'b'*40)

    def bound_plan(self, alias=False):
        packet = self.packet(alias); output = self.root/'prepared'; output.mkdir()
        ref = self.write(output/'packet.json', packet)
        # The preparation manifest is independently maintained by root; a
        # modeled tiny fixture must not silently repin the live control file.
        with patch('prepare_native_pressure_plan.preparation_control_pins', return_value={}):
            plan = p.bind_plan(packet, ref, output, p.CATALOG)
        return packet, plan

    def test_legacy_typed_reference_packet_keeps_original_identity_after_replay(self):
        packet = self.packet()
        for arm in packet['arms'].values():
            for row in arm['cases']:
                oracle = row['oracle']; oracle.pop('digestKind')
                oracle['digest'] = p.model.digest(p.common.read(oracle['valueRef']['path']))
        before = copy.deepcopy(packet)
        self.assertEqual(before, p.verify_bundle(packet, 'a'*40, 'b'*40))
        self.assertEqual(before, packet)
        for mutation in ('typed-digest', 'foreign-ref', 'wrong-kind', 'raw-digest', 'raw-inline'):
            bad = copy.deepcopy(packet); oracle = bad['arms']['B']['cases'][0]['oracle']
            if mutation == 'typed-digest': oracle['digest'] = 'f'*64
            elif mutation == 'foreign-ref': oracle['valueRef'] = bad['arms']['B']['cases'][1]['oracle']['valueRef']
            elif mutation == 'wrong-kind': oracle['digestKind'] = 'unknown'
            elif mutation == 'raw-digest': oracle['digestKind'] = p.common.JVM_RAW_UNIVERSE_DIGEST
            else:
                oracle['value'] = p.common.read(oracle.pop('valueRef')['path'])
                oracle['digestKind'] = p.common.JVM_RAW_UNIVERSE_DIGEST
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                p.verify_bundle(bad, 'a'*40, 'b'*40)

    def test_legacy_inline_signed_zero_digest_cannot_override_raw_payload(self):
        # common.typed considers signed zero equal; the historical model digest
        # did not. Compatibility must verify both against the bound payload.
        value = {'finite': 0.0}; ref = self.write(self.root/'signed-zero.json', value)
        expected = {'kind': 'jvm-complete-legal-limit-multiset-v1', 'valueRef': ref,
                    'digestKind': p.common.JVM_RAW_UNIVERSE_DIGEST, 'digest': ref['sha256']}
        inline = {'kind': expected['kind'], 'value': {'finite': -0.0},
                  'digest': p.model.digest({'finite': -0.0})}
        self.assertEqual(p.common.typed(value), p.common.typed(inline['value']))
        with self.assertRaisesRegex(ValueError, 'actual independent payload'):
            p._normalized_legacy_oracle(inline, expected, {ref['path']: ref['sha256']})

    def test_correctness_unknown_or_raw_inline_identity_is_rejected(self):
        root = Path(self.roots['B']); plan = p.common.read(root/'plan.json')
        case = plan['cases'][0]; ref = plan['rawDerivationEvidence']['universes'][case['id']]
        for identity in ('unknown', p.common.JVM_RAW_UNIVERSE_DIGEST):
            case['oracleByArm']['B']['digestKind'] = identity
            with self.subTest(identity=identity), self.assertRaises(ValueError):
                p._pressure_oracle(case, 'B', ref, p.artifacts.ref(root/'audit.json'), self.observed[str(root)]['pins'])

    def test_actual_three_audits_and_each_comparison_once_with_all35_oracles(self):
        packet = self.packet()
        self.assertEqual(3, self.audit_mock.call_count); self.assertEqual(['A', 'B'], self.comparison_calls)
        for arm in packet['arms'].values():
            self.assertEqual(35, len(arm['cases'])); self.assertEqual(4*1024**3, arm['maxHeapBytes']); self.assertEqual('-Xmx4g', arm['serverArgv'][1])
            self.assertEqual('io.johnsonlee.graphite.cli.MainKt', arm['serverArgv'][5])
        for key in ('strictEquivalence', 'completeSemanticEquivalence', 'sourceToDeclarationCompletenessClaim',
                    'performanceAcceptance', 'acceptanceEligible'): self.assertIs(False, packet[key])
        self.assertEqual(p.assembled.CORRECTED_MODEL, p.fixture_bindings(packet)['comparisonModel'])

    def rewrite_query_plan(self, root, plan):
        audit = self.observed[str(root)]
        ref = self.write(root/'plan.json', plan)
        audit['plan'] = ref; audit['pins'][ref['path']] = ref['sha256']
        self.write(root/'audit.json', audit)

    def test_legacy_inline_correctness_emits_reference_only_packet_and_plan(self):
        packet, plan = self.bound_plan()
        for name, arm in packet['arms'].items():
            for row in arm['cases']:
                oracle = row['oracle']
                self.assertNotIn('value', oracle)
                self.assertEqual(oracle['valueRef'], oracle['valueProof'])
                self.assertEqual(p.common.JVM_RAW_UNIVERSE_DIGEST, oracle['digestKind'])
                self.assertEqual(oracle['valueRef']['sha256'], oracle['digest'])
                self.assertEqual(packet['pins'][oracle['valueRef']['path']], oracle['valueRef']['sha256'])
        for case in plan['cases']:
            for oracle in case['oracleByArm'].values():
                self.assertNotIn('value', oracle)
                self.assertIn('valueRef', oracle)
        self.assertEqual(packet, p.verify_bundle(packet, 'a'*40, 'b'*40))

    def test_reference_correctness_preserves_exact_independent_payload_binding(self):
        root = Path(self.roots['B']); plan = p.common.read(root/'plan.json')
        for case in plan['cases']:
            oracle = case['oracleByArm']['B']; oracle.pop('value')
            oracle['valueRef'] = plan['rawDerivationEvidence']['universes'][case['id']]
        self.rewrite_query_plan(root, plan)
        arm, _, _ = p.audited_arm(root, 'b'*40, 'candidate')
        self.assertTrue(all('value' not in row['oracle'] for row in arm['cases']))
        self.assertEqual([case['oracleByArm']['B']['valueRef'] for case in plan['cases']],
                         [row['oracle']['valueRef'] for row in arm['cases']])

    def test_reference_payload_read_once_by_original_compiler(self):
        root = Path(self.roots['B']); plan = p.common.read(root/'plan.json')
        case = plan['cases'][0]; ref = plan['rawDerivationEvidence']['universes'][case['id']]
        oracle = case['oracleByArm']['B']; oracle.pop('value'); oracle['valueRef'] = ref
        oracle.update(digestKind=p.common.JVM_RAW_UNIVERSE_DIGEST, digest=ref['sha256'])
        reads = []; read = Path.read_bytes
        def observed(path):
            if str(path) == ref['path']: reads.append(str(path))
            return read(path)
        digest = p.model.digest
        def request_digest_only(value):
            self.assertFalse(type(value) is dict and value.get('schema') in
                             ('graphite.jvm-legal-row-universe.v1', 'graphite.jvm-distinct-row-universe.v2'),
                             'raw identity does not build a typed universe digest')
            return digest(value)
        with patch.object(Path, 'read_bytes', new=observed), \
                patch.object(p.model, 'digest', side_effect=request_digest_only), \
                patch.object(p.model, 'CompiledLegalLimitOracle', wraps=p.model.CompiledLegalLimitOracle) as compiler:
            result = p._pressure_oracle(case, 'B', ref, p.artifacts.ref(root/'audit.json'),
                                        self.observed[str(root)]['pins'])
        self.assertEqual([ref['path']], reads)
        self.assertEqual(1, compiler.call_count)
        self.assertEqual(ref, result['valueRef']); self.assertNotIn('value', result)

    def test_reference_substitution_ambiguous_shape_and_byte_drift_fail(self):
        root = Path(self.roots['B']); original = p.common.read(root/'plan.json')
        for mutation in ('foreign-ref', 'both', 'neither', 'bytes'):
            plan = copy.deepcopy(original); oracle = plan['cases'][0]['oracleByArm']['B']
            ref = plan['rawDerivationEvidence']['universes'][plan['cases'][0]['id']]
            value = oracle.pop('value'); oracle['valueRef'] = ref
            changed = Path(ref['path']); raw = changed.read_bytes()
            if mutation == 'foreign-ref': oracle['valueRef'] = plan['rawDerivationEvidence']['universes'][plan['cases'][1]['id']]
            elif mutation == 'both': oracle['value'] = value
            elif mutation == 'neither': oracle.pop('valueRef')
            else: changed.write_bytes(raw+b' ')
            self.rewrite_query_plan(root, plan)
            try:
                with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                    p.audited_arm(root, 'b'*40, 'candidate')
            finally: changed.write_bytes(raw)

    def test_pressure_reference_requires_exact_raw_payload_pin(self):
        root = Path(self.roots['B']); plan = p.common.read(root/'plan.json')
        case = plan['cases'][0]; ref = plan['rawDerivationEvidence']['universes'][case['id']]
        audit = self.observed[str(root)]
        for replacement in (None, 'f'*64):
            pins = dict(audit['pins'])
            if replacement is None: pins.pop(ref['path'])
            else: pins[ref['path']] = replacement
            with self.subTest(replacement=replacement), self.assertRaises(ValueError):
                p._pressure_oracle(case, 'B', ref, p.artifacts.ref(root/'audit.json'), pins)

    def test_legacy_packet_replay_keeps_archived_shape_without_ignoring_payload(self):
        packet = self.packet()
        for arm in packet['arms'].values():
            for row in arm['cases']:
                oracle = row['oracle']; ref = oracle.pop('valueRef')
                oracle['value'] = p.common.read(ref['path'])
                oracle.pop('digestKind'); oracle['digest'] = p.model.digest(oracle['value'])
        original = copy.deepcopy(packet)
        self.assertEqual(original, p.verify_bundle(packet, 'a'*40, 'b'*40))
        self.assertEqual(original, packet)
        bad = copy.deepcopy(packet)
        bad['arms']['B']['cases'][0]['oracle']['value']['totalMatches'] = 1
        with self.assertRaisesRegex(ValueError, 'differs from actual independent payload'):
            p.verify_bundle(bad, 'a'*40, 'b'*40)

    def test_previous26_and34_audits_cannot_authorize_new35_pressure(self):
        root = Path(self.roots['B']); original = copy.deepcopy(self.observed[str(root)])
        for count in (26, 34):
            value = copy.deepcopy(original)
            value['status'] = f'PASS_ALL{count}_JVM_RESPONSES_INDEPENDENT_RAW_AUDIT'
            value[f'all{count}ActualResponsesVerified'] = value.pop('all35ActualResponsesVerified')
            self.observed[str(root)] = value
            self.write(root/'audit.json', value)
            with self.subTest(count=count), self.assertRaisesRegex(ValueError, 'complete actual independently audited JVM35'):
                p.audited_arm(root, 'b'*40, 'candidate')

    def test_new_claim_cannot_hide_missing_raw_zero_response(self):
        root = Path(self.roots['B']); value = self.observed[str(root)]
        self.assertEqual('global-wide-four-properties-zero', value['cases'][-1]['case'])
        value['cases'].pop()
        self.write(root/'audit.json', value)
        with self.assertRaisesRegex(ValueError, 'complete JVM35 case inventory'):
            p.audited_arm(root, 'b'*40, 'candidate')

    def test_same4f_parent_reuses_exact_audit_and_actual_artifact_role(self):
        packet = self.packet(True)
        self.assertEqual(2, self.audit_mock.call_count); self.assertEqual(['B'], self.comparison_calls)
        self.assertTrue(p.common.same_accepted_artifacts(packet, 'A'))
        self.assertEqual('parent', packet['arms']['A']['role']); self.assertEqual('accepted-baseline', packet['arms']['A']['artifactRole'])
        for base, roots in [('a'*40, {**self.roots, 'A': self.roots['C']}),
                            (p.artifacts.ACCEPTED, self.roots), ('a'*40, {**self.roots, 'B': self.roots['A']})]:
            with self.assertRaisesRegex(ValueError, 'same4f'): p.assemble(roots, base, 'b'*40)

    def test_bound_plan_real_proofs_legal_payloads_and_single_bundle_replay(self):
        packet, plan = self.bound_plan()
        with patch.object(p, 'verify_bundle', wraps=p.verify_bundle) as replay:
            self.assertEqual(plan, p.common.validate_plan(plan))
            self.assertEqual(1, replay.call_count)
        self.assertEqual('PASS', p.common.verify_inputs(plan)['status'])
        self.assertEqual(7, len(plan['proofs'])); self.assertEqual(list('CABBAC'), [c['arm'] for c in plan['cells']])
        self.assertEqual([], plan['coverage']['unavailableFamilies'])
        claims = next(r for r in plan['proofs'] if r['role'] == 'independent-correctness' and r['arm'] == 'B')['bindings']
        self.assertEqual(p.common.digest_bytes(p.common.canonical(plan['cases'][0]['request'])), claims['requestDigests']['dynamic-miss'])
        self.assertNotEqual(plan['cases'][0]['requestSha256'], claims['requestDigests']['dynamic-miss'])
        self.assertEqual({case['id']: p.common.JVM_RAW_UNIVERSE_DIGEST for case in plan['cases']}, claims['oracleDigestKinds'])
        for case in plan['cases']:
            self.assertEqual(64, len(case['requestedGraphIds']))
            self.assertEqual(2 if case['family'] in ('graph-routing', 'full-projection') else 64, len(case['targetGraphIds']))

    def test_stored_audit_mismatch_missing_claim_and_observed_digest_not_authority(self):
        root = Path(self.roots['B']); audit = self.observed[str(root)]
        path = root/'audit.json'; original = path.read_bytes(); path.write_text('{}')
        with self.assertRaisesRegex(ValueError, 'stored JVM35'): p.audited_arm(root, 'b'*40, 'candidate')
        path.write_bytes(original); audit['oracleAuthorityVerified'] = False
        with self.assertRaisesRegex(ValueError, 'complete actual'): p.audited_arm(root, 'b'*40, 'candidate')
        audit['oracleAuthorityVerified'] = True
        plan = p.common.read(root/'plan.json'); plan['cases'][0]['oracleByArm']['B']['digest'] = 'f'*64
        ref = self.write(root/'plan.json', plan); audit['plan'] = ref; audit['pins'][ref['path']] = ref['sha256']; self.write(path, audit)
        with self.assertRaisesRegex(ValueError, 'independent raw universe'): p.audited_arm(root, 'b'*40, 'candidate')

    def test_raw_universe_scope_wrong_runtime_launch_and_unknown_role_reject(self):
        root = Path(self.roots['B'])
        with self.assertRaises(ValueError): p.audited_arm(root, 'b'*40, 'parent')
        audit = self.observed[str(root)]; plan = p.common.read(root/'plan.json'); plan['argv'][1] = p.PRESSURE_HEAP  # the correctness server's heap, not the pressure server's
        ref = self.write(root/'plan.json', plan); audit['plan'] = ref; audit['pins'][ref['path']] = ref['sha256']; self.write(root/'audit.json', audit)
        with self.assertRaisesRegex(ValueError, 'server entry'): p.audited_arm(root, 'b'*40, 'candidate')

    def test_missing_core_cannot_produce_packet_or_complete_claim(self):
        with patch.object(p.assembled, 'corrected_comparison', side_effect=ValueError('missing complete139 raw Local authority')):
            with self.assertRaisesRegex(ValueError, 'complete139'): self.packet()

    def test_packet_relabel_or_oracle_substitution_fails_actual_replay(self):
        packet = self.packet()
        for mutation in ('claims', 'runtime', 'oracle'):
            bad = copy.deepcopy(packet)
            if mutation == 'claims': bad['completeSemanticEquivalence'] = True
            elif mutation == 'runtime': bad['arms']['B']['runtimeFiles']['foreign.jar'] = 'f'*64
            else: bad['arms']['B']['cases'][0]['oracle']['digest'] = 'f'*64
            with self.subTest(mutation=mutation), self.assertRaisesRegex(ValueError, 'differs from actual'):
                p.verify_bundle(bad, 'a'*40, 'b'*40)

    def test_digest_kind_proofs_are_required_only_for_explicit_identities(self):
        packet, plan = self.bound_plan()
        for mutation in ('missing', 'changed'):
            bad = copy.deepcopy(plan)
            claim = next(proof['bindings'] for proof in bad['proofs']
                         if proof['role'] == 'independent-correctness' and proof['arm'] == 'B')
            if mutation == 'missing': claim.pop('oracleDigestKinds')
            else: claim['oracleDigestKinds'][bad['cases'][0]['id']] = 'unknown'
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                p.common.validate_plan(bad)
        # Rebind an actual legacy packet; omission is part of its old proof shape.
        for arm in packet['arms'].values():
            for row in arm['cases']:
                oracle = row['oracle']; oracle.pop('digestKind')
                oracle['digest'] = p.model.digest(p.common.read(oracle['valueRef']['path']))
        out = self.root/'legacy'; out.mkdir(); ref = self.write(out/'packet.json', packet)
        with patch('prepare_native_pressure_plan.preparation_control_pins', return_value={}):
            legacy = p.bind_plan(packet, ref, out, p.CATALOG)
        for proof in legacy['proofs']:
            if proof['role'] == 'independent-correctness':
                self.assertNotIn('oracleDigestKinds', proof['bindings'])
        self.assertEqual(legacy, p.common.validate_plan(legacy))

    def test_shared_rejects_unbound_jvm35_foreign_oracle_and_promoted_fixture(self):
        packet, plan = self.bound_plan()
        for mutation in ('unbound', 'foreign-value', 'fixture-claim', 'routing', 'native-mode'):
            bad = copy.deepcopy(plan)
            if mutation == 'unbound': bad.pop('jvmProducerAuthority')
            elif mutation == 'foreign-value': bad['cases'][0]['oracleByArm']['B']['digest'] = 'f'*64
            elif mutation == 'fixture-claim': next(r for r in bad['proofs'] if r['role'] == 'fixture-equivalence')['bindings']['completeSemanticEquivalence'] = True
            elif mutation == 'routing': next(c for c in bad['cases'] if c['family'] == 'graph-routing')['targetGraphIds'] = list(p.model.FIXTURE_GRAPH_IDS)
            else: bad['engine'] = 'native'
            with self.subTest(mutation=mutation), self.assertRaises((ValueError, KeyError)): p.common.validate_plan(bad)

    def test_missing_evidence_is_unavailable_and_failed_audit_stays_failed(self):
        for suffix, failure, expected in [('missing', FileNotFoundError('missing actual raw139 evidence'), 'UNAVAILABLE'),
                                         ('failed', ValueError('actual JVM response failure'), 'FAIL')]:
            with patch.object(p, 'assemble', side_effect=failure):
                output = self.root/suffix
                result = p.prepare(self.roots, 'a'*40, 'b'*40, output)
            self.assertEqual(expected, result['status']); self.assertFalse(result['performanceAcceptance'])
            self.assertEqual(result, p.common.read(output/'preparation-status.json'))
            self.assertFalse((output/'plan.json').exists()); self.assertFalse((output/'packet.json').exists())
            self.assertTrue(result['missingProducers'] if expected == 'UNAVAILABLE' else result['errors'])

    def test_malformed_existing_input_retains_failure_diagnostic(self):
        self.write(Path(self.roots['B'])/'plan.json', {})
        output = self.root/'malformed'
        result = p.prepare(self.roots, 'a'*40, 'b'*40, output)
        self.assertEqual('FAIL', result['status']); self.assertTrue(result['errors'])
        self.assertEqual(result, p.common.read(output/'preparation-status.json'))
        self.assertFalse((output/'plan.json').exists())

    def test_preparation_cannot_write_inside_evidence_and_alias_plan_passes(self):
        with self.assertRaisesRegex(ValueError, 'outside immutable'):
            p.prepare(self.roots, 'a'*40, 'b'*40, Path(self.roots['B'])/'new')
        packet, plan = self.bound_plan(True)
        self.assertEqual(plan, p.common.validate_plan(plan))
        self.assertFalse(next(r for r in plan['proofs'] if r['role'] == 'fixture-equivalence')['bindings']['completeSemanticEquivalence'])


if __name__ == '__main__': unittest.main()
