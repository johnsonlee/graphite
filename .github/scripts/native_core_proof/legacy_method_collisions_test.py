"""Reject missing evidence and unrelated changes; preserve strict default gates."""
import copy
import unittest
from . import core_semantics as core
from . import legacy_method_collisions as collisions
from .method_authority_test import Fake, klass, I
from .legacy_wire import Invalid

CALLER = ('A', 'run', '()V')
ONE = ('A', 'm', '([I)V')
TWO = ('A', 'm', '([[I)V')
THREE = ('A', 'm', '([[[I)V')


def auth():
    result = Fake({'A': klass('A', [('run', '()V'), ('m', ONE[2]), ('m', TWO[2]), ('m', THREE[2])])})
    result.allow_legacy_collisions = True
    return result


def fixture():
    def row(method, ordinal):
        return dict(caller=CALLER, callee=method, ordinal=ordinal, origin=None, originMember=None)
    a = {'rows': {10: row(TWO, 0), 11: row(TWO, 1), 12: row(ONE, 0)},
         'ordinalEntries': 3, 'originEntries': 0}
    b = copy.deepcopy(a)
    for ordinal, row in enumerate(b['rows'].values()):
        row.update(callee=ONE, ordinal=ordinal)
    return a, b


def metadata(types, suffix=None):
    strings = ['A', 'm', 'void'] + types
    data = I(0x47524d03) + I(len(types))
    for i in range(len(types)):
        data += I(0) + I(1) + I(1) + I(3+i) + I(2)
    data += I(0)*7 if suffix is None else suffix
    spans = []
    return core.metadata(data, strings, {}, spans), spans


class Collisions(unittest.TestCase):
    def test_opt_in_retains_all_exact_declarations(self):
        a = auth()
        self.assertTrue(a.pair(TWO, ONE, 'definition', 'two'))
        self.assertTrue(a.pair(THREE, ONE, 'definition', 'three'))
        self.assertEqual(len(a.mapping), 2)
        self.assertTrue(all(len(p['legacyProjectionCandidates']) == 3 for p in a.proofs.values()))
        with self.assertRaises(Invalid):
            a.pair(('A','m','([[[[I)V'), ONE, 'definition', 'missing')

    def test_group_repartition_checks_every_member_and_keeps_inputs(self):
        a,b = fixture(); before = copy.deepcopy(a); evidence = []
        result = collisions.compare_ordinals(a,b,auth(),evidence)
        self.assertEqual(result['status'],'PASS'); self.assertEqual(a,before)
        self.assertEqual([r['nodeId'] for r in evidence[0]['allMembers']], [10,11,12])
        self.assertEqual(len(evidence[0]['targetDeclarations']), 2)
        self.assertEqual(result['correctedOrdinalGroups'],1)

    def test_opt_in_required(self):
        a,b=fixture(); authority=auth(); authority.allow_legacy_collisions=False
        with self.assertRaises(Invalid):collisions.compare_ordinals(a,b,authority,[])

    def test_rejects_missing_negative_duplicate_gapped_or_wrong_rank(self):
        for arm,node,value in [('A',10,None),('B',10,-1),('B',11,0),('B',12,4),('A',11,0),('A',12,1)]:
            with self.subTest(arm=arm,node=node,value=value):
                a,b=fixture(); (a if arm=='A' else b)['rows'][node]['ordinal']=value
                with self.assertRaises(Invalid):collisions.compare_ordinals(a,b,auth(),[])

    def test_unrelated_ordinal_change_without_overload_rejected(self):
        a,b=fixture()
        for r in a['rows'].values():r['callee']=ONE
        with self.assertRaises(Invalid):collisions.compare_ordinals(a,b,auth(),[])

    def test_origins_and_member_identity_remain_exact(self):
        for key,value in [('origin',10),('originMember',(CALLER,ONE)),('caller',('A','missing','()V')),('callee',('A','other',TWO[2]))]:
            a,b=fixture();a['rows'][12][key]=value
            with self.assertRaises(Invalid):collisions.compare_ordinals(a,b,auth(),[])

    def test_unchanged_callee_still_needs_exact_declaration(self):
        a,b=fixture();authority=auth()
        authority.classes['A']=klass('A',[('run','()V'),('m',TWO[2])])
        with self.assertRaises(Invalid):collisions.compare_ordinals(a,b,authority,[])

    def test_unchanged_caller_still_needs_exact_declaration(self):
        a,b=fixture();authority=auth()
        authority.classes['A']=klass('A',[('m',ONE[2]),('m',TWO[2])])
        with self.assertRaises(Invalid):collisions.compare_ordinals(a,b,authority,[])

    def test_all_callsite_ids_required(self):
        a,b=fixture();del b['rows'][11]
        with self.assertRaises(Invalid):collisions.compare_ordinals(a,b,auth(),[])

    def test_return_only_difference_does_not_create_ordinal_counter(self):
        a,b=fixture()
        for n in a['rows']:
            a['rows'][n]['callee']=('A','m','([I)[[I') if n<12 else ('A','m','([I)[I')
            b['rows'][n]['callee']=('A','m','([I)[I')
        authority=auth();authority.classes['A']=klass('A',[('run','()V'),('m','([I)[[I'),('m','([I)[I')])
        with self.assertRaises(Invalid):collisions.compare_ordinals(a,b,authority,[])

    def test_metadata_recovers_overloads_in_original_order(self):
        a,am=metadata(['int[]','int[][]','int[][][]']);b,bm=metadata(['int[]']);evidence=[]
        self.assertEqual(collisions.compare_metadata(a,b,am,bm,auth(),evidence), b)
        self.assertEqual(evidence[0]['B'], [ONE,TWO,THREE]);self.assertEqual(len(evidence[0]['declarations']),3)

    def test_metadata_missing_or_reordered_method_rejected(self):
        authority=auth();authority.classes['A']=klass('A',[('m','([I)V'),('m','([[I)V'),('m','([J)V')])
        a,am=metadata(['int[][]','long[]'])
        for types in (['int[]'],['long[]','int[]']):
            b,bm=metadata(types)
            with self.assertRaises(Invalid):collisions.compare_metadata(a,b,am,bm,authority,[])

    def test_metadata_unrelated_suffix_is_not_rewritten(self):
        a,am=metadata(['int[]','int[][]']);b,bm=metadata(['int[]'])
        # A recognized optional metadata digest is parsed but cannot be waived.
        a += I(0x47525801) + bytes(32)
        b += I(0x47525801) + bytes([1])*32
        with self.assertRaises(Invalid):collisions.compare_metadata(a,b,am,bm,auth(),[])

    def test_metadata_missing_unchanged_declaration_in_collision_rejected(self):
        a,am=metadata(['int[]','int[][]']);b,bm=metadata(['int[]']);authority=auth()
        authority.classes['A']=klass('A',[('m',TWO[2])])
        with self.assertRaises(Invalid):collisions.compare_metadata(a,b,am,bm,authority,[])

    def test_metadata_bad_span_or_count_is_rejected(self):
        a,am=metadata(['int[]','int[][]']);b,bm=metadata(['int[]'])
        for data,spans in [(a[:4]+I(1)+a[8:],am),(a,am[1:]),(a,[dict(am[0],start=9),am[1]])]:
            with self.assertRaises(Invalid):collisions.compare_metadata(data,b,spans,bm,auth(),[])


