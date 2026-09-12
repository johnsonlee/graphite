from pathlib import Path
import json,csv,hashlib,collections,base64,datetime,itertools
R=Path(__file__).parent;D=R/'cold-regression-profile';P=Path('/private/tmp/graphite-main-profiling-n50joikp')
def load(p):return json.loads(p.read_text())
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def rows(p):return list(csv.DictReader(p.open(),delimiter='\t'))
def ns(s):
 a,b=s.removesuffix('Z').split('.');return int(datetime.datetime.fromisoformat(a).replace(tzinfo=datetime.timezone.utc).timestamp())*10**9+int(b.ljust(9,'0'))
def threadgroup(t):
 if t.startswith(('broad-query-pressure-worker ','graphite-cypher-scan-','graphite-callsite-segment-','graphite-callsite-scan-')):return 'application'
 if 'CompilerThread' in t:return 'jit'
 if t.startswith(('Java: GC Thread','Java: G1 ')):return 'gc'
 return 'otherBackground'
summary=load(D/'summary.json');patterns=summary['methodPatterns'];receipt=load(D/'input-receipt.json');complete=load(D/'completed.json');assert complete['status']=='complete' and complete['graphFilesUnchanged'] and complete['jarHashesUnchanged']
c2=load(P/'oracle-v2/catalog.json');c3=load(P/'oracle-v3/catalog.json');catalog={q['id']:q for q in c3['queries'] if q['logicalId']=='or-four-broad'}
assert len(catalog)==2
for q in c2['queries']:
 if q['logicalId']=='or-four-broad':assert q==catalog[q['id']]
