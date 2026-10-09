"""Exact direct classfile parser retained from bounded15-method authority, no IO on import."""
import struct,hashlib
from .legacy_wire import need
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
        need(0<index<len(cp) and cp[index] and cp[index][0]==7,'hierarchy class CP type')
        return utf(cp[index][1]).replace('/','.')
    super_name=class_name(super_) if super_ else None
    interfaces=[class_name(r.u2()) for _ in range(r.u2())]
    need(len(interfaces)==len(set(interfaces)),'duplicate interfaces')
    fields=[]
    for _ in range(r.u2()):
        start=r.p;flags,name,desc=r.u2(),utf(r.u2()),utf(r.u2());attributes=attrs()
        fields.append({'name':name,'descriptor':desc,'accessFlags':flags,'static':bool(flags&8),'signature':next((a['signature'] for a in attributes if a['name']=='Signature'),None),'attributes':attributes,'rawFieldInfoHex':b[start:r.p].hex()})
    methods=[]
    for _ in range(r.u2()):
        start=r.p;flags,name,desc=r.u2(),utf(r.u2()),utf(r.u2());attributes=attrs()
        methods.append({'name':name,'descriptor':desc,'accessFlags':flags,'signature':next((a['signature'] for a in attributes if a['name']=='Signature'),None),'attributes':attributes,'rawMethodInfoHex':b[start:r.p].hex()})
    classattrs=attrs();need(r.p==len(b),'classfile trailing bytes')
    return {'owner':owner,'superName':super_name,'interfaces':interfaces,'major':major,'minor':minor,'accessFlags':access,'classSignature':next((a['signature'] for a in classattrs if a['name']=='Signature'),None),'fields':fields,'methods':methods}
def type_name(d):
    n=len(d)-len(d.lstrip('['));base=d[n:];primitive={'B':'byte','C':'char','D':'double','F':'float','I':'int','J':'long','S':'short','Z':'boolean'}
    if base in primitive:t=primitive[base]
    else:need(base.startswith('L') and base.endswith(';') and ';' not in base[1:-1],'field descriptor');t=base[1:-1].replace('/','.')
    return t+'[]'*n


def legacy_descriptor(text):
    # Parse every parameter and return, preserving bases/order/arity exactly.
    def atom(pos,returns=False):
        start=pos
        while pos<len(text) and text[pos]=='[':pos+=1
        dimensions=pos-start;need(dimensions<=255 and pos<len(text),'descriptor truncation')
        if text[pos]=='L':
            end=text.find(';',pos);need(end>pos+1,'object descriptor')
            base=text[pos:end+1];need(not any(c in base[1:-1] for c in '.;['),'object name');pos=end+1
        else:
            base=text[pos];need(base in ('BCDFIJSZV' if returns and not dimensions else 'BCDFIJSZ'),'descriptor primitive');pos+=1
        return ('[' if dimensions else '')+base,pos
    need(text.startswith('('),'method descriptor');pos=1;arguments=[]
    while pos<len(text) and text[pos]!=')':
        value,pos=atom(pos);arguments.append(value)
    need(pos<len(text) and text[pos]==')','parameter end');value,pos=atom(pos+1,True)
    need(pos==len(text),'descriptor trailing');return '('+''.join(arguments)+')'+value
