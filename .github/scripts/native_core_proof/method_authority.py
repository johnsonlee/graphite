"""Explicit paired method-array corrections; never global string normalization."""
import hashlib,json,zipfile
from pathlib import Path
from . import method_classfile as classfile
from . import field_authority
from .legacy_wire import need

def pool(raw):
    r=classfile.R(raw);need(r.u4()==0xcafebabe,'class magic');r.take(4);cp=[None]*r.u2();i=1
    while i<len(cp):
        start=r.p;tag=r.u1()
        if tag==1:value=r.take(r.u2())
        elif tag in (3,4):value=r.take(4)
        elif tag in (5,6):value=r.take(8)
        elif tag in (7,8,16,19,20):value=r.u2()
        elif tag in (9,10,11,12,17,18):value=(r.u2(),r.u2())
        elif tag==15:value=(r.u1(),r.u2())
        else:raise ValueError('CP tag '+str(tag))
        cp[i]={'index':i,'tag':tag,'value':value,'offset':start,'rawHex':raw[start:r.p].hex()}
        if tag in (5,6):i+=1;need(i<len(cp),'wide CP bounds')
        i+=1
    return cp

def references(raw):
    cp=pool(raw)
    def get(i,tag):need(0<i<len(cp) and cp[i] is not None and cp[i]['tag']==tag,'CP reference kind');return cp[i]
    def utf(i):return get(i,1)['value'].decode('ascii')
    def evidence(row):return {k:v for k,v in row.items() if k!='value'}
    rows=[]
    for r in cp:
        if r is None or r['tag'] not in (10,11):continue
        ci,ni=r['value'];c=get(ci,7);n=get(ni,12);name,desc=n['value'];owner=utf(c['value']).replace('/','.')
        rows.append({'kind':'Methodref' if r['tag']==10 else 'InterfaceMethodref','owner':owner,'name':utf(name),'descriptor':utf(desc),'constantPoolEvidence':[evidence(x) for x in (r,c,n,get(c['value'],1),get(name,1),get(desc,1))]})
    return rows

def select(refs,target):
    exact=[r for r in refs if [r['owner'],r['name'],r['descriptor']]==target]
    projected=[r for r in refs if r['owner']==target[0] and r['name']==target[1] and classfile.legacy_descriptor(r['descriptor'])==classfile.legacy_descriptor(target[2])]
    keys={(r['owner'],r['name'],r['descriptor']) for r in projected}
    need(exact,'missing exact CP member reference');need(keys=={tuple(target)},'ambiguous legacy projection among caller CP member references')
    return {'exactReferences':exact,'legacyProjectionCandidates':projected,'distinctProjectionTargetCount':len(keys)}


