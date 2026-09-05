"""Read-only hypothesis/history screen; no subprocesses, Java, build or measurement."""
from pathlib import Path
from collections import Counter
from datetime import datetime,timezone
from decimal import Decimal
import gzip,hashlib,json,re,struct,xml.etree.ElementTree as ET,zipfile
P=Path(__file__).resolve().parent
W=Path('/Users/johnsonlee/.codex/worktrees/ac7b5da2-2450-48c5-894c-5fd84ab6cb7d/graphite')
CAP=Path('/private/tmp/graphite-query-marker-capture')
JAR=Path('/private/tmp/graphite-next-baseline.T2FTs9/graphite-webgraph/build/libs/webgraph-1.0.0-SNAPSHOT-jmh.jar')
OWNER='io/johnsonlee/graphite/webgraph/MappedWebGraphBackedGraph'
NODE='parallelRawDistinctCallSiteStringProjection$lambda$32$lambda$31$lambda$30'
WORKER='parallelRawDistinctCallSiteStringProjection$lambda$32$lambda$31'
TARGET='global-wide-wrapped-case-insensitive-distinct-targeted'
def sha(b):return hashlib.sha256(b).hexdigest()
def load(p):return json.loads(Path(p).read_text())
def ns(s):
 m=re.fullmatch(r'(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)(?:\.(\d{1,9}))?([+-]\d\d:\d\d|Z)',s);assert m
 dt=datetime.fromisoformat(m[1]+m[3].replace('Z','+00:00'))
 return int((dt-datetime(1970,1,1,tzinfo=timezone.utc)).total_seconds())*10**9+int((m[2] or '').ljust(9,'0'))
def duration(s):return int(Decimal(s[2:-1])*10**9)
class Reader:
 def __init__(self,b):self.b=b;self.i=0
 def n(self,n):v=self.b[self.i:self.i+n];assert len(v)==n;self.i+=n;return v
 def u1(self):return self.n(1)[0]
 def u2(self):return int.from_bytes(self.n(2),'big')
 def u4(self):return int.from_bytes(self.n(4),'big')
assert sha(JAR.read_bytes())=='a5c2db2b0020798488916ec86902459d1044a7dcef606a73e00055883cdf5abe'
with zipfile.ZipFile(JAR) as z:
 b=z.read(OWNER+'.class');hidden=[n for n in z.namelist() if 'MappedWebGraphBackedGraph$$Lambda' in n]
assert not hidden
r=Reader(b);assert r.u4()==0xcafebabe;r.n(4);cp=[None]*r.u2();i=1
while i<len(cp):
 tag=r.u1()
 if tag==1:cp[i]=(tag,r.n(r.u2()).decode('utf8',errors='replace'))
 elif tag in [3,4]:cp[i]=(tag,r.n(4))
 elif tag in [5,6]:cp[i]=(tag,r.n(8));i+=1
 elif tag in [7,8,16,19,20]:cp[i]=(tag,r.u2())
 elif tag in [9,10,11,12,17,18]:cp[i]=(tag,r.u2(),r.u2())
 elif tag==15:cp[i]=(tag,r.u1(),r.u2())
 else:raise AssertionError(tag)
 i+=1
def utf(i):assert cp[i][0]==1;return cp[i][1]
def attrs(reader):
 a={}
 for _ in range(reader.u2()):name=utf(reader.u2());a[name]=reader.n(reader.u4())
 return a
r.n(6);r.n(2*r.u2())
for _ in range(r.u2()):r.n(6);attrs(r)
methods={}
for _ in range(r.u2()):r.u2();name=utf(r.u2());desc=utf(r.u2());methods[(name,desc)]=attrs(r)
ca=attrs(r);assert r.i==len(b)
def member(i):
 x=cp[i];assert x[0] in [9,10,11];nt=cp[x[2]];assert nt[0]==12
 return {'owner':utf(cp[x[1]][1]),'name':utf(nt[1]),'descriptor':utf(nt[2])}
def args(desc):
 result=[];i=1
 while desc[i]!=')':
  start=i
  while desc[i]=='[':i+=1
  if desc[i]=='L':i=desc.index(';',i)+1
  else:i+=1
  result.append(desc[start:i])
 return result
