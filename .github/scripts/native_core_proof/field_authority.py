"""Narrow classfile-backed expected Field corrections; never broad normalization."""
import csv,hashlib,json,struct,zipfile
from pathlib import Path
from .legacy_wire import need

def sha(path):
    h=hashlib.sha256()
    with Path(path).open('rb') as f:
        for b in iter(lambda:f.read(1<<20),b''):h.update(b)
    return h.hexdigest()

class R:
    def __init__(self,b):self.b=b;self.p=0
    def take(self,n):
        need(n>=0 and self.p+n<=len(self.b),'classfile truncation');v=self.b[self.p:self.p+n];self.p+=n;return v
    def u1(self):return self.take(1)[0]
    def u2(self):return struct.unpack('>H',self.take(2))[0]
    def u4(self):return struct.unpack('>I',self.take(4))[0]
def parse(b):
    r=R(b);need(r.u4()==0xcafebabe,'magic');minor,major=r.u2(),r.u2();cp=[None]*r.u2();i=1
    while i<len(cp):
        tag=r.u1()
        if tag==1:cp[i]=(tag,r.take(r.u2()))
        elif tag in (3,4):cp[i]=(tag,r.take(4))
        elif tag in (5,6):cp[i]=(tag,r.take(8));i+=1;need(i<len(cp),'wide CP entry')
        elif tag in (7,8,16,19,20):cp[i]=(tag,r.u2())
        elif tag in (9,10,11,12,17,18):cp[i]=(tag,r.take(4))
        elif tag==15:cp[i]=(tag,r.take(3))
        else:raise ValueError('unknown CP tag '+str(tag))
        i+=1
    def utf(i):
        need(0<i<len(cp) and cp[i] and cp[i][0]==1,'UTF CP type')
        # All authority names/descriptors/signatures in this bounded Android set
        # are ASCII. Reject unsupported non-ASCII rather than silently decode MUTF8.
        return cp[i][1].decode('ascii')
    def attrs():
        a=[]
        for _ in range(r.u2()):
            name=utf(r.u2());raw=r.take(r.u4());a.append({'name':name,'rawHex':raw.hex(),'sha256':hashlib.sha256(raw).hexdigest()})
            if name=='Signature':need(len(raw)==2,'Signature length');a[-1]['signature']=utf(struct.unpack('>H',raw)[0])
        need(sum(x['name']=='Signature' for x in a)<=1,'duplicate Signature');return a
    access,this,super_=r.u2(),r.u2(),r.u2();need(cp[this][0]==7,'class CP');owner=utf(cp[this][1]).replace('/','.')
    def class_name(index):
        need(0<index<len(cp) and cp[index] and cp[index][0]==7,'ancestor class CP')
        return utf(cp[index][1]).replace('/','.')
    super_name=class_name(super_) if super_ else None
    interfaces=[class_name(r.u2()) for _ in range(r.u2())]
    need(len(set(interfaces))==len(interfaces),'duplicate direct interface')
    fields=[]
    for _ in range(r.u2()):
        start=r.p;flags,name,desc=r.u2(),utf(r.u2()),utf(r.u2());attributes=attrs()
        fields.append({'name':name,'descriptor':desc,'accessFlags':flags,'static':bool(flags&8),'signature':next((a['signature'] for a in attributes if a['name']=='Signature'),None),'attributes':attributes,'rawFieldInfoHex':b[start:r.p].hex()})
    for _ in range(r.u2()):r.take(6);attrs()
    classattrs=attrs();need(r.p==len(b),'classfile trailing bytes')
    return {'owner':owner,'superName':super_name,'interfaces':interfaces,'major':major,'minor':minor,'accessFlags':access,'classSignature':next((a['signature'] for a in classattrs if a['name']=='Signature'),None),'fields':fields}
