import copy
import json
from pathlib import Path
import tempfile
import unittest
from . import raw_local_types as r
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

    def authority(self, mutate=None):
        spec={'graphId':'fixture-tika-00','shardBytecodeSha256':'a'*64,'classCount':3,'exports':{}}
        for arm in ('C','B'):
            rows=[{'record':'header','graphId':spec['graphId'],'shardBytecodeSha256':'a'*64,'classCount':3,
                   'scope':'fixture64-no-fold','folding':False},self.rows[arm],{'record':'complete','methodCount':1}]
            if mutate:mutate(arm,rows)
            path=self.root/(arm+'.jsonl');path.write_text(''.join(json.dumps(x)+'\n' for x in rows))
            spec['exports'][arm]={'path':str(path),'sha256':r.sha(path)}
        return r.Authority(spec,self.root)

    def check(self, value='byte[][]', method=M):
        oracle=self.authority();oracle.observe(local(value),method);return oracle

    def test_raw_rank_authority_for_changed_array(self):
        report=self.check().finish();self.assertEqual(1,report['arrayCount']);self.assertFalse(report['completeSemanticEquivalence'])

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
        report=json.loads((self.root/'raw-local-type-proof.json').read_text())
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