nk=[k for k in methods if k[0]==NODE];wk=[k for k in methods if k[0]==WORKER];assert len(nk)==len(wk)==1
ncode=Reader(methods[nk[0]]['Code']);ncode.n(4);node_code=ncode.n(ncode.u4());ncode.n(8*ncode.u2());nattrs=attrs(ncode)
lvt=Reader(nattrs['LocalVariableTable']);params=[]
for _ in range(lvt.u2()):
 start,length,nameidx,descidx,index=[lvt.u2() for _ in range(5)]
 if start==0 and length==790:params.append({'slot':index,'name':utf(nameidx),'descriptor':utf(descidx)})
params.sort(key=lambda x:x['slot']);assert len(params)==15 and params[-1]['name']=='nodeId'
wcode=Reader(methods[wk[0]]['Code']);wcode.n(4);worker_code=wcode.n(wcode.u4())
assert len(node_code)==790 and worker_code[108]==186
indy_index=int.from_bytes(worker_code[109:111],'big');indy=cp[indy_index];assert indy[0]==18
nt=cp[indy[2]];capture_desc=utf(nt[2]);captures=args(capture_desc)
assert len(captures)==14 and args(nk[0][1])==captures+['I']
boot=Reader(ca['BootstrapMethods']);boots=[]
for _ in range(boot.u2()):ref=boot.u2();a=[boot.u2() for _ in range(boot.u2())];boots.append((ref,a))
handle,bootstrap_args=boots[indy[1]];bm=member(cp[handle][2]);assert bm['owner']=='java/lang/invoke/LambdaMetafactory'
impl=cp[bootstrap_args[1]];assert impl[0]==15 and impl[1]==6
implementation=member(impl[2]);assert implementation['name']==NODE and implementation['descriptor']==nk[0][1]
bytecode={'jarSha256':sha(JAR.read_bytes()),'mappedClassSha256':sha(b),'nodeCodeBytes':790,'staticParameterCount':15,'capturedValueCount':14,'parameters':params,'workerCodeBytes':len(worker_code),'lambdaFactoryBci':108,'invokeDynamicCpIndex':indy_index,'captureDescriptor':capture_desc,'bootstrap':bm,'implementationHandle':implementation,'hiddenClassPresentInFrozenJar':False,'hiddenWrapperCodeBytesEvidence':'Runtime XML method bytes=61; XML parser BCI57 invokestatic and actual native stack BCI57. No dumped hidden class is available, so no claim of independent full wrapper class bytes.','wrapperCallBci':57}

