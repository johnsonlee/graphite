"""Tiny protocol files and real versioned payloads; no subprocess/network/corpus."""
import copy
import json
from pathlib import Path
import unittest
from unittest.mock import patch

import normalize_native_pressure_oracles as n
import prepare_native_pressure_plan as preparation
import test_assemble_native_pressure_producers as fixtures


class NormalizationTests(unittest.TestCase):
    def setUp(self):
        self.fixture = fixtures.AuditedArmTests()
        self.fixture.revision = getattr(self, 'revision', 'b' * 40)
        self.fixture.role = getattr(self, 'role', 'candidate')
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.directory = self.fixture.fixture.out
        self.output = self.fixture.root / 'normalized'

    def normalize(self, origin='B'):
        result = n.normalize(self.directory, origin, self.output)
        arm, _, _ = self.fixture.read_arm()
        result['arms'] = {origin: arm}
        return result

    def test_all39_keep_exact_payloads_requests_and_actual_runtime_without_semantic_pass(self):
        result = self.normalize()
        refs = result['independentCaseOracles']['B']
        cases = self.fixture.fixture.plan['cases']
        self.assertEqual([c['id'] for c in cases], list(refs))
        cache = set()
        with patch.object(n, 'build', wraps=n.build) as build:
            for case in cases:
                oracle = case['oracleByArm']['B']
                expected = {k: oracle[k] for k in ('digest', 'rows')}
                value = preparation.independent_case_oracle(result, case, 'B', expected, cache)
                self.assertEqual(expected, {k: value[k] for k in ('digest', 'rows')})
                self.assertEqual(oracle['kind'], value['kind'])
                self.assertEqual(oracle.get('valueProof', oracle['proof']), refs[case['id']]['expected'])
                self.assertEqual(n.artifacts.ref(self.directory / (case['id'] + '.body')),
                                 refs[case['id']]['body'])
            self.assertEqual(1, build.call_count)
        plan = n.common.read(self.output / 'plan.json')
        audit = n.common.read(self.output / 'audit.json')
        self.assertEqual(self.fixture.fixture.plan['graphs'], plan['arms']['B']['graphs'])
        self.assertEqual(n.artifacts.ref(self.fixture.root / 'query-correctness-audit.json'), audit['freshAudit'])
        self.assertEqual(n.SCOPE, audit['authorityScope'])
        self.assertFalse(audit['completeSemanticEquivalence'])
        self.assertFalse(result['completeSemanticEquivalence'])
        self.assertFalse(result['performanceAcceptance'])
        self.assertNotIn('proofs', result)  # No fixture-equivalence or producer promotion.

    def test_candidate_cannot_be_relabelled_parent(self):
        with self.assertRaisesRegex(ValueError, 'execution role'):
            self.normalize('A')

    def test_candidate_cannot_be_relabelled_accepted(self):
        with self.assertRaisesRegex(ValueError, 'accepted origin'):
            self.normalize('C')

    def test_status_and_repin_cannot_promote_semantic_scope(self):
        result = self.normalize()
        refs = next(iter(result['independentCaseOracles']['B'].values()))
        path = Path(refs['audit']['path'])
        value = n.common.read(path); value['completeSemanticEquivalence'] = True
        path.write_text(json.dumps(value))
        refs['audit'] = n.artifacts.ref(path); result['pins'][str(path)] = refs['audit']['sha256']
        with self.assertRaisesRegex(ValueError, 'differs from raw'):
            n.verify(result, refs, 'B')

    def test_repin_cannot_change_executed_query_or_runtime(self):
        result = self.normalize()
        refs = next(iter(result['independentCaseOracles']['B'].values()))
        path = Path(refs['plan']['path'])
        old = n.common.read(path)
        for mutate in [lambda v: v['arms']['B'].update(revision='c' * 40),
                       lambda v: next(iter(v['cases'].values()))['request']['body'].update(query='RETURN 7'),
                       lambda v: v['arms']['B']['graphs'].pop(),
                       lambda v: v['cases'].pop(next(iter(v['cases'])))]:
            value = copy.deepcopy(old); mutate(value); path.write_text(json.dumps(value))
            refs['plan'] = n.artifacts.ref(path); result['pins'][str(path)] = refs['plan']['sha256']
            with self.assertRaisesRegex(ValueError, 'differs from raw'):
                n.verify(result, refs, 'B')

    def test_raw_body_change_is_rejected_even_when_normalized_receipt_is_untouched(self):
        result = self.normalize()
        refs = next(iter(result['independentCaseOracles']['B'].values()))
        Path(refs['body']['path']).write_text('{}')
        with self.assertRaises(ValueError):
            n.verify(result, refs, 'B')

    def test_missing_input_pin_is_rejected_by_existing_preparation(self):
        result = self.normalize()
        case = self.fixture.fixture.plan['cases'][0]
        refs = result['independentCaseOracles']['B'][case['id']]
        del result['pins'][refs['expected']['path']]
        with self.assertRaises(ValueError):
            preparation.independent_case_oracle(result, case, 'B', case['oracleByArm']['B'])

    def test_output_cannot_overwrite_existing_evidence(self):
        self.normalize()
        with self.assertRaises(FileExistsError):
            self.normalize()


