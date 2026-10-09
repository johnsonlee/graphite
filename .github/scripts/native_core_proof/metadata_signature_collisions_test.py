"""Return-omitted table recovery must match complete raw declarations and order."""
import unittest
from . import core_semantics as core
from . import legacy_method_collisions as collisions
from . import metadata_signature_collisions as model
from .method_authority_test import Fake,klass,I
from .legacy_wire import Invalid

ONE=('A','m','([I)I')
TWO=('A','m','([[I)J')
OLD=('A','m','([I)J')
OTHER=('A','n','()V')

def authority(keys=(ONE,TWO,OTHER)):
    a=Fake({'A':klass('A',[(k[1],k[2]) for k in keys])});a.allow_legacy_collisions=True;return a

def metadata(keys):
    strings=[]
    def sid(s):
        if s not in strings:strings.append(s)
        return I(strings.index(s))
    data=I(0x47524d03)+I(len(keys))
    for owner,name,d in keys:
        types={'[I':'int[]','[[I':'int[][]','[[[I':'int[][][]','I':'int','J':'long','V':'void'}
        p,r=d[1:].split(')');params=[types[p]] if p else []
        data+=sid(owner)+sid(name)+I(len(params))+b''.join(sid(t) for t in params)+sid(types[r])
    spans=[];return core.metadata(data+I(0)*7,strings,{},spans),spans

def compare(keys=(ONE,TWO),old=(OLD,),auth=None):
    a,am=metadata(keys);b,bm=metadata(old);ev=[];auth=auth or authority()
    result=collisions.compare_metadata(a,b,am,bm,auth,[],return_evidence=ev)
    return result,b,ev,auth

class ReturnOmittedMetadata(unittest.TestCase):
    def test_complete_group_keeps_long_return_without_rewriting_int(self):
        result,b,ev,auth=compare();self.assertEqual(result,b)
        self.assertEqual(ev[0]['retainedMethod'],TWO)
        self.assertEqual(ev[0]['B'],[ONE,TWO]);self.assertEqual(ev[0]['recoveredMethods'],1)
        self.assertFalse(ev[0]['methodReturnNormalization'])
        self.assertEqual(auth.mapping,{TWO:OLD})

    def test_strict_default_still_rejects(self):
        a,am=metadata((ONE,TWO));b,bm=metadata((OLD,))
        with self.assertRaises(Invalid):collisions.compare_metadata(a,b,am,bm,authority(),[])

    def test_wrong_survivor_is_rejected(self):
        with self.assertRaisesRegex(Invalid,'exact linked-set/map'):compare(old=(ONE,))

    def test_missing_raw_source_overload_is_rejected(self):
        with self.assertRaisesRegex(Invalid,'exact direct'):compare(auth=authority((ONE,)))

    def test_unreported_source_overload_is_rejected(self):
        with self.assertRaisesRegex(Invalid,'complete source group'):compare(auth=authority((ONE,TWO,('A','m','([[[I)J'))))

    def test_reordered_group_is_rejected(self):
        with self.assertRaisesRegex(Invalid,'complete source group/order'):compare(keys=(TWO,ONE))

    def test_map_keeps_first_key_position_across_unrelated_row(self):
        result,b,ev,_=compare(keys=(ONE,OTHER,TWO),old=(OLD,OTHER))
        self.assertEqual(result,b);self.assertEqual(ev[0]['firstTablePosition'],0)
        self.assertEqual(ev[0]['retainedOriginalPosition'],2)

    def test_unrelated_suffix_remains_exact(self):
        a,am=metadata((ONE,TWO));b,bm=metadata((OLD,))
        with self.assertRaisesRegex(Invalid,'outside method table'):
            collisions.compare_metadata(a+b'x',b+b'y',am,bm,authority(),[],return_evidence=[])

    def test_duplicate_stored_parameter_key_fails(self):
        with self.assertRaisesRegex(Invalid,'unique stored candidate signature'):
            compare(keys=(ONE,OLD),auth=authority((ONE,OLD)))

    def test_arbitrary_return_change_is_not_authorized(self):
        with self.assertRaises(Invalid):compare(keys=(ONE,),old=(OLD,))

    def test_linked_set_dedup_precedes_last_writer_map(self):
        third=('A','m','([[[I)I')
        # A later duplicate of the int MethodDescriptor is dropped, so long wins.
        self.assertEqual(model.table_result([ONE,TWO,third],True),[OLD])
        self.assertEqual(model.table_result([ONE,TWO,third],False),[ONE,TWO,third])

    def test_conflicting_visitation_alternatives_rejected(self):
        # Descriptor order I,J,I differs from rendered order I,I,J.
        # Their current map order differs, so no visitation guess is permitted.
        third=('A','m','([[[I)I')
        with self.assertRaisesRegex(Invalid,'visitation alternatives disagree'):
            compare(keys=(ONE,TWO,third),auth=authority((ONE,TWO,third)))

    def test_span_rebasing_preserves_remaining_method_proofs(self):
        a,am=metadata((ONE,TWO));b,bm=metadata((OLD,))
        # A trailing ordinary method reference: only its own array rank changes.
        tail_a,ta=metadata((TWO,));tail_b,tb=metadata((OLD,))
        ra=tail_a[ta[0]['start']:ta[0]['end']];rb=tail_b[tb[0]['start']:tb[0]['end']]
        am.append(dict(ta[0],start=len(a),end=len(a)+len(ra),tableRow=False))
        bm.append(dict(tb[0],start=len(b),end=len(b)+len(rb),tableRow=False))
        self.assertEqual(collisions.compare_metadata(a+ra,b+rb,am,bm,authority(),[],return_evidence=[]),b+rb)

if __name__=='__main__':unittest.main()
