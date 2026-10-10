"""Explicit synthetic-constructor key collapse; no arbitrary GRS row allowance.

The source-bound writer visits constructors by raw descriptor (and its fallback
by full rendered signature). Both orders must select the same last writer.
Fingerprints of surviving identities stay exact. Recovered, previously lost
identities are retained in evidence; their digests are not independently
recomputed by this structural projection proof.
"""
from collections import defaultdict
import struct
from . import method_classfile
from .legacy_wire import need,Method


def projected(m):
    def collapse(t):
        while t.endswith('[][]'):t=t[:-2]
        return t
    # Validate before constructing a projected signature from typed parameters.
    method_classfile.legacy_descriptor(m.key[2])
    return Method(m.owner,m.name,tuple(collapse(t) for t in m.parameters),collapse(m.result))


def framed(data,spans):
    need(spans,'collision requires nonempty GRS section')
    start=spans[0]['start']-8;need(start>=0,'GRS frame start')
    need(data[start:start+4]==struct.pack('>i',0x47525301),'GRS01 section header')
    need(struct.unpack_from('>i',data,start+4)[0]==len(spans),'GRS01 complete count')
    pos=start+8;keys=[]
    for row in spans:
        raw=row['key'].encode('utf-8');end=pos+4+len(raw)
        need(row['start']==pos and row['end']==end,'GRS complete contiguous key spans')
        need(data[pos:end]==struct.pack('>i',len(raw))+raw,'GRS key bytes')
        need(len(row['fingerprint'])==16 and data[end:end+16]==row['fingerprint'],'GRS fingerprint bytes')
        keys.append(row['key']);pos=end+16
    need(len(set(keys))==len(keys) and keys==sorted(keys),'unique sorted scoped ASCII GRS keys')
    need(all(k.isascii() for k in keys),'non-ASCII GRS ordering outside scope')
    return start,pos


def collapse(actual,reference,a_spans,b_spans,a_methods,b_methods,authority,evidence):
    need(getattr(authority,'allow_legacy_collisions',False),'explicit method collision mode required')
    a_start,a_end=framed(actual,a_spans);framed(reference,b_spans)
    lookups=[]
    for methods in (a_methods,b_methods):
        by_signature=defaultdict(list)
        for row in methods:
            if row.get('tableRow'):by_signature[row['method'].signature].append(row['method'])
        lookups.append(by_signature)
    groups=defaultdict(list)
    for index,row in enumerate(a_spans):
        candidates=lookups[0].get(row['key'],[])
        need(len(candidates)<=1,'ambiguous synthetic full metadata method')
        m=candidates[0] if candidates else None
        groups[projected(m).signature if m else row['key']].append((index,row,m))
    reference_keys=[row['key'] for row in b_spans]
    need(sorted(groups)==reference_keys,'complete projected synthetic key set differs')
    keep=set(range(len(a_spans)));changed=0
    for old,members in groups.items():
        if len(members)==1:continue
        changed+=1;target=next(row for row in b_spans if row['key']==old)
        previous=lookups[1].get(old,[])
        need(len(previous)==1,'collision reference full metadata constructor required')
        declared=[]
        for index,row,m in members:
            need(m is not None and m.name=='<init>' and m.result=='void','only exact constructor array collision allowed')
            need(projected(m).key==previous[0].key,'complete legacy constructor descriptor')
            proof=authority.direct(m.key)
            need(proof is not None and proof['method']['accessFlags']&0x1000,'exact ACC_SYNTHETIC constructor required')
            authority.pair(m.key,previous[0].key,'definition','metadata:synthetic-collision:'+str(index))
            declared.append({'index':index,'key':row['key'],'fullMethod':m.key,'fingerprint':row['fingerprint'].hex(),'declaration':proof})
        owner=members[0][2].owner
        need(all(m.owner==owner for _,_,m in members),'one constructor owner')
        keys={m.key for _,_,m in members}
        need(len(keys)==len(members),'distinct complete constructors required')
        # All ACC_SYNTHETIC constructors with this legacy descriptor must be
        # represented, not just the convenient surviving subset.
        klass=authority.klass(owner)
        source_keys={(owner,m['name'],m['descriptor']) for m in klass['parsed']['methods']
                     if m['name']=='<init>' and m['accessFlags']&0x1000
                     and method_classfile.legacy_descriptor(m['descriptor'])==previous[0].key[2]}
        need(keys==source_keys,'all source synthetic constructor overloads required')
        descriptor_order=sorted(members,key=lambda x:x[2].key[2])
        rendered_order=sorted(members,key=lambda x:x[2].signature)
        winner=descriptor_order[-1]
        need(winner[0]==rendered_order[-1][0],'producer constructor visitation orders disagree')
        need(winner[1]['fingerprint']==target['fingerprint'],'last writer fingerprint does not match reference')
        for index,_,_ in members:
            if index!=winner[0]:keep.remove(index)
        evidence.append({'C':dict(key=old,fingerprint=target['fingerprint'].hex(),fullMethod=previous[0].key),
                         'B':declared,'retainedIndex':winner[0],
                         'descriptorVisitOrder':[x[2].key for x in descriptor_order],
                         'renderedVisitOrder':[x[2].key for x in rendered_order],
                         'recoveredIdentities':len(members)-1,
                         'newFingerprintRecomputed':False})
    need(changed>0 and len(keep)==len(b_spans),'row-count difference requires exact constructor collisions')
    # Rewrite only the count and remove specifically proved extra rows. Preserve
    # winner key bytes for the existing strict key-correction verifier.
    chosen=sorted((a_spans[i] for i in keep),key=lambda row:projected(lookups[0][row['key']][0]).signature if lookups[0].get(row['key']) else row['key'])
    payload=bytearray(struct.pack('>ii',0x47525301,len(chosen)));spans=[]
    for row in chosen:
        chunk=actual[row['start']:row['end']+16];start=a_start+len(payload)
        payload.extend(chunk);spans.append(dict(row,start=start,end=start+len(chunk)-16))
    return actual[:a_start]+bytes(payload)+actual[a_end:],spans


