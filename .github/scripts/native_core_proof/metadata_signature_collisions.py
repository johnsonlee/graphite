"""Exact source-bound recovery of methods lost by return-omitted legacy keys.

Only the initial method table is reshaped. Every source overload in a changed
legacy signature group is required. Model the linked set first, then the
return-omitted LinkedHashMap with first-key position and last value. No method
return type is individually rewritten to another overload's return type.
"""
from collections import defaultdict
import struct
from .legacy_wire import need
from . import method_classfile as cf


def project(key):return key[:2]+(cf.legacy_descriptor(key[2]),)
def sig(key):
    cf.legacy_descriptor(key[2]);return key[:2]+(key[2].split(')',1)[0]+')',)


def rendered(key):
    text=key[2];cf.legacy_descriptor(text);pos=1;types=[]
    while text[pos]!=')':
        start=pos
        while text[pos]=='[':pos+=1
        if text[pos]=='L':pos=text.index(';',pos)+1
        else:pos+=1
        types.append(cf.type_name(text[start:pos]))
    result=text[pos+1:];result='void' if result=='V' else cf.type_name(result)
    return '<'+key[0]+': '+result+' '+key[1]+'('+','.join(types)+')>'


def table_result(ordered,legacy):
    # linkedSetOf<MethodDescriptor> drops equal full descriptors before build().
    unique=list(dict.fromkeys(project(k) if legacy else k for k in ordered))
    index={}
    for key in unique:index[sig(key)]=key
    return list(index.values())


def reshape(actual,reference,a,a_end,b,authority,evidence):
    need(getattr(authority,'allow_legacy_collisions',False),'explicit legacy method mode required')
    reference_by_signature={sig(s['method'].key):s for s in b}
    need(len(reference_by_signature)==len(b),'unique stored reference signature keys')
    need(len({sig(s['method'].key) for s in a})==len(a),'unique stored candidate signature keys')
    groups=defaultdict(list)
    for index,row in enumerate(a):groups[sig(project(row['method'].key))].append((index,row))
    replacements={};skipped=set()
    for legacy_signature,members in groups.items():
        projected={project(s['method'].key) for _,s in members}
        if len(projected)<=1:continue
        need(legacy_signature in reference_by_signature,'legacy signature missing from reference')
        target=reference_by_signature[legacy_signature];target_key=target['method'].key
        need(target_key in projected and len(members)>1,'no actual source overload explains reference method')
        keys=[s['method'].key for _,s in members]
        owner,name=keys[0][:2]
        need(all(k[:2]==(owner,name) for k in keys),'same declared member group')
        proofs=[authority.direct(k) for k in keys]
        need(all(p is not None for p in proofs),'every retained or shadowed method needs exact direct declaration')
        klass=authority.klass(owner)
        source=[(owner,m['name'],m['descriptor']) for m in klass['parsed']['methods']
                if m['name']==name and sig(project((owner,m['name'],m['descriptor'])))==legacy_signature]
        need(len(source)==len(set(source)),'duplicate full source declaration')
        descriptor_order=sorted(source,key=lambda k:k[2]);rendered_order=sorted(source,key=rendered)
        actual_expected=table_result(descriptor_order,False);legacy_expected=table_result(descriptor_order,True)
        need(actual_expected==table_result(rendered_order,False) and legacy_expected==table_result(rendered_order,True),'producer visitation alternatives disagree')
        need(keys==actual_expected,'complete source group/order required')
        need(legacy_expected==[target_key],'exact linked-set/map reference survivor')
        # Choose the first raw declaration which survives linked-set equality
        # with the final old descriptor. Its mapped bytes are checked by the
        # existing strict descriptor verifier after this table-only reshape.
        chosen_key=next(k for k in descriptor_order if project(k)==target_key)
        chosen=next((x for x in members if x[1]['method'].key==chosen_key),None)
        need(chosen is not None,'reference survivor absent from current metadata')
        first=members[0][0];replacements[first]=chosen[1]
        skipped.update(i for i,_ in members[1:])
        for i,row in members:
            key=row['method'].key
            # Authorize only that method's own array projection; never rewrite
            # an int return into long merely because another overload won.
            authority.pair(key,project(key),'definition','metadata:signature-collision:'+str(i))
        evidence.append({'legacySignature':legacy_signature,'C':target_key,'B':keys,
                         'declarations':proofs,'allSourceDeclarations':source,
                         'descriptorVisitOrder':descriptor_order,'renderedVisitOrder':rendered_order,
                         'retainedMethod':chosen_key,'firstTablePosition':first,'retainedOriginalPosition':chosen[0],
                         'recoveredMethods':len(members)-1,'methodReturnNormalization':False})
    if not replacements:return actual,a
    payload=bytearray(actual[:4]+struct.pack('>i',len(a)-len(skipped)));rows=[]
    for index,old in enumerate(a):
        if index in skipped:continue
        row=replacements.get(index,old);start=len(payload);payload.extend(actual[row['start']:row['end']])
        rows.append(dict(row,start=start,end=len(payload)))
    return bytes(payload)+actual[a_end:],rows


