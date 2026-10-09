"""Independent fresh-writer GRCS02 decoder; no Graphite or Java imports.

Unicode trigram semantics are compared in full to accepted C, not regenerated
with Python's different Unicode lowercase tables. Fresh dictionaries must be unique.
"""
import hashlib
import struct
import zlib
from . import legacy_wire as wire
need=wire.need
MASK=(1<<64)-1
PROPERTIES=('caller_class','caller_name','callee_class','callee_name')

def dictionary_identity(strings):
    digest=hashlib.sha256(struct.pack('>i',len(strings)));previous=None
    for value in strings:
        key=value.encode('utf-16-be',errors='strict')
        need(previous is None or previous<key,'fresh dictionary must be unique Java-UTF16 sorted')
        previous=key;raw=value.encode('utf-8',errors='strict')
        digest.update(struct.pack('>i',len(raw)));digest.update(raw)
    return digest.digest()

def callsites(data,offsets,strings,identities):
    """Core caller already independently validated every complete record/offset/tag."""
    sid={s:i for i,s in enumerate(strings)}
    need(len(sid)==len(strings),'duplicate decoded SID')
    rows=[]
    for node,tag in identities.items():
        if tag!=12:continue
        offset=struct.unpack_from('>q',offsets,8+8*node)[0]-1
        r=wire.Reader(data,strings);r.pos=offset
        need(r.i()==node and r.byte()==12,'CallSite actual record identity')
        caller,callee=wire.method(r),wire.method(r)
        values=(caller.owner,caller.name,callee.owner,callee.name)
        rows.append((node,offset,tuple(sid[v]for v in values)))
    return rows

def content_identity(semantic,rows):
    digest=hashlib.sha256(semantic);digest.update(struct.pack('>i',len(rows)))
    for node,offset,sids in rows:
        digest.update(struct.pack('>iqiiii',node,offset,*sids))
    return digest.digest()

def signature(hashes):
    bits=0
    for value in hashes:
        mixed=(value^(value>>11)^((value<<7)&0xffffffff))&0xffffffff
        bits|=1<<(value&63);bits|=1<<(mixed&63)
    return bits

def ascii_trigrams(value):
    need(value.isascii(),'ASCII-only independent lowercase authority')
    value=value.lower()
    return sorted({(ord(value[i])*31+ord(value[i+1]))*31+ord(value[i+2]) for i in range(len(value)-2)})

class IndexReader(wire.Reader):
    def __init__(self,data):super().__init__(data);self.crc=0
    def integer(self):
        v=super().i();self.crc=zlib.crc32(struct.pack('<i',v),self.crc);return v
    def long(self):
        v=super().q();self.crc=zlib.crc32(struct.pack('<q',v),self.crc);return v
    def raw(self,n):
        v=self.take(n);self.crc=zlib.crc32(v,self.crc);return bytes(v)

def decode(data,strings,rows,semantic_file,content_file,node_capacity):
    semantic=dictionary_identity(strings);need(semantic_file==semantic,'complete string semantic identity')
    expected_content=content_identity(semantic,rows)
    need(content_file==expected_content,'CallSite content identity from actual physical offsets/SIDs')
    need(len({node for node,_,_ in rows})==len(rows),'duplicate CallSite ID')
    need(all(0<=node<node_capacity and offset>=8 and len(sids)==4 and all(0<=s<len(strings)for s in sids)for node,offset,sids in rows),'CallSite bounds')
    r=IndexReader(data)
    need(r.integer()==0x47524353 and r.integer()==2,'GRCS02 header')
    ns,nc=r.integer(),r.integer();need(ns==len(strings) and nc==len(rows),'exact string/CallSite counts')
    need(r.raw(32)==expected_content,'index content identity')
    unique=[r.integer()for _ in range(4)];need(all(0<=n<=ns for n in unique),'property unique count')
    np=r.integer();retained=r.long();need(np>0,'trigram posting count')
    expected_bytes=76+sum(8*n+4*nc for n in unique)+8*ns+8*np+8
    need(expected_bytes==len(data),'complete index framing/EOF')
    need(retained==256+14*16+16*nc+8*sum(unique)+8*ns+8*np,'exact retained-size header')
    property_values=[];used=set()
    for column,n in enumerate(unique):
        ids=[r.integer()for _ in range(n)];ends=[r.integer()for _ in range(n)];posts=[r.integer()for _ in range(nc)]
        need(all(0<=s<ns for s in ids) and all(a<b for a,b in zip(ids,ids[1:])),'unique sorted property SIDs')
        need((ends[-1]if ends else 0)==nc and all(a<b for a,b in zip([0]+ends,ends)),'strict posting ends')
        expected={}
        for node,_,sids in rows:expected.setdefault(sids[column],[]).append(node)
        need(ids==sorted(expected),'complete property membership')
        start=0;normalized=[]
        for sid,end in zip(ids,ends):
            need(posts[start:end]==expected[sid],'complete ordered property posting IDs/multiplicity')
            normalized.append((strings[sid],tuple(posts[start:end])));start=end;used.add(sid)
        property_values.append(tuple(normalized))
    signatures=[r.long()&MASK for _ in range(ns)];by_sid={s:[] for s in used};previous=None
    for _ in range(np):
        packed=r.long();need(previous is None or previous<packed,'unique signed sorted trigram/SID postings');previous=packed
        sid=packed&0xffffffff;trigram=(packed>>32)&0xffffffff
        need(sid in used,'trigram posting references non-CallSite SID');by_sid[sid].append(trigram)
    checksum=wire.Reader.q(r);r.done();need(checksum==r.crc,'full numeric-LE CRC32')
    normalized=[];ascii_count=unicode_count=0
    for sid,value in enumerate(strings):
        if sid not in used:need(signatures[sid]==0,'unused SID signature must be zero');continue
        hashes=sorted(by_sid[sid]);need(signatures[sid]==signature(hashes),'signature agrees with complete posting hashes')
        if value.isascii():need(hashes==ascii_trigrams(value),'independent ASCII trigram set');ascii_count+=1
        else:unicode_count+=1
        normalized.append((value,signatures[sid],tuple(hashes)))
    return {'properties':tuple(property_values),'trigrams':tuple(normalized),'strings':ns,'callSites':nc,
            'asciiRecomputed':ascii_count,'unicodeAcceptedStructure':unicode_count,'trigramPostings':np,
            'semanticIdentity':semantic.hex(),'contentIdentity':expected_content.hex(),'crc32':checksum}

def compare(actual,reference):
    need(actual['callSites']==reference['callSites'],'cross-arm CallSite count')
    need(actual['properties']==reference['properties'],'complete four-property semantics/order differs')
    need(actual['trigrams']==reference['trigrams'],'complete decoded-string signature/trigram semantics differs')
    return {'status':'PASS','scope':'FULL_CALLSITE_INDEX_SEMANTICS_AND_PER_FILE_IDENTITIES',
            'callSites':actual['callSites'],'properties':4,'asciiStringsRecomputed':actual['asciiRecomputed'],
            'unicodeStringsComparedToAccepted':actual['unicodeAcceptedStructure'],
            'unicodeAuthority':'Exact same decoded strings, signatures and all trigram hash memberships as accepted C; no Python Unicode case conversion',
            'dictionaryContract':'Fresh approved exports only: unique strict UTF8, Java-UTF16 sorted; duplicate decoded SIDs rejected',
            'actual':{k:v for k,v in actual.items()if k not in ('properties','trigrams')},
            'reference':{k:v for k,v in reference.items()if k not in ('properties','trigrams')}}