def type_name(d):
    n=len(d)-len(d.lstrip('['));need(n<=255,'descriptor array dimensions');base=d[n:];primitive={'B':'byte','C':'char','D':'double','F':'float','I':'int','J':'long','S':'short','Z':'boolean'}
    if base in primitive:t=primitive[base]
    else:
        need(base.startswith('L') and base.endswith(';'),'field descriptor')
        name=base[1:-1];need(name and all(c not in name for c in '.;[') and all(part for part in name.split('/')),'field descriptor class name');t=name.replace('/','.')
    return t+'[]'*n


class Signature:
    """Complete field Signature grammar, retaining the legacy outer visitor name."""
    def __init__(self,s):self.s=s;self.p=0
    def token(self,delimiters):
        start=self.p
        while self.p<len(self.s) and self.s[self.p] not in delimiters:self.p+=1
        text=self.s[start:self.p];need(text and all(ord(c)>32 and c not in ':>[' for c in text),'signature identifier');return text
    def take(self,c):need(self.p<len(self.s) and self.s[self.p]==c,'signature framing');self.p+=1
    def args(self,depth):
        if self.p>=len(self.s) or self.s[self.p]!='<':return
        self.p+=1;n=0
        while self.p<len(self.s) and self.s[self.p]!='>':
            n+=1;c=self.s[self.p]
            if c=='*':self.p+=1
            else:
                if c in '+-':self.p+=1
                self.type(depth+1,False)
        need(n>0,'empty signature arguments');self.take('>')
    def type(self,depth=0,primitive=False):
        need(depth<128 and self.p<len(self.s),'signature depth/truncation');arrays=0
        while self.p<len(self.s) and self.s[self.p]=='[':arrays+=1;self.p+=1
        need(arrays<=255 and self.p<len(self.s),'signature array count')
        tag=self.s[self.p];self.p+=1
        if tag=='T':
            name=self.token(';');need('/' not in name and '.' not in name and '<' not in name,'variable name');self.take(';');return {'kind':'variable','legacy':name+'[]'*arrays,'arrays':arrays}
        if tag=='L':
            root=self.token('<.;');need(not root.startswith('/') and not root.endswith('/') and '//' not in root,'class signature name');self.args(depth);inners=[]
            while self.p<len(self.s) and self.s[self.p]=='.':
                self.p+=1;name=self.token('<.;');need('/' not in name,'inner signature name');inners.append(name);self.args(depth)
            self.take(';');return {'kind':'class','legacy':root.replace('/','.')+'[]'*arrays,'binary':(root+'$'+'$'.join(inners) if inners else root).replace('/','.')+'[]'*arrays,'inner':bool(inners),'arrays':arrays}
        need((primitive or arrays) and tag in 'BCDFIJSZ','invalid field signature type')
        return {'kind':'primitive','legacy':type_name(tag)+'[]'*arrays,'arrays':arrays}
    def done(self):
        result=self.type();need(self.p==len(self.s),'trailing field Signature');return result

def correction(c_key,b_key,field):
    need(c_key[:2]==b_key[:2] and c_key[3]==b_key[3],'non-type Field difference')
    need(field['static']==b_key[3] and field['name']==b_key[1],'classfile field static/name')
    expected=type_name(field['descriptor']);need(b_key[2]==expected and c_key[2]!=expected,'candidate descriptor must be authoritative correction')
    sig=field['signature']
    if sig is None:
        dimensions=len(field['descriptor'])-len(field['descriptor'].lstrip('['))
        need(dimensions>1 and c_key[2]==type_name(field['descriptor'][dimensions:])+'[]','unsupported no-Signature old type')
        return 'LEGACY_ARRAY_DIMENSION_COLLAPSE'
    old=Signature(sig).done();need(c_key[2]==old['legacy'],'old type not exact Signature visitor result')
    if old['kind']=='variable':
        dimensions=len(field['descriptor'])-len(field['descriptor'].lstrip('['))
        need(old['arrays']==dimensions,'Signature/descriptor array mismatch')
        return 'LEGACY_TYPE_VARIABLE_CLASSNAME'
    need(old['kind']=='class' and old['inner'] and old['binary']==expected,'unsupported Signature correction')
    return 'LEGACY_OUTER_ONLY_INNER_CLASSNAME'

