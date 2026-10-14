"""CLI-to-Authority correctness; unrelated node/source checks use test doubles."""
import contextlib
import gzip
import json
from pathlib import Path
import unittest
from unittest.mock import MagicMock, patch
from . import check_graph as check, raw_local_export as exports, raw_local_types as raw
from . import declarations_test, raw_local_types_test
from .local_array_corrections_test import local
from run_native_core_equivalence import COUNTS


class RawLocalCliTests(unittest.TestCase):
    def fixture(self):
        declaration = declarations_test.DeclarationBindingTests()
        declaration.setUp(); self.addCleanup(declaration.doCleanups)
        raw_fixture = raw_local_types_test.RawLocalTests()
        raw_fixture.setUp(); self.addCleanup(raw_fixture.doCleanups)
        spec = raw_fixture.authority().spec
        root = declaration.root
        reference = root/'reference'; reference.mkdir()
        (reference/'forward.properties').write_text('graphclass=example.Graph\n')
        output = root/'proof'; (output/'graphs').mkdir(parents=True)
        row = {'id': spec['graphId'], 'B': str(declaration.graph), 'C': str(reference),
               'BExport': declaration.row, 'CExport': declaration.row, 'fieldAuthority': {'corpus': 'test'}}
        plan = {'output': str(output), 'graphs': [row], 'sourceRule': {}, 'rawLocalExports': [], 'pins': {}}
        for arm, ref in spec['exports'].items():
            provenance = root/(arm+'.tsv')
            provenance.write_text('graphId\tshardBytecodeSha256\tclassCount\n'+
                f"{spec['graphId']}\t{spec['shardBytecodeSha256']}\t{spec['classCount']}\n")
            plan['pins'][str(provenance)] = raw.sha(provenance)
            plan['rawLocalExports'].append({'arm': arm, 'corpus': 'test', 'provenance': str(provenance)})
            target = output/'raw-locals'/('raw-'+arm+'-test')/(row['id']+'.jsonl.gz')
            target.parent.mkdir(parents=True); target.write_bytes(Path(ref['path']).read_bytes())
        path = root/'plan.json'; path.write_text(json.dumps(plan))
        return declaration, plan, row, path

    def exercise(self, mode):
        declaration, plan, row, path = self.fixture()
        spec = exports.binding(plan, row)
        public = raw.Authority(spec, declaration.root)
        public.observe(local('byte[][]'), raw_local_types_test.M)
        expected_report = public.finish()
        target = Path(spec['exports']['B']['path'])
        if mode == 'malformed':
            target.write_text(target.read_text().replace('"folding": false', '"folding": false, "folding": false'))
        original_load = check.declarations.load
        def declaration_load(*args):
            result = original_load(*args)
            if mode == 'drift': target.write_text('changed after CLI byte binding')
            return result
        def compare(*args):
            # This is reached only after the real core.prove constructs the
            # unchanged raw Authority. All parsing and Local checks are real.
            authority = args[-1]
            authority.observe(local('byte[][]'), raw_local_types_test.M)
            self.assertEqual(authority.finish(), expected_report)
            return {key: 0 for key in COUNTS}
        with contextlib.ExitStack() as stack:
            stack.enter_context(patch.object(check.sys, 'argv', ['check_graph', str(path), raw.sha(path), row['id']]))
            parsed = stack.enter_context(patch.object(raw, 'load', wraps=raw.load))
            stack.enter_context(patch.object(check.declarations, 'load', side_effect=declaration_load))
            for module, name in ((check.core.method_authority, 'Authority'),
                                 (check.core.local_array_corrections, 'Authority'),
                                 (check.core.legacy_method_collisions, 'Corrections'),
                                 (check.core.synthetic_metadata, 'Corrections')):
                stack.enter_context(patch.object(module, name, return_value=MagicMock()))
            stack.enter_context(patch.object(check.core.parameter_array_corrections, 'save'))
            compared = stack.enter_context(patch.object(check.core, '_prove', side_effect=compare))
            proof = Path(plan['output'])/'graphs'/row['id']
            if mode == 'pass':
                check.main()
                self.assertEqual(parsed.call_count, 2)
                compared.assert_called_once()
                saved = json.loads((proof/'record.json').read_text())
                self.assertTrue(saved['status'].startswith('PASS_CORE_'))
                self.assertFalse(saved['strictEquivalence'])
                self.assertEqual(json.loads(gzip.decompress((proof/'core/raw-local-type-proof.json.gz').read_bytes())), expected_report)
            else:
                message = 'duplicate raw export JSON key' if mode == 'malformed' else 'raw Local export pin'
                with self.assertRaisesRegex(ValueError, message): check.main()
                compared.assert_not_called()
                self.assertTrue((proof/'declarations.json').exists())
                self.assertFalse((proof/'record.json').exists())

    def test_cli_parses_once_per_arm_and_retains_same_complete_raw_report(self): self.exercise('pass')
    def test_cli_rejects_malformed_raw_before_core_comparison(self): self.exercise('malformed')
    def test_cli_rechecks_bytes_after_identity_binding_before_core_comparison(self): self.exercise('drift')

    def test_public_binding_remains_serializable_and_fully_validating(self):
        _, plan, row, _ = self.fixture()
        with patch.object(exports, 'load', wraps=raw.load) as parsed:
            spec = exports.binding(plan, row)
            self.assertEqual(json.loads(json.dumps(spec)), spec)
            self.assertEqual(parsed.call_count, 2)
        file = Path(spec['exports']['C']['path'])
        file.write_text('not JSON')
        with self.assertRaises(ValueError): exports.binding(plan, row)