catalog=load(CAP/'original-catalog.json');qordinal=next(q['ordinal'] for q in catalog if q['id']==TARGET)
recordings=[]
for number in [1,2,3]:
 name=f'profile-{number}';path=CAP/(name+'.compilation.xml');raw=path.read_bytes() if path.exists() else gzip.decompress(Path(str(path)+'.gz').read_bytes());xml=ET.fromstring(raw)
 tty=list(xml.find('tty'));nmethods={e.get('compile_id'):dict(e.attrib) for e in tty if e.tag=='nmethod'}
 tasks=xml.findall('compilation_log/task');calls=[];wrappers=set()
 for task in tasks:
  klasses={x.get('id'):x.get('name') for x in task.iter('klass')}
  ms={x.get('id'):x for x in task.iter('method')}
  def resolved(mid):
   m=ms[mid];return {'owner':klasses.get(m.get('holder')),'name':m.get('name'),'bytes':m.get('bytes'),'arguments':m.get('arguments')}
  for parent in task.iter():
   children=list(parent);current_bci=None
   for j,x in enumerate(children):
    if x.tag=='bc':current_bci=dict(x.attrib)
    if x.tag!='call' or x.get('method') not in ms:continue
    callee=resolved(x.get('method'))
    if not (callee['name']==NODE or callee['name']=='test' and (callee['owner']=='java.util.function.IntPredicate' or 'MappedWebGraphBackedGraph$$Lambda' in (callee['owner'] or ''))):continue
    outcomes=[]
    for y in children[j+1:]:
     if y.tag in ['call','bc','parse']:break
     if y.tag in ['inline_fail','inline_success','direct_call']:outcomes.append({'tag':y.tag,**y.attrib})
    entry={'task':dict(task.attrib),'publishedNmethod':nmethods.get(task.get('compile_id')),'call':dict(x.attrib),'callee':callee,'bytecode':current_bci,'outcomes':outcomes}
    calls.append(entry)
    if callee['name']==NODE and 'MappedWebGraphBackedGraph$$Lambda' in task.get('method',''):
     wrappers.add(task.get('method').split(' test ')[0]);assert task.get('bytes')=='61' and current_bci=={'code':'184','bci':'57'}
     assert len(callee['arguments'].split())==15
 assert len(wrappers)==1
 node_calls=[x for x in calls if x['callee']['name']==NODE and 'MappedWebGraphBackedGraph$$Lambda' in x['task']['method']]
 assert node_calls and all(any(o['tag']=='inline_fail' and o['reason'] in ['callee is too large','hot method too big'] for o in c['outcomes']) for c in node_calls)
 index_calls=[x for x in calls if x['task']['method'].startswith('io.johnsonlee.graphite.webgraph.MappedNodeTypeIndex forEachIdWhile ') and x['callee']['name']=='test']
 assert len(index_calls)==2 and all(c['publishedNmethod']['compiler']=='c1' and any(o.get('reason')=='no static binding' for o in c['outcomes']) for c in index_calls)
 prefix=next(iter(wrappers)).split('/0x')[0].replace('.','/')
 events=load(CAP/(name+'.events.json'))['recording']['events'];marker=next(e['values'] for e in events if e['type']=='graphite.diagnostic.QueryExecution' and e['values']['ordinal']==qordinal)
 start,end=ns(marker['startTime']),ns(marker['startTime'])+duration(marker['duration'])
 samples=[];wrapper_leaf=[];target_all=0;target_app=0;node_types=Counter();wrapper_types=Counter();target_wrappers=0;compilations=[]
 for idx,event in enumerate(events):
  v=event['values'];time=ns(v['startTime']);inside=start<=time<end
  if event['type']=='jdk.Compilation':
   m=v['method'];owner=m['type']['name'];method_name=m['name']
   if method_name==NODE and owner==OWNER or method_name=='test' and owner.replace('.', '/').startswith(prefix):
    compilations.append({'eventIndex':idx,'compileId':v['compileId'],'compiler':v['compiler'],'level':v['compileLevel'],'method':owner+'.'+method_name,'startNanos':time,'endNanos':time+duration(v['duration']),'endRelativeToTargetStartNanos':time+duration(v['duration'])-start,'targetDurationNanos':end-start,'completedWithinTarget':start<=time+duration(v['duration'])<=end,'succeeded':v['succeded']})
  if event['type']!='jdk.ExecutionSample':continue
  thread=v['sampledThread']['javaName'];application=thread=='broad-query-pressure-worker' or thread.startswith(('graphite-cypher-scan-','graphite-callsite-segment-'))
  frames=(v.get('stackTrace') or {}).get('frames',[])
  if inside:target_all+=1;target_app+=application
  nodes=[f for f in frames if f['method']['name']==NODE and f['method']['type']['name']==OWNER]
  ws=[f for f in frames if f['method']['name']=='test' and f['method']['type']['name'].replace('.', '/').startswith(prefix)]
  if frames and ws and frames[0] is ws[0]:wrapper_leaf.append({'eventIndex':idx,'insideTargeted':inside,'bci':ws[0]['bytecodeIndex'],'type':ws[0]['type'],'thread':thread})
  if inside and nodes:
   assert len(nodes)==1 and len(ws)==1 and ws[0]['bytecodeIndex']==57,(name,idx,len(nodes),len(ws),prefix,[(f['method']['type']['name'],f['method']['name']) for f in frames[:5]])
   node_types[nodes[0]['type']]+=1;wrapper_types[ws[0]['type']]+=1;target_wrappers+=1
   samples.append({'eventIndex':idx,'thread':thread,'application':application,'nodeFrameType':nodes[0]['type'],'nodeBci':nodes[0]['bytecodeIndex'],'wrapperFrameType':ws[0]['type'],'wrapperBci':57,'wrapperLeaf':frames[0] is ws[0]})
 assert target_wrappers==len(samples)
 recordings.append({'name':name,'xmlSha256':sha(raw),'nativeEventsSha256':sha((CAP/(name+'.events.json')).read_bytes()),'wrapperRuntimeName':list(wrappers)[0],'wrapperPublishedNmethods':[e for e in nmethods.values() if e.get('method','').startswith(list(wrappers)[0]+' test ')],'wrapperQueuedTasks':[e.attrib for e in tty if e.tag=='task_queued' and e.get('method','').startswith(list(wrappers)[0]+' test ')],'wrapperToNodeCalls':node_calls,'indexToIntPredicateCalls':index_calls,'otherRelevantCalls': [c for c in calls if c not in node_calls and c not in index_calls],'nativeCompilationEvents':compilations,'targetedAllJavaSnapshots':target_all,'targetedApplicationJavaSnapshots':target_app,'targetedNodeSnapshots':len(samples),'targetedNodeFrameTypes':dict(node_types),'targetedWrapperFrameTypes':dict(wrapper_types),'targetedSamples':samples,'wholeRecordingWrapperLeafSamples':wrapper_leaf})
