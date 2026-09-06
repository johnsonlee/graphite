"""Compare same-layout classes while masking only exact SMAP/debug attribute byte spans."""
import struct,hashlib,zipfile

def normalize(data):
 b=bytearray(data);spans=[];cp={};off=8
 def u2(pos):return struct.unpack_from('>H',data,pos)[0]
 def u4(pos):return struct.unpack_from('>I',data,pos)[0]
 def mask(begin,end,kind):
  spans.append({'offset':begin,'bytes':end-begin,'kind':kind});b[begin:end]=b'\0'*(end-begin)
 count=u2(off);off+=2;i=1
 while i<count:
  tag=data[off];off+=1
  if tag==1:
   length=u2(off);off+=2;cp[i]=data[off:off+length].decode('utf8','replace')
   if cp[i].startswith('SMAP\n'):mask(off,off+length,'constant-pool SMAP string')
   off+=length
  elif tag in (3,4,9,10,11,12,17,18):off+=4
  elif tag in (5,6):off+=8;i+=1
  elif tag in (7,8,16,19,20):off+=2
  elif tag==15:off+=3
  else:raise AssertionError(tag)
  i+=1
 off+=6; interfaces=u2(off);off+=2+2*interfaces
 def attrs(pos):
  count=u2(pos);pos+=2
  for _ in range(count):
   name=cp[u2(pos)];size=u4(pos+2);start=pos+6;end=start+size
   if name in ('SourceDebugExtension','LineNumberTable'):mask(start,end,name)
   elif name=='Code':
    q=start+4;codesize=u4(q);q+=4+codesize;exceptions=u2(q);q+=2+8*exceptions
    assert attrs(q)==end
   pos=end
  return pos
 for _ in range(2):
  members=u2(off);off+=2
  for _ in range(members):off=attrs(off+6)
 off=attrs(off);assert off==len(data)
 return bytes(b),spans

def audit(jar,oldjar,names):
 r=[]
 with zipfile.ZipFile(jar) as z,zipfile.ZipFile(oldjar) as old:
  for name in names:
   a=z.read(name);b=old.read(name);an,asp=normalize(a);bn,bsp=normalize(b)
   r.append({'class':name,'currentBytes':len(a),'parentBytes':len(b),'currentSha256':hashlib.sha256(a).hexdigest(),'parentSha256':hashlib.sha256(b).hexdigest(),'allOtherBytesEqual':an==bn,'maskedKinds':['constant-pool SMAP string','SourceDebugExtension','LineNumberTable'],'currentMaskedSpans':asp,'parentMaskedSpans':bsp})
 return r
