import copy
import gzip
import json
from pathlib import Path
import tempfile
import unittest
from . import raw_local_types as r
from . import formatter_binding_test as binding_fixture
from . import formatter_binding as binding
from . import local_array_corrections as corrections
from .local_array_corrections_test import local

M = ['owner.类型', 'method', '(I)[[B']
A = {'kind':'array','dimension':2,'base':{'kind':'primitive','name':'byte'}}


class RawLocalTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name);self.rows={a:self.method() for a in ('C','B')}

    def method(self, raw=None):
        return {'record':'method','method':M,'locals':{'$stack4':[{'type':copy.deepcopy(raw or A),'origin':'body.locals'}]},
                'typedAllocations':{},'statementCount':2}

    def authority(self, mutate=None, source_authority=None):
        spec={'graphId':'fixture-tika-00','shardBytecodeSha256':'a'*64,'classCount':3,'exports':{}}
        for arm in ('C','B'):
            rows=[{'record':'header','graphId':spec['graphId'],'shardBytecodeSha256':'a'*64,'classCount':3,
                   'scope':'fixture64-no-fold','folding':False},self.rows[arm],{'record':'complete','methodCount':1}]
            if mutate:mutate(arm,rows)
            path=self.root/(arm+'.jsonl');path.write_text(''.join(json.dumps(x)+'\n' for x in rows))
            spec['exports'][arm]={'path':str(path),'sha256':r.sha(path)}
        return r.Authority(spec,self.root,source_authority)

    def check(self, value='byte[][]', method=M):
        oracle=self.authority();oracle.observe(local(value),method);return oracle

    def test_raw_rank_authority_for_changed_array(self):
        report=self.check().finish();self.assertEqual(1,report['arrayCount']);self.assertFalse(report['completeSemanticEquivalence'])

    def test_flat_renderer_persists_the_base_type_and_one_rank(self):
        # A rebased PR's parent arm keeps the accepted flat renderer: a raw byte[][] is persisted
        # as byte[], which the proof expects, and a rank-preserving string there is a mismatch.
        from types import SimpleNamespace
        flat=SimpleNamespace(kinds={'C':'legacy-flat','B':'legacy-flat'})
        oracle=self.authority(source_authority=flat);oracle.observe(local('byte[]'),M);report=oracle.finish()
        self.assertEqual((1,True),(report['arrayCount'],report['flatRendering']))
        self.assertEqual(('byte[]',2,'PASS_ARRAY'),(oracle.rows[0]['expectedType'],oracle.rows[0]['rawDimension'],oracle.rows[0]['status']))
        kept=self.authority(source_authority=flat);kept.observe(local('byte[][]'),M)
        with self.assertRaisesRegex(ValueError,'conflicts/missing'):kept.finish()
        self.assertEqual(['persisted array differs from raw Local.type'],kept.rows[0]['issues'])
        self.assertFalse(self.check().finish()['flatRendering'])
        with self.assertRaisesRegex(ValueError,'renderer differs'):r.verify_creation_inputs({'occurrences':[],'flatRendering':True},None,{})
        r.verify_creation_inputs({'occurrences':[],'flatRendering':True},flat,{})
        # The outer runner knows the renderer from its plan even when no creation proof bound a source authority.
        r.verify_creation_inputs({'occurrences':[],'flatRendering':True},None,{},flat=True)
        with self.assertRaisesRegex(ValueError,'renderer differs'):r.verify_creation_inputs({'occurrences':[],'flatRendering':True},None,{},flat=False)
        with self.assertRaisesRegex(ValueError,'authority renderer differs'):r.verify_creation_inputs({'occurrences':[],'flatRendering':True},flat,{},flat=False)

    def test_flat_writer_method_key_matches_the_raw_export_through_the_rank_fold(self):
        # A flat writer's graph renders byte[][] as byte[] in its method keys too; the raw export
        # keeps the JVM descriptor. The proof matches through the same fold (Attempt290).
        from types import SimpleNamespace
        flat=SimpleNamespace(kinds={'C':'legacy-flat','B':'legacy-flat'})
        folded=M[:2]+['(I)[B']
        oracle=self.authority(source_authority=flat);oracle.observe(local('byte[]'),folded);report=oracle.finish()
        self.assertEqual((1,0),(report['arrayCount'],report['unprovedCount']))
        self.assertEqual({'C':M,'B':M},oracle.rows[0]['rawMethods'])
        exact=self.authority();exact.observe(local('byte[][]'),folded)
        with self.assertRaisesRegex(ValueError,'conflicts/missing'):exact.finish()
        self.assertEqual(['C:missing raw method/local','B:missing raw method/local'],exact.rows[0]['issues'])

    def test_flat_overloads_sharing_a_folded_key_must_agree_on_the_local(self):
        # Overloads that differ only in array rank share a flat writer's method key; every body persists
        # its locals under it. They prove a local when they agree on its flat rendering, carry the
        # recurring identity, and are ambiguous when the renderings or a creation witness differ.
        from types import SimpleNamespace
        flat=SimpleNamespace(kinds={'C':'legacy-flat','B':'legacy-flat'})
        def twin(arm,rows,raw,witness=None):
            other=copy.deepcopy(rows[1]);other['method']=M[:2]+['(I)[[[B'];other['locals']['$stack4'][0]['type']=raw
            if witness:other['typedAllocations']={'$stack4':[{'ordinal':0,'type':{'kind':'class','name':'owner.Alloc'}}]}
            rows.insert(2,other);rows[-1]['methodCount']=2
        rank_only=self.authority(mutate=lambda arm,rows:twin(arm,rows,dict(A,dimension=3)),source_authority=flat)
        rank_only.observe(local('byte[]'),M[:2]+['(I)[B']);rank_only.observe(local('byte[]'),M[:2]+['(I)[B'])
        report=rank_only.finish();self.assertEqual((2,0),(report['arrayCount'],report['unprovedCount']))
        self.assertTrue(all(row['collidedMethodKey'] for row in rank_only.rows))
        # Bodies that render the local differently: the persisted type picks its body; none matching is ambiguous.
        other_base=self.authority(mutate=lambda arm,rows:twin(arm,rows,dict(A,base={'kind':'primitive','name':'int'})),source_authority=flat)
        other_base.observe(local('byte[]'),M[:2]+['(I)[B']);other_base.observe(local('int[]'),M[:2]+['(I)[B'])
        self.assertEqual((2,0),(other_base.finish()['arrayCount'],other_base.finish()['unprovedCount']))
        self.assertEqual(['(I)[[B','(I)[[[B'],[row['rawMethods']['B'][2] for row in other_base.rows])
        # Non-array locals of the same name are attributed by their rendering too (run 910: cloneArray's
        # $stack4 is a NullPointerException in one body and an int in the other).
        def scalar(arm,rows,raw,witness=None):
            rows[1]=copy.deepcopy(rows[1]);rows[1]['locals']['$stack4'][0]['type']={'kind':'class','name':'owner.First'}
            twin(arm,rows,raw,witness)
        pair=self.authority(mutate=lambda arm,rows:scalar(arm,rows,{'kind':'class','name':'owner.Second'}),source_authority=flat)
        pair.observe(local('owner.Second'),M[:2]+['(I)[B']);pair.observe(local('owner.First'),M[:2]+['(I)[B'])
        self.assertEqual(0,pair.finish()['unprovedCount'])
        self.assertEqual(['(I)[[[B','(I)[[B'],[row['rawMethods']['B'][2] for row in pair.rows])
        # A typed allocation of a non-array local never carries a creation proof, so it does not block.
        allocated=self.authority(mutate=lambda arm,rows:scalar(arm,rows,{'kind':'class','name':'owner.Alloc'},witness=True),source_authority=flat)
        allocated.observe(local('owner.Alloc'),M[:2]+['(I)[B']);allocated.observe(local('owner.First'),M[:2]+['(I)[B'])
        self.assertEqual(0,allocated.finish()['unprovedCount'])
        # The single body rendering the persisted array is its witness, whatever another body allocates into
        # a local of the same name (run 910: AbstractSimplex.<init>'s $stack18 is a new ZeroException in the
        # double[] overload and a row of the matrix in the double[][] one).
        beside=self.authority(mutate=lambda arm,rows:twin(arm,rows,{'kind':'class','name':'owner.Alloc'},witness=True),source_authority=flat)
        beside.observe(local('byte[]'),M[:2]+['(I)[B']);beside.observe(local('owner.Alloc'),M[:2]+['(I)[B'])
        report=beside.finish();self.assertEqual((1,0),(report['arrayCount'],report['unprovedCount']))
        self.assertEqual(['(I)[[B','(I)[[[B'],[row['rawMethods']['B'][2] for row in beside.rows])
        # A body whose raw type is no array is checked only for not being persisted as one, as uncollided.
        untyped=self.authority(mutate=lambda arm,rows:twin(arm,rows,{'kind':'unsupported','runtimeClass':'sootup.core.types.UnknownType'}),source_authority=flat)
        untyped.observe(local('unknown'),M[:2]+['(I)[B']);untyped.observe(local('byte[]'),M[:2]+['(I)[B'])
        self.assertEqual(0,untyped.finish()['unprovedCount'])
        self.assertEqual(['(I)[[[B','(I)[[B'],[row['rawMethods']['B'][2] for row in untyped.rows])
        none=self.authority(mutate=lambda arm,rows:twin(arm,rows,dict(A,base={'kind':'primitive','name':'int'})),source_authority=flat)
        none.observe(local('long[]'),M[:2]+['(I)[B'])
        with self.assertRaisesRegex(ValueError,'conflicts/missing'):none.finish()
        self.assertIn('C:flat method key ambiguous among raw overloads',none.rows[0]['issues'])
        witnessed=self.authority(mutate=lambda arm,rows:twin(arm,rows,dict(A,dimension=3),witness=True),source_authority=flat)
        witnessed.observe(local('byte[]'),M[:2]+['(I)[B'])
        with self.assertRaisesRegex(ValueError,'conflicts/missing'):witnessed.finish()
        self.assertIn('B:flat method key ambiguous among raw overloads',witnessed.rows[0]['issues'])
        elsewhere=self.authority(mutate=lambda arm,rows:twin(arm,rows,copy.deepcopy(A)),source_authority=flat)
        for arm in elsewhere.methods.values():
            for key,row in arm.items():
                if key[2]=='(I)[[[B':row['locals']={}
        elsewhere.observe(local('byte[]'),M[:2]+['(I)[B']);self.assertEqual(0,elsewhere.finish()['unprovedCount'])
        alone=self.authority(source_authority=flat);alone.observe(local('byte[]'),M[:2]+['(I)[B']);alone.observe(local('byte[]'),M[:2]+['(I)[B'])
        with self.assertRaisesRegex(ValueError,'conflicts/missing'):alone.finish()
        self.assertIn('duplicate persisted full method/local identity',alone.rows[1]['issues'])

    def test_gzip_compressed_export_loads_as_the_plain_one(self):
        # The exporter gzips its JSON lines (a 64-graph export is ~10 GB plain); the pin is over the
        # file's bytes and the rows read the same.
        plain=self.authority();plain.observe(local('byte[][]'),M);plain.finish()
        for arm,ref in plain.spec['exports'].items():
            path=Path(ref['path']);zipped=path.with_suffix('.jsonl.gz')
            zipped.write_bytes(gzip.compress(path.read_bytes(),mtime=0))
            self.assertEqual(r.load(ref,plain.spec['graphId'],'a'*64,3),
                             r.load({'path':str(zipped),'sha256':r.sha(zipped)},plain.spec['graphId'],'a'*64,3))
            with self.assertRaisesRegex(ValueError,'raw Local export pin'):
                r.load({'path':str(zipped),'sha256':ref['sha256']},plain.spec['graphId'],'a'*64,3)
        report=self.root/'raw-local-type-proof.json.gz'
        self.assertEqual(b'\x1f\x8b',report.read_bytes()[:2])
        self.assertEqual(1,json.loads(gzip.decompress(report.read_bytes()))['localCount'])

    def test_unchanged_rank_one_is_checked(self):
        for arm in self.rows:self.rows[arm]['locals']['$stack4'][0]['type']['dimension']=1
        self.assertEqual(1,self.check('byte[]').finish()['arrayCount'])

    def test_unchanged_wrong_rank_one_is_rejected(self):
        oracle=self.check('byte[]')
        with self.assertRaisesRegex(ValueError,'conflicts/missing'):oracle.finish()
        self.assertEqual(2,oracle.rows[0]['rawDimension'])

    def test_array_disguised_as_scalar_is_rejected(self):
        with self.assertRaises(ValueError):self.check('byte').finish()

    def test_method_full_return_identity_not_omitted(self):
        with self.assertRaises(ValueError):self.check(method=M[:2]+['(I)[B']).finish()

    def test_method_full_parameter_rank_not_collapsed(self):
        with self.assertRaises(ValueError):self.check(method=M[:2]+['([I)[[B']).finish()

    def test_conflicting_same_name_never_selects_matching_candidate(self):
        self.rows['B']['locals']['$stack4'].append({'type':dict(A,dimension=1),'origin':'stmt:0'})
        oracle=self.check()
        with self.assertRaises(ValueError):oracle.finish()
        self.assertIn('B:conflicting same-name raw types',oracle.rows[0]['issues'])

    def test_duplicate_identical_raw_occurrences_are_not_conflicts(self):
        self.rows['B']['locals']['$stack4']*=3
        self.assertEqual(1,self.check().finish()['arrayCount'])

    def test_typed_allocation_not_used_to_guess_creation_order(self):
        self.rows['B']['typedAllocations']['$stack4']=[{'ordinal':1,'type':{'kind':'class','name':'Foo'}}]
        oracle=self.check()
        with self.assertRaises(ValueError):oracle.finish()
        self.assertIn('B:typed allocation requires creation-order proof',oracle.rows[0]['issues'])

    def creation_source(self):
        fixture=binding_fixture.FormatterBindingTests();fixture.setUp();self.addCleanup(fixture.doCleanups)
        output=fixture.root/'raw-creation';report=binding.save(output,fixture.sources,fixture.fixtures,fixture.pins)
        authority=corrections.Authority(output,report['rule'],{'schema':'graphite.classfile-field-authority.v1',
            'arms':{arm:{'fixtureManifest':fixture.fixtures[arm]} for arm in ('C','B')}})
        return fixture,authority

    def ordinary_before_allocation(self):
        for arm in self.rows:
            row=self.rows[arm];row['statementCount']=60
            row['typedAllocations']['$stack4']=[{'ordinal':57,'type':{'kind':'class','name':'java.util.ArrayList'}}]
            row['ordinaryAssignments']={'$stack4':[{'ordinal':9,'type':copy.deepcopy(A)}]}
            row['locals']['$stack4'].append({'type':copy.deepcopy(A),'origin':'ordinary-assignment:9'})

    def with_creation_source(self):
        fixture,source=self.creation_source();oracle=self.authority(source_authority=source)
        oracle.observe(local('byte[][]'),M);return fixture,oracle

    def test_ordinary_assignment_in_both_arms_proves_later_new_keeps_array(self):
        self.ordinary_before_allocation();_,oracle=self.with_creation_source();report=oracle.finish()
        self.assertEqual(1,report['arrayCount']);proof=report['occurrences'][0]['creationOrderProof']
        self.assertEqual({'C','B'},set(proof['arms']))
        self.assertEqual(9,proof['arms']['B']['ordinaryAssignment']['ordinal'])
        self.assertEqual(57,proof['arms']['B']['firstTypedAllocationOrdinal'])
        self.assertFalse(report['completeSemanticEquivalence'])

    def allocation_first(self):
        for row in self.rows.values():
            row['statementCount']=8
            row['typedAllocations']={'$stack4':[{'ordinal':2,'type':{'kind':'class','name':'First'}}]}
            row['ordinaryAssignments']={'$stack4':[{'ordinal':5,'type':copy.deepcopy(A)}]}
            row['locals']['$stack4']=[{'type':copy.deepcopy(A),'origin':origin} for origin in
                ('body.locals','stmt:2','typed-allocation:2','stmt:5','ordinary-assignment:5')]

    def allocation_check(self, saved='First'):
        _,source=self.creation_source();oracle=self.authority(source_authority=source)
        oracle.observe(local(saved),M);return oracle

    def test_first_allocation_derives_class_without_claiming_raw_array_match(self):
        self.allocation_first();report=self.allocation_check().finish();row=report['occurrences'][0]
        self.assertEqual((0,1),(report['arrayCount'],report['typedAllocationCount']))
        self.assertEqual('PASS_TYPED_ALLOCATION',row['status'])
        self.assertEqual(A,row['rawType']);self.assertEqual('First',row['expectedType'])
        self.assertEqual('typed-allocation-at-first-local-statement',row['creationOrderProof']['rule'])
        self.assertTrue(report['sourceCreationInputs']);self.assertFalse(report['completeSemanticEquivalence'])

    def test_allocation_first_requires_both_arms_earliest_statement_and_witnesses(self):
        mutations=[
            lambda row:row['locals']['$stack4'].append({'type':A,'origin':'stmt:0'}),
            lambda row:row['locals']['$stack4'].pop(1),
            lambda row:row['locals']['$stack4'].pop(2),
            lambda row:row['locals']['$stack4'].append({'type':A,'origin':'unknown:0'}),
            lambda row:row['locals']['$stack4'].append({'type':A,'origin':'stmt:8'}),
            lambda row:row['locals']['$stack4'].append({'type':A,'origin':'stmt:-1'}),
            lambda row:row['typedAllocations']['$stack4'][0]['type'].update(name='Other'),
            lambda row:row['typedAllocations'].clear(),
            lambda row:row['locals']['$stack4'].append({'type':dict(A,dimension=1),'origin':'stmt:3'}),
        ]
        for index,mutate in enumerate(mutations):
            with self.subTest(index=index):
                self.allocation_first();mutate(self.rows['C'])
                with self.assertRaises(ValueError):self.allocation_check().finish()

    def test_allocation_first_never_selects_saved_matching_later_allocation(self):
        self.allocation_first()
        for row in self.rows.values():
            row['typedAllocations']['$stack4'].append({'ordinal':6,'type':{'kind':'class','name':'Later'}})
            row['locals']['$stack4'].extend({'type':A,'origin':origin} for origin in ('stmt:6','typed-allocation:6'))
        self.assertEqual('First',self.allocation_check().finish()['occurrences'][0]['expectedType'])
        for saved in ('Later','byte[][]','byte[]'):
            with self.subTest(saved=saved),self.assertRaises(ValueError):self.allocation_check(saved).finish()

    def test_allocation_first_without_source_authority_cannot_pass(self):
        self.allocation_first();oracle=self.authority();oracle.observe(local('First'),M)
        with self.assertRaises(ValueError):oracle.finish()

    def test_observed_hive_short_writable_allocation_precedes_byte_array_assignments(self):
        # Actual failed CI row plus independently reproduced statementCount=157.
        # C/B target-method.json SHA256 bec8a5c25d326e04b5f116b9c5c94a328
        # 483a0ece04e88d5d16134dc373c770d. This unit fixture retains r only;
        # it does not claim to replay the real full-shard export authority.
        method=['org.apache.hadoop.hive.serde2.teradata.TeradataBinarySerde','deserializeField',
                '(Lorg/apache/hadoop/hive/serde2/teradata/TeradataBinaryDataInputStream;'
                'Lorg/apache/hadoop/hive/serde2/typeinfo/TypeInfo;Ljava/lang/Object;Z)Ljava/lang/Object;']
        raw={'kind':'array','dimension':1,'base':{'kind':'primitive','name':'byte'}}
        saved='org.apache.hadoop.hive.serde2.io.ShortWritable'
        origins=['body.locals','stmt:30','typed-allocation:30','stmt:31','stmt:33',
                 'ordinary-assignment:33','stmt:34','stmt:35','stmt:130',
                 'ordinary-assignment:130','stmt:154','stmt:155']
        for arm in self.rows:
            self.rows[arm]={'record':'method','method':method,'statementCount':157,
                'locals':{'r':[{'type':raw,'origin':origin} for origin in origins]},
                'typedAllocations':{'r':[{'ordinal':30,'type':{'kind':'class','name':saved}}]},
                'ordinaryAssignments':{'r':[{'ordinal':i,'type':raw} for i in (33,130)]}}
        _,source=self.creation_source();oracle=self.authority(source_authority=source)
        oracle.observe(local(saved,name='r',node=15864636),method);report=oracle.finish()
        row=report['occurrences'][0]
        self.assertEqual('PASS_TYPED_ALLOCATION',row['status'])
        self.assertEqual((0,1,0),(report['arrayCount'],report['typedAllocationCount'],report['unprovedCount']))
        self.assertEqual(raw,row['rawType']);self.assertEqual(saved,row['expectedType'])
        self.assertEqual(30,row['creationOrderProof']['arms']['B']['firstStatementOrdinal'])
        r.verify_creation_inputs(report,source,oracle.pins)

    def test_allocation_first_report_cannot_drop_proof_or_change_classification(self):
        self.allocation_first();oracle=self.allocation_check();report=oracle.finish()
        r.verify_creation_inputs(report,oracle.source_authority,oracle.pins)
        for mutation in (
            lambda x:x['occurrences'][0].pop('creationOrderProof'),
            lambda x:x['occurrences'][0].update(status='PASS_ARRAY'),
            lambda x:x['occurrences'][0].update(expectedType='byte[][]'),
            lambda x:x['occurrences'][0]['arms']['B'].pop('statementCount'),
            lambda x:x.pop('sourceCreationInputs'),
        ):
            changed=copy.deepcopy(report);mutation(changed)
            with self.assertRaises(ValueError):r.verify_creation_inputs(changed,oracle.source_authority,oracle.pins)

    def test_ordinary_assignment_without_source_authority_still_blocks(self):
        self.ordinary_before_allocation()
        with self.assertRaisesRegex(ValueError,'conflicts/missing'):self.check().finish()

    def test_missing_one_arm_witness_and_uses_are_not_creation_proof(self):
        self.ordinary_before_allocation();del self.rows['C']['ordinaryAssignments']
        self.rows['C']['locals']['$stack4'].append({'type':A,'origin':'stmt:0'})
        _,oracle=self.with_creation_source()
        with self.assertRaises(ValueError):oracle.finish()

    def test_later_or_equal_ordinary_assignment_cannot_justify_saved_array(self):
        for ordinal in (57,58):
            with self.subTest(ordinal=ordinal):
                self.ordinary_before_allocation()
                for row in self.rows.values():
                    row['ordinaryAssignments']['$stack4'][0]['ordinal']=ordinal
                    row['locals']['$stack4'][-1]['origin']='ordinary-assignment:'+str(ordinal)
                with self.assertRaises(ValueError):
                    _,oracle=self.with_creation_source();oracle.finish()

    def test_earliest_of_all_typed_allocations_must_follow_assignment(self):
        self.ordinary_before_allocation()
        self.rows['C']['typedAllocations']['$stack4'].insert(0,{'ordinal':2,'type':{'kind':'class','name':'Earlier'}})
        _,oracle=self.with_creation_source()
        with self.assertRaises(ValueError):oracle.finish()

    def test_ordinary_assignment_does_not_resolve_raw_type_conflict(self):
        self.ordinary_before_allocation();self.rows['B']['locals']['$stack4'].append({'type':dict(A,dimension=1),'origin':'stmt:0'})
        _,oracle=self.with_creation_source()
        with self.assertRaises(ValueError):oracle.finish()
        self.assertNotIn('creationOrderProof',oracle.rows[0])

    def test_assignment_cannot_hide_saved_rank_error(self):
        self.ordinary_before_allocation();_,source=self.creation_source();oracle=self.authority(source_authority=source)
        oracle.observe(local('byte[]'),M)
        with self.assertRaises(ValueError):oracle.finish()

    def test_assignment_requires_exact_left_local_occurrence(self):
        self.ordinary_before_allocation();self.rows['B']['locals']['$stack4'][-1]['origin']='stmt:9'
        with self.assertRaisesRegex(ValueError,'raw left Local'):self.authority()

    def test_assignment_ordinal_type_range_and_duplicates_rejected(self):
        for ordinal in (-1,60,True,'9'):
            with self.subTest(ordinal=ordinal):
                self.ordinary_before_allocation();self.rows['B']['ordinaryAssignments']['$stack4'][0]['ordinal']=ordinal
                with self.assertRaisesRegex(ValueError,'ordinal'):self.authority()
        self.ordinary_before_allocation();self.rows['B']['ordinaryAssignments']['$stack4']*=2
        with self.assertRaisesRegex(ValueError,'ordinal'):self.authority()

    def test_source_mutation_before_or_after_witness_is_rejected(self):
        self.ordinary_before_allocation();fixture,source=self.creation_source()
        oracle=self.authority(source_authority=source)
        path=Path(source.rule['arms']['B']['adapter']);path.write_text(path.read_text()+'changed')
        with self.assertRaisesRegex(ValueError,'authority changed'):oracle.observe(local('byte[][]'),M)
        _,oracle=self.with_creation_source();path=Path(oracle.source_authority.rule['arms']['C']['adapter'])
        path.write_text(path.read_text()+'changed')
        with self.assertRaisesRegex(ValueError,'changed'):oracle.finish()

    def test_real_creation_report_flows_through_graph_receipt_with_exact_source_pins(self):
        self.creation_graph_report(False)

    def test_allocation_first_report_flows_through_graph_receipt_with_exact_source_pins(self):
        self.creation_graph_report(True)

    def creation_graph_report(self, allocation):
        # Only the exporter-binding input is injected; source manifests, source
        # rules, raw Local reader, creation proof and graph receipt audit are real.
        from unittest.mock import patch
        import run_native_core_equivalence as runner
        if allocation:
            self.allocation_first();fixture,source=self.creation_source()
            oracle=self.authority(source_authority=source);oracle.observe(local('First'),M)
        else:
            self.ordinary_before_allocation();fixture,oracle=self.with_creation_source()
        graph_id=oracle.spec['graphId'];proof=self.root/'graphs'/graph_id
        (proof/'core').mkdir(parents=True);oracle.out=proof/'core';report=oracle.finish()
        self.assertEqual(2,len(report['inputs']));self.assertTrue(report['sourceCreationInputs'])
        self.assertTrue(all(oracle.pins[p]==h for p,h in report['sourceCreationInputs'].items()))
        source_path=fixture.root/'raw-creation/local-array-source-rule.json'
        row={'id':graph_id,'B':'/actual/B','C':'/actual/C','fieldAuthority':{
            'schema':'graphite.classfile-field-authority.v1',
            'arms':{a:{'fixtureManifest':fixture.fixtures[a]} for a in ('C','B')}}}
        plan={'output':str(self.root),'rawLocalExports':[],'formatterKinds':{'C':'legacy-flat','B':'rank-preserving'},
              'sourceRule':{'path':str(source_path),'sha256':r.sha(source_path)}}
        (proof/'core/field-bijection.tsv').write_text('tiny mapping')
        counts={k:0 for k in runner.COUNTS}
        receipt={'actual':row['B'],'reference':row['C'],'inputs':dict(oracle.pins),
                 'mappingSha256':r.sha(proof/'core/field-bijection.tsv'),**counts}
        declaration={'status':'PASS_ADDITIVE_DECLARATION_WIRE_VALIDITY','declarationTable':'present',
                     'sourceToDeclarationCompletenessClaim':False,'queryExpectedAuthority':False}
        properties={'status':'PASS_REQUIRES_COMPLETE_TOPOLOGY'}
        def save(path,value):path.write_text(json.dumps(value))
        def write_receipts():
            save(proof/'core/receipt.json',receipt)
            save(proof/'record.json',{'status':runner.CORE_PASS,'strictEquivalence':False,
                'core':receipt,'declarations':declaration,'properties':properties,**counts})
            save(proof/'topology.json',{'status':runner.TOPOLOGY_PASS,'strictEquivalence':False,
                'coreReceiptSha256':r.sha(proof/'core/receipt.json'),'mappingSha256':receipt['mappingSha256'],
                'inputPins':receipt['inputs'],'actual':row['B'],'reference':row['C'],**counts})
        save(proof/'declarations.json',declaration);save(proof/'properties.json',properties)
        write_receipts()
        with patch.object(runner.raw_local_export,'binding',return_value=oracle.spec):
            self.assertEqual(graph_id,runner.graph_result(plan,row)['id'])
            source_pin=next(iter(report['sourceCreationInputs']))
            report=copy.deepcopy(report)
            del report['sourceCreationInputs'][source_pin]
            save(proof/'core/raw-local-type-proof.json.gz',report)
            with self.assertRaisesRegex(ValueError,'exact Local creation source'):runner.graph_result(plan,row)
            report=copy.deepcopy(oracle.finish())
            del report['sourceCreationInputs'];del report['occurrences'][0]['creationOrderProof']
            save(proof/'core/raw-local-type-proof.json.gz',report)
            with self.assertRaisesRegex(ValueError,'required exact ordinary'):runner.graph_result(plan,row)
            report=copy.deepcopy(oracle.finish())
            key='firstStatementOrdinal' if allocation else 'firstTypedAllocationOrdinal'
            report['occurrences'][0]['creationOrderProof']['arms']['B'][key]=58
            save(proof/'core/raw-local-type-proof.json.gz',report)
            with self.assertRaisesRegex(ValueError,'required exact ordinary'):runner.graph_result(plan,row)
            report=oracle.finish();del receipt['inputs'][source_pin];write_receipts()
            with self.assertRaisesRegex(ValueError,'missing from core'):runner.graph_result(plan,row)

    def test_regular_nonarray_allocation_is_outside_array_proof(self):
        for arm in self.rows:
            self.rows[arm]=self.method({'kind':'class','name':'Foo'})
            self.rows[arm]['typedAllocations']['$stack4']=[{'ordinal':1,'type':{'kind':'class','name':'Foo'}}]
        report=self.check('Foo').finish();self.assertEqual(0,report['arrayCount'])
        self.assertTrue(report['occurrences'][0]['outsideArrayScopeNotes'])

    def test_missing_is_retained_and_later_rows_are_checked(self):
        oracle=self.authority();oracle.observe(local('byte[][]',name='missing',node=1),M)
        oracle.observe(local('byte[][]',node=2),M)
        with self.assertRaises(ValueError):oracle.finish()
        report=json.loads(gzip.decompress((self.root/'raw-local-type-proof.json.gz').read_bytes()))
        self.assertEqual((2,1,1),(report['localCount'],report['arrayCount'],report['unprovedCount']))

    def test_duplicate_persisted_identity_rejected(self):
        oracle=self.check();oracle.observe(local('byte[][]',node=2),M)
        with self.assertRaises(ValueError):oracle.finish()

    def test_raw_arms_must_agree_without_candidate_selection(self):
        self.rows['C']['locals']['$stack4'][0]['type']['dimension']=3
        with self.assertRaises(ValueError):self.check().finish()

    def test_saved_array_requires_raw_array(self):
        for arm in self.rows:self.rows[arm]=self.method({'kind':'primitive','name':'byte'})
        with self.assertRaises(ValueError):self.check('byte[]').finish()

    def test_unicode_base_and_rank255(self):
        for arm in self.rows:self.rows[arm]=self.method({'kind':'array','dimension':255,'base':{'kind':'class','name':'包.类型🚀'}})
        self.assertEqual(1,self.check('包.类型🚀'+'[]'*255).finish()['arrayCount'])

    def test_truncated_export_is_not_success(self):
        with self.assertRaisesRegex(ValueError,'complete raw'):self.authority(lambda a,rows:rows.pop())

    def test_duplicate_method_is_rejected(self):
        def mutate(a,rows):rows.insert(1,copy.deepcopy(rows[1]));rows[-1]['methodCount']=2
        with self.assertRaisesRegex(ValueError,'duplicate complete'):self.authority(mutate)

    def test_wrong_shard_and_folding_rejected(self):
        for key,value in [('graphId','different'),('shardBytecodeSha256','b'*64),('folding',True)]:
            with self.subTest(key=key),self.assertRaises(ValueError):self.authority(lambda a,rows:rows[0].update({key:value}))

    def test_raw_dimension_malformed_rejected(self):
        for dimension in (0,256,True,'2'):
            with self.subTest(dimension=dimension),self.assertRaises(ValueError):r.render(dict(A,dimension=dimension))

    def test_export_mutation_rejected(self):
        oracle=self.check();(self.root/'B.jsonl').write_text('mutated')
        with self.assertRaisesRegex(ValueError,'changed'):oracle.finish()

    def test_partial_inventory_cannot_claim_pass(self):
        oracle=self.check();self.assertEqual('PARTIAL_RAW_LOCAL_TYPE_PROOF',oracle.save()['status'])


if __name__=='__main__':unittest.main()
