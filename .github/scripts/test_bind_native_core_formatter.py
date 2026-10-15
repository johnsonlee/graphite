"""Composition tests; raw marker replay and exact source policy have separate tests."""
import copy
from pathlib import Path
import unittest
from unittest.mock import patch
import bind_native_core_formatter as b
from native_core_proof import formatter_binding_test as fixtures


class FormatterAdapterTests(unittest.TestCase):
    def setUp(self):
        self.f=fixtures.FormatterBindingTests();self.f.setUp();self.addCleanup(self.f.doCleanups)
        self.out=self.f.root/'formatter';self.marker_root=self.f.root/'marker';self.marker_root.mkdir()
        self.audit={'arms':{},'pins':{},'completeSemanticEquivalence':False}
        for arm in ('C','B'):
            artifact={'sourceManifest':self.f.sources[arm],'fixtureManifest':self.f.fixtures[arm],
                      'producerPacket':{'path':str(self.f.root/arm/'packet.json'),'sha256':'0'*64},
                      'pins':{r['path']:r['sha256'] for r in (self.f.sources[arm],self.f.fixtures[arm])}}
            ref=self.f.write(arm+'-audit.json',artifact)
            self.audit['arms'][arm]={'artifactAudit':ref,'revision':self.f.manifests[arm]['revision']}
            self.audit['pins'][ref['path']]=ref['sha256']
        self.path=self.marker_root/'audit.json';self.path.write_text(__import__('json').dumps(self.audit))
        self.controls={**self.f.pins,str(Path(b.__file__).resolve()):b.common.sha(b.__file__)}
        p=patch.object(b.marker,'audit',side_effect=lambda root:copy.deepcopy(self.audit));p.start();self.addCleanup(p.stop)
        p=patch.object(b,'preparation_control_pins',return_value=self.controls);p.start();self.addCleanup(p.stop)

    def test_actual_pair_receipts_remain_linked_and_all_acceptance_claims_false(self):
        result=b.execute(self.path,self.out)
        self.assertEqual(self.f.sources,result['sourceManifests'])
        self.assertEqual(self.f.fixtures,result['fixtureManifests'])
        self.assertEqual(b.marker.artifacts.ref(self.path),result['upstream']['markerAudit'])
        self.assertEqual('b'*40,b.common.read(result['rule']['path'])['arms']['B']['revision'])
        for key in ('completeSemanticEquivalence','productionFormatterTestsVerified','syntheticLocalInferenceOracleClaim','performanceAcceptance'):
            self.assertIs(False,result[key])
        b.marker.artifacts.verify_pins(result['pins'])

    def test_private_result_matches_public_and_replays_on_every_call(self):
        with patch.object(b.marker,'audit',side_effect=lambda root:copy.deepcopy(self.audit)) as replay:
            public=b.bind(self.path,self.out)
            bindings,checked=b._bind_with_marker(self.path,self.out)
        self.assertEqual(2,replay.call_count)
        self.assertEqual(public,bindings);self.assertEqual(self.audit,checked)
        checked['arms']['B']['artifactAudit']['path']='corrupted caller value'
        bindings[3]['artifactAudits']['B']['path']='different caller value'
        self.assertEqual(self.audit,b.common.read(self.path))
        self.assertEqual(public,b.bind(self.path,self.out))

    def test_failed_replay_is_propagated_and_next_call_replays(self):
        with patch.object(b.marker,'audit',side_effect=[ValueError('raw marker failure'),copy.deepcopy(self.audit)]) as replay:
            with self.assertRaisesRegex(ValueError,'raw marker failure'):b._bind_with_marker(self.path,self.out)
            self.assertEqual(self.audit,b._bind_with_marker(self.path,self.out)[1])
        self.assertEqual(2,replay.call_count)

    def test_marker_receipt_changed_during_replay_rejected(self):
        def changed(root):
            self.path.write_text(self.path.read_text()+' ')
            return copy.deepcopy(self.audit)
        with patch.object(b.marker,'audit',side_effect=changed):
            with self.assertRaisesRegex(ValueError,'marker audit changed'):b._bind_with_marker(self.path,self.out)

    def test_forged_stored_marker_status_cannot_override_raw_replay(self):
        value=copy.deepcopy(self.audit);value['completeSemanticEquivalence']=True
        self.path.write_text(__import__('json').dumps(value))
        with self.assertRaisesRegex(ValueError,'independent raw replay'):b.execute(self.path,self.out)
        self.assertFalse(self.out.exists())

    def test_changed_actual_source_manifest_rejected(self):
        Path(self.f.sources['B']['path']).write_text('{}')
        with self.assertRaises(ValueError):b.execute(self.path,self.out)

    def test_identical_writer_alias_requires_strict_path_not_correction_rule(self):
        self.audit['arms']['B']['revision']=self.audit['arms']['C']['revision']
        self.path.write_text(__import__('json').dumps(self.audit))
        with self.assertRaisesRegex(ValueError,'strict comparison'):b.execute(self.path,self.out)

    def test_output_cannot_mutate_measured_graphs(self):
        with self.assertRaisesRegex(ValueError,'outside measured'):
            b.execute(self.path,self.f.root/'B'/'graphs'/'accidental')


if __name__=='__main__':unittest.main()