assert sha(P/'oracle-v2/catalog.json')==receipt['catalogSha256']
wpath=P/'four-or/profile-workloads.tsv';ws=rows(wpath);assert len(ws)==80 and sha(wpath)==receipt['workloadSha256']
expectedids=[f'or-four-broad-{kind}-{i:02d}' for i in range(40) for kind in ['rows','distinct']];assert [w['id'] for w in ws]==expectedids
assert len(receipt['graphFiles'])==64 and len({g['id'] for g in receipt['graphFiles']})==64
out={'schema':'independent-rejected138-cold-profile-audit-v1','passed':True,'regressionCauseLocated':False,'rejectionRemainsFinal':True,'newMeasurements':False,'verifiedQueryCount':160,'verifiedFullRowCount':32000,'v2SelectedQueriesEqualV3':True,'recordings':{},'groups':[],'recordedInputJarHashes':receipt['jarHashes'],'inputHashes':{p.name:sha(p) for p in [D/'completed.json',D/'input-receipt.json',D/'summary.json',R/'analyze-cold-regression.py',R/'profile-cold-regression.py']}}
assert receipt['jarHashes']==load(R/'old34-pairs/local-progress.json')['jarHashes']
for side in ['base','candidate']:
 folder=D/(side+'-analysis');ap=folder/'analysis.json';analysis=load(ap);tsv=rows(D/(side+'.tsv'));actual=[json.loads(s) for s in (D/(side+'-rows.jsonl')).read_text().splitlines()]
 assert len(analysis['queries'])==len(tsv)==len(actual)==80
 assert [q['id'] for q in analysis['queries']]==[r['id'] for r in tsv]==[r['id'] for r in actual]==expectedids
 assert analysis['tsvSha256']==sha(D/(side+'.tsv')) and analysis['catalogSha256']==sha(wpath)
 assert analysis['jfr']==analysis['windowSource']==str(D/(side+'.jfr')) and analysis['windowSourceSha256']==analysis['jfrSha256']
 assert analysis['executionSampleMetric']=='cpuSamples'
 assert any(s['name']=='event' and s['value']=='cpu' for s in analysis['activeSettings'])
 assert analysis['validation']['passed'] and analysis['validation']['expectedCount']==80
 cmd=load(D/(side+'-command.json'));cp=cmd[cmd.index('-cp')+1];jar=str(R/'candidate-jmh.jar') if side=='candidate' else '/private/tmp/graphite-next-baseline.T2FTs9/graphite-webgraph/build/libs/webgraph-1.0.0-SNAPSHOT-jmh.jar'
 assert cp.endswith(':'+jar) and cmd[-1]=='per-query-cold' and '-XX:ActiveProcessorCount=4' in cmd
 assert any('event=cpu,interval=1ms' in arg and 'alloc=256k' in arg and 'file='+str(D/(side+'.jfr')) in arg for arg in cmd)
 assert cmd[-4]==str(wpath)
 analyzedcmd=load(D/(side+'-analysis-command.json'));assert '--expected-count' in analyzedcmd and analyzedcmd[analyzedcmd.index('--expected-count')+1]=='80'
 grouped={k:{'counters':collections.Counter(),'threads':collections.defaultdict(collections.Counter),'threadGroups':collections.defaultdict(collections.Counter),'leaves':collections.defaultdict(collections.Counter),'perQuery':[],'collapsedFiles':[]} for k in ['rows','distinct']};events=collections.Counter();previousEnd=None;gaps=[]
 for index,(q,o,a,w) in enumerate(zip(analysis['queries'],tsv,actual,ws),1):
  canonical=catalog[q['id'].rsplit('-',1)[0]];kind='distinct' if canonical['distinct'] else 'rows';g=grouped[kind]
  assert base64.b64decode(w['queryBase64']).decode()==canonical['query']
  assert a['rows']==canonical['expectedRows'] and a['columns']==['n.caller_class','n.caller_name','n.callee_class','n.callee_name']
  digest=hashlib.sha256(json.dumps(a['rows'],ensure_ascii=False,separators=(',',':')).encode()).hexdigest()
  queryhash=hashlib.sha256(canonical['query'].encode()).hexdigest()
  assert q['workloadIdentity']==o['workloadIdentity']==queryhash and q['digest']==o['digest']==digest
  assert q['rowCount']==int(o['rowCount'])==len(a['rows'])==200
  assert q['outcome']==o['outcome']=='success' and o['inputSourceCount']=='64' and o['resetMode']=='per-query-cold'
  assert o['hitGraphIds']==','.join(sorted({gid for row in a['rows'] for gid in row['graphIds']}))
  assert q['ordinal']==index and q['tsvLatencyNanos']==int(o['latencyNanos'])>0
  start,end=ns(q['start']),ns(q['end']);assert end>start and end-start==q['traceDurationNanos']
  if previousEnd is not None:assert start>=previousEnd
  previousEnd=end;gap=int(o['latencyNanos'])-q['traceDurationNanos'];assert gap==q['latencyGapNanos'] and gap>=-1000000;gaps.append(gap)
  assert q['requestThread'].startswith('broad-query-pressure-worker ')
  qc=collections.Counter()
  for metric in ['cpuSamples','allocationSampledBytes']:
   m=q['metrics'][metric];path=folder/q['collapsed'][metric];threadweights=collections.Counter();incl=collections.Counter();leaf=collections.Counter();threadincl=collections.defaultdict(collections.Counter);threadleaf=collections.defaultdict(collections.Counter)
   for line in path.read_text().splitlines():
    stack,wgt=line.rsplit(' ',1);weight=int(wgt);assert weight>0
    thread,*frames=stack.split(';');assert frames;unique=set(frames);last=frames[-1]
    threadweights[thread]+=weight
    for frame in unique:incl[frame]+=weight;threadincl[thread][frame]+=weight
    leaf[last]+=weight;threadleaf[thread][last]+=weight
    group=threadgroup(thread);app=group=='application';qc[metric+'.all']+=weight;qc[metric+('.application' if app else '.background')]+=weight
    g['threadGroups'][metric][group]+=weight;g['threads'][metric][thread]+=weight
    if app:
     names=[name for name,pattern in patterns.items() if any(frame.startswith(pattern) for frame in frames)]
     for name in names:qc[metric+'.'+name]+=weight;g['leaves'][metric+'.'+name][last]+=weight
     for x,y in itertools.combinations(names,2):qc[metric+'.intersection.'+x+'+'+y]+=weight
   assert sum(threadweights.values())==sum(leaf.values())==m['weight']
   assert dict(threadweights)=={t:tm['weight'] for t,tm in m['threads'].items() if tm['weight']}
   assert sum(tm['eventCount'] for tm in m['threads'].values())==m['eventCount']
   for top,counter in [('topInclusiveFrames',incl),('topLeafFrames',leaf)]:
    for entry in m[top]:assert counter[entry['name']]==entry['weight']
    for t,tm in m['threads'].items():
     for entry in tm[top]:assert (threadincl if top=='topInclusiveFrames' else threadleaf)[t][entry['name']]==entry['weight']
   assert m['missingStackEvents']==m['truncatedStackEvents']==0
   qc[metric+'.missingStackEvents']+=m['missingStackEvents'];qc[metric+'.truncatedStackEvents']+=m['truncatedStackEvents'];events.update(m['eventTypes'])
   g['collapsedFiles'].append({'path':str(path.relative_to(R)),'sha256':sha(path),'weight':m['weight'],'eventCount':m['eventCount']})
  g['counters'].update(qc);g['perQuery'].append({'id':q['id'],'counters':dict(qc),'graphWorkUnits':int(o['graphWorkUnits'])})
 for et,count in events.items():assert analysis['attributedEventCounts'][et]==count
 for kind,g in grouped.items():
  assert len(g['perQuery'])==40;pub=next(x for x in summary['groups'] if x['revision']==side and x['projection']==kind)
  assert collections.Counter(pub['counters'])==g['counters']
  for actualq,pubq in zip(g['perQuery'],pub['perQuery']):assert actualq['id']==pubq['id'] and collections.Counter(actualq['counters'])==collections.Counter(pubq['counters'])
  for key,items in pub['topLeavesByMethod'].items():
   for name,weight in items:assert g['leaves'][key][name]==weight
  assert sum(g['threadGroups']['cpuSamples'].values())==g['counters']['cpuSamples.all']
  out['groups'].append({'revision':side,'projection':kind,'counters':dict(g['counters']),'threadGroups':dict(g['threadGroups']),'threads':dict(g['threads']),'workUnitsFrequency':dict(collections.Counter(str(x['graphWorkUnits']) for x in g['perQuery'])),'topMethodLeaves':{k:v.most_common(12) for k,v in g['leaves'].items()},'collapsedChecks':g['collapsedFiles']})
 out['recordings'][side]={'analysisSha256':sha(ap),'tsvSha256':sha(D/(side+'.tsv')),'fullRowsSha256':sha(D/(side+'-rows.jsonl')),'commandSha256':sha(D/(side+'-command.json')),'commandJar':jar,'recordedJfrSha256':analysis['jfrSha256'],'attributedEventsChecked':dict(events),'allRecordingCpuEvents':analysis['eventCounts']['jdk.ExecutionSample'],'unattributedCpuEvents':analysis['eventCounts']['jdk.ExecutionSample']-events['jdk.ExecutionSample'],'windowCount':80,'minGapNanos':min(gaps),'maxGapNanos':max(gaps),'positiveUntracedNanos':sum(max(0,x) for x in gaps),'collapsedMetricFilesChecked':160}
