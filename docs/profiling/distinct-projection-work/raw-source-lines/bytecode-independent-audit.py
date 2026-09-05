from pathlib import Path
from collections import Counter
import json,struct,hashlib,zipfile,re
P=Path(__file__).resolve().parent
R=json.loads((P/'bytecode-receipt.json').read_text());M=json.loads((P/'source-mapping.json').read_text());S=json.loads((P/'summary.json').read_text())
def sha(b):return hashlib.sha256(b).hexdigest()
def filesha(p):
 h=hashlib.sha256()
 with p.open('rb') as f:
  for b in iter(lambda:f.read(1024*1024),b''):h.update(b)
 return h.hexdigest()
class Reader:
 def __init__(self,b):self.b=b;self.i=0
 def n(self,n):a=self.b[self.i:self.i+n];assert len(a)==n;self.i+=n;return a
 def u1(self):return self.n(1)[0]
 def u2(self):return int.from_bytes(self.n(2),'big')
 def u4(self):return int.from_bytes(self.n(4),'big')
jar=Path(R['jar']);assert filesha(jar)==R['jarSha256']
prior=json.loads(Path('/private/tmp/graphite-distinct-phase-profiling.by0z0asb/input-receipt.json').read_text());assert prior['jar']==str(jar) and prior['jarSha256']==R['jarSha256']
with zipfile.ZipFile(jar) as z: b=z.read(R['classEntry'])
assert sha(b)==R['classSha256']==M['classSha256'] and b==(P/'MappedWebGraphBackedGraph.class').read_bytes()
f=Reader(b);assert f.u4()==0xcafebabe;minor=f.u2();major=f.u2();n=f.u2();cp=[None]*n;i=1
while i<n:
 tag=f.u1()
 if tag==1:cp[i]=(tag,f.n(f.u2()).decode('utf-8',errors='replace'))
 elif tag in (3,4):cp[i]=(tag,f.n(4))
 elif tag in (5,6):cp[i]=(tag,f.n(8));i+=1
 elif tag in (7,8,16,19,20):cp[i]=(tag,f.u2())
 elif tag in (9,10,11,12,17,18):cp[i]=(tag,f.u2(),f.u2())
 elif tag==15:cp[i]=(tag,f.u1(),f.u2())
 else:raise AssertionError(tag)
 i+=1
def utf(i):assert cp[i][0]==1;return cp[i][1]
def classname(i):assert cp[i][0]==7;return utf(cp[i][1])
def attrs(r):
 out=[]
 for _ in range(r.u2()):name=utf(r.u2());value=r.n(r.u4());out.append((name,value))
 return out
flags=f.u2();this=f.u2();sup=f.u2();owner=classname(this);f.n(2*f.u2())
for _ in range(f.u2()):f.n(6);attrs(f)
methods=[]
for _ in range(f.u2()):
 flags=f.u2();name=utf(f.u2());desc=utf(f.u2());a=attrs(f);methods.append((name,desc,a))
a=attrs(f);assert f.i==len(b)
smap=dict(a)['SourceDebugExtension'].decode();assert smap.strip()=='\n'.join(x[2:] if x.startswith('  ') else x for x in (P/'class-smap.txt').read_text().splitlines()).strip()
name='parallelRawDistinctCallSiteStringProjection$lambda$32$lambda$31$lambda$30'
selected=[x for x in methods if x[0]==name];assert len(selected)==1
_,desc,ma=selected[0];c=Reader(dict(ma)['Code']);stack=c.u2();locals_=c.u2();code=c.n(c.u4());c.n(8*c.u2());ca=attrs(c);assert c.i==len(c.b)
lt=Reader(dict(ca)['LineNumberTable']);table=[(lt.u2(),lt.u2()) for _ in range(lt.u2())];assert lt.i==len(lt.b)
assert [line for pos,line in table if pos==121]==[3637,3638]
# Decode instruction boundaries independently from Code bytes, not javap offsets.
length={**{x:2 for x in [16,18,21,22,23,24,25,54,55,56,57,58,169,188]},**{x:3 for x in [17,19,20,132,*range(153,169),178,179,180,181,182,183,184,187,189,192,193,198,199]},185:5,186:5,197:4,200:5,201:5}
positions=[];at=0
while at<len(code):
 positions.append(at);op=code[at]
 if op==196:step=6 if code[at+1]==132 else 4
 elif op in (170,171):
  start=at+1+(-(at+1)%4)
  if op==170:
   lo,hi=struct.unpack('>ii',code[start+4:start+12]);step=start-at+12+4*(hi-lo+1)
  else:step=start-at+8+8*int.from_bytes(code[start+4:start+8],'big')
 else:step=length.get(op,1)
 at+=step