assert sum(r['targetedApplicationJavaSnapshots'] for r in recordings)==87
assert sum(r['targetedNodeSnapshots'] for r in recordings)==78
assert Counter({k:sum(r['targetedWrapperFrameTypes'].get(k,0) for r in recordings) for k in ['JIT compiled','Interpreted']})=={'JIT compiled':70,'Interpreted':8}
logs=list((W/'docs').glob('*optimization-attempts.md'));assert len(logs)==3
history=[];related=[]
for path in sorted(logs):
 lines=path.read_text().splitlines();heading='';attempt=None
 for i,line in enumerate(lines,1):
  if line.startswith('### '):heading=line;match=re.search(r'Attempt (\d+)',line);attempt=int(match[1]) if match else None
  if re.search(r'IntPredicate|predicate object|state object|named (?:class|predicate)|captur|forwarding|lambda|inline',line,re.I):
   history.append({'path':str(path.relative_to(W)),'line':i,'attempt':attempt,'heading':heading,'text':line})
  if path.name=='wrapped-case-insensitive-query-optimization-attempts.md' and line.startswith('### ') and attempt in [6,63,112,130,133,134,135,136,137,138,139,140]:related.append({'attempt':attempt,'path':str(path.relative_to(W)),'line':i,'heading':heading})
source_paths=['graphite-webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/MappedWebGraphBackedGraph.kt','graphite-webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/NodeTypeIndex.kt','graphite-webgraph/src/test/kotlin/io/johnsonlee/graphite/webgraph/ParallelDistinctDisjunctionTest.kt']
out={'scope':'Read-only next-hypothesis screen; not Attempt141, no candidate or measurement','decision':'Distinct and not ruled out by inlining: retain only as a low-confidence single falsifiable mechanism hypothesis. Existing evidence does not establish material exclusive forwarding cost or a 10x opportunity. Any later test must first prove exact semantic scope and emitted shape; no test is initiated here.','correctedCounts':{'wrapperCodeBytes':61,'wrapperCallBci':57,'staticCallbackParameters':15,'captures':14,'nodeIdArguments':1},'bytecode':bytecode,'recordings':recordings,'historySearch':{'files':{str(p.relative_to(W)):sha(p.read_bytes()) for p in logs},'matches':history,'relatedAttempts':related,'exactNamedRawDistinctPredicateObjectAttemptFound':False,'boundedAbsenceClaim':'Only the three chronological logs and current source were searched; not a claim about every historical unpublished change.'},'sourceSha256':{p:sha((W/p).read_bytes()) for p in source_paths},'limits':['The hidden lambda class is runtime-generated and was not dumped. Original class bootstrap/descriptor and runtime XML/JFR identities are checked, not a full hidden-class bytecode dump.','Inlining failures refer to these completed compilation tasks. They do not prove every invocation executes a separate call or quantify interpreter/C1 overhead.','A lower wrapper frame and BCI57 identify a suspended caller, not exclusive CPU or argument-copy cost. Whole-recording wrapper leaf counts are only 0/1/1.','Native ExecutionSample is a periodic Java snapshot, not process CPU; JIT compiled labels do not resolve C1 versus C2.','Moving a body into an instance method may replace cheap local loads with repeated getfield/synthetic accessor operations and change JIT profile/compilation behavior; no savings are established.','No while traversal, IntArray indexes, postings, caching, primitive matcher, work accounting or pool change belongs to the proposed scope. All earlier rejections remain final.']}
(P/'audit.json').write_text(json.dumps(out,indent=2,ensure_ascii=False)+'\n')
print(json.dumps({'captures':14,'parameters':15,'wrapperBytes':61,'targeted':[[r['name'],r['targetedNodeSnapshots'],r['targetedApplicationJavaSnapshots'],r['targetedWrapperFrameTypes']] for r in recordings],'historyMatches':len(history)},ensure_ascii=False))