class OtherArmNormalizationTests(unittest.TestCase):
    def check_origin(self, origin, revision, role, alias=False):
        fixture = fixtures.AuditedArmTests()
        fixture.revision, fixture.role = revision, role
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        result = n.normalize(fixture.fixture.out, origin, fixture.root / 'normalized')
        arm, _, _ = fixture.read_arm()
        result['arms'] = {origin: arm}
        if origin == 'A':
            # Unselected C identity only distinguishes the non-alias branch.
            result['arms']['C'] = {'revision': n.portable.ACCEPTED}
        target = 'A' if alias else origin
        if alias:
            result['arms']['A'] = copy.deepcopy(arm)
            self.assertTrue(n.common.same_accepted_artifacts(result, 'A'))
        cache = set()
        variant = 'C' if origin == 'C' else 'B'
        with patch.object(n, 'build', wraps=n.build) as build:
            for case in fixture.fixture.plan['cases']:
                oracle = case['oracleByArm'][variant]
                expected = {k: oracle[k] for k in ('digest', 'rows')}
                actual = preparation.independent_case_oracle(result, case, target, expected, cache)
                self.assertEqual(expected, {k: actual[k] for k in ('digest', 'rows')})
                refs = result['independentCaseOracles'][origin][case['id']]
                self.assertEqual(oracle.get('valueProof', oracle['proof']), refs['expected'])
                self.assertEqual(n.artifacts.ref(fixture.fixture.out / (case['id'] + '.body')), refs['body'])
            self.assertEqual(1, build.call_count)
        plan = n.common.read(fixture.root / 'normalized/plan.json')
        self.assertEqual(revision, plan['arms'][origin]['revision'])
        self.assertEqual(variant, fixture.fixture.plan['expectationVariant'])
        self.assertEqual(role, fixture.fixture.plan['role'])
        self.assertEqual(fixture.fixture.plan['graphs'], plan['arms'][origin]['graphs'])
        self.assertEqual(39, len(result['independentCaseOracles'][origin]))
        self.assertFalse(result['completeSemanticEquivalence'])
        self.assertFalse(result['performanceAcceptance'])

    def test_accepted_all39_use_actual_c_revision_and_base_payloads(self):
        self.check_origin('C', n.portable.ACCEPTED, 'accepted-baseline')

    def test_parent_all39_use_actual_a_revision_and_candidate_payloads(self):
        self.check_origin('A', 'a' * 40, 'parent')

    def test_identical_accepted_parent_uses_c_proofs_without_relabeling(self):
        self.check_origin('C', n.portable.ACCEPTED, 'accepted-baseline', alias=True)


if __name__ == '__main__':
    unittest.main()