def canonical_field_payload(key):
    owner,name,kind,static=key;raw=bytearray([9])
    for value in (owner,name,kind):
        encoded=value.encode('utf-8',errors='strict');raw.extend(struct.pack('>i',len(encoded)));raw.extend(encoded)
    need(type(static)is bool,'static type');raw.append(int(static));return bytes(raw)

def match_fields(actual,reference,authority):
    """Actual=B. Exact matches first; corrections must form a closed total bijection."""
    match={k:k for k in actual.keys()&reference.keys()};records=[]
    pending_b=actual.keys()-reference.keys();pending_c=reference.keys()-actual.keys()
    def groups(keys):
        out={}
        for k in keys:
            group=(k[0],k[1],k[3]);need(group not in out,'ambiguous unmatched owner/name/static');out[group]=k
        return out
    bg,cg=groups(pending_b),groups(pending_c);need(bg.keys()==cg.keys(),'unmatched Field owner/name/static')
    for group in sorted(bg):
        bk,ck=bg[group],cg[group];proof=authority.field_key(bk) if getattr(authority,'allow_inherited_fields',False) else authority.field(bk[0],bk[1]);category=correction(ck,bk,proof['field'])
        need(actual[bk][1]==canonical_field_payload(bk) and reference[ck][1]==canonical_field_payload(ck),'unexpected Field payload')
        match[bk]=ck;records.append({'category':category,'B':{'nodeId':actual[bk][0],'key':list(bk),'canonicalPayloadHex':actual[bk][1].hex()},'C':{'nodeId':reference[ck][0],'key':list(ck),'canonicalPayloadHex':reference[ck][1].hex()},'classfileAuthority':proof})
    need(len(match)==len(actual)==len(reference) and len(set(match.values()))==len(reference),'total unique Field correspondence')
    # Keep self-mapped IDs out of TSV, exactly like the original proof.
    remap={actual[k][0]:reference[v][0] for k,v in match.items() if actual[k][0]!=reference[v][0]}
    need({v[0] for v in actual.values()}=={v[0] for v in reference.values()},'complete Field ID set changed')
    need(set(remap)==set(remap.values()),'not closed Field permutation')
    return match,remap,records