class OrdinalBindings(unittest.TestCase):
    def setup_case(self):
        from .callsite_ordinals_test import encode
        a,b=fixture(); inputs=[];metadata=[]
        for graph in (a,b):
            raw,digest=encode([(n,row['ordinal']) for n,row in graph['rows'].items()])
            inputs.append((raw,{n:(row['caller'],row['callee']) for n,row in graph['rows'].items()}))
            metadata.append(b'checked metadata prefix'+I(0x47524202)+digest)
        return metadata,inputs

    def test_raw_block_and_semantic_repartition_prove_changed_binding(self):
        (a,b),inputs=self.setup_case();evidence=[]
        self.assertEqual(collisions.compare_binding(a,b,inputs,auth(),evidence),b)
        self.assertEqual(evidence[0]['semanticProof']['callSites'],3)
        self.assertEqual(len(evidence[0]['ordinalGroups']),1)

    def test_changed_arbitrary_metadata_byte_rejected(self):
        (a,b),inputs=self.setup_case()
        with self.assertRaises(Invalid):collisions.compare_binding(b'x'+a[1:],b,inputs,auth(),[])

    def test_digest_header_or_bytes_cannot_be_forged(self):
        (a,b),inputs=self.setup_case()
        for bad in (a[:-36]+I(0x47524201)+a[-32:],a[:-32]+bytes(32)):
            with self.assertRaises(Invalid):collisions.compare_binding(bad,b,inputs,auth(),[])

    def test_corrupt_sidecar_block_rejected(self):
        (a,b),inputs=self.setup_case();raw,members=inputs[0]
        inputs[0]=(raw[:-1]+bytes([raw[-1]^1]),members)
        with self.assertRaises(Invalid):collisions.compare_binding(a,b,inputs,auth(),[])

    def test_valid_hashes_with_unexplained_ordinal_change_rejected(self):
        from .callsite_ordinals_test import encode
        (a,b),inputs=self.setup_case();raw,digest=encode([(10,0),(11,9),(12,0)])
        inputs[0]=(raw,inputs[0][1]);a=a[:-32]+digest
        with self.assertRaises(Invalid):collisions.compare_binding(a,b,inputs,auth(),[])

    def test_no_raw_sidecar_means_no_binding_waiver(self):
        (a,b),inputs=self.setup_case()
        with self.assertRaises(Invalid):collisions.compare_binding(a,b,inputs[:1],auth(),[])

if __name__=='__main__':unittest.main()