class Authority(field_authority.Authority):
    def __init__(self,spec,actual,reference,out,allow_legacy_collisions=False,allow_inherited_class_methods=False):
        # Platform marker evidence is field-only; method lookup stays corpus-only.
        method_spec={k:v for k,v in spec.items() if k!='platformMarker'}
        super().__init__(method_spec,actual,reference,out)
        self.allow_legacy_collisions=allow_legacy_collisions
        self.allow_inherited_class_methods=allow_inherited_class_methods
        self.proofs={};self.occurrences=[];self.mapping={};self.reverse={};self.complete=False;self.failed_corrections=[]
    def klass(self,owner):
        need(owner and not any(c in owner for c in ('/','\\','\x00')) and all(owner.split('.')),'method owner path')
        if owner not in self.cache:
            if self.zip is None:self.zip=zipfile.ZipFile(self.jar)
            entry=owner.replace('.','/')+'.class'
            count=self.zip.namelist().count(entry)
            need(count<=1,'duplicate method owner class')
            if not count:return None
            info=self.zip.getinfo(entry);need(info.file_size<=64*1024*1024,'bounded method class size')
            raw=self.zip.read(info);parsed=classfile.parse(raw);need(parsed['owner']==owner,'method this_class')
            path=self.out/'method-classfiles'/entry;path.parent.mkdir(parents=True,exist_ok=True)
            with path.open('xb') as f:f.write(raw)
            self.cache[owner]={'parsed':parsed,'raw':raw,'evidence':{'jarEntry':entry,'rawClassFile':str(path),'classBytesSha256':hashlib.sha256(raw).hexdigest(),'classSignature':parsed['classSignature'],'accessFlags':parsed['accessFlags']}}
        return self.cache[owner]
    def direct(self,key):
        klass=self.klass(key[0])
        if klass is None:return None
        same=[m for m in klass['parsed']['methods'] if m['name']==key[1]]
        exact=[m for m in same if m['descriptor']==key[2]]
        need(len(exact)<=1,'duplicate exact direct method')
        if not exact:return None
        old=classfile.legacy_descriptor(key[2]);projected=[m for m in same if classfile.legacy_descriptor(m['descriptor'])==old]
        need(len(projected)==1 or getattr(self,'allow_legacy_collisions',False),'ambiguous direct method legacy projection')
        return {'mode':'EXACT_DIRECT_DECLARATION','class':klass['evidence'],'method':exact[0],'legacyProjectionCandidates':projected}
    def inherited_class_method(self,key):
        need(getattr(self,'allow_inherited_class_methods',False),'inherited method mode required')
        if key[1] in ('<init>','<clinit>'):return None
        owner=key[0];seen=set();steps=[]
        while owner is not None:
            need(owner not in seen and len(seen)<256,'cyclic/deep method superclass chain')
            seen.add(owner);klass=self.klass(owner)
            if klass is None:return None
            parsed=klass['parsed']
            if parsed['accessFlags']&0x0200:return None
            matches=[m for m in parsed['methods'] if m['name']==key[1] and m['descriptor']==key[2]]
            need(len(matches)<=1,'duplicate exact hierarchy method')
            steps.append({'owner':owner,'class':klass['evidence'],'exactMatch':bool(matches),
                          'sameNameDeclarations':[m for m in parsed['methods'] if m['name']==key[1]]})
            if matches:
                return {'mode':'EXACT_CLASS_SUPERCHAIN_DECLARATION_NOT_INVOCATION_SELECTION',
                        'symbolicOwner':key[0],'declaringOwner':owner,'lookupDescriptor':key[2],
                        'lookupSteps':steps,'class':klass['evidence'],'method':matches[0],
                        'accessControlClaim':False,'interfaceResolutionClaim':False}
            owner=parsed['superName']
        return None

    def pair(self,actual,reference,role,location,caller=None):
        try:return self._pair(actual,reference,role,location,caller)
        except Exception as error:
            if not hasattr(self,'failed_corrections'):self.failed_corrections=[]
            self.failed_corrections.append({'location':location,'role':role,'B':list(actual),'C':list(reference),'caller':list(caller) if caller is not None else None,'error':repr(error)})
            raise
    def _pair(self,actual,reference,role,location,caller=None):
        actual,reference=tuple(actual),tuple(reference)
        if actual==reference:return False
        need(actual[:2]==reference[:2],'non-array method owner/name difference')
        need(classfile.legacy_descriptor(actual[2])==reference[2] and actual[2]!=reference[2],'method difference not exact legacy array collapse')
        need(reference not in self.reverse or self.reverse[reference]==actual or getattr(self,'allow_legacy_collisions',False),'multiple full methods collapse to reference identity')
        need(actual not in self.mapping or self.mapping[actual]==reference,'method correction inconsistency')
        proof=self.direct(actual)
        if proof is None and role=='callee' and getattr(self,'allow_inherited_class_methods',False):
            proof=self.inherited_class_method(actual)
        if proof is None:
            need(role=='callee' and caller is not None,'caller/definition requires direct method declaration')
            caller=tuple(caller);caller_proof=self.direct(caller);need(caller_proof is not None,'CP authority requires exact direct source caller')
            klass=self.klass(caller[0]);cp=select(references(klass['raw']),list(actual))
            proof={'mode':'EXACT_CALLER_CP_REFERENCE_NOT_RESOLUTION_OR_INVOCATION_SELECTION','caller':list(caller),'callerDeclaration':caller_proof,**cp}
        self.mapping[actual]=reference;self.reverse[reference]=actual
        encoded=json.dumps(proof,sort_keys=True,separators=(',',':')).encode();pid=hashlib.sha256(encoded).hexdigest();self.proofs[pid]=proof
        self.occurrences.append({'location':location,'role':role,'B':list(actual),'C':list(reference),'proofId':pid})
        return True
    def save(self):
        classes={str(p):field_authority.sha(p) for p in sorted((self.out/'method-classfiles').rglob('*.class'))}
        value={'status':'PASS_EXPLICIT_METHOD_ARRAY_CORRECTIONS' if self.complete else 'PARTIAL_METHOD_ARRAY_CORRECTION_EVIDENCE_NOT_CORE_PASS',
               'strictEquivalence':False,'declarationResolutionClaim':False,'invocationSelectionClaim':False,
               'exceptionCount':len(self.occurrences),'uniqueMethodCount':len(self.mapping),
               'occurrences':self.occurrences,'failedCorrections':getattr(self,'failed_corrections',[]),'proofs':self.proofs,'inputs':self.pins,'rawClassPins':classes,
               'sourceCommit':'cfcfb191e3859550e05322223459f1a9b283e622'}
        (self.out/'method-corrections.json').write_text(json.dumps(value,indent=2)+'\n')
        return value
    def __enter__(self):return self
    def __exit__(self,kind,error,trace):
        # Save every verified earlier correction even if a later strict gate fails.
        try:super().finish()
        finally:self.save()

def compare_spans(a,b,a_spans,b_spans,authority,location):
    need(len(a_spans)==len(b_spans),'method occurrence count')
    replacements=[]
    for index,(x,y) in enumerate(zip(a_spans,b_spans)):
        need(x['role']==y['role'],'method occurrence role')
        if authority.pair(x['method'].key,y['method'].key,x['role'],location+':'+str(index),x['caller'].key if x['caller'] else None):
            replacements.append((x['start'],x['end'],b[y['start']:y['end']]))
    # Replace only the canonical serialization span of a proved parsed method.
    for start,end,value in reversed(replacements):a=a[:start]+value+a[end:]
    return a

def compare_ordinals(actual,reference,authority):
    from . import callsite_ordinals
    rows={};need(actual['rows'].keys()==reference['rows'].keys(),'ordinal CallSite ID set changed')
    for node,a in actual['rows'].items():
        b=reference['rows'][node]
        # Origins (including their raw full member tuple), values and presence
        # remain exact; only this CallSite's caller/callee method spans can vary.
        need(all(a[k]==b[k] for k in ('ordinal','origin','originMember')),'ordinal/origin value/member mismatch')
        row=dict(a)
        for role in ('caller','callee'):
            if authority.pair(a[role],b[role],role,'ordinal:'+str(node)+':'+role,a['caller'] if role=='callee' else None):row[role]=b[role]
        rows[node]=row
    converted=dict(actual,rows=rows)
    result=callsite_ordinals.compare(converted,reference)
    result.update(scope='COMPLETE_CALLSITE_ORDINAL_ORIGIN_EXACT_WITH_EXPLICIT_METHOD_ARRAY_CORRECTIONS',strictEquivalence=False)
    return result