class Authority:
    def __init__(self,spec,actual,reference,out,allow_inherited_fields=False):
        self.allow_inherited_fields=allow_inherited_fields
        self.spec=spec;self.out=Path(out);self.pins={};self.cache={};self.zip=None
        need(spec['schema']=='graphite.classfile-field-authority.v1','authority schema')
        self.jar=Path(spec['jar']['path']);self.jar_digest=spec['jar']['sha256']
        for label,root in [('B',actual),('C',reference)]:
            item=spec['arms'][label];mf=Path(item['fixtureManifest']['path']);pr=Path(item['provenance']['path'])
            for path,h in [(mf,item['fixtureManifest']['sha256']),(pr,item['provenance']['sha256'])]:
                need(sha(path)==h,'authority metadata pin');self.pins[str(path)]=h
            manifest=json.loads(mf.read_text());need(manifest['files'][str(pr)]==self.pins[str(pr)],'producer provenance binding')
            graphs=[g for g in manifest['graphs'] if g['id']==spec['graphId']];need(len(graphs)==1 and Path(graphs[0]['path']).resolve()==Path(root).resolve(),'authority graph binding')
            with pr.open() as stream:rows=[r for r in csv.DictReader(stream,delimiter='\t') if r['graphId']==spec['graphId']]
            need(len(rows)==1,'unique graph provenance');row=rows[0]
            need(Path(row['graphPath']).resolve()==Path(root).resolve() and row['corpus']==spec['corpus'] and row['sourceJarSha256']==self.jar_digest and row['sourceJar']==self.jar.name,'graph source JAR authority')
            need([j for j in manifest['inputJars'] if j['corpus']==spec['corpus']]==[spec['jar']],'exact producer JAR declaration')
        need(not self.jar.is_symlink() and sha(self.jar)==self.jar_digest,'actual source JAR digest');self.pins[str(self.jar)]=self.jar_digest
        self.marker=None
        if 'platformMarker' in spec:
            need(allow_inherited_fields,'platform marker requires explicit inherited field mode')
            from . import marker_authority
            self.marker=marker_authority.Marker(spec['platformMarker'])
            self.pins.update(self.marker.pins)
    def _field_class(self,owner):
        need(owner and not any(c in owner for c in ('/','\\','\x00')) and all(p not in ('','.','..') for p in owner.split('.')),'class owner path')
        if owner not in self.cache:
            if self.zip is None:self.zip=zipfile.ZipFile(self.jar)
            entry=owner.replace('.','/')+'.class';count=self.zip.namelist().count(entry)
            if count==0 and owner=='java.io.Serializable' and getattr(self,'marker',None) is not None:
                self.cache[owner]=self.marker.archive(self.out)
                return self.cache[owner]
            need(count==1,'missing/duplicate exact declared owner')
            info=self.zip.getinfo(entry);need(info.file_size<=64*1024*1024,'classfile diagnostic size bound')
            raw=self.zip.read(info);klass=parse(raw);need(klass['owner']==owner,'this_class owner')
            path=self.out/'classfiles'/entry;path.parent.mkdir(parents=True,exist_ok=True)
            with path.open('xb') as f:f.write(raw)
            klass.update(jarEntry=entry,classBytesSha256=hashlib.sha256(raw).hexdigest(),rawClassFile=str(path));self.cache[owner]=klass
        return self.cache[owner]
    def field(self,owner,name):
        klass=self._field_class(owner);fields=[f for f in klass['fields'] if f['name']==name];need(len(fields)==1,'missing/inherited/ambiguous declared field')
        return {'jarSha256':self.jar_digest,'class':{k:v for k,v in klass.items() if k!='fields'},'field':fields[0]}
    def field_key(self,key):
        from .legacy_wire import descriptor
        need(self.allow_inherited_fields,'inherited field lookup requires explicit mode')
        owner,name,kind,static=key;expected=descriptor(kind)
        active=set();absent=set();steps=[]
        def lookup(current,depth):
            need(depth<=256 and current not in active,'cyclic/deep field hierarchy')
            if current in absent:return None
            active.add(current)
            klass=self._field_class(current)
            matches=[f for f in klass['fields'] if f['name']==name and f['descriptor']==expected]
            need(len(matches)<=1,'duplicate exact declared field')
            steps.append({'owner':current,'class':{k:v for k,v in klass.items() if k!='fields'},
                          'sameNameDeclarations':[f for f in klass['fields'] if f['name']==name],
                          'exactMatch':bool(matches)})
            if matches:
                return {'jarSha256':self.jar_digest,'class':{k:v for k,v in klass.items() if k!='fields'},'field':matches[0]}
            for interface in klass['interfaces']:
                result=lookup(interface,depth+1)
                if result is not None:return result
            if not klass['accessFlags']&0x0200 and klass['superName'] is not None:
                result=lookup(klass['superName'],depth+1)
                if result is not None:return result
            active.remove(current);absent.add(current);return None
        result=lookup(owner,0)
        need(result is not None,'field not found in completely checked source hierarchy')
        need(result['field']['static']==static,'resolved field static mismatch')
        result.update(symbolicOwner=owner,lookupDescriptor=expected,lookupSteps=steps,
                      mode='EXACT_NAME_DESCRIPTOR_FIELD_HIERARCHY_LOOKUP',accessControlClaim=False)
        return result
    def finish(self):
        if self.zip is not None:self.zip.close();self.zip=None
        for p,h in self.pins.items():need(sha(p)==h,'classfile authority input changed')
