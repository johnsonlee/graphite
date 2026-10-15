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
                f.ADAPTER:'\n'.join(['private val localNodes = mutableMapOf<LocalKey, LocalVariable>()',
                    'clearMethodState(methodDescriptor)',
                    'localNodes[key]?.type', 'localNodes[key]?.type',
                    *(['getOrCreateValueNode(stmtOperand, method)'] * 7),*self.contract['cacheDeclarations'],
                    *(text for texts in self.contract['boundaries'].values() for text in texts)]),
                self.contract['localNode']['path']:self.contract['localNode']['body'],
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
        self.assertEqual(12,len(report['boundaries']['C']['exactBodyCounts']))
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

    def test_creation_dispatch_value_route_and_immutable_type_are_bound(self):
        mutations=[(f.ADAPTER,'is JAssignStmt -> processAssignment(stmt, method)','is JAssignStmt -> Unit'),
                   (f.ADAPTER,'is Local -> getOrCreateLocal(value, method)','is Local -> null'),
                   (f.ADAPTER,'localNodes.remove(key)','localNodes.clear()'),
                   (f.ADAPTER,'method === other.method','method == other.method'),
                   (f.ADAPTER,'val nodes = method.parameterTypes.mapIndexed','getOrCreateLocal(extra, methodDescriptor)\n        val nodes = method.parameterTypes.mapIndexed'),
                   (f.ADAPTER,'activeMethodLocals = mutableListOf()','activeMethodLocals = oldLocals'),
                   (self.contract['localNode']['path'],'val type: TypeDescriptor','var type: TypeDescriptor')]
        for name,old,new in mutations:
            with self.subTest(old=old):
                path=Path(self.manifests['B']['root'])/name;original=path.read_text()
                self.change('B',name,lambda x:x.replace(old,new))
                with self.assertRaises(Invalid):self.bind()
                self.change('B',name,lambda x:original)

    def test_extra_typed_factory_or_cache_mutation_callsite_rejected(self):
        for extra in ('getOrCreateLocalWithType(local, method, other)',
                      'LocalVariable(id, name, type, method)', 'localNodes[key] = replacement',
                      'getOrCreateLocal(extra, method)', 'getOrCreateValueNode(extra, method'):
            with self.subTest(extra=extra):
                path=Path(self.manifests['B']['root'])/f.ADAPTER;original=path.read_text()
                self.change('B',f.ADAPTER,lambda x:x+'\n'+extra)
                with self.assertRaisesRegex(Invalid,'call sites'):self.bind()
                self.change('B',f.ADAPTER,lambda x:original)

    def test_duplicate_formatter_rejected(self):
        self.change('B',f.FOLDING,lambda x:x+'\n'+x)
        with self.assertRaisesRegex(Invalid,'formatter source'):self.bind()

    def test_parent_arm_with_legacy_formatter_binds_as_legacy_flat_pair(self):
        # A rebased PR's parent arm is main, whose renderer is the accepted baseline's flat one:
        # the kind is read off the bytes, recorded in the rule, and licenses no Local collapse.
        self.change('B',f.FOLDING,lambda _:self.contract['formatters']['C'])
        out=self.root/'proof';report=f.save(out,self.sources,self.fixtures,self.pins)
        self.assertEqual('legacy-flat',report['boundaries']['B']['formatterKind'])
        self.assertEqual('legacy-flat',report['boundaries']['C']['formatterKind'])
        rule=json.loads(Path(report['rule']['path']).read_text())
        self.assertEqual({'C':'legacy-flat','B':'legacy-flat'},{arm:item['formatterKind'] for arm,item in rule['arms'].items()})
        authority=local.Authority(out,report['rule'],{'schema':'graphite.classfile-field-authority.v1',
            'arms':{arm:{'fixtureManifest':self.fixtures[arm]} for arm in ('C','B')}})
        self.assertFalse(authority.collapses);self.assertEqual({'C':'legacy-flat','B':'legacy-flat'},authority.kinds)
        self.assertFalse(authority.save()['collapseLicensed'])

    def test_candidate_pair_records_rank_preserving_kind_and_licenses_collapse(self):
        out=self.root/'proof';report=f.save(out,self.sources,self.fixtures,self.pins)
        self.assertEqual('rank-preserving',report['boundaries']['B']['formatterKind'])
        self.assertEqual('rank-preserving',json.loads(Path(report['rule']['path']).read_text())['arms']['B']['formatterKind'])
        authority=local.Authority(out,report['rule'],{'schema':'graphite.classfile-field-authority.v1',
            'arms':{arm:{'fixtureManifest':self.fixtures[arm]} for arm in ('C','B')}})
        self.assertTrue(authority.collapses);self.assertTrue(authority.save()['collapseLicensed'])

    def test_accepted_arm_must_hold_the_legacy_formatter(self):
        self.change('C',f.FOLDING,lambda _:self.contract['formatters']['B'])
        with self.assertRaisesRegex(Invalid,'legacy flat renderer'):self.bind()

    def test_formatter_matching_neither_or_both_reviewed_renderers_rejected(self):
        for arm in ('C','B'):
            for text in (self.contract['formatters'][arm].replace('type.toString()','"$type"'),
                         self.contract['formatters']['C']+'\n'+self.contract['formatters']['B'],
                         self.contract['formatters'][arm]+'\n'+self.contract['formatters'][arm]):
                with self.subTest(arm=arm,text=text[:40]):
                    self.change(arm,f.FOLDING,lambda _:text)
                    with self.assertRaisesRegex(Invalid,'exact reviewed formatter source'):self.bind()
            self.change(arm,f.FOLDING,lambda _:self.contract['formatters'][arm])

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