out['additionalRawFamilyCoverage']=[]
for g in out['groups']:
 if g['projection']!='distinct':continue
 family=collections.Counter()
 for check in g['collapsedChecks']:
  metric='cpuSamples' if '.cpuSamples.collapsed' in check['path'] else 'allocationSampledBytes'
  for line in (R/check['path']).read_text().splitlines():
   stack,w=line.rsplit(' ',1);thread,*frames=stack.split(';');weight=int(w)
   if threadgroup(thread)!='application':continue
   prefix='io.johnsonlee.graphite.webgraph.MappedWebGraphBackedGraph.parallelRawDistinctCallSiteStringProjection'
   if any(frame.startswith(prefix) for frame in frames):
    family[metric+'.methodOrGeneratedLambdaUnion']+=weight
    if not any(frame.startswith(prefix+'(') for frame in frames):family[metric+'.generatedLambdaWithoutMethod']+=weight
 out['additionalRawFamilyCoverage'].append({'revision':g['revision'],'projection':'distinct','counters':dict(family)})
helper=P/'control-v2-26/classes';helperfiles=[p for p in helper.rglob('*') if p.is_file()]
assert [p.relative_to(helper).as_posix() for p in helperfiles]==['MultiKeywordProfileRunner.class']
out['classpathHelperFiles']={str(p):sha(p) for p in helperfiles}
vr=load(D/'validator-bytecode-receipt.json');vb=D/'base-validator-javap.txt';vc=D/'candidate-validator-javap.txt'
assert vb.read_bytes()==vc.read_bytes() and sha(vb)==vr['baseSha256'] and sha(vc)==vr['candidateSha256']
for side in ['base','candidate']:
 command=load(D/(side+'-validator-javap-command.json'))
 assert '-c' in command and '-p' in command and out['recordings'][side]['commandJar'] in command
out['validatorBytecodeCheck']={'receipt':vr,'independentlyComparedBytes':True,'receiptSha256':sha(D/'validator-bytecode-receipt.json')}
out['limitations']=['This independently parses saved analyzer output and collapsed stacks, not the native JFR binary; its exact-signature event extraction remains the ProfileWindows result.','Method inclusive weights overlap and must not be added; intersections and per-thread/all-thread conservation were independently verified.','No sample for selectedAnchor/postingValidation does not establish zero calls.','TLAB weights are sampled weighted bytes, not exact complete allocation bytes or objects.','CPU event mode is cpu even though async-profiler engine setting is wall; companion JVM samples were not merged.','One recording per revision in sequence is diagnostic; 40 repetitions do not independently repeat the original three paired experiments.','Final graph/JAR equality is documented by completed receipt and capture script assertions; this audit did not rehash large graph/JAR/JFR files.','Profiled timing does not replace original acceptance measurements; rejected138 remains rejected and no regression cause has been located.']
(R/'cold-profile-independent-audit.json').write_text(json.dumps(out,indent=2)+'\n')
print(json.dumps({'passed':True,'verifiedQueryCount':160,'verifiedRows':32000,'recordings':out['recordings'],'groups':[{'revision':g['revision'],'projection':g['projection'],'counters':g['counters'],'threadGroups':g['threadGroups']} for g in out['groups']]},indent=2))