assert at==len(code)
excerpt=(P/'per-node-javap.txt').read_text();assert excerpt.strip() in (P/'mapped-javap.txt').read_text();assert sha(excerpt.encode())==M['javapExcerptSha256'];assert filesha(P/'class-smap.txt')==M['smapSha256']
for cmd in R['commands']:assert filesha(P/cmd['output'])==cmd['sha256']
textcode,texttables=excerpt.split('    LineNumberTable:',1)
instructions={int(x):y.strip() for x,y in re.findall(r'^\s+(\d+): (.+)$',textcode,re.M)}
assert set(instructions)==set(positions)
assert [(int(pc),int(line)) for line,pc in re.findall(r'line (\d+): (\d+)',texttables.split('    LocalVariableTable:')[0])]==table
mnemonics={42:'aload_0',46:'iaload',79:'iastore',153:'ifeq',167:'goto',182:'invokevirtual',184:'invokestatic',185:'invokeinterface',192:'checkcast',193:'instanceof',198:'ifnull'}
def check_instruction(pc,text):
 op=code[pc];assert text.split()[0]==mnemonics[op],(pc,op,text)
 if op in (153,167,198):assert int(text.split()[1])==pc+int.from_bytes(code[pc+1:pc+3],'big',signed=True)
 if op in (182,184,185,192,193):
  idx=int.from_bytes(code[pc+1:pc+3],'big');assert int(re.search(r'#(\d+)',text).group(1))==idx
  if op in (192,193):assert text.split('// class ',1)[1]==classname(idx)
  else:
   item=cp[idx];assert item[0] in (10,11);nt=cp[item[2]];assert nt[0]==12
   symbol=classname(item[1])+'.'+utf(nt[1])+':'+utf(nt[2]);comment=text.split('// ',1)[1].split(' ',1)[1].replace('"','')
   assert comment==symbol or classname(item[1])==owner and comment==symbol[len(owner)+1:]
   if op==185:assert int(re.search(r'#\d+,\s+(\d+)',text).group(1))==code[pc+3] and code[pc+4]==0
# Parse all SMAP strata/file maps/range increments generically from the binary class attribute.
strata={};current=None;mode=None;ls=smap.splitlines();i=3
while i<len(ls):
 line=ls[i];i+=1
 if line.startswith('*S '):current=line[3:];strata[current]={'files':{},'maps':[]};mode=None
 elif line=='*F':mode='files'
 elif line=='*L':mode='lines'
 elif line.startswith('*'):mode=None
 elif mode=='files':
  if line.startswith('+ '):fid,name_=line[2:].split(' ',1);path=ls[i];i+=1
  else:fid,name_=line.split(' ',1);path=None
  strata[current]['files'][int(fid)]=(name_,path)
 elif mode=='lines':
  m=re.fullmatch(r'(\d+)(?:#(\d+))?(?:,(\d+))?:(\d+)(?:,(\d+))?',line);assert m,line
  ins,fid,repeat,outs,inc=m.groups();strata[current]['maps'].append((int(ins),int(fid or 1),int(repeat or 1),int(outs),int(inc or 1)))
