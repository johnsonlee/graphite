"""Scoped independent GR*03 decoder: resolve every SID, retain every other byte.
Source-only draft. No graph files are read on import. A failed check stops proof.
"""
from pathlib import Path
import hashlib, json, mmap, struct
from contextlib import ExitStack
from . import legacy_wire as wire
from . import callsite_index
from . import callsite_ordinals
from . import field_authority
from . import method_authority
from . import local_array_corrections
from . import raw_local_types
from . import parameter_array_corrections
from . import legacy_method_collisions
from . import synthetic_metadata
need = wire.need

class CanonicalReader(wire.Reader):
    def __init__(self, data, strings=(), remap=None):
        super().__init__(data, strings); self.events=[]; self.event_size=0; self.remap=remap or {}
    def take(self,n):
        value=super().take(n); self.events.append(bytes(value)); self.event_size+=len(value); return value
    def sid(self):
        index=struct.unpack('>i',wire.Reader.take(self,4))[0]
        need(0<=index<len(self.strings),'SID bounds')
        value=self.strings[index]; value.encode('utf-8',errors='strict')
        encoded=value.encode('utf-8'); self.events.append(struct.pack('>i',len(encoded))+encoded); self.event_size+=4+len(encoded)
        return value
    def ref(self):
        value=struct.unpack('>i',wire.Reader.take(self,4))[0]
        self.events.append(struct.pack('>i',self.remap.get(value,value))); self.event_size+=4; return value
    def canonical(self): return b''.join(self.events)

def sha(path):
    h=hashlib.sha256()
    with Path(path).open('rb') as f:
        for b in iter(lambda:f.read(1<<20),b''): h.update(b)
    return h.hexdigest()

def load_strings(export, original):
    receipt=json.loads((export/'receipt.json').read_text())
    need(receipt['inputSha256']==sha(original),'string export input binding')
    need(receipt['outputSha256']==sha(export/'strings.bin'),'string export output binding')
    need(receipt['helperSourceSha256']=='2957d2ddbcd32dd595e4496dfa12a597ef5dd88a07118a4e89fea611c9358835','exporter source pin')
    values,digest=wire.strings_export((export/'strings.bin').read_bytes())
    need(len(values)==receipt['entryCount'] and digest==receipt['semanticSha256'],'export complete semantic receipt')
    return values

def read_method(reader,role,spans=None,caller=None):
    start=reader.event_size;value=wire.method(reader)
    if spans is not None:spans.append({'role':role,'method':value,'caller':caller,'start':start,'end':reader.event_size})
    return value

def nodes(data,offsets,strings,typeindex,remap=None,method_spans=None):
    # This scoped copied parser retains every decoded record, including formerly
    # discarded constant/annotation/callsite payloads. See provenance receipt.
    r=CanonicalReader(data,strings,remap); o=wire.Reader(offsets)
    need(r.i()==0x47524e03 and o.i()==0x47524c03,'node/offset header')
    count,slots=r.count(5),o.count(8); need(len(offsets)==8+8*slots,'offset size')
    checker=wire.TypeIndexCheck(typeindex); seen=set()
    for _ in range(count):
        start=r.pos; r.events=[]; r.event_size=0; node=r.i()
        if method_spans is not None:method_spans.clear()
        need(0<=node<slots and node not in seen,'node ID'); seen.add(node)
        need(struct.unpack_from('>q',offsets,8+8*node)[0]==start+1,'node offset')
        tag=r.byte(); key=None
        if tag in (0,3):r.take(4)
        elif tag==1:r.sid()
        elif tag in (2,4):r.take(8)
        elif tag==5:r.boolean()
        elif tag==6:pass
        elif tag==7:
            r.sid();r.sid()
            for _ in range(r.count(1)):wire.any_value(r)
        elif tag==8:r.sid();r.sid();read_method(r,'definition',method_spans)
        elif tag==9:key=(r.sid(),r.sid(),r.sid(),r.boolean())
        elif tag==10:r.i();r.sid();read_method(r,'definition',method_spans)
        elif tag==11:
            read_method(r,'definition',method_spans)
            if r.boolean():r.sid()
        elif tag==12:
            caller=read_method(r,'caller',method_spans);read_method(r,'callee',method_spans,caller);r.i();r.ref()
            for _ in range(r.count()):r.ref()
        elif tag==13:
            r.sid();r.sid();r.sid();keys=set()
            for _ in range(r.count(5)):
                key0=r.sid();need(key0 not in keys,'annotation duplicate');keys.add(key0);wire.any_value(r)
        elif tag==14:
            r.sid();r.sid();wire.any_value(r);r.sid()
            if r.boolean():r.sid()
        elif tag==15:
            r.sid();r.sid();r.sid()
            if r.boolean():r.sid()
        else:raise wire.Invalid('unknown node tag '+str(tag))
        checker.consume(tag,node)
        yield node,tag,key,r.canonical()
    r.done();checker.done()
    for node in range(slots):
        if node not in seen:need(struct.unpack_from('>q',offsets,8+8*node)[0]==0,'absent node offset')

