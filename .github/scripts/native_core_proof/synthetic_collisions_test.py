import copy
import struct
import unittest
from unittest.mock import patch
from . import core_semantics as core
from . import synthetic_metadata as sm
from .method_authority_test import Fake,klass
from .legacy_wire import Method,Invalid
I=lambda x:struct.pack('>i',x)
M1=Method('A','<init>',('int','byte[]'),'void')
M2=Method('A','<init>',('int','byte[][]'),'void')

def fixture(methods,entries,tail=b''):
    strings=[]
    def sid(s):
        if s not in strings:strings.append(s)
        return I(strings.index(s))
    data=I(0x47524d03)+I(len(methods))
    for m in methods:
        data+=sid(m.owner)+sid(m.name)+I(len(m.parameters))+b''.join(sid(t) for t in m.parameters)+sid(m.result)
    data+=I(0)*7+I(0x47525301)+I(len(entries))
    for key,digest in sorted(entries):data+=sid(key)+digest
    data+=tail;ms=[];ss=[];canonical=core.metadata(data,strings,{},ms,ss)
    return canonical,ms,ss

def authority():
    a=Fake({'A':klass('A',[('<init>',M1.key[2]),('<init>',M2.key[2])])});a.allow_legacy_collisions=True
    for m in a.classes['A']['parsed']['methods']:m['accessFlags']=0x1000
    return a

def pair():return fixture([M1,M2],[(M1.signature,b'a'*16),(M2.signature,b'b'*16)]),fixture([M1],[(M1.signature,b'b'*16)])

def compare(a,b,auth=None,groups=None):
    ac,am,ak=a;bc,bm,bk=b
    return sm.compare(ac,bc,ak,bk,am,bm,authority() if auth is None else auth,[],collision_groups=[] if groups is None else groups)

class Collisions(unittest.TestCase):
    def test_constructor_projection_proves_exact_winner_and_preserves_evidence(self):
        a,b=pair();groups=[];actual=compare(a,b,groups=groups)
        # Initial method table is deliberately left for its separate verifier.
        self.assertEqual(a[0][:a[2][0]['start']-8],actual[:a[2][0]['start']-8])
        self.assertEqual(b[0][b[2][0]['start']-8:],actual[a[2][0]['start']-8:])
        self.assertEqual(1,groups[0]['recoveredIdentities']);self.assertEqual(1,groups[0]['retainedIndex'])
        self.assertEqual(['61'*16,'62'*16],[r['fingerprint'] for r in groups[0]['B']])
        self.assertFalse(groups[0]['newFingerprintRecomputed'])
    def test_default_strict_mode_still_rejects_count_difference(self):
        a,b=pair()
        with self.assertRaises(Invalid):sm.compare(a[0],b[0],a[2],b[2],a[1],b[1],authority(),[])
    def test_wrong_reference_winner_rejected_even_if_it_belongs_to_group(self):
        a,_=pair();b=fixture([M1],[(M1.signature,b'a'*16)])
        with self.assertRaises(Invalid):compare(a,b)
    def test_missing_source_synthetic_flag_or_exact_declaration_rejected(self):
        a,b=pair()
        for mode in ['flag','method','optin']:
            auth=authority()
            if mode=='flag':auth.classes['A']['parsed']['methods'][0]['accessFlags']=0
            elif mode=='method':auth.classes['A']['parsed']['methods'].pop()
            else:auth.allow_legacy_collisions=False
            with self.assertRaises(Invalid):compare(a,b,auth)
    def test_missing_third_source_overload_rejected(self):
        a,b=pair();auth=authority();row=copy.deepcopy(auth.classes['A']['parsed']['methods'][0]);row['descriptor']='(I[[[B)V';auth.classes['A']['parsed']['methods'].append(row)
        with self.assertRaises(Invalid):compare(a,b,auth)
    def test_nonconstructor_collision_rejected(self):
        m1=Method('A','m',M1.parameters,'void');m2=Method('A','m',M2.parameters,'void')
        a=fixture([m1,m2],[(m1.signature,b'a'*16),(m2.signature,b'b'*16)]);b=fixture([m1],[(m1.signature,b'b'*16)])
        with self.assertRaises(Invalid):compare(a,b)
    def test_extra_unrelated_row_or_missing_reference_key_rejected(self):
        a,b=pair()
        for changed in [fixture([M1,M2],[(M1.signature,b'a'*16),(M2.signature,b'b'*16),('payload[][]',b'c'*16)]),fixture([M1,M2],[(M2.signature,b'b'*16),('payload[][]',b'c'*16)])]:
            with self.assertRaises(Invalid):compare(changed,b)
    def test_corrupt_span_count_or_fingerprint_bytes_rejected(self):
        a,b=pair()
        for change in ['span','count','fingerprint']:
            raw,ms,ss=copy.deepcopy(a)
            if change=='span':ss[0]['end']+=1
            elif change=='count':start=ss[0]['start']-4;raw=raw[:start]+I(999)+raw[start+4:]
            else:ss[0]['fingerprint']=b'z'*16
            with self.assertRaises(Invalid):compare((raw,ms,ss),b)
    def test_other_fingerprints_and_suffix_remain_strict(self):
        a=fixture([M1,M2],[(M1.signature,b'a'*16),(M2.signature,b'b'*16),('Z',b'z'*16)],I(0x47524202)+b'A'*32)
        b=fixture([M1],[(M1.signature,b'b'*16),('Z',b'z'*16)],I(0x47524202)+b'B'*32)
        actual=compare(a,b);self.assertTrue(actual.endswith(b'A'*32));self.assertNotEqual(actual[-32:],b[0][-32:])
        bad=fixture([M1],[(M1.signature,b'b'*16),('Z',b'q'*16)],I(0x47524202)+b'B'*32)
        with self.assertRaises(Invalid):compare(a,bad)
    def test_collision_cannot_hide_missing_or_ambiguous_metadata_method(self):
        a,b=pair();raw,ms,ss=a
        for rows in [ms[:1],ms+ms]:
            with self.assertRaises(Invalid):compare((raw,rows,ss),b)

if __name__=='__main__':
    with patch('subprocess.Popen',side_effect=AssertionError('no child processes')):unittest.main()
