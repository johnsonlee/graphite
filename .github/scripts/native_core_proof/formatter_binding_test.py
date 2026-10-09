"""Tiny reviewed source fragments and manifests, no Kotlin/JVM/graph execution."""
import json
from pathlib import Path
import tempfile
import unittest
from . import formatter_binding as f
from . import local_array_corrections as local
from .legacy_wire import Invalid


class FormatterBindingTests(unittest.TestCase):
    def setUp(self):
        temp=tempfile.TemporaryDirectory();self.addCleanup(temp.cleanup);self.root=Path(temp.name).resolve()
        self.sources={};self.fixtures={};self.manifests={};self.contract=json.loads(f.CONTRACT.read_text())
        self.pins={str(f.CONTRACT):f.sha(f.CONTRACT),str(Path(f.__file__)):f.sha(f.__file__)}
        for arm,revision in [('C','c'*40),('B','b'*40)]:
            root=self.root/arm;root.mkdir()
            files={f.FOLDING:self.contract['formatters'][arm],
                f.ADAPTER:'\n'.join([*self.contract['cacheDeclarations'],
                    *(text for texts in self.contract['boundaries'].values() for text in texts)]),
                **{name:'same dependency input' for name in self.contract['dependencyFiles']}}
            for name,text in files.items():
                path=root/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(text)
            self.manifests[arm]={'revision':revision,'root':str(root),'files':{name:f.sha(root/name) for name in files}}
            self.repin(arm)

    def write(self,name,value):
        path=self.root/name;path.write_text(json.dumps(value));return {'path':str(path),'sha256':f.sha(path)}

    def repin(self,arm):
        source=self.manifests[arm];root=Path(source['root'])
        source['files']={name:f.sha(root/name) for name in source['files']}
        self.sources[arm]=self.write(arm+'-source.json',source)
        self.fixtures[arm]=self.write(arm+'-fixture.json',{'writerRevision':source['revision'],
                                          'sourceManifestSha256':self.sources[arm]['sha256']})

    def bind(self):return f.source_rule(self.sources,self.fixtures,self.pins)

    def change(self,arm,name,transform):
        path=Path(self.manifests[arm]['root'])/name;path.write_text(transform(path.read_text()));self.repin(arm)

    def test_fresh_rule_consumed_without_static_paths_or_revision_constants(self):
        out=self.root/'proof';report=f.save(out,self.sources,self.fixtures,self.pins)
        authority=local.Authority(out,report['rule'],{'schema':'graphite.classfile-field-authority.v1',
            'arms':{arm:{'fixtureManifest':self.fixtures[arm]} for arm in ('C','B')}})
        self.assertEqual('b'*40,authority.rule['arms']['B']['revision'])
        self.assertEqual(2,report['boundaries']['B']['exactBodyCounts']['toMethodDescriptor'])
        self.assertEqual(7,len(report['boundaries']['C']['exactBodyCounts']))
        self.assertFalse(report['productionFormatterTestsVerified'])
        self.assertFalse(report['syntheticLocalInferenceOracleClaim']);self.assertFalse(report['completeSemanticEquivalence'])
        self.assertTrue(all(f.sha(path)==digest for path,digest in report['pins'].items()))

    def test_actual_parent_revision_supported_as_comparison_slot(self):
        self.manifests['B']['revision']='a'*40;self.repin('B')
        rule,_=self.bind();self.assertEqual('a'*40,rule['arms']['B']['revision'])

    def test_candidate_projection_does_not_replace_production_formatter(self):
        self.change('B',f.FOLDING,lambda x:x.replace('repeat(type.dimension)','repeat(1)'))
        with self.assertRaisesRegex(Invalid,'formatter source'):self.bind()

    def test_repinning_changed_local_binding_or_cache_rejected(self):
        self.change('B',f.ADAPTER,lambda x:x.replace('identityMutableMap<Type, TypeDescriptor>()','mutableMapOf<Type, TypeDescriptor>()'))
        with self.assertRaisesRegex(Invalid,'identity cache'):self.bind()

    def test_changed_existing_method_and_added_overload_rejected(self):
        original=(Path(self.manifests['B']['root'])/f.ADAPTER).read_text()
        self.change('B',f.ADAPTER,lambda x:x.replace('return LocalKey(method, localName)','return LocalKey(method, "other")'))
        with self.assertRaisesRegex(Invalid,'adapter body changed'):self.bind()
        self.change('B',f.ADAPTER,lambda x:original+'\n    private fun localKey(extra: Int) = extra')
        with self.assertRaisesRegex(Invalid,'overload set'):self.bind()

    def test_duplicate_formatter_rejected(self):
        self.change('B',f.FOLDING,lambda x:x+'\n'+x)
        with self.assertRaisesRegex(Invalid,'formatter source'):self.bind()

    def test_dependency_change_even_when_manifest_rebound_rejected(self):
        self.change('B',self.contract['dependencyFiles'][0],lambda x:x+'changed')
        with self.assertRaisesRegex(Invalid,'dependency versions'):self.bind()

    def test_writer_revision_or_manifest_identity_mismatch_rejected(self):
        self.fixtures['B']=self.write('B-fixture.json',{'writerRevision':'f'*40,
            'sourceManifestSha256':self.sources['B']['sha256']})
        with self.assertRaisesRegex(Invalid,'writer source linkage'):self.bind()

    def test_drift_or_source_outside_manifest_rejected(self):
        path=Path(self.manifests['B']['root'])/f.ADAPTER;path.write_text(path.read_text()+'changed')
        with self.assertRaisesRegex(Invalid,'manifested source'):self.bind()

    def test_contract_and_helper_must_be_reviewed_controls(self):
        for path in self.pins:
            with self.subTest(path=path):
                pins=dict(self.pins);pins[path]='0'*64
                with self.assertRaisesRegex(Invalid,'reviewed formatter'):f.source_rule(self.sources,self.fixtures,pins)

    def test_existing_output_and_source_checkout_outputs_rejected(self):
        out=self.root/'saved';f.save(out,self.sources,self.fixtures,self.pins)
        with self.assertRaises(FileExistsError):f.save(out,self.sources,self.fixtures,self.pins)
        with self.assertRaisesRegex(Invalid,'outside actual writer'):f.save(self.root/'B'/'output',self.sources,self.fixtures,self.pins)


if __name__=='__main__':unittest.main()
