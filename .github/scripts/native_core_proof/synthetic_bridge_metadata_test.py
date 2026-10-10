import copy
import struct
import unittest
from . import core_semantics as core
from . import synthetic_metadata as sm
from .method_authority_test import Fake,klass
from .legacy_wire import Invalid,Method
I=lambda n:struct.pack('>i',n)
KEY=('A','interpolate','([[B)LLeaf;')


def authority(bridge_name='interpolate',bridge_desc='([[B)LBase;',flags=0x1041):
    cl=klass('A',[(KEY[1],KEY[2]),(bridge_name,bridge_desc)])
    cl['parsed']['methods'][1]['accessFlags']=flags
    return Fake({'A':cl})


def fixture(short=False):
    method=Method('A','interpolate',('byte[]' if short else 'byte[][]',),'Leaf')
    strings=['A','interpolate',method.parameters[0],'Leaf',method.signature]
    raw=I(0x47524d03)+I(1)+I(0)+I(1)+I(1)+I(2)+I(3)+I(0)*7+I(0x47525301)+I(1)+I(4)+b'x'*16
    methods=[];keys=[];body=core.metadata(raw,strings,{},methods,keys)
    return body,methods,keys


class SyntheticBridgeKeys(unittest.TestCase):
    def test_exact_return_colliding_bridge_records_full_descriptor(self):
        a=authority();p=sm.eligibility(a,KEY,a.direct(KEY),True)
        self.assertEqual(p['mode'],'RETURN_OMITTED_KEY_WITH_EXACT_SYNTHETIC_BRIDGE')
        self.assertEqual(p['bridge']['descriptor'],'([[B)LBase;')
        self.assertEqual(p['metadataDeclaration']['method']['descriptor'],KEY[2])
        self.assertEqual(p['bridge']['accessFlags'],0x1041)

    def test_old_default_remains_strict(self):
        a=authority()
        with self.assertRaises(Invalid):sm.eligibility(a,KEY,a.direct(KEY),False)
        x,xm,xk=fixture();y,ym,yk=fixture(True)
        with self.assertRaises(Invalid):sm.compare(x,y,xk,yk,xm,ym,a,[])

    def test_synthetic_and_bridge_flags_both_required(self):
        for flags in (1,0x41,0x1001):
            a=authority(flags=flags)
            with self.assertRaises(Invalid):sm.eligibility(a,KEY,a.direct(KEY),True)

    def test_same_exact_name_and_parameters_required(self):
        for name,desc in [('other','([[B)LBase;'),('interpolate','([B)LBase;'),('interpolate','([[I)LBase;'),('interpolate','(I[[B)LBase;')]:
            a=authority(name,desc)
            with self.assertRaises(Invalid):sm.eligibility(a,KEY,a.direct(KEY),True)

    def test_ambiguous_synthetic_siblings_rejected(self):
        a=authority();extra=copy.deepcopy(a.classes['A']['parsed']['methods'][1]);extra['descriptor']='([[B)LOther;'
        a.classes['A']['parsed']['methods'].append(extra)
        with self.assertRaises(Invalid):sm.eligibility(a,KEY,a.direct(KEY),True)

    def test_only_corrected_key_bytes_change_and_fingerprint_is_preserved(self):
        x,xm,xk=fixture();y,ym,yk=fixture(True);evidence=[]
        corrected=sm.compare(x,y,xk,yk,xm,ym,authority(),evidence,allow_return_collision=True)
        self.assertEqual(corrected[:xk[0]['start']],x[:xk[0]['start']])
        self.assertEqual(corrected[xk[0]['start']:],y[yk[0]['start']:])
        self.assertEqual(evidence[0]['fingerprint'],'78'*16)
        self.assertEqual(evidence[0]['syntheticEligibility']['bridge']['descriptor'],'([[B)LBase;')

    def test_bridge_never_waives_fingerprint_or_metadata_identity(self):
        x,xm,xk=fixture();y,ym,yk=fixture(True);bad=copy.deepcopy(yk);bad[0]['fingerprint']=b'z'*16
        for keys,methods in ((bad,ym),(yk,[])):
            with self.assertRaises(Invalid):sm.compare(x,y,xk,keys,xm,methods,authority(),[],allow_return_collision=True)

if __name__=='__main__':unittest.main()
