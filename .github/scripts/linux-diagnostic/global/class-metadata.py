"""Minimal class-file attribution parser. No JVM, disassembly process or source inference."""
def class_metadata(data):
 if not data.startswith(b'\xca\xfe\xba\xbe'):return {}
 pos=8;cp={}
 def u2():
  nonlocal pos
  n=int.from_bytes(data[pos:pos+2],'big');pos+=2;return n
 n=u2();idx=1
 while idx<n:
  tag=data[pos];pos+=1
  if tag==1:
   size=u2();cp[idx]=data[pos:pos+size].decode('utf-8',errors='replace');pos+=size
  elif tag in (3,4):pos+=4
  elif tag in (5,6):pos+=8;idx+=1
  elif tag in (7,8,16,19,20):cp[idx]=(tag,u2())
  elif tag in (9,10,11,12,17,18):cp[idx]=(tag,u2(),u2())
  elif tag==15:pos+=3
  else:raise AssertionError(('constant pool tag',tag))
  idx+=1
 def cls(i):return cp[cp[i][1]] if i else None
 u2();owner=cls(u2());u2();count=u2();pos+=2*count
 def attributes():
  nonlocal pos
  result={}
  for _ in range(u2()):
   name=cp[u2()];size=int.from_bytes(data[pos:pos+4],'big');pos+=4;result[name]=data[pos:pos+size];pos+=size
  return result
 for _ in range(2):
  for member in range(u2()):pos+=6;attributes()
 attrs=attributes();out={'className':owner}
 if 'SourceFile' in attrs:out['sourceFile']=cp[int.from_bytes(attrs['SourceFile'],'big')]
 if 'SourceDebugExtension' in attrs:out['sourceDebugExtension']=attrs['SourceDebugExtension'].decode('utf-8')
 if 'EnclosingMethod' in attrs:
  b=attrs['EnclosingMethod'];ci=int.from_bytes(b[:2],'big');mi=int.from_bytes(b[2:],'big');out['enclosingClass']=cls(ci)
  if mi:_,name,desc=cp[mi];out['enclosingMethod']=cp[name];out['enclosingDescriptor']=cp[desc]
 if 'InnerClasses' in attrs:
  b=attrs['InnerClasses'];out['innerClasses']=[]
  for i in range(int.from_bytes(b[:2],'big')):
   off=2+i*8;ci=int.from_bytes(b[off:off+2],'big');oi=int.from_bytes(b[off+2:off+4],'big');ni=int.from_bytes(b[off+4:off+6],'big')
   out['innerClasses'].append({'innerClass':cls(ci),'outerClass':cls(oi),'innerName':cp[ni] if ni else None})
 return out
