from pathlib import Path
import struct,json,zipfile,subprocess,hashlib,collections,re,os
R=Path(__file__).parent
jars={'base':Path('/private/tmp/graphite-next-baseline.T2FTs9/graphite-webgraph/build/libs/webgraph-1.0.0-SNAPSHOT-jmh.jar'),'candidate':R/'candidate-jmh.jar'}
classes=['io.johnsonlee.graphite.webgraph.MappedCallSiteStringIndexView$Companion','io.johnsonlee.graphite.webgraph.PersistentIndexViewValidator']
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def parse(data):
 pos=0
 def read(n):
  nonlocal pos
  value=data[pos:pos+n];pos+=n;return value
 def u1():return int.from_bytes(read(1),'big')
 def u2():return int.from_bytes(read(2),'big')
 def u4():return int.from_bytes(read(4),'big')
 assert read(4)==b'\xca\xfe\xba\xbe';minor=u2();major=u2();count=u2();cp=[None]*count;i=1
 while i<count:
  tag=u1()
  if tag==1:cp[i]=read(u2()).decode('utf-8','replace')
  elif tag in [3,4]:read(4)
  elif tag in [5,6]:read(8);i+=1
  elif tag in [7,8,16,19,20]:read(2)
  elif tag in [9,10,11,12,17,18]:read(4)
  elif tag==15:read(3)
  else:raise ValueError(tag)
  i+=1
 read(6);read(u2()*2)
 def attrs():
  result={}
  for _ in range(u2()):
   name=cp[u2()];value=read(u4());result[name]=value
  return result
 for _ in range(u2()):read(6);attrs()
 methods=[]
 for _ in range(u2()):
  access=u2();name=cp[u2()];desc=cp[u2()];attributes=attrs();m={'name':name,'descriptor':desc,'accessFlags':access}
  if 'Code' in attributes:
   code=attributes['Code'];stack,locals_,length=struct.unpack_from('>HHI',code);m.update({'codeLengthBytes':length,'maxStack':stack,'maxLocals':locals_})
  methods.append(m)
 return {'major':major,'minor':minor,'classFileBytes':len(data),'methods':methods}
results={};javap='/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin/javap'
for side,jar in jars.items():
 results[side]={}
 with zipfile.ZipFile(jar) as z:
  for cls in classes:
   simple=cls.rsplit('.',1)[1].replace('$','-');cmd=[javap,'-classpath',str(jar),'-c','-p',cls];p=R/(side+'-'+simple+'-javap.txt')
   (R/(side+'-'+simple+'-javap-command.json')).write_text(json.dumps(cmd,indent=2)+'\n')
   p.write_bytes(subprocess.check_output(cmd,stderr=subprocess.STDOUT,env={**os.environ,'JAVA_TOOL_OPTIONS':'-XX:ActiveProcessorCount=4'}))
   result=parse(z.read(cls.replace('.','/')+'.class'));result['javapSha256']=sha(p);result['javapFile']=p.name
   blocks={};current=None
   for line in p.read_text().splitlines():
    if re.match(r'^  \S.*\);$',line):current=line.strip();blocks[current]=[]
    elif current:blocks[current].append(line)
   result['methodInvocationEvidence']={}
   for header,lines in blocks.items():
    if any(name in header for name in ['load(', 'validatePersistentIndex(', 'updateInts(', 'updateLongs(', 'updateLongs$default(', 'updateInts$default(']):
     calls=[line.strip() for line in lines if re.search(r'\binvoke\w+\b',line)]
     result['methodInvocationEvidence'][header]={'callbackInvoke':[line for line in calls if 'kotlin/jvm/functions/Function1.invoke' in line],'boxedValueOf':[line for line in calls if 'java/lang/Integer.valueOf' in line or 'java/lang/Long.valueOf' in line],'validatorUpdateCalls':[line for line in calls if 'PersistentIndexViewValidator.updateInts' in line or 'PersistentIndexViewValidator.updateLongs' in line],'allInvocations':calls}
   results[side][cls]=result
out={'jars':{s:{'path':str(p),'sha256':sha(p)} for s,p in jars.items()},'classes':results,'scope':'Code attribute code_length is exact byte length, not source lines or class-file bytes. Invocation counts are static bytecode sites, not dynamic runtime calls. No C2 huge-method threshold or native JIT code-size claim is inferred.'}
(R/'bytecode-receipt.json').write_text(json.dumps(out,indent=2)+'\n')
for side,cs in results.items():
 print(side)
 for cls,c in cs.items():
  print(cls,[(m['name'],m.get('codeLengthBytes')) for m in c['methods'] if m['name'] in ['load','validatePersistentIndex','updateInts','updateLongs','updateInts$default','updateLongs$default']])
  for header,ev in c['methodInvocationEvidence'].items():
   print(header,'callbackInvoke',len(ev['callbackInvoke']),'boxedValueOf',len(ev['boxedValueOf']),'validatorUpdateCalls',len(ev['validatorUpdateCalls']))
