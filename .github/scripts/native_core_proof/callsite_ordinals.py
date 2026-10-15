"""Complete independent GRQ04/GRB02 proof. No lazy-block or raw-byte waiver."""
import hashlib
import struct
from . import legacy_wire as wire
need=wire.need
BLOCK_ENTRIES=256
BLOCK_BYTES=2048

def member_identities(data,offsets,strings,identities):
    out={}
    for node,tag in identities.items():
        if tag!=12:continue
        offset=struct.unpack_from('>q',offsets,8+8*node)[0]-1
        r=wire.Reader(data,strings);r.pos=offset
        need(r.i()==node and r.byte()==12,'ordinal CallSite node ID/tag')
        caller,callee=wire.method(r),wire.method(r)
        # Return-inclusive JVM descriptors are part of each identity, not legacy signature text.
        out[node]=(caller.key,callee.key)
    return out

def decode(raw,binding,members):
    r=wire.Reader(raw)
    need(r.i()==0x47525104,'GRQ04 header')
    copied=bytes(r.take(32));need(len(binding)==32 and copied==binding,'copied GRB02 binding')
    count,origins=r.i(),r.i()
    need(0<=origins<=count<=len(members),'ordinal/origin counts')
    heads_count=(count+255)//256;entry_bytes=8*(count+origins);blocks=(entry_bytes+2047)//2048
    entries_at=44+4*heads_count+32*blocks
    need(entries_at+entry_bytes==len(raw),'complete ordinal length/EOF')
    heads=[r.i()for _ in range(heads_count)];digests=[bytes(r.take(32))for _ in range(blocks)]
    need(hashlib.sha256(raw[36:entries_at]).digest()==binding,'metadata-bound complete ordinal index SHA')
    entries=raw[entries_at:]
    for block,digest in enumerate(digests):
        need(hashlib.sha256(entries[block*2048:min((block+1)*2048,entry_bytes)]).digest()==digest,'ordinal entries block SHA')
    ordinals={};previous=-1
    for _ in range(count):
        node,value=r.i(),r.i()
        need(node in members and node>previous,'ordinal unique ascending actual CallSite IDs')
        # Preserve signed stored ordinals exactly; do not infer or repair their values.
        previous=node;ordinals[node]=value
    need(heads==list(ordinals)[::256],'every ordinal block head binds its first actual ID')
    derivations={};previous=-1
    for _ in range(origins):
        node,origin=r.i(),r.i()
        need(node in ordinals and node>previous,'origin unique ascending ordinal-member IDs')
        need(origin in members and origin!=node,'origin resolves another actual CallSite')
        previous=node;derivations[node]=origin
    r.done()
    # Include ABSENT explicitly for every actual CallSite, not only stored ordinal rows.
    canonical={node:{'caller':member[0],'callee':member[1],
                     'ordinal':ordinals.get(node),'origin':derivations.get(node),
                     'originMember':members[derivations[node]]if node in derivations else None}
               for node,member in members.items()}
    return {'rows':canonical,'ordinalEntries':count,'originEntries':origins,'blocks':blocks,'binding':binding.hex(),
            'sidecarSha256':hashlib.sha256(raw).hexdigest()}

def bind_metadata(raw):
    need(len(raw)>=36 and struct.unpack_from('>i',raw,len(raw)-36)[0]==0x47524202,'final GRB02 metadata binding')
    return bytes(raw[-32:])

def compare(actual,reference):
    # The caller persists every difference before raising; no sampling/truncation.
    differences=[]
    for node in sorted(set(actual['rows'])|set(reference['rows'])):
        a,b=actual['rows'].get(node),reference['rows'].get(node)
        if a!=b:differences.append({'nodeId':node,'actual':a,'reference':b})
    return {'status':'FAIL'if differences else 'PASS','scope':'COMPLETE_CALLSITE_ID_FULL_MEMBER_ORDINAL_ORIGIN_EQUALITY',
            'actual':{k:v for k,v in actual.items()if k!='rows'},
            'reference':{k:v for k,v in reference.items()if k!='rows'},
            'callSites':len(actual['rows']),'differences':differences,
            'nodeMapping':'Exact CallSite IDs only; independently proven Field mapping cannot rename a CallSite',
            'physicalOrderAllowance':'None within sorted sidecar entries; underlying nodedata offsets/order validated independently'}
