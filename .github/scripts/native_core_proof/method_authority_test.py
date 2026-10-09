"""No-child tests of exact parsed-method exception boundaries and raw origins."""
import copy,json,struct,tempfile,unittest
from pathlib import Path
from unittest.mock import patch
from . import method_authority as m
from . import method_classfile as cf
from . import core_semantics as core
from . import legacy_wire as wire
from .legacy_wire import Invalid
I=lambda x:struct.pack('>i',x)
class Fake(m.Authority):
    def __init__(self,classes,out=None):
        self.classes=classes;self.proofs={};self.occurrences=[];self.mapping={};self.reverse={};self.pins={};self.cache={};self.zip=None;self.complete=False;self.out=out
    def klass(self,owner):return self.classes.get(owner)
def klass(owner,methods):return {'parsed':{'owner':owner,'methods':[{'name':n,'descriptor':d,'accessFlags':1,'signature':None,'rawMethodInfoHex':'00'} for n,d in methods]},'evidence':{'owner':owner},'raw':b'fake-synthetic'}
def ref(owner,name,desc):return {'owner':owner,'name':name,'descriptor':desc,'kind':'Methodref','constantPoolEvidence':[]}
A=('A','m','([[I)[[B');B=('A','m','([I)[B')
class Methods(unittest.TestCase):
    def test_only_full_descriptor_dimensions_change(self):
        a=Fake({'A':klass('A',[('m',A[2])])});self.assertTrue(a.pair(A,B,'definition','test'));self.assertEqual(len(a.proofs),1)
        for wrong in [('X','m',B[2]),('A','n',B[2]),('A','m','([J)[B'),('A','m','()[B'),('A','m','([I)V')]:
            with self.assertRaises(Invalid):a.pair(A,wrong,'definition','bad')
    def test_return_dimensions_retained_and_malformed_rejected(self):
        self.assertEqual(cf.legacy_descriptor('(J[[Ljava/lang/String;)[[[I'),'(J[Ljava/lang/String;)[I')
        for bad in ('(I','([V)V','()Vx','(L;)V'):
            with self.assertRaises(Invalid):cf.legacy_descriptor(bad)
    def test_direct_projection_collision_rejects_even_exact_exists(self):
        a=Fake({'A':klass('A',[('m',A[2]),('m',B[2])])})
        with self.assertRaises(Invalid):a.pair(A,B,'caller','x')
    def test_missing_definition_never_uses_cp(self):
        a=Fake({'Caller':klass('Caller',[('run','()V')])})
        for role in ('caller','definition'):
            with self.assertRaises(Invalid):a.pair(A,B,role,'x',('Caller','run','()V'))
    def test_callee_cp_requires_exact_direct_caller_and_unique_projection(self):
        a=Fake({'Caller':klass('Caller',[('run','()V')])})
        with patch.object(m,'references',return_value=[ref(*A)]):
            self.assertTrue(a.pair(A,B,'callee','x',('Caller','run','()V')))
            with self.assertRaises(Invalid):a.pair(A,B,'callee','y',('Caller','absent','()V'))
        self.assertIn('NOT_RESOLUTION_OR_INVOCATION_SELECTION',next(iter(a.proofs.values()))['mode'])
        with patch.object(m,'references',return_value=[ref(*A),ref(*B)]):
            with self.assertRaises(Invalid):a.pair(A,B,'callee','z',('Caller','run','()V'))
    def test_graphwide_full_method_projection_collision_rejects(self):
        a=Fake({'A':klass('A',[('m',A[2])])});a.pair(A,B,'caller','first')
        a.classes['A']=klass('A',[('m','([[[I)[[B')])
        with self.assertRaises(Invalid):a.pair(('A','m','([[[I)[[B'),B,'caller','second')
    def method_bytes(self,result,prefix='same'):
        strings=[prefix,'A','m','int[][]' if result=='byte[][]' else 'int[]',result]
        r=core.CanonicalReader(I(0)+I(1)+I(2)+I(1)+I(3)+I(4),strings);r.sid();spans=[];core.read_method(r,'definition',spans);return r.canonical(),spans
    def test_span_replacement_preserves_nonmethod_text(self):
        ac,am=self.method_bytes('byte[][]');bc,bm=self.method_bytes('byte[]');a=Fake({'A':klass('A',[('m',A[2])])})
        self.assertEqual(m.compare_spans(ac,bc,am,bm,a,'x'),bc)
        wrong,wm=self.method_bytes('byte[][]','different');self.assertNotEqual(m.compare_spans(wrong,bc,wm,bm,a,'y'),bc)
    def test_multiple_different_length_spans_leave_adjacent_array_strings_exact(self):
        def parse(short):
            strings=['int[][]','A','m','int[]' if short else 'int[][]','byte[]' if short else 'byte[][]','n','long[]' if short else 'long[][][]','double[]' if short else 'double[][]']
            data=I(0)+I(1)+I(2)+I(1)+I(3)+I(4)+I(1)+I(5)+I(1)+I(6)+I(7)+I(0)
            r=core.CanonicalReader(data,strings);r.sid();spans=[];caller=core.read_method(r,'caller',spans);core.read_method(r,'callee',spans,caller);r.sid();r.done();return r.canonical(),spans
        a,am=parse(False);b,bm=parse(True);auth=Fake({'A':klass('A',[('m',A[2]),('n','([[[J)[[D')])})
        changed=m.compare_spans(a,b,am,bm,auth,'two')
        self.assertEqual(changed,b);self.assertEqual(changed.count(b'int[][]'),2);self.assertEqual(len(auth.occurrences),2)
        self.assertNotEqual(am[1]['start'],bm[1]['start'])

    def test_span_roles_counts_and_method_name_not_waived(self):
        ac,am=self.method_bytes('byte[][]');bc,bm=self.method_bytes('byte[]');a=Fake({'A':klass('A',[('m',A[2])])})
        with self.assertRaises(Invalid):m.compare_spans(ac,bc,am,[],a,'x')
        bm[0]['role']='callee'
        with self.assertRaises(Invalid):m.compare_spans(ac,bc,am,bm,a,'x')
    def ordinal(self,method):
        return {'rows':{7:{'caller':method,'callee':('C','n','()V'),'ordinal':3,'origin':None,'originMember':None}},'ordinalEntries':1,'originEntries':0,'blocks':1,'binding':'b','sidecarSha256':'s'}
    def test_ordinals_correction_leaves_original_rows_unchanged(self):
        a,b=self.ordinal(A),self.ordinal(B);before=copy.deepcopy(a);auth=Fake({'A':klass('A',[('m',A[2])])})
        result=m.compare_ordinals(a,b,auth);self.assertEqual(result['status'],'PASS');self.assertEqual(a,before);self.assertFalse(result['strictEquivalence'])
    def test_ordinal_origin_and_origin_member_stay_strict(self):
        for key,value in [('ordinal',4),('origin',2),('originMember',(A,('C','n','()V')))]:
            a,b=self.ordinal(A),self.ordinal(B);a['rows'][7][key]=value
            with self.assertRaises(Invalid):m.compare_ordinals(a,b,Fake({'A':klass('A',[('m',A[2])])}))
        a,b=self.ordinal(A),self.ordinal(B);b['rows'][8]=b['rows'].pop(7)
        with self.assertRaises(Invalid):m.compare_ordinals(a,b,Fake({}))
    def test_metadata_definition_span_only_not_arbitrary_text(self):
        strings=['A','m','int[][]','byte[][]'];other=['A','m','int[]','byte[]']
        data=I(0x47524d03)+I(1)+I(0)+I(1)+I(1)+I(2)+I(3)+I(0)*7
        am=[];bm=[];a=core.metadata(data,strings,{},am);b=core.metadata(data,other,{},bm)
        self.assertEqual(m.compare_spans(a,b,am,bm,Fake({'A':klass('A',[('m',A[2])])}),'metadata'),b)
    def test_evidence_survives_later_exception_with_pending_status(self):
        with tempfile.TemporaryDirectory() as td:
            a=Fake({'A':klass('A',[('m',A[2])])},Path(td))
            with self.assertRaisesRegex(Invalid,'later strict'):
                with a:a.pair(A,B,'definition','x');raise Invalid('later strict')
            result=json.loads((Path(td)/'method-corrections.json').read_text());self.assertEqual(result['exceptionCount'],1);self.assertEqual(result['status'],'PARTIAL_METHOD_ARRAY_CORRECTION_EVIDENCE_NOT_CORE_PASS')
if __name__=='__main__':unittest.main()
