from pathlib import Path
import struct,json,zipfile,subprocess,hashlib,collections,re,os,difflib
R=Path(__file__).resolve().parent
jars={'base':Path('/private/tmp/graphite-next-baseline.T2FTs9/graphite-webgraph/build/libs/webgraph-1.0.0-SNAPSHOT-jmh.jar'),'candidate':R/'candidate-jmh.jar'}
cls='io.johnsonlee.graphite.webgraph.MappedWebGraphBackedGraph';raw='parallelRawDistinctCallSiteStringProjection'
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def parse(data):
 pos=0
 def read(n):
  nonlocal pos
  value=data[pos:pos+n];assert len(value)==n;pos+=n;return value
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
results={};nodes={};javap='/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin/javap'
for side,jar in jars.items():
 assert jar.exists()
 cmd=[javap,'-classpath',str(jar),'-c','-l','-p',cls];p=R/(side+'-MappedWebGraphBackedGraph-javap.txt')
 assert not p.exists()
 (R/(side+'-javap-command.json')).write_text(json.dumps(cmd,indent=2)+'\n')
 p.write_bytes(subprocess.check_output(cmd,stderr=subprocess.STDOUT,env={**os.environ,'JAVA_TOOL_OPTIONS':'-XX:ActiveProcessorCount=4'}))
 with zipfile.ZipFile(jar) as z:data=z.read(cls.replace('.','/')+'.class')
 result=parse(data);result['classSha256']=hashlib.sha256(data).hexdigest();result['javapSha256']=sha(p)
 blocks={};current=None
 for line in p.read_text().splitlines():
  if re.match(r'^  \S.*\);$',line):current=line.strip();blocks[current]=[]
  elif current:blocks[current].append(line)
 header=next(h for h in blocks if h.startswith('private static final boolean '+raw+'$') and 'Ref$IntRef' in h)
 text='\n'.join(blocks[header]);code=text.split('    LineNumberTable:')[0]
 instructions=[(int(i),s.strip()) for i,s in re.findall(r'^\s+(\d+): (.+)$',code,re.M)]
 methodname=header.split(' boolean ',1)[1].split('(',1)[0]
 meta=next(m for m in result['methods'] if m['name']==methodname)
 table=[(int(b),int(l)) for l,b in re.findall(r'line (\d+): (\d+)',text.split('    LineNumberTable:',1)[1].split('    LocalVariableTable:')[0])]
 predicate=[]
 for b,ins in instructions:
  latest=max(start for start,line in table if start<=b);lines=[line for start,line in table if start==latest]
  if 580 in lines:predicate.append({'bci':b,'instruction':ins})
 calls={k:[{'bci':b,'instruction':s} for b,s in instructions if needle in s] for k,needle in {'listGet':'java/util/List.get:', 'numberIntValue':'java/lang/Number.intValue:', 'integerValueOf':'java/lang/Integer.valueOf:', 'exactSetContains':'IntOpenHashSet.contains:', 'getIndices':'CollectionsKt.getIndices:', 'iteratorNextInt':'IntIterator.nextInt:'}.items()}
 node={'header':header,'metadata':meta,'instructionCount':len(instructions),'primitiveIntArrayLoadCount':sum(s=='iaload' for b,s in instructions),'predicateSourceLine580':predicate,'staticInstructionSites':calls}
 result['rawFamilyMethods']=[m for m in result['methods'] if m['name'].startswith(raw)];result['nodeCallback']=node;results[side]=result;nodes[side]=instructions
 (R/(side+'-per-node-javap.txt')).write_text(header+'\n'+text+'\n')
 def norm(s):
  s=re.sub(r'#\d+','#CP',s);s=re.sub(r'\$lambda\$\d+','$lambda$N',s)
  s=re.sub(r'^(if\w*|goto)\s+-?\d+',r'\1 <target>',s)
  return ' '.join(s.split())
 (R/(side+'-per-node-normalized.txt')).write_text('\n'.join(norm(s) for b,s in instructions)+'\n')
base=results['base']['nodeCallback'];cand=results['candidate']['nodeCallback']
assert base['metadata']['descriptor'].count('[I')+1==cand['metadata']['descriptor'].count('[I')
assert 'java/util/List.get:' in ' '.join(i['instruction'] for i in base['predicateSourceLine580'])
assert 'java/lang/Number.intValue:' in ' '.join(i['instruction'] for i in base['predicateSourceLine580'])
assert not any('invoke' in i['instruction'] for i in cand['predicateSourceLine580'])
assert sum(i['instruction']=='iaload' for i in cand['predicateSourceLine580'])==2
assert len(base['staticInstructionSites']['listGet'])-1==len(cand['staticInstructionSites']['listGet'])
assert len(base['staticInstructionSites']['numberIntValue'])-1==len(cand['staticInstructionSites']['numberIntValue'])
for key in ('integerValueOf','exactSetContains','getIndices','iteratorNextInt'):assert len(base['staticInstructionSites'][key])==len(cand['staticInstructionSites'][key])
assert cand['staticInstructionSites']['listGet'] and cand['staticInstructionSites']['numberIntValue'] and cand['staticInstructionSites']['integerValueOf']
normaldiff=''.join(difflib.unified_diff((R/'base-per-node-normalized.txt').read_text().splitlines(True),(R/'candidate-per-node-normalized.txt').read_text().splitlines(True),fromfile='base-node',tofile='candidate-node'))
(R/'node-instruction.diff').write_text(normaldiff)
out={'attempt':140,'jars':{s:{'path':str(p),'sha256':sha(p)} for s,p in jars.items()},'class':cls,'results':results,'verifiedMechanism':'Only the predicatePropertyIndexes captured argument changes from List to int[]; source-line580 List.get + Number cast/intValue becomes int-array load. Remaining list/projection/selected boxing operations retained; static site counts are not runtime allocation or performance measurements.','nodeCodeBytes':{s:r['nodeCallback']['metadata']['codeLengthBytes'] for s,r in results.items()},'nodeInstructionCounts':{s:r['nodeCallback']['instructionCount'] for s,r in results.items()},'normalization':'For readable diff only: constant-pool numbers and generated lambda number suffixes normalized, branch absolute BCI targets omitted. Raw disassembly and actual class Code lengths retained.','performanceRun':False}
(R/'bytecode-receipt.json').write_text(json.dumps(out,indent=2)+'\n')
print('CODE LENGTHS',out['nodeCodeBytes']);print('INSTRUCTION COUNTS',out['nodeInstructionCounts']);print(normaldiff)
for side,r in results.items():print(side,r['nodeCallback']['header'],{k:len(v) for k,v in r['nodeCallback']['staticInstructionSites'].items()},[(m['name'],m['codeLengthBytes']) for m in r['rawFamilyMethods']])
