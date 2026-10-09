import copy
import unittest
import struct
from . import core_semantics as core
from . import synthetic_metadata as sm
from .method_authority_test import Fake,klass
from .legacy_wire import Invalid,Method
I=lambda n:struct.pack('>i',n)


def fixture(short=False,fingerprint=b'x'*16,key=None):
    method=Method('A','lambda$m$0',('byte[]' if short else 'byte[][]',),'void')
    strings=['A','lambda$m$0',method.parameters[0],'void',key or method.signature]
    data=I(0x47524d03)+I(1)+I(0)+I(1)+I(1)+I(2)+I(3)+I(0)*7+I(0x47525301)+I(1)+I(4)+fingerprint
    methods=[];synthetic=[];canonical=core.metadata(data,strings,{},methods,synthetic)
    return canonical,methods,synthetic


def authority(synthetic=True):
    cl=klass('A',[('lambda$m$0','([[B)V')]);cl['parsed']['methods'][0]['accessFlags']=0x100a if synthetic else 0xa
    return Fake({'A':cl})


class SyntheticKeys(unittest.TestCase):
    def test_only_key_text_changes_fingerprint_and_other_bytes_preserved(self):
        a,am,ak=fixture();b,bm,bk=fixture(True);evidence=[]
        value=sm.compare(a,b,ak,bk,am,bm,authority(),evidence)
        self.assertEqual(value[:ak[0]['start']],a[:ak[0]['start']])
        self.assertEqual(value[ak[0]['start']:],b[bk[0]['start']:])
        self.assertEqual('78'*16,evidence[0]['fingerprint']);self.assertEqual(('A','lambda$m$0','([[B)V'),evidence[0]['fullBMethod'])

    def test_fingerprint_mismatch_rejected_even_with_valid_array_change(self):
        a,am,ak=fixture();b,bm,bk=fixture(True,b'y'*16)
        with self.assertRaises(Invalid):sm.compare(a,b,ak,bk,am,bm,authority(),[])

    def test_non_synthetic_declaration_rejected(self):
        a,am,ak=fixture();b,bm,bk=fixture(True)
        with self.assertRaises(Invalid):sm.compare(a,b,ak,bk,am,bm,authority(False),[])

    def test_missing_classfile_declaration_rejected(self):
        a,am,ak=fixture();b,bm,bk=fixture(True)
        with self.assertRaises(Invalid):sm.compare(a,b,ak,bk,am,bm,Fake({}),[])

    def test_missing_or_ambiguous_metadata_method_rejected(self):
        a,am,ak=fixture();b,bm,bk=fixture(True)
        for left,right in [([],bm),(am,[]),(am+am,bm),(am,bm+bm)]:
            with self.assertRaises(Invalid):sm.compare(a,b,ak,bk,left,right,authority(),[])

    def test_arbitrary_array_like_string_not_treated_as_method_key(self):
        a,am,ak=fixture(key='payload[][]');b,bm,bk=fixture(True,key='payload[]')
        with self.assertRaises(Invalid):sm.compare(a,b,ak,bk,am,bm,authority(),[])

    def test_row_count_and_unchanged_key_fingerprint_stay_strict(self):
        a,am,ak=fixture();b,bm,bk=fixture()
        with self.assertRaises(Invalid):sm.compare(a,b,ak,[],am,bm,authority(),[])
        bad=copy.deepcopy(bk);bad[0]['fingerprint']=b'y'*16
        with self.assertRaises(Invalid):sm.compare(a,b,ak,bad,am,bm,authority(),[])
        evidence=[];self.assertEqual(a,sm.compare(a,b,ak,bk,am,bm,authority(),evidence));self.assertEqual([],evidence)

    def test_nonarray_method_rename_rejected(self):
        a,am,ak=fixture();b,bm,bk=fixture(True)
        bm[0]['method']=Method('A','lambda$n$0',('byte[]',),'void');bk[0]['key']=bm[0]['method'].signature
        with self.assertRaises(Invalid):sm.compare(a,b,ak,bk,am,bm,authority(),[])

if __name__=='__main__':unittest.main()