def metadata(data,strings,remap,method_spans=None,synthetic_spans=None):
    r=CanonicalReader(data,strings,remap);need(r.i()==0x47524d03,'metadata header')
    # Capture all method rows by full JVM signature, never legacy signature map.
    keys=set()
    for _ in range(r.count(16)):
        key=read_method(r,'definition',method_spans).key;need(key not in keys,'duplicate full method');keys.add(key)
        if method_spans is not None:method_spans[-1]['tableRow']=True
    def keyed(read,minimum=8):
        keys=set()
        for _ in range(r.count(minimum)):
            key=r.sid();need(key not in keys,'duplicate metadata key');keys.add(key);read()
    def strings():
        values=[r.sid() for _ in range(r.count())];need(len(set(values))==len(values),'set duplicates')
    keyed(strings);keyed(strings)
    def values():
        for _ in range(r.count(1)):wire.any_value(r)
    keyed(values);keyed(r.sid);keyed(lambda:keyed(r.i));keyed(lambda:keyed(lambda:keyed(lambda:wire.any_value(r))))
    for _ in range(r.count(36)):
        r.ref();read_method(r,'definition',method_spans);op=r.i();need(0<=op<6,'comparison op');r.ref()
        for _ in range(2):
            # Branch members are sets; canonicalize only their order, not values.
            count=r.count();start=len(r.events);values=[r.ref() for _ in range(count)]
            need(len(set(values))==len(values),'duplicate branch member')
            del r.events[start:];r.events.extend(struct.pack('>i',v) for v in sorted(remap.get(v,v) for v in values))
    sections=set()
    while r.pos<len(data):
        header=r.i();need(header not in sections,'optional duplicate');sections.add(header)
        if header in (0x47525801,0x47524202):r.take(32)
        elif header==0x47525301:
            keys=set()
            for _ in range(r.count(20)):
                start=r.event_size;key=r.sid();end=r.event_size
                need(key not in keys,'synthetic duplicate');keys.add(key);fingerprint=bytes(r.take(16))
                if synthetic_spans is not None:synthetic_spans.append({'key':key,'start':start,'end':end,'fingerprint':fingerprint})
        else:raise wire.Invalid('unknown optional section')
    r.done();return r.canonical()

def comparisons(data,remap):
    r=wire.Reader(data);need(r.i()==0x47524303,'comparisons header');out={}
    for _ in range(r.count(16)):
        a,b,op,c=r.i(),r.i(),r.i(),r.i();key=(remap.get(a,a),remap.get(b,b))
        need(key not in out and 0<=op<6,'comparison key/op');out[key]=(op,remap.get(c,c))
    r.done();return out

def overview(data,strings):
    r=CanonicalReader(data,strings);need(r.i()==0x47524f03,'class overview header');r.i()
    for _ in range(r.count(8)):r.sid();r.i()
    for _ in range(r.count(12)):r.sid();r.sid();r.i()
    r.done();return r.canonical()

def validate_field_remap(remap,actual_ids,reference_ids):
    need(all(type(k) is int and type(v) is int and k>=0 and v>=0 for k,v in remap.items()),'invalid mapped field ID')
    need(set(remap)<=actual_ids and set(remap.values())<=reference_ids,'mapping includes nonfield ID')
    need(set(remap)==set(remap.values()),'field permutation is not closed')

