"""Evidence handoff correctness only; no real server, build or performance run."""
import copy
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import assemble_native_pressure_producers as bundle
import prepare_native_pressure_plan as preparation
import test_native_query_correctness as query_fixtures


class AuditedArmTests(unittest.TestCase):
    def setUp(self):
        self.fixture = query_fixtures.QueryCorrectnessTests()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        q = self.fixture
        self.root = q.fixture.out
        q.artifact_path = self.root / 'artifact-audit.json'
        q.fixture.write(q.artifact_path, q.fixture.run_audit())
        q.out = self.root / 'query-correctness'
        q.out.mkdir()
        q.plan = query_fixtures.runner.prepare(q.artifact_path, q.fixture.inputs_path,
                                              'b' * 40, 'candidate', q.out, 22840)
        q.run_queries()
        q.fixture.write(self.root / 'query-correctness-audit.json', bundle.queries.audit(q.out))
        self.source_inputs = bundle.artifacts.ref(q.fixture.inputs_path)
        self.no_child = patch('subprocess.Popen', side_effect=AssertionError('no child allowed'))
        self.no_child.start()
        self.addCleanup(self.no_child.stop)

    def read_arm(self):
        return bundle.audited_arm(self.root, 'b' * 40, 'candidate', self.source_inputs)

    def test_full_current_arm_preserves_all39_requests_responses_and_scope(self):
        arm, pins, identity = self.read_arm()
        self.assertEqual(arm['revision'], 'b' * 40)
        self.assertEqual(len(arm['graphs']), 64)
        self.assertEqual(len(arm['cases']), 39)
        self.assertEqual([c['id'] for c in arm['cases']], [c['id'] for c in self.fixture.plan['cases']])
        self.assertEqual(arm['readiness'], self.fixture.plan['readiness'])
        self.assertEqual(identity, self.fixture.fixture.runtime['toolchainIdentity'])
        for actual, expected in zip(arm['cases'], self.fixture.plan['cases']):
            self.assertEqual(actual['request'], expected['request'])
            self.assertEqual(actual['targetGraphIds'], expected['targetGraphIds'])
            self.assertEqual(actual['oracle'], expected['oracleByArm']['B'])
            self.assertEqual(pins[actual['observed']['body']['path']], actual['observed']['body']['sha256'])

    def test_forged_stored_response_audit_fails_raw_recomputation(self):
        p = self.root / 'query-correctness-audit.json'
        value = bundle.common.read(p)
        value['cases'][0]['canonicalSha256'] = 'f' * 64
        self.fixture.fixture.write(p, value)
        with self.assertRaisesRegex(ValueError, 'recomputed raw query evidence'):
            self.read_arm()

    def test_query_success_cannot_be_promoted_to_semantic_equivalence(self):
        p = self.root / 'query-correctness-audit.json'
        value = bundle.common.read(p)
        value['completeSemanticEquivalence'] = True
        self.fixture.fixture.write(p, value)
        with self.assertRaisesRegex(ValueError, 'partial proof scope'):
            self.read_arm()

    def test_different_source_manifest_cannot_be_joined(self):
        self.source_inputs = dict(self.source_inputs, sha256='f' * 64)
        with self.assertRaisesRegex(ValueError, 'same source input manifest'):
            self.read_arm()


class BundleAssemblyTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.base = bundle.artifacts.ACCEPTED
        self.candidate = 'b' * 40
        self.put('source-inputs.json', {'test': 'metadata-only'})
        self.directories()
        no_child = patch('subprocess.Popen', side_effect=AssertionError('child forbidden'))
        no_child.start()
        self.addCleanup(no_child.stop)

    def put(self, name, value):
        path = self.root / name
        path.write_text(json.dumps(value))
        return path

    def directories(self, mapping=None):
        self.put('arm-directories.json', {
            'schema': 'graphite.native-artifact-arm-directories.v1',
            'directories': mapping or {'C': 'C', 'A': 'C', 'B': 'B'},
            'acceptedRevision': bundle.artifacts.ACCEPTED, 'baseRevision': self.base,
            'candidateRevision': self.candidate,
            'status': 'ARTIFACTS_ONLY_INDEPENDENT_PROOFS_REQUIRED', 'performanceAcceptance': False})

    @staticmethod
    def arm(root, revision, role, inputs):
        # Only directory composition is mocked here; AuditedArmTests exercises
        # the real artifact/query handoff with every versioned response payload.
        return {'revision': revision, 'artifactRole': role, 'cases': [{'id': str(i)} for i in range(39)]}, {}, {'compiler': 'same'}

    def assemble(self):
        with patch.object(bundle, 'audited_arm', side_effect=self.arm) as audit:
            value = bundle.assemble(self.root, self.base, self.candidate)
        return value, audit

    def test_exact_accepted_parent_reuses_audited_artifacts_without_relabelling_origin(self):
        value, audit = self.assemble()
        self.assertEqual([call.args[0].name for call in audit.call_args_list], ['C', 'B'])
        self.assertEqual(value['arms']['A']['revision'], bundle.artifacts.ACCEPTED)
        self.assertEqual(value['arms']['A']['artifactRole'], 'accepted-baseline')
        self.assertEqual(value['arms']['A']['role'], 'parent')
        self.assertEqual(value['status'], bundle.STATUS)
        for key in ('completeSemanticEquivalence', 'performanceAcceptance', 'acceptanceEligible'):
            self.assertIs(value[key], False)
        self.assertTrue(value['missingAuthority'])

    def test_distinct_parent_requires_its_own_audit(self):
        self.base = 'a' * 40
        self.directories({'C': 'C', 'A': 'A', 'B': 'B'})
        value, audit = self.assemble()
        self.assertEqual([call.args[0].name for call in audit.call_args_list], ['C', 'A', 'B'])
        self.assertEqual(value['arms']['A']['revision'], self.base)
        self.assertEqual(value['arms']['A']['artifactRole'], 'parent')

    def test_wrong_parent_alias_is_rejected_before_any_audit(self):
        self.base = 'a' * 40
        self.directories()
        with patch.object(bundle, 'audited_arm', side_effect=AssertionError('must reject before audit')):
            with self.assertRaisesRegex(ValueError, 'permitted A/C alias'):
                bundle.assemble(self.root, self.base, self.candidate)

    def test_mismatched_toolchains_do_not_form_a_comparison(self):
        def different(root, revision, role, inputs):
            arm, pins, _ = self.arm(root, revision, role, inputs)
            return arm, pins, {'compiler': root.name}
        with patch.object(bundle, 'audited_arm', side_effect=different):
            with self.assertRaisesRegex(ValueError, 'matched native build toolchains'):
                bundle.assemble(self.root, self.base, self.candidate)

    def test_conflicting_pins_never_overwrite_earlier_authority(self):
        pins = {str(self.root / 'same'): 'a' * 64}
        with self.assertRaisesRegex(ValueError, 'conflicting evidence pin'):
            bundle.merge_pins(pins, {str(self.root / 'same'): 'b' * 64})
        self.assertEqual(pins[str(self.root / 'same')], 'a' * 64)

    def test_promoted_bundle_status_or_claim_cannot_pass_verification(self):
        value, _ = self.assemble()
        for key, replacement in [('status', 'PASS'), ('completeSemanticEquivalence', True),
                                 ('performanceAcceptance', True), ('missingAuthority', [])]:
            wrong = copy.deepcopy(value)
            wrong[key] = replacement
            with self.subTest(key=key), patch.object(bundle, 'assemble', return_value=value):
                with self.assertRaisesRegex(ValueError, 'current raw evidence'):
                    bundle.verify_bundle(wrong, self.base, self.candidate)

    def test_preparation_records_completed_inputs_but_does_not_create_a_pressure_plan(self):
        value, _ = self.assemble()
        args = SimpleNamespace(output=self.root / 'prepared', provenance=self.root / 'absent',
                               fixture=self.root / 'absent', base_sha=self.base, candidate_sha=self.candidate,
                               producers=self.put('bundle.json', value))
        with patch.object(bundle, 'verify_bundle', return_value=value):
            result = preparation.prepare(args)
        self.assertEqual(result['status'], 'UNAVAILABLE')
        self.assertFalse(result['passed'])
        self.assertFalse(result['performanceAcceptance'])
        self.assertEqual(result['availableInputs']['matchedNativeBundle']['auditedCasesByArm'],
                         {'C': 39, 'A': 39, 'B': 39})
        self.assertEqual(result['missingProducers'], value['missingAuthority'])
        self.assertFalse((args.output / 'plan.json').exists())


if __name__ == '__main__':
    unittest.main()
