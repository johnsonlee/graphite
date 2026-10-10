"""Tiny packet contracts, not actual upstream execution or performance evidence.

Only JVM correctness/core audit boundaries are modeled. All metadata files,
ordered26 universes, pins, plan/proof checks and request digests are real.
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
            value = model_test.universe([], case); ref = self.write(raw_dir/'universes'/(case['id']+'.json'), value)
            pins[ref['path']] = ref['sha256']; universes[case['id']] = ref
            case['oracleByArm'] = {variant: {'kind': 'jvm-complete-legal-limit-multiset-v1', 'value': value,
                                            'digest': p.model.digest(value), 'rows': 0}}
        jar = p.artifacts.ref(producer/'runtime/graphite.jar')
        plan = {'rawDerivationRoot': str(raw_dir), 'serverJar': jar, 'graphs': graphs, 'cases': cases,
                'rawDerivationReplay': {'universes': universes}, 'expectationVariant': variant,
                'data': str(http/'data'), 'port': 23800+ord(name),
                'readiness': {'expected': {'count': 64, 'graphs': graphs}}}
        plan['argv'] = [str(self.java), '-Xmx4g', '-XX:ActiveProcessorCount=4', '-cp', jar['path'],
            'io.johnsonlee.graphite.cli.MainKt', 'serve', '--data', plan['data'], '--port', str(plan['port']),
            '--load-mode', 'MAPPED', '--max-concurrent-cypher', '4', '--cypher-max-timeout-ms', '240000',
            *p.common.graph_arguments(graphs)]
        plan_ref = self.write(http/'plan.json', plan); pins[plan_ref['path']] = plan_ref['sha256']
        result = {'schema': 'graphite.jvm-query-correctness-audit.v1', 'status': p.queries.AUDIT,
            'revision': revision, 'role': role, **refs, 'plan': plan_ref, 'pins': pins,
            'cases': [{'case': c['id'], 'targetGraphIds': c['targetGraphIds']} for c in cases],
            'independentRawExpectationsVerified': True, 'sourceRuleApplicabilityVerified': True,
            'all26ActualResponsesVerified': True, 'oracleAuthorityVerified': True,
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

    def test_actual_three_audits_and_each_comparison_once_with_all26_oracles(self):
        packet = self.packet()
        self.assertEqual(3, self.audit_mock.call_count); self.assertEqual(['A', 'B'], self.comparison_calls)
        for arm in packet['arms'].values():
            self.assertEqual(26, len(arm['cases'])); self.assertEqual(4*1024**3, arm['maxHeapBytes'])
            self.assertEqual('io.johnsonlee.graphite.cli.MainKt', arm['serverArgv'][5])
        for key in ('strictEquivalence', 'completeSemanticEquivalence', 'sourceToDeclarationCompletenessClaim',
                    'performanceAcceptance', 'acceptanceEligible'): self.assertIs(False, packet[key])
        self.assertEqual(p.assembled.CORRECTED_MODEL, p.fixture_bindings(packet)['comparisonModel'])

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
        for case in plan['cases']:
            self.assertEqual(64, len(case['requestedGraphIds']))
            self.assertEqual(2 if case['family'] == 'graph-routing' else 64, len(case['targetGraphIds']))

    def test_stored_audit_mismatch_missing_claim_and_observed_digest_not_authority(self):
        root = Path(self.roots['B']); audit = self.observed[str(root)]
        path = root/'audit.json'; original = path.read_bytes(); path.write_text('{}')
        with self.assertRaisesRegex(ValueError, 'stored JVM26'): p.audited_arm(root, 'b'*40, 'candidate')
        path.write_bytes(original); audit['oracleAuthorityVerified'] = False
        with self.assertRaisesRegex(ValueError, 'complete actual'): p.audited_arm(root, 'b'*40, 'candidate')
        audit['oracleAuthorityVerified'] = True
        plan = p.common.read(root/'plan.json'); plan['cases'][0]['oracleByArm']['B']['digest'] = 'f'*64
        ref = self.write(root/'plan.json', plan); audit['plan'] = ref; audit['pins'][ref['path']] = ref['sha256']; self.write(path, audit)
        with self.assertRaisesRegex(ValueError, 'independent raw universe'): p.audited_arm(root, 'b'*40, 'candidate')

    def test_raw_universe_scope_wrong_runtime_launch_and_unknown_role_reject(self):
        root = Path(self.roots['B'])
        with self.assertRaises(ValueError): p.audited_arm(root, 'b'*40, 'parent')
        audit = self.observed[str(root)]; plan = p.common.read(root/'plan.json'); plan['argv'][1] = '-Xmx8g'
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

    def test_shared_rejects_unbound_jvm26_foreign_oracle_and_promoted_fixture(self):
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