class SourceRules:
    def __init__(self, source_rule, producer_spec):
        import json,hashlib
        from pathlib import Path
        from .source_bindings import load_rule
        from .metadata_signature_collisions import check_streamed_method_sources
        rule,self.pins=load_rule(source_rule,producer_spec);fingerprints=[]
        for arm in ('C','B'):
            info=rule['arms'][arm];manifest_path=Path(info['manifest'])
            need(hashlib.sha256(manifest_path.read_bytes()).hexdigest()==info['manifestSha256'],'producer manifest changed')
            manifest=json.loads(manifest_path.read_text());need(manifest['revision']==info['revision'],'producer revision')
            self.pins[str(manifest_path)]=info['manifestSha256'];root=Path(manifest['root'])
            relative={
                'identity':'frontend/jvm/sootup/src/main/kotlin/io/johnsonlee/graphite/sootup/SyntheticIdentity.kt',
                'asm':'frontend/jvm/sootup/src/main/kotlin/sootup/java/bytecode/frontend/conversion/GraphiteAsmClassSource.kt',
                'adapter':'frontend/jvm/sootup/src/main/kotlin/io/johnsonlee/graphite/sootup/SootUpAdapter.kt',
                'serializer':'frontend/jvm/webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/NodeSerializer.kt'}
            source={}
            for name,rel in relative.items():
                path=root/rel;raw=path.read_bytes();digest=hashlib.sha256(raw).hexdigest()
                need(manifest['files'].get(str(path),manifest['files'].get(rel))==digest,'source outside actual producer manifest')
                self.pins[str(path)]=digest;source[name]=raw.decode()
            fingerprints.append(source['identity'])
            need(source['identity'].count('return members.associate { it.key to it.fingerprint!! }')==1,'last-write collector source')
            check_streamed_method_sources(source['asm'])
            need('resolveMethodsOrEmpty(sootClass).sortedBy { it.signature.toString() }.forEach(action)' in source['adapter'],'fallback signature order')
            need('.sortedWith(compareBy({ (it.bodySource as? MethodNode)?.name ?: it.name }, { (it.bodySource as? MethodNode)?.desc ?: it.signature.toString() }))' in source['adapter'],'bytecode descriptor order')
            need('syntheticIdentities.addMethod(method, methodDescriptor.signature, syntheticMethod)' in source['adapter'],'exact identity key input')
            need('for ((member, fingerprint) in metadata.syntheticIdentities.toSortedMap())' in source['serializer'],'stored GRS key order')
        need(fingerprints[0]==fingerprints[1],'fingerprint collector changed between producers')

    def verify(self):
        from pathlib import Path
        import hashlib
        need(all(hashlib.sha256(Path(p).read_bytes()).hexdigest()==h for p,h in self.pins.items()),'synthetic collision source changed')