def compare_exact_nodes(actual, reference):
    """Complete ID/payload equality and per-tag order; cross-tag physical layout may differ."""
    pending={};actual_order={};reference_order={}
    for node,tag,key,canonical in actual:
        need(node not in pending,'duplicate actual node ID')
        pending[node]=(tag,canonical)
        actual_order.setdefault(tag,[]).append(node)
    total=len(pending);seen=set()
    for node,tag,key,canonical in reference:
        need(node not in seen,'duplicate reference node ID');seen.add(node)
        need(node in pending,'missing actual node ID '+str(node))
        expected_tag,expected_payload=pending.pop(node)
        need(expected_tag==tag,'node tag differs '+str(node))
        need(expected_payload==canonical,'node complete payload differs '+str(node))
        reference_order.setdefault(tag,[]).append(node)
    need(actual_order==reference_order,'per-tag ordered node IDs differ')
    need(not pending,'missing reference node IDs')
    need(len(seen)==total,'node count differs')
    return total

def validate_node_index(data, offsets, identities):
    """Every GRI03 row must point at its independently decoded per-file GRN03 record."""
    reader=wire.Reader(data);need(reader.i()==0x47524903,'node index header')
    count=reader.count(13);need(count==len(identities),'node index count');seen=set()
    for _ in range(count):
        node,tag,offset=reader.i(),reader.byte(),reader.q()
        need(node in identities and node not in seen,'node index missing/duplicate ID')
        need(tag==identities[node],'node index tag')
        need(offset>=8 and struct.unpack_from('>q',offsets,8+8*node)[0]==offset+1,'node index physical offset')
        seen.add(node)
    reader.done();need(seen==set(identities),'node index incomplete IDs')
    return {'status':'PASS','rows':count,'allUniqueIDsTagsAndPerFileOffsetsChecked':True}

def prove(actual,reference,actual_export,reference_export,out,allow_field_bijection=False,field_authority_spec=None,method_array_corrections=False,source_local_arrays=False,parameter_arrays=False,legacy_overload_collisions=False,synthetic_method_keys=False,inherited_fields=False,source_rule=None,raw_local_exports=None):
    need(raw_local_exports is None or (source_local_arrays and allow_field_bijection), 'raw Local authority requires complete corrected node inventory')
    need(not inherited_fields or synthetic_method_keys,'inherited field mode requires all preceding explicit corrections')
    need(not synthetic_method_keys or legacy_overload_collisions,'synthetic key mode requires all explicit overload/type modes')
    need(not legacy_overload_collisions or parameter_arrays,'legacy overload mode requires all explicit array correction modes')
    need(not parameter_arrays or (method_array_corrections and source_local_arrays),'Parameter correction requires explicit Local and method modes')
    need(not source_local_arrays or method_array_corrections,'Local correction mode requires parsed method comparison')
    need(not source_local_arrays or source_rule is not None,'Local correction requires explicit pinned writer source rule')
    if not method_array_corrections:
        return _prove(actual,reference,actual_export,reference_export,out,allow_field_bijection,field_authority_spec)
    need(field_authority_spec is not None and allow_field_bijection,'method corrections require explicit fresh classfile authority')
    out.mkdir(exist_ok=False)
    with method_authority.Authority(field_authority_spec,actual,reference,out,allow_legacy_collisions=legacy_overload_collisions,allow_inherited_class_methods=legacy_overload_collisions) as authority:
        local_rules=local_array_corrections.Authority(out,source_rule,field_authority_spec) if source_local_arrays else None
        parameter_rows=[] if parameter_arrays else None
        collisions=legacy_method_collisions.Corrections(out,source_rule,field_authority_spec) if legacy_overload_collisions else None
        synthetic=synthetic_metadata.Corrections(out,source_rule,field_authority_spec) if synthetic_method_keys else None
        raw_locals=raw_local_types.Authority(raw_local_exports,out) if raw_local_exports is not None else None
        completed=False
        try:
            result=_prove(actual,reference,actual_export,reference_export,out,allow_field_bijection,field_authority_spec,authority,local_rules,parameter_rows,collisions,synthetic,inherited_fields,raw_locals)
            completed=True
            return result
        finally:
            if raw_locals is not None:raw_locals.save()
            if local_rules is not None:local_rules.save()
            if parameter_rows is not None:
                authority.save()
                parameter_array_corrections.save(out,parameter_rows,completed)
            if collisions is not None:
                authority.save()
                collisions.complete=completed
                collisions.save()
            if synthetic is not None:
                authority.save()
                synthetic.complete=completed
                synthetic.save()

