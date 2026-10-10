"""Tiny independent ordinal framing/identity fixtures only; no child or real payload."""
import hashlib
import struct
import unittest
from . import callsite_ordinals as co
from .legacy_wire import Invalid
I=lambda x:struct.pack('>i',x)

def encode(pairs,origins=(),heads=None):
    entries=b''.join(I(n)+I(v)for n,v in [*pairs,*origins])
    if heads is None:heads=[n for n,_ in pairs[::256]]
    index=I(len(pairs))+I(len(origins))+b''.join(map(I,heads))
    index+=b''.join(hashlib.sha256(entries[i:i+2048]).digest()for i in range(0,len(entries),2048))
    digest=hashlib.sha256(index).digest()
    return I(0x47525104)+digest+index+entries,digest

def members(n=4):return {i:(('Owner','caller','()V'),('Target','callee','()Ljava/lang/String;'))for i in range(n)}

class OrdinalTests(unittest.TestCase):
    def test_all_ordinals_absence_origins_and_full_descriptors(self):
        raw,d=encode([(0,0),(2,-7)],[(2,1)]);proof=co.decode(raw,d,members())
        self.assertIsNone(proof['rows'][1]['ordinal']);self.assertEqual(-7,proof['rows'][2]['ordinal'])
        self.assertEqual(members()[1],proof['rows'][2]['originMember']);self.assertEqual('PASS',co.compare(proof,proof)['status'])
    def test_every_truncation_trailing_and_wrong_header(self):
        raw,d=encode([(0,1)])
        for cut in range(len(raw)):
            with self.subTest(cut=cut),self.assertRaises(Invalid):co.decode(raw[:cut],d,members())
        for bad in [raw+b'x',I(0x47525103)+raw[4:]]:
            with self.assertRaises(Invalid):co.decode(bad,d,members())
    def test_metadata_binding_and_every_entry_block(self):
        raw,d=encode([(i,i*3)for i in range(257)],[(256,0)])
        self.assertEqual(2,co.decode(raw,d,members(257))['blocks'])
        self.assertEqual(d,co.bind_metadata(b'prefix'+I(0x47524202)+d))
        for delta in [44,len(raw)-1]:
            bad=bytearray(raw);bad[delta]^=1
            with self.assertRaises(Invalid):co.decode(bad,d,members(257))
        with self.assertRaises(Invalid):co.decode(raw,bytes(32),members(257))
        with self.assertRaises(Invalid):co.bind_metadata(I(0x47524201)+d)
    def test_valid_digests_do_not_hide_wrong_heads(self):
        raw,d=encode([(0,0),(1,1)],heads=[1])
        with self.assertRaises(Invalid):co.decode(raw,d,members())
    def test_valid_digests_do_not_hide_duplicate_reordered_or_noncallsite_ids(self):
        for pairs in [[(0,0),(0,1)],[(1,1),(0,0)],[(9,1)]]:
            raw,d=encode(pairs)
            with self.subTest(pairs=pairs),self.assertRaises(Invalid):co.decode(raw,d,members())
    def test_invalid_origin_membership_duplicate_and_self(self):
        for origins in [[(2,9)],[(1,0)],[(2,2)],[(2,0),(2,1)]]:
            raw,d=encode([(0,0),(2,1)],origins)
            with self.subTest(origins=origins),self.assertRaises(Invalid):co.decode(raw,d,members())
    def test_semantic_changed_ordinal_origin_or_method_not_normalized(self):
        raw,d=encode([(0,0),(2,1)],[(2,0)]);base=co.decode(raw,d,members())
        changed=[]
        for pairs,origins in [([(0,1),(2,1)],[(2,0)]), ([(0,0),(2,1)],[(2,1)]), ([(0,0)],[])]:
            raw,d=encode(pairs,origins);changed.append(co.decode(raw,d,members()))
        altered=members();altered[0]=(('Other','caller','()V'),('Target','callee','()Ljava/lang/Object;'))
        raw,d=encode([(0,0),(2,1)],[(2,0)]);changed.append(co.decode(raw,d,altered))
        for value in changed:self.assertEqual('FAIL',co.compare(value,base)['status']);self.assertTrue(co.compare(value,base)['differences'])
    def test_canonical_maps_ignore_only_dictionary_insertion_order(self):
        raw,d=encode([(0,0),(2,1)]);a=co.decode(raw,d,members());b=co.decode(raw,d,dict(reversed(list(members().items()))))
        self.assertEqual('PASS',co.compare(a,b)['status'])
    def test_independent_member_join_keeps_full_caller_callee_identity(self):
        from .core_semantics_test import CallSiteReferenceTests
        from .core_semantics import nodes
        data,offsets,strings,index=CallSiteReferenceTests.callsite(-1,[])
        identities={node:tag for node,tag,_,_ in nodes(data,offsets,strings,index)}
        self.assertEqual({3:(('Owner','caller','()V'),('Owner','callee','()V'))},co.member_identities(data,offsets,strings,identities))

if __name__=='__main__':unittest.main()
