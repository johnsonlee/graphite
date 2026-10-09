"""Portable authorities must survive relocation and reject stale or weakened inputs."""
import copy
import json
from pathlib import Path
import shutil
import tempfile
import unittest

import native_portable_oracles as portable
import multigraph_pressure as pressure


class PortableOracleTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / 'unrelated-checkout'
        shutil.copytree(portable.DEFAULT_INDEX.parent, self.root)
        self.path = self.root / 'index.json'
        self.index = pressure.read(self.path)
        self.inputs = {'schema': 'graphite.fixture64-source-inputs.v1',
                       'jars': copy.deepcopy(self.index['sourceJars'])}

    def load(self):
        self.path.write_text(json.dumps(self.index))
        return portable.load(self.path, source_inputs=self.inputs)

    def case(self, name):
        return next(case for case in self.index['cases'] if case['id'] == name)

    def test_all_cases_relocated_without_original_directories(self):
        bundle = self.load()
        self.assertEqual(len(bundle['cases']), 39)
        self.assertEqual(len(bundle['pins']), 48)
        self.assertEqual(sum(len(c['targetGraphIds']) == 2 for c in bundle['cases']), 2)
        self.assertEqual(sum(c['oracleKind'] == 'native-complete-legal-limit-multiset-v1'
                             for c in bundle['cases']), 4)
        schema = next(c for c in bundle['cases'] if c['id'] == 'schema-key-histogram')
        self.assertEqual(schema['oracleByArm']['C']['rows'], 26)
        self.assertEqual(schema['oracleByArm']['B']['rows'], 28)
        self.assertEqual(schema['oracleByArm']['B']['digest'],
                         'ce4f62ac23534f10a947559830b356a29f1af4e75faaa3c6a4ef288b4fdaed41')
        for case in bundle['cases']:
            for oracle in case['oracleByArm'].values():
                self.assertTrue(Path(oracle['proof']['path']).is_relative_to(self.root.resolve()))
        self.assertNotIn('A', schema['oracleByArm'])  # Parent alias needs actual identity, not this loader.

    def test_full_legal_multiset_is_kept_not_one_limit_response(self):
        bundle = self.load()
        case = next(c for c in bundle['cases'] if c['id'] == 'slow-dataflowTargetHit')
        oracle = case['oracleByArm']['B']
        self.assertEqual(oracle['rows'], 50)
        self.assertEqual(oracle['value']['totalMatches'], 11795)
        self.assertFalse(oracle['value']['exactEncounterOrderClaim'])
        self.assertEqual(sum(r['multiplicity'] for r in oracle['value']['rows']), 11795)

    def test_wrong_jar_or_duplicate_corpus_rejected(self):
        self.inputs['jars'][0]['sha256'] = '0' * 64
        with self.assertRaisesRegex(ValueError, 'same source JARs'):
            self.load()
        self.inputs['jars'][0] = copy.deepcopy(self.inputs['jars'][1])
        with self.assertRaisesRegex(ValueError, 'source JAR identities'):
            self.load()

    def test_query_and_actual_scope_cannot_change(self):
        self.index['cases'][0]['targetGraphIds'] = self.index['graphIds'][:1]
        with self.assertRaisesRegex(ValueError, 'exact query and scope'):
            self.load()

    def test_missing_or_duplicate_case_rejected(self):
        removed = self.index['cases'].pop()
        with self.assertRaisesRegex(ValueError, 'complete unique oracle catalog'):
            self.load()
        self.index['cases'].append(copy.deepcopy(self.index['cases'][0]))
        with self.assertRaisesRegex(ValueError, 'complete unique oracle catalog'):
            self.load()
        self.index['cases'][-1] = removed

    def test_payload_tampering_rejected(self):
        expected = self.index['cases'][0]['expected']['base']
        (self.root / expected['payload']['path']).write_text('{}')
        with self.assertRaisesRegex(ValueError, 'blob bytes changed'):
            self.load()

    def test_absolute_and_escaping_paths_rejected(self):
        ref = self.index['cases'][0]['expected']['base']['payload']
        for path in ['/tmp/fake.json', '../index.json']:
            ref['path'] = path
            with self.assertRaisesRegex(ValueError, 'content-addressed blob path'):
                self.load()

    def test_symlink_payload_rejected(self):
        ref = self.index['cases'][0]['expected']['base']['payload']
        path = self.root / ref['path']
        outside = self.root.parent / 'outside.json'
        path.rename(outside)
        path.symlink_to(outside)
        with self.assertRaisesRegex(ValueError, 'blob containment'):
            self.load()

    def test_cannot_promote_historical_proof_to_current_acceptance(self):
        for key in ['ciAcceptance', 'completeSemanticEquivalence', 'performanceAcceptance']:
            self.index[key] = True
            with self.assertRaisesRegex(ValueError, 'cannot declare acceptance'):
                self.load()
            self.index[key] = False

    def test_schema_exception_cannot_be_applied_to_other_cases(self):
        schema = self.case('schema-key-histogram')['expected']['candidate']
        self.index['cases'][0]['expected']['candidate'] = copy.deepcopy(schema)
        with self.assertRaisesRegex(ValueError, 'completed schema authority'):
            self.load()

    def test_independent_payload_cannot_use_another_cases_audit(self):
        expected = self.case('feature-methods')['expected']['candidate']
        expected['authority']['audit'] = copy.deepcopy(
            self.case('slow-dataflowTargetHit')['expected']['candidate']['authority']['audit'])
        with self.assertRaisesRegex(ValueError, 'expected/response binding'):
            self.load()

    def test_known_digest_cannot_be_replaced_by_observed_first_response(self):
        self.index['cases'][0]['expected']['base']['digest'] = '0' * 64
        with self.assertRaisesRegex(ValueError, 'full expected payload digest'):
            self.load()


if __name__ == '__main__':
    unittest.main()