def _prove(actual,reference,actual_export,reference_export,out,allow_field_bijection=False,field_authority_spec=None,method_corrections=None,local_rules=None,parameter_rows=None,collisions=None,synthetic=None,inherited_fields=False,raw_locals=None):
    if method_corrections is None:out.mkdir(exist_ok=False)
    required=['forward.graph','forward.offsets','forward.properties','graph.labels','graph.labelprefix','graph.strings','graph.nodedata','graph.nodeoffsets','graph.typeindex','graph.metadata','graph.comparisons','graph.branchdefs','graph.callsite-ordinals','graph.classoverview','graph.resources']
    required.extend(['graph.nodeindex','graph.strings.identity','graph.callsite-string-index','graph.callsite-string-content.identity'])
    pins={str(root/name):sha(root/name) for root in (actual,reference) for name in required if (root/name).exists()}
    for name in required:need((actual/name).exists()==(reference/name).exists(),'file presence '+name)
    (out/'input-pins.json').write_text(json.dumps(pins,indent=2)+'\n')
    astrings=load_strings(actual_export,actual/'graph.strings');bstrings=load_strings(reference_export,reference/'graph.strings')
    with ExitStack() as stack:
        def mapped(root,name):
            f=stack.enter_context((root/name).open('rb'));return stack.enter_context(mmap.mmap(f.fileno(),0,access=mmap.ACCESS_READ))
        args=[(mapped(root,'graph.nodedata'),mapped(root,'graph.nodeoffsets'),strings,mapped(root,'graph.typeindex')) for root,strings in ((actual,astrings),(reference,bstrings))]
        fields=[];identities=[]
        for arg in args:
            f={};node_tags={}
            for node,tag,key,canonical in nodes(*arg):
                node_tags[node]=tag
                if tag==9:need(key not in f,'duplicate complete field key');f[key]=(node,canonical[4:])
            fields.append(f);identities.append(node_tags)
        af,bf=fields
        # Keep original strict mode available. New mode is explicitly a corrected
        # comparison, never a relabelled strict equivalence proof.
        correction_records=[];authority_receipt=None
        if field_authority_spec is None:
            need(af.keys()==bf.keys(),'complete field key mismatch')
            correspondence={k:k for k in af}
            remap={af[k][0]:bf[k][0] for k in af if af[k][0]!=bf[k][0]}
        else:
            need(allow_field_bijection,'classfile corrections only in fresh comparison')
            raw_differences={'B':[{'key':list(k),'nodeId':af[k][0],'payloadHex':af[k][1].hex()} for k in sorted(af.keys()-bf.keys())],
                             'C':[{'key':list(k),'nodeId':bf[k][0],'payloadHex':bf[k][1].hex()} for k in sorted(bf.keys()-af.keys())]}
            (out/'raw-field-key-differences.json').write_text(json.dumps(raw_differences,indent=2)+'\n')
            authority=field_authority.Authority(field_authority_spec,actual,reference,out,allow_inherited_fields=inherited_fields)
            try:correspondence,remap,correction_records=field_authority.match_fields(af,bf,authority)
            finally:authority.finish()
            pins.update(authority.pins)
            raw_classes={str(p):sha(p) for p in sorted((out/'classfiles').rglob('*.class'))}
            pins.update(raw_classes)
            authority_receipt={'status':'PASS_EXPLICIT_CLASSFILE_FIELD_CORRECTIONS','strictEquivalence':False,
                'graphId':field_authority_spec['graphId'],'exceptionCount':len(correction_records),
                'inheritedFieldLookupAllowed':inherited_fields,
                'inheritedFieldCorrectionCount':sum(r['classfileAuthority'].get('symbolicOwner',r['B']['key'][0])!=r['classfileAuthority']['class']['owner'] for r in correction_records),
                'exactSharedKeys':len(af)-len(correction_records),'fields':len(af),
                'rawDifferencesSha256':sha(out/'raw-field-key-differences.json'),'corrections':correction_records,
                'authoritySpec':field_authority_spec,'inputs':authority.pins,'rawClassPins':raw_classes,
                'sourceCommit':'cfcfb191e3859550e05322223459f1a9b283e622'}
            (out/'field-corrections.json').write_text(json.dumps(authority_receipt,indent=2)+'\n')
        corrected_keys={tuple(r['B']['key']) for r in correction_records}
        validate_field_remap(remap,{v[0] for v in af.values()},{v[0] for v in bf.values()})
        need(allow_field_bijection or not remap,'migration changed field IDs')
        node_indexes=[validate_node_index(mapped(root,'graph.nodeindex'),args[index][1],identities[index])
                      for index,root in enumerate((actual,reference))]
        index_proofs=[]
        for index,root in enumerate((actual,reference)):
            data,offsets,strings,_=args[index]
            rows=callsite_index.callsites(data,offsets,strings,identities[index])
            index_proofs.append(callsite_index.decode(mapped(root,'graph.callsite-string-index'),strings,rows,
                (root/'graph.strings.identity').read_bytes(),(root/'graph.callsite-string-content.identity').read_bytes(),
                (len(offsets)-8)//8))
        index_equivalence=callsite_index.compare(*index_proofs)
        ordinal_proofs=[]
        for index,root in enumerate((actual,reference)):
            data,offsets,strings,_=args[index]
            members=callsite_ordinals.member_identities(data,offsets,strings,identities[index])
            ordinal_proofs.append(callsite_ordinals.decode((root/'graph.callsite-ordinals').read_bytes(),
                callsite_ordinals.bind_metadata((root/'graph.metadata').read_bytes()),members))
        ordinal_equivalence=callsite_ordinals.compare(*ordinal_proofs)
        if method_corrections is not None:
            (out/'ordinal-equivalence-raw.json').write_text(json.dumps(ordinal_equivalence,indent=2)+'\n')
            if collisions is None:
                ordinal_equivalence=method_authority.compare_ordinals(*ordinal_proofs,method_corrections)
            else:
                ordinal_equivalence=legacy_method_collisions.compare_ordinals(*ordinal_proofs,method_corrections,collisions.ordinal_groups)
            method_corrections.save()
        (out/'ordinal-equivalence.json').write_text(json.dumps(ordinal_equivalence,indent=2)+'\n')
        need(ordinal_equivalence['status']=='PASS','complete CallSite ordinal/origin mismatch; all rows retained')
        if allow_field_bijection:
            total=0;actual_methods=[];reference_methods=[]
            for av,bv in __import__('itertools').zip_longest(nodes(*args[0],remap=remap,method_spans=actual_methods),nodes(*args[1],method_spans=reference_methods)):
                need(av is not None and bv is not None,'node count mismatch')
                aid,at,ak,ac=av;bid,bt,bk,bc=bv
                need(aid==bid and at==bt,'node identity/tag order mismatch')
                if raw_locals is not None and at==8:
                    need(len(actual_methods)==1,'one complete raw Local method identity')
                    raw_locals.observe(ac,actual_methods[0]['method'].key)
                if at==9:
                    reference_key=correspondence[ak]
                    if ak in corrected_keys:
                        need(ac[4:]==field_authority.canonical_field_payload(ak) and
                             bf[reference_key][1]==field_authority.canonical_field_payload(reference_key),
                             'unexpected corrected Field payload '+str(aid))
                    else:need(ac[4:]==bf[reference_key][1],'node complete payload mismatch '+str(aid))
                else:
                    if method_corrections is not None:
                        ac=method_authority.compare_spans(ac,bc,actual_methods,reference_methods,method_corrections,'node:'+str(aid))
                    if local_rules is not None and at==8:
                        need(len(actual_methods)==1,'one Local method identity')
                        ac=local_array_corrections.compare(ac,bc,actual_methods[0]['method'].key,local_rules.occurrences)
                    if parameter_rows is not None and at==10:
                        need(len(actual_methods)==len(reference_methods)==1,'one Parameter method identity')
                        ac=parameter_array_corrections.compare(ac,bc,actual_methods[0]['method'],reference_methods[0]['method'],method_corrections,parameter_rows)
                    need(ac==bc,'node complete payload mismatch '+str(aid))
                total+=1
        else:
            total=compare_exact_nodes(nodes(*args[0]),nodes(*args[1]))
        if raw_locals is not None:
            raw_locals.finish();pins.update(raw_locals.pins)

    actual_methods=[];reference_methods=[]
    actual_synthetic=[];reference_synthetic=[]
    am=metadata((actual/'graph.metadata').read_bytes(),astrings,remap,actual_methods,actual_synthetic)
    bm=metadata((reference/'graph.metadata').read_bytes(),bstrings,{},reference_methods,reference_synthetic)
    if synthetic is not None:
        am=synthetic_metadata.compare(am,bm,actual_synthetic,reference_synthetic,
            actual_methods,reference_methods,method_corrections,synthetic.occurrences,allow_return_collision=True,collision_groups=synthetic.collision_groups)
    if method_corrections is not None:
        if collisions is None:
            am=method_authority.compare_spans(am,bm,actual_methods,reference_methods,method_corrections,'metadata')
        else:
            ordinal_inputs=[((root/'graph.callsite-ordinals').read_bytes(),
                {node:(row['caller'],row['callee']) for node,row in proof['rows'].items()})
                for root,proof in zip((actual,reference),ordinal_proofs)]
            am=legacy_method_collisions.compare_metadata(am,bm,actual_methods,reference_methods,method_corrections,collisions.metadata_groups,
                ordinal_inputs=ordinal_inputs,binding_evidence=collisions.binding_evidence,return_evidence=collisions.return_groups)
    need(am==bm,'complete metadata SID/remapped-reference transcript mismatch')
    need(comparisons((actual/'graph.comparisons').read_bytes(),remap)==comparisons((reference/'graph.comparisons').read_bytes(),{}),'comparisons mismatch')
    need(overview((actual/'graph.classoverview').read_bytes(),astrings)==overview((reference/'graph.classoverview').read_bytes(),bstrings),'class overview mismatch')
    # Existing accepted real3 branch/local-definition/ordinal/resource sidecars
    # are unchanged under the field-only permutation. Fail rather than waive a
    # newly differing sidecar; any broader rewrite requires its own decoder.
    exact=['graph.branchdefs','graph.resources']
    for name in exact:
        if (actual/name).exists():need(sha(actual/name)==sha(reference/name),'sidecar differs '+name)
    if method_corrections is not None:
        method_corrections.finish();method_corrections.complete=True
        method_receipt=method_corrections.save()
        pins.update(method_corrections.pins);pins.update(method_receipt['rawClassPins'])
    if local_rules is not None:
        local_rules.complete=True;local_rules.save();pins.update(local_rules.pins)
    if collisions is not None:
        collisions.source.finish();pins.update(collisions.source.pins)
    if synthetic is not None:
        synthetic.source.verify();pins.update(synthetic.source.pins)
    need(all(sha(Path(p))==h for p,h in pins.items()),'input mutation')
    (out/'field-bijection.tsv').write_text(''.join(f'{a}\t{b}\n' for a,b in sorted(remap.items())))
    result={'actual':str(actual.resolve()),'reference':str(reference.resolve()),'mappingSha256':sha(out/'field-bijection.tsv'),'coreParserSha256':sha(Path(__file__)),'legacyParserSha256':sha(Path(__file__).with_name('legacy_wire.py')),'status':'PASS','role':'FULL_NODE_METADATA_SID_SEMANTICS_NOT_SERVER_PROOF','nodes':total,'fields':len(af),'movedFields':len(remap),'fieldOnlyBijectionAllowed':allow_field_bijection,'exactSidecars':exact,'allNonFieldNodeIdentitiesAndNonReferencePayloadsExact':True,'callSiteReceiverAndArgumentsMappedThroughProvenFieldBijection':True,'allMetadataRowsIncludingFullReturnDescriptorsChecked':True,'topology':'REQUIRES_SEPARATE_FULL_LABELED_EDGE_PROOF','inputs':pins,
            'nodeComparison':'ORIGINAL_ORDER_WITH_CLOSED_FIELD_PERMUTATION' if allow_field_bijection else 'EXACT_ID_TAG_COMPLETE_PAYLOAD_BY_ID',
            'perTagNodeIterationOrderPreserved':not allow_field_bijection,
            'legacyNodeIndexProofs':node_indexes,'callSiteIndexProof':index_equivalence,
            'callSiteIndexParserSha256':sha(Path(__file__).with_name('callsite_index.py')),
            'ordinalProofSha256':sha(out/'ordinal-equivalence.json'),
            'ordinalParserSha256':sha(Path(__file__).with_name('callsite_ordinals.py'))}
    if authority_receipt is not None:
        result.update(status='PASS_WITH_EXPLICIT_CLASSFILE_FIELD_CORRECTIONS',
            role='FULL_CORE_WITH_CLASSFILE_FIELD_CORRECTIONS_NOT_STRICT_EQUIVALENCE',
            strictEquivalence=False,fieldCorrectionCount=len(correction_records),
            fieldCorrectionProofSha256=sha(out/'field-corrections.json'),
            fieldAuthorityParserSha256=sha(Path(__file__).with_name('field_authority.py')))
    if method_corrections is not None:
        result.update(status='PASS_WITH_EXPLICIT_CLASSFILE_FIELD_AND_METHOD_CORRECTIONS',
            role='FULL_CORE_WITH_CLASSFILE_FIELD_AND_METHOD_CORRECTIONS_NOT_STRICT_EQUIVALENCE',
            allNonFieldNodeIdentitiesAndNonReferencePayloadsExact=False,
            allPayloadsOtherThanExplicitFieldTypesAndMethodDescriptorsExact=True,methodCorrectionCount=len(method_corrections.occurrences),
            uniqueCorrectedMethods=len(method_corrections.mapping),methodCorrectionProofSha256=sha(out/'method-corrections.json'),
            methodAuthorityParserSha256=sha(Path(__file__).with_name('method_authority.py')),
            methodClassfileParserSha256=sha(Path(__file__).with_name('method_classfile.py')),
            rawOrdinalProofSha256=sha(out/'ordinal-equivalence-raw.json'))
    if local_rules is not None:
        result.update(status='PASS_WITH_EXPLICIT_FIELD_METHOD_AND_LOCAL_ARRAY_CORRECTIONS',
            role='FULL_CORE_WITH_EXPLICIT_FIELD_METHOD_AND_LOCAL_ARRAY_CORRECTIONS_NOT_STRICT_EQUIVALENCE',
            allPayloadsOtherThanExplicitFieldTypesAndMethodDescriptorsExact=False,
            allPayloadsExceptExplicitFieldMethodAndLocalTypeCorrectionsExact=True,
            localArrayCorrectionCount=len(local_rules.occurrences),
            localArrayCorrectionProofSha256=sha(out/'local-array-corrections.json'),
            localArrayParserSha256=sha(Path(__file__).with_name('local_array_corrections.py')))
    if parameter_rows is not None:
        parameter_array_corrections.save(out,parameter_rows,True)
        result.update(status='PASS_WITH_EXPLICIT_FIELD_METHOD_LOCAL_AND_PARAMETER_ARRAY_CORRECTIONS',
            role='FULL_CORE_WITH_EXPLICIT_FIELD_METHOD_LOCAL_AND_PARAMETER_ARRAY_CORRECTIONS_NOT_STRICT_EQUIVALENCE',
            allPayloadsExceptExplicitFieldMethodAndLocalTypeCorrectionsExact=False,
            allPayloadsExceptExplicitFieldMethodLocalAndParameterTypeCorrectionsExact=True,
            parameterArrayCorrectionCount=len(parameter_rows),
            parameterArrayCorrectionProofSha256=sha(out/'parameter-array-corrections.json'),
            parameterArrayParserSha256=sha(Path(__file__).with_name('parameter_array_corrections.py')))
    if collisions is not None:
        collisions.complete=True;collisions.save()
        result.update(status='PASS_WITH_EXPLICIT_FIELD_METHOD_LOCAL_PARAMETER_AND_OVERLOAD_CORRECTIONS',
            role='FULL_CORE_WITH_EXPLICIT_FIELD_METHOD_LOCAL_PARAMETER_AND_OVERLOAD_CORRECTIONS_NOT_STRICT_EQUIVALENCE',
            allPayloadsExceptExplicitFieldMethodLocalAndParameterTypeCorrectionsExact=False,
            allPayloadsExceptExplicitTypeAndOverloadCorrectionsExact=True,
            legacyOrdinalGroupCount=len(collisions.ordinal_groups),
            legacyMetadataGroupCount=len(collisions.metadata_groups),
            legacyReturnOmittedGroupCount=len(collisions.return_groups),
            recoveredMetadataMethodCount=sum(g['recoveredMethods'] for g in collisions.return_groups),
            metadataSignatureCollisionParserSha256=sha(Path(__file__).with_name('metadata_signature_collisions.py')),
            legacyOrdinalBindingCount=len(collisions.binding_evidence),
            legacyCollisionProofSha256=sha(out/'legacy-method-collisions.json'),
            legacyCollisionParserSha256=sha(Path(__file__).with_name('legacy_method_collisions.py')))
    if synthetic is not None:
        synthetic.complete=True;synthetic.save()
        result.update(status='PASS_WITH_EXPLICIT_TYPE_OVERLOAD_AND_SYNTHETIC_KEY_CORRECTIONS',
            role='FULL_CORE_WITH_EXPLICIT_TYPE_OVERLOAD_AND_SYNTHETIC_KEY_CORRECTIONS_NOT_STRICT_EQUIVALENCE',
            allPayloadsExceptExplicitTypeAndOverloadCorrectionsExact=False,
            allPayloadsExceptExplicitTypeOverloadAndSyntheticKeysExact=not synthetic.collision_groups,
            allPayloadsExceptExplicitTypeOverloadSyntheticKeysAndConstructorCollisionsExact=True,
            syntheticConstructorCollisionGroupCount=len(synthetic.collision_groups),
            recoveredSyntheticIdentityCount=sum(g['recoveredIdentities'] for g in synthetic.collision_groups),
            syntheticCollisionParserSha256=sha(Path(__file__).with_name('synthetic_collisions.py')),
            recoveredSyntheticFingerprintRecomputed=False,
            syntheticMethodKeyCorrectionCount=len(synthetic.occurrences),
            syntheticMethodKeyProofSha256=sha(out/'synthetic-method-key-corrections.json'),
            syntheticMethodKeyParserSha256=sha(Path(__file__).with_name('synthetic_metadata.py')))
    if inherited_fields:
        result.update(status='PASS_WITH_EXPLICIT_TYPE_OVERLOAD_SYNTHETIC_AND_INHERITED_FIELD_CORRECTIONS',
            role='FULL_CORE_WITH_EXPLICIT_TYPE_OVERLOAD_SYNTHETIC_AND_INHERITED_FIELD_CORRECTIONS_NOT_STRICT_EQUIVALENCE',
            inheritedFieldLookupAllowed=True,inheritedFieldCorrectionCount=authority_receipt['inheritedFieldCorrectionCount'])
    (out/'receipt.json').write_text(json.dumps(result,indent=2)+'\n');return result

if __name__=='__main__':
    import argparse
    p=argparse.ArgumentParser();p.add_argument('actual',type=Path);p.add_argument('reference',type=Path);p.add_argument('actual_export',type=Path);p.add_argument('reference_export',type=Path);p.add_argument('output',type=Path);p.add_argument('--allow-field-bijection',action='store_true');a=p.parse_args()
    need(__debug__,'Python optimization forbidden');print(json.dumps(prove(a.actual,a.reference,a.actual_export,a.reference_export,a.output,a.allow_field_bijection)))