def check_streamed_method_sources(text):
    # Both actual producers iterate the same retained ClassNode passed to the
    # superclass. Bind the entire class body, not a freestanding sort snippet:
    # a matching expression on another node would not prove visitation order.
    variants=[]
    for declaration,node,interface in (
            ('private val node: ClassNode','node',''),
            ('override val parsedDeclarationNode: ClassNode','parsedDeclarationNode',', ParsedDeclarationSource')):
        variants.append(
            'internal class GraphiteAsmClassSource(\n'
            '    location: AnalysisInputLocation,\n'
            '    path: Path,\n'
            '    type: ClassType,\n'
            '    '+declaration+'\n'
            ') : AsmClassSource(location, path, type, '+node+')'+interface+' {\n'
            '    fun methodSources(): List<AsmMethodSource> =\n'
            '        '+node+'.methods.map { it as AsmMethodSource }.sortedWith(compareBy({ it.name }, { it.desc }))\n'
            '}')
    need(text.count('internal class GraphiteAsmClassSource(')==1
         and text.count('fun methodSources(')==1
         and sum(text.count(block) for block in variants)==1,
         'streamed raw descriptor order or retained node identity changed')


def bind_sources(types):
    from pathlib import Path
    import json,hashlib
    pins={}
    snippets={
        'frontend/jvm/core/src/main/kotlin/io/johnsonlee/graphite/graph/MmapGraphBuilder.kt':[
            'private val methods = linkedSetOf<MethodDescriptor>()','methods.add(method)',
            'val methodIndex = LinkedHashMap<String, MethodDescriptor>(methods.size)',
            'methods.forEach { methodIndex[it.signature] = it }'],
        'frontend/jvm/core/src/main/kotlin/io/johnsonlee/graphite/core/Node.kt':[
            'data class MethodDescriptor(',
            'val signature: String get() = "${declaringClass.className}.$name(${parameterTypes.joinToString(",") { it.className }})"'],
        'frontend/jvm/sootup/src/main/kotlin/sootup/java/bytecode/frontend/conversion/GraphiteAsmClassSource.kt':[],
        'frontend/jvm/sootup/src/main/kotlin/io/johnsonlee/graphite/sootup/SootUpAdapter.kt':[
            'resolveMethodsOrEmpty(sootClass).sortedBy { it.signature.toString() }.forEach(action)',
            '.sortedWith(compareBy({ (it.bodySource as? MethodNode)?.name ?: it.name }, { (it.bodySource as? MethodNode)?.desc ?: it.signature.toString() }))']}
    for item in types.rule['arms'].values():
        manifest=json.loads(Path(item['manifest']).read_text());root=Path(manifest['root'])
        for rel,required in snippets.items():
            path=root/rel;raw=path.read_bytes();digest=hashlib.sha256(raw).hexdigest()
            need(manifest['files'].get(str(path),manifest['files'].get(rel))==digest,'actual metadata producer source changed')
            text=raw.decode();need(all(part in text for part in required),'exact metadata insertion/iteration rule changed')
            if rel.endswith('/GraphiteAsmClassSource.kt'):check_streamed_method_sources(text)
            pins[str(path)]=digest
    return pins