def resolve(line,stratum):
 matches=[]
 for ins,fid,repeat,outs,inc in strata[stratum]['maps']:
  if outs<=line<outs+repeat*inc:matches.append((strata[stratum]['files'][fid][0],ins+(line-outs)//inc))
 assert len(matches)<=1,(line,stratum,matches)
 return matches[0] if matches else None
# Semantically inspected half-open instruction regions. Broad regions include guards/control.
regions=[(0,65,'interrupt'),(65,72,'accounting'),(72,176,'raw_address_read'),(176,200,'scratch_write'),(200,277,'range_or_control'),(277,297,'predicate_index_read'),(297,328,'exact_set_check'),(328,455,'fallback_match'),(455,473,'range_or_control'),(473,608,'selected_tuple'),(608,774,'visible_tuple'),(774,len(code),'limit_check')]
assert regions[0][0]==0 and all(x[1]==y[0] for x,y in zip(regions,regions[1:]))
rows=[];total=0;aggregate={};all_dist=Counter();duplicates=[]
for r in M['rows']:
 source=next(s for s in S['rows'] if (s['phase'],s['id'],s['stage'])==(r['phase'],r['id'],r['stage']))
 source_dist=Counter({(f['method'],f['lineNumber'],f['bytecodeIndex'],f['frameType']):f['samples'] for f in source['nodeLeafDistribution']})
 actual_dist=Counter();groups=Counter();types=Counter();locations=Counter()
 for f in r['mappedLeafFrames']:
  pc=f['bytecodeIndex'];line=f['lineNumber'];weight=f['samples'];assert f['method']==owner.replace('/','.')+'.'+name+desc
  assert pc in positions;check_instruction(pc,f['instruction']);assert f['instruction']==instructions[pc]
  latest=max(pos for pos,_ in table if pos<=pc);candidates=[ln for pos,ln in table if pos==latest]
  assert f['lineTableCandidates']==candidates and line in candidates
  if len(candidates)>1:duplicates.append({'recording':r['phase'],'query':r['id'],'bci':pc,'recordedLine':line,'candidates':candidates,'samples':weight})
  base=resolve(line,'Kotlin');debug=resolve(line,'KotlinDebug');assert base
  expected={'file':base[0],'line':base[1],'inlineCallSiteLine':debug[1] if debug else None};assert expected==f['sourceLocation'],(line,expected,f)
  region=next(label for a,b,label in regions if a<=pc<b);assert region==f['bytecodeRegion']
  groups[region]+=weight;types[f['frameType']]+=weight;locations[(expected['file'],expected['line'],expected['inlineCallSiteLine'])]+=weight
  actual_dist[(f['method'],line,pc,f['frameType'])]+=weight
 assert actual_dist==source_dist
 assert dict(groups)==r['bytecodeRegionSampleCounts'] and dict(types)==r['frameTypes'] and sum(groups.values())==r['nodeLeafSamples']
 assert Counter({(v['file'],v['line'],v['inlineCallSiteLine']):v['samples'] for v in r['sourceSampleCounts']})==locations
 category='targeted' if r['id'].endswith('targeted') else 'denseProvenance' if r['stage']=='provenance' else 'denseInitial'
 ag=aggregate.setdefault(category,{'regions':Counter(),'types':Counter(),'samples':0});ag['regions'].update(groups);ag['types'].update(types);ag['samples']+=sum(groups.values());total+=sum(groups.values())
 rows.append({'recording':r['phase'],'query':r['id'],'stage':r['stage'],'samples':r['nodeLeafSamples'],'allInstructionsLineCandidatesSmapAndCountsVerified':True})
assert total==148 and aggregate['targeted']['samples']==98 and aggregate['denseProvenance']['samples']==43 and aggregate['denseInitial']['samples']==7
assert aggregate['targeted']['types']=={'Interpreted':63,'C1 compiled':35} and aggregate['denseProvenance']['types']=={'Interpreted':3,'C1 compiled':40}
result={'result':'pass','newJavaOrPerformanceRun':False,'ranParentMappingScript':False,'method':'Read frozen JAR via zip; independently parse binary constant pool, Code, instruction boundaries, LineNumberTable and SourceDebugExtension; generic SMAP strata decoder; verify all 148 sampled BCI opcodes/operands and constant-pool target names, preserve duplicate line starts; recompute all regions/types/locations.','jar':str(jar),'jarSha256':R['jarSha256'],'classSha256':sha(b),'classVersion':{'major':major,'minor':minor},'classMatchesActualJfrInputReceipt':True,'methodCodeBytes':len(code),'methodInstructionCount':len(positions),'methodLineTableEntries':len(table),'totalNodeLeafSamples':total,'aggregate':aggregate,'duplicateLineCandidateSamples':duplicates,'rows':rows,'sourceMappingSha256':filesha(P/'source-mapping.json'),'parentMappingScriptSha256':filesha(P/'map-bytecode.py'),'readmeSha256':filesha(P/'README.md'),'limits':['No second JFR decoder; frame-event identity uses independently audited export/partition from prior receipt.','BCI identifies recorded bytecode position, not machine-PC stalls, exclusive instruction CPU time or savings.','Region names are broad semantic spans including guards and loops; selected_tuple is not a count of actual constructed tuples.','Kotlin line metadata can describe inline source/call sites and duplicate start PCs; all candidates retained.','Compiled-frame labels refer to these instrumented recordings only, not unprofiled gate state.']}
(P/'bytecode-independent-audit.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n');print('PASS',total,'samples',len(code),'byte method',aggregate,'duplicate candidates',duplicates)
