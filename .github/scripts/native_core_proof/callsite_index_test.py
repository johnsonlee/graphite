"""Synthetic only. No real fixtures, Graphite runtime or subprocesses."""
import hashlib
import struct
import unittest
import zlib
from unittest.mock import patch
from . import callsite_index as ci
from .legacy_wire import Invalid
I=lambda v:struct.pack('>i',v)
Q=lambda v:struct.pack('>q',v if v<(1<<63)else v-(1<<64))

def fixture(strings=('Abc','Owner','méthod'),trigram_override=None,ascii_override=None):
    strings=list(strings);lookup={s:i for i,s in enumerate(strings)}
    rows=[(3,8,(lookup['Owner'],lookup['Abc'],lookup['Owner'],lookup['méthod'])),
          (7,63,(lookup['Owner'],lookup['méthod'],lookup['Owner'],lookup['Abc']))]
    semantic=hashlib.sha256(I(len(strings))+b''.join(I(len(s.encode()))+s.encode()for s in strings)).digest()
    content=hashlib.sha256(semantic+I(2)+b''.join(I(n)+Q(o)+b''.join(map(I,ss))for n,o,ss in rows)).digest()
    groups=[];ends=[];posts=[];used=set()
    for column in range(4):
        group={}
        for node,_,ids in rows:group.setdefault(ids[column],[]).append(node)
        ids=sorted(group);groups.append(ids);e=[];p=[]
        for sid in ids:p+=group[sid];e.append(len(p));used.add(sid)
        ends.append(e);posts.append(p)
    # Literal expected lowercase characters include a non-ASCII fixture without using decoder conversion.
    lowers={'Abc':'abc','Owner':'owner','méthod':'méthod'};hashes={}
    for sid in used:
        text=lowers[strings[sid]];hashes[sid]=sorted({ord(text[i])*961+ord(text[i+1])*31+ord(text[i+2])for i in range(len(text)-2)})
    if trigram_override is not None:hashes[lookup['méthod']]=trigram_override
    if ascii_override is not None:hashes[lookup['Abc']]=ascii_override
    signatures=[]
    for sid in range(len(strings)):
        value=0
        for h in hashes.get(sid,[]):value|=1<<(h%64);value|=1<<((h^(h>>11)^(h<<7))%64)
        signatures.append(value)
    packed=sorted((h<<32)|sid for sid,hs in hashes.items()for h in hs)
    typed=[('i',0x47524353),('i',2),('i',len(strings)),('i',2),('b',content)]
    typed += [('i',len(x))for x in groups]+[('i',len(packed)),('q',256+14*16+32+8*sum(map(len,groups))+8*len(strings)+8*len(packed))]
    for group,end,post in zip(groups,ends,posts):typed += [('i',x)for x in group+end+post]
    typed += [('q',x)for x in signatures+packed]
    return {'strings':strings,'rows':rows,'semantic':semantic,'content':content,'typed':typed}

def encode(typed):
    out=bytearray();checksum=0
    for kind,value in typed:
        if kind=='b':raw=value;check=value
        else:
            if kind=='q' and value>=1<<63:value-=1<<64
            raw=struct.pack('>'+kind,value);check=struct.pack('<'+kind,value)
        out+=raw;checksum=zlib.crc32(check,checksum)
    return bytes(out)+Q(checksum)

def decode(f,data=None):return ci.decode(encode(f['typed'])if data is None else data,f['strings'],f['rows'],f['semantic'],f['content'],8)

class Tests(unittest.TestCase):
    def test_complete_sid_remap_and_unused_dictionary_entries(self):
        a=decode(fixture());b=decode(fixture(('', 'Abc','AddedType','Owner','méthod','💡')))
        self.assertEqual('PASS',ci.compare(a,b)['status']);self.assertEqual(1,a['unicodeAcceptedStructure'])
    def test_every_truncated_prefix_and_trailing_rejected(self):
        f=fixture();raw=encode(f['typed'])
        for n in range(len(raw)):
            with self.subTest(n=n),self.assertRaises(Invalid):decode(f,raw[:n])
        with self.assertRaises(Invalid):decode(f,raw+b'\0')
    def test_crc_corruption_rejected(self):
        f=fixture();raw=bytearray(encode(f['typed']));raw[-1]^=1
        with self.assertRaises(Invalid):decode(f,raw)
    def test_repaired_crc_cannot_hide_wrong_property_postings(self):
        f=fixture();# header11 entries; first group SID,end,node3,node7
        f['typed'][13]=('i',7);f['typed'][14]=('i',3)
        with self.assertRaises(Invalid):decode(f)
    def test_content_identity_binds_actual_offsets_and_sids(self):
        f=fixture();n,o,ss=f['rows'][0];f['rows'][0]=(n,o+1,ss)
        with self.assertRaises(Invalid):decode(f)
    def test_dictionary_identity_and_duplicate_sid_rejected(self):
        f=fixture();f['semantic']=bytes(32)
        with self.assertRaises(Invalid):decode(f)
        with self.assertRaises(Invalid):ci.dictionary_identity(['same','same'])
    def test_inconsistent_signature_rejected_even_with_valid_crc(self):
        f=fixture()
        # Find signatures immediately after four full property CSR sections.
        at=11+sum(2*len({row[2][c]for row in f['rows']})+2 for c in range(4))
        k,v=f['typed'][at];self.assertEqual('q',k);f['typed'][at]=(k,v^1)
        with self.assertRaises(Invalid):decode(f)
    def test_unicode_structure_difference_fails_cross_arm(self):
        a=decode(fixture());b=decode(fixture(trigram_override=[12345]))
        with self.assertRaises(Invalid):ci.compare(a,b)
    def test_actual_property_value_difference_rejected(self):
        f=fixture();node,offset,sids=f['rows'][0];f['rows'][0]=(node,offset,(0,*sids[1:]))
        with self.assertRaises(Invalid):decode(f)
    def test_header_counts_and_retained_memory_rejected(self):
        for offset,value in [(0,0),(1,1),(2,100),(3,1),(5,9999),(9,-1),(10,1)]:
            f=fixture();kind,_=f['typed'][offset];f['typed'][offset]=(kind,value)
            with self.subTest(offset=offset),self.assertRaises((Invalid,struct.error)):decode(f)
    def test_ascii_semantics_cannot_be_accepted_by_crc(self):
        with self.assertRaises(Invalid):decode(fixture(ascii_override=[12345]))
    def test_unused_signature_cannot_survive_valid_crc(self):
        f=fixture(('', 'Abc','Owner','méthod'))
        at=11+sum(2*len({row[2][c]for row in f['rows']})+2 for c in range(4))
        self.assertEqual(('q',0),f['typed'][at]);f['typed'][at]=('q',1)
        with self.assertRaises(Invalid):decode(f)
    def test_complete_callsite_extraction_uses_checked_offsets_and_four_fields(self):
        from .core_semantics_test import CallSiteReferenceTests
        from .core_semantics import nodes
        data,offsets,strings,index=CallSiteReferenceTests.callsite(-1,[1,2])
        ids={node:tag for node,tag,_,_ in nodes(data,offsets,strings,index)}
        self.assertEqual([(3,8,(0,2,0,1))],ci.callsites(data,offsets,strings,ids))
        with self.assertRaises(Invalid):ci.callsites(data,offsets,strings+['Owner'],ids)

if __name__=='__main__':
    with patch('subprocess.Popen',side_effect=AssertionError('no children')):unittest.main()
