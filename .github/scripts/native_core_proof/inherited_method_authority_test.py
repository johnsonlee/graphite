import copy,unittest
from .method_authority_test import Fake,klass
from .legacy_wire import Invalid
from . import legacy_method_collisions as collision
from .legacy_method_collisions_test import fixture

KEY=('A','m','([[I)V');OLD=('A','m','([I)V')

def cl(owner,parent,methods,flags=0x21):
    value=klass(owner,methods);value['parsed'].update(superName=parent,interfaces=[],accessFlags=flags);return value


def auth():
    a=Fake({'A':cl('A','Parent',[('run','()V')]),'Parent':cl('Parent',None,[('m','([[I)V'),('m','([I)V')])})
    a.allow_legacy_collisions=True;a.allow_inherited_class_methods=True;return a


class InheritedMethods(unittest.TestCase):
    def test_exact_parent_descriptor_preserves_symbolic_owner(self):
        a=auth();p=a.inherited_class_method(KEY)
        self.assertEqual(p['symbolicOwner'],'A');self.assertEqual(p['declaringOwner'],'Parent')
        self.assertEqual(p['method']['descriptor'],'([[I)V')
        self.assertEqual([s['owner'] for s in p['lookupSteps']],['A','Parent'])
        self.assertFalse(p['accessControlClaim']);self.assertFalse(p['interfaceResolutionClaim'])

    def test_nearer_matching_declaration_wins(self):
        a=auth();a.classes['A']['parsed']['methods'].append(copy.deepcopy(a.classes['Parent']['parsed']['methods'][0]))
        self.assertEqual(a.inherited_class_method(KEY)['declaringOwner'],'A')

    def test_wrong_descriptor_in_child_does_not_hide_exact_parent(self):
        a=auth();a.classes['A']['parsed']['methods'].append(copy.deepcopy(a.classes['Parent']['parsed']['methods'][1]))
        self.assertEqual(a.inherited_class_method(KEY)['declaringOwner'],'Parent')
        self.assertIsNone(a.inherited_class_method(('A','m','([[I)I')))

    def test_callee_only_pair_uses_explicit_superclass_mode(self):
        a=auth();self.assertTrue(a.pair(KEY,OLD,'callee','test',('A','run','()V')))
        self.assertEqual(next(iter(a.proofs.values()))['declaringOwner'],'Parent')
        for role in ('caller','definition'):
            with self.assertRaises(Invalid):auth().pair(KEY,OLD,role,'test')

    def test_default_and_direct_checks_remain_strict(self):
        a=auth();self.assertIsNone(a.direct(KEY));a.allow_inherited_class_methods=False
        with self.assertRaises(Invalid):a.inherited_class_method(KEY)
        with self.assertRaises(Invalid):collision.declared(auth(),KEY)

    def test_missing_parent_does_not_skip_to_an_interface(self):
        a=auth();del a.classes['Parent'];a.classes['A']['parsed']['interfaces']=['Interface'];a.classes['Interface']=cl('Interface',None,[('m',KEY[2])],0x201)
        self.assertIsNone(a.inherited_class_method(KEY))

    def test_interface_owner_and_initializers_are_not_inherited(self):
        a=auth();a.classes['A']['parsed']['accessFlags']=0x201
        self.assertIsNone(a.inherited_class_method(KEY))
        for name in ('<init>','<clinit>'):self.assertIsNone(auth().inherited_class_method(('A',name,'([[I)V')))

    def test_duplicate_exact_declaration_and_cycles_reject(self):
        a=auth();a.classes['Parent']['parsed']['methods'].append(copy.deepcopy(a.classes['Parent']['parsed']['methods'][0]))
        with self.assertRaises(Invalid):a.inherited_class_method(KEY)
        a=auth();a.classes['Parent']['parsed'].update(methods=[],superName='A')
        with self.assertRaises(Invalid):a.inherited_class_method(KEY)

    def test_counter_repartition_keeps_full_inherited_targets(self):
        a,b=fixture();rows=[];result=collision.compare_ordinals(a,b,auth(),rows)
        self.assertEqual(result['status'],'PASS');self.assertEqual(result['correctedOrdinalGroups'],1)
        self.assertEqual([r['nodeId'] for r in rows[0]['allMembers']],[10,11,12])
        self.assertEqual({tuple(d['method']) for d in rows[0]['targetDeclarations']},{KEY,OLD})
        self.assertEqual({d['proof']['declaringOwner'] for d in rows[0]['targetDeclarations']},{'Parent'})

    def test_counter_repartition_still_rejects_wrong_rank(self):
        a,b=fixture();a['rows'][11]['ordinal']=0
        with self.assertRaises(Invalid):collision.compare_ordinals(a,b,auth(),[])

if __name__=='__main__':unittest.main()
