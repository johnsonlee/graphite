import unittest
from . import field_authority as f
from .legacy_wire import Invalid
from .field_authority_test import class_bytes


def field(descriptor='[[D',static=False):
    return {'name':'x','descriptor':descriptor,'static':static,'signature':None,'accessFlags':8 if static else 4}


def klass(name,fields=(),parent=None,interfaces=(),interface=False):
    return {'owner':name,'fields':list(fields),'superName':parent,'interfaces':list(interfaces),'accessFlags':0x0200 if interface else 1}


class Fake(f.Authority):
    def __init__(self,classes):self.classes=classes;self.allow_inherited_fields=True;self.jar_digest='source';self.calls=[]
    def _field_class(self,owner):
        self.calls.append(owner)
        f.need(owner in self.classes,'missing exact source class')
        return self.classes[owner]


class InheritedFields(unittest.TestCase):
    def test_parser_retains_actual_super_and_interfaces(self):
        parsed=f.parse(class_bytes());self.assertEqual('java.lang.Object',parsed['superName']);self.assertEqual([],parsed['interfaces'])

    def test_exact_descriptor_in_superclass_with_symbolic_owner_preserved(self):
        a=Fake({'Child':klass('Child',[field('[D')],'Parent'),'Parent':klass('Parent',[field()],'Unavailable')})
        proof=a.field_key(('Child','x','double[][]',False))
        self.assertEqual('Parent',proof['class']['owner']);self.assertEqual('Child',proof['symbolicOwner'])
        self.assertEqual(['Child','Parent'],a.calls);self.assertEqual('[[D',proof['lookupDescriptor'])
        self.assertEqual([False,True],[p['exactMatch'] for p in proof['lookupSteps']])
        self.assertFalse(proof['accessControlClaim'])

    def test_declared_field_wins_without_loading_unneeded_ancestor(self):
        a=Fake({'C':klass('C',[field()],'Missing',['MissingInterface'])})
        self.assertEqual('C',a.field_key(('C','x','double[][]',False))['class']['owner']);self.assertEqual(['C'],a.calls)

    def test_direct_interface_precedes_superclass(self):
        a=Fake({'C':klass('C',parent='Parent',interfaces=['I']), 'I':klass('I',[field(static=True)],interface=True),'Parent':klass('Parent',[field()])})
        self.assertEqual('I',a.field_key(('C','x','double[][]',True))['class']['owner']);self.assertEqual(['C','I'],a.calls)

    def test_missing_earlier_interface_does_not_expose_later_parent(self):
        a=Fake({'C':klass('C',parent='Parent',interfaces=['Missing']), 'Parent':klass('Parent',[field()])})
        with self.assertRaises(Invalid):a.field_key(('C','x','double[][]',False))
        self.assertNotIn('Parent',a.calls)

    def test_interface_object_super_is_not_a_field_search_parent(self):
        a=Fake({'C':klass('C',parent='Parent',interfaces=['I']), 'I':klass('I',parent='java.lang.Object',interface=True),'Parent':klass('Parent',[field()])})
        self.assertEqual('Parent',a.field_key(('C','x','double[][]',False))['class']['owner'])
        self.assertNotIn('java.lang.Object',a.calls)

    def test_cycles_missing_target_duplicates_and_static_mismatch_reject(self):
        cases=[{'C':klass('C',parent='C')},{'C':klass('C')},{'C':klass('C',[field(),field()])},{'C':klass('C',[field(static=True)])}]
        for classes in cases:
            with self.assertRaises(Invalid):Fake(classes).field_key(('C','x','double[][]',False))

    def test_default_direct_mode_still_rejects_inherited_field(self):
        a=Fake({'C':klass('C',parent='P'),'P':klass('P',[field()])});a.allow_inherited_fields=False
        with self.assertRaises(Invalid):a.field('C','x')
        with self.assertRaises(Invalid):a.field_key(('C','x','double[][]',False))

    def test_completed_absent_diamond_is_not_a_cycle(self):
        a=Fake({'C':klass('C',parent='P',interfaces=['I','J']),'I':klass('I',interfaces=['K'],interface=True),
                'J':klass('J',interfaces=['K'],interface=True),'K':klass('K',interface=True),'P':klass('P',[field()])})
        self.assertEqual('P',a.field_key(('C','x','double[][]',False))['class']['owner'])
        self.assertEqual(1,a.calls.count('K'))

if __name__=='__main__':unittest.main()
