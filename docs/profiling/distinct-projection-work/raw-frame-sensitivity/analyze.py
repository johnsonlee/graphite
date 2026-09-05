from pathlib import Path
from collections import Counter
import json,csv,hashlib,calendar,time,re,zipfile
P=Path(__file__).resolve().parent;O=Path('/private/tmp/graphite-main-profiling-n50joikp');RAW='io.johnsonlee.graphite.webgraph.MappedWebGraphBackedGraph.parallelRawDistinctCallSiteStringProjection';NODE=RAW+'$lambda$32$lambda$31$lambda$30('
APP=('broad-query-pressure-worker [','graphite-cypher-scan-','graphite-callsite-scan-','graphite-callsite-segment-')
def sha(p):
 h=hashlib.sha256()
 with p.open('rb') as f:
  for b in iter(lambda:f.read(1024*1024),b''):h.update(b)
 return h.hexdigest()
def ns(t):
 main,_,fraction=t.rstrip('Z').partition('.')
 return calendar.timegm(time.strptime(main,'%Y-%m-%dT%H:%M:%S'))*10**9+int(fraction.ljust(9,'0'))
cat=json.loads((O/'query-catalog.json').read_text());jar=Path(cat['baselineJar']['path']);jarhash=sha(jar)
assert cat['baselineRef']==cat['baselineJar']['checkoutHeadVerified']=='4e328b0109e13c896b74004823fb049fcb19251a'
assert jarhash==cat['baselineJar']['sha256']=='a5c2db2b0020798488916ec86902459d1044a7dcef606a73e00055883cdf5abe'
with zipfile.ZipFile(jar) as z:classhash=hashlib.sha256(z.read('io/johnsonlee/graphite/webgraph/MappedWebGraphBackedGraph.class')).hexdigest()
assert classhash=='41f1966d893020d389727faf93bb49ad28d4c0754f82485d9c7774d5ae3097ca'
receipt=json.loads((P/'export-receipt.json').read_text());assert sha(P/'RawFrameSensitivity.java')==receipt['sourceSha256']
assert sha(P/'ProfileWindows.java')==receipt['profileWindowsSourceSha256'] and sha(P/'ProfileWindows.class')==receipt['profileWindowsClassSha256']
rows=[];inputs=[];windows=[];combined={};total_raw=0
for i in (3,4,5):
 d=json.loads((P/f'cpu-{i}.json').read_text());a=json.loads((O/f'cpu-{i}-analysis-v1/analysis.json').read_text());cmd=json.loads((O/f'cpu-{i}-command.json').read_text())
 jfr=O/f'cpu-{i}.jfr';tsv=O/f'cpu-{i}.tsv';t=list(csv.DictReader(tsv.open(),delimiter='\t'));r=next(r for r in receipt['exports'] if r['recording']==i)
 assert sha(jfr)==a['jfrSha256']==r['jfrSha256BeforeAndAfter'];assert sha(tsv)==a['tsvSha256'];assert sha(P/f'cpu-{i}.json')==r['outputSha256']
 assert cmd[cmd.index('-jar')+1]==str(jar) and cmd[3]=='io.johnsonlee.graphite.webgraph.LargeBroadQueryPressureBenchmark.replayBroadQueries'
 args=cmd[cmd.index('-jvmArgs')+1]
 log=O/f'cpu-{i}.log'; vm=[line for line in log.read_text().splitlines() if line.startswith('# VM options: ')]
 assert vm==['# VM options: '+args]
 assert re.findall(r'(?:^|,)trace=([^, ]+)',args)==['io.johnsonlee.graphite.cypher.CrossGraphCypherExecutor.execute']
 assert 'event=cpu,interval=1ms,alloc=256k,lock=1ms' in args and f'file={jfr}' in args and 'ActiveProcessorCount=4' in args
 assert a['executionSampleMetric']=='cpuSamples' and a['validation']['passed']
 assert d['cpuEventsAllRecording']==a['eventCounts']['jdk.ExecutionSample'];assert len(d['events'])==d['rawFamilyCpuEvents'];total_raw+=len(d['events'])
 traces=sorted(d['methodTraces'],key=lambda e:ns(e['start']));assert len(traces)==len(t)==len(a['queries'])==34
 assert [r['id'] for r in t]==[r['id'] for r in cat['queries']]==[r['id'] for r in a['queries']]
 assigned=Counter();all_original_cpu=0
 for n,(tr,q,trow) in enumerate(zip(traces,a['queries'],t)):
  start=ns(tr['start']);end=ns(tr['end']);assert tr['exactOuterExecute'] and tr['method']=='io.johnsonlee.graphite.cypher.CrossGraphCypherExecutor.execute(Ljava/lang/String;Ljava/util/Map;)Lio/johnsonlee/graphite/cypher/CypherResult;'
  assert tr['thread']==q['requestThread'] and tr['thread'].startswith('broad-query-pressure-worker [')
  assert (tr['start'],tr['end'],tr['durationNanos'])==(q['start'],q['end'],q['traceDurationNanos'])
  assert end-start==tr['durationNanos'] and end>start and (n==0 or ns(traces[n-1]['end'])<=start)
  assert trow['outcome']==q['outcome']=='success' and trow['digest']==q['digest'] and int(trow['rowCount'])==q['rowCount'] and trow['workloadIdentity']==q['workloadIdentity']
  assert int(trow['latencyNanos'])==q['tsvLatencyNanos'] and q['tsvLatencyNanos']-tr['durationNanos']==q['latencyGapNanos']>=-1000000
  expected=Counter();full=Counter();cpu=q['metrics'].get('cpuSamples');selected=[]
  if 'cpuSamples' in q['collapsed']:
   path=O/f'cpu-{i}-analysis-v1'/q['collapsed']['cpuSamples']
   for line in path.read_text().splitlines():
    stack,weight=line.rsplit(' ',1);full[stack]+=int(weight)
    if any(f.startswith(RAW) for f in stack.split(';')[1:]):expected[stack]+=int(weight)
   assert sum(full.values())==cpu['weight'];all_original_cpu+=sum(full.values())
   thread_counts=Counter()
   for s,w in full.items():thread_counts[s.split(';',1)[0]]+=w
   assert thread_counts=={k:v['weight'] for k,v in cpu['threads'].items()}
  for idx,e in enumerate(d['events']):
   if start<=ns(e['timestamp'])<end:assigned[idx]+=1;selected.append(e)
  actual=Counter()
  for e in selected:
   assert not e['truncated']
   stack=e['thread']+';'+';'.join(f['method'].replace(';',':').replace('\n',' ') for f in reversed(e['framesLeafFirst']));actual[stack]+=1
  assert actual==expected,(i,q['id'],actual-expected,expected-actual)
  windows.append({'recording':i,'ordinal':n+1,'id':q['id'],'start':tr['start'],'end':tr['end'],'traceDurationNanos':tr['durationNanos'],'tsvLatencyNanos':q['tsvLatencyNanos'],'latencyGapNanos':q['latencyGapNanos'],'rawCpuEvents':len(selected),'rawFullThreadMethodStacksMatch':True})
  if not q['id'].endswith(('distinct-targeted','distinct-dense')):continue
  app=[e for e in selected if e['thread'].startswith(APP)];node=[e for e in app if e['framesLeafFirst'][0]['method'].startswith(NODE)]
  types=Counter(e['framesLeafFirst'][0]['frameType'] for e in node);dist=Counter((e['framesLeafFirst'][0]['lineNumber'],e['framesLeafFirst'][0]['bytecodeIndex'],e['framesLeafFirst'][0]['frameType']) for e in node)
  unknown_line=sum(e['framesLeafFirst'][0]['lineNumber']<=0 for e in node);unknown_bci=sum(e['framesLeafFirst'][0]['bytecodeIndex']<0 for e in node)
  appcpu=sum(v['weight'] for k,v in cpu['threads'].items() if k.startswith(APP))
  row={'recording':i,'id':q['id'],'scope':'whole outer query, not phase','allThreadCpuSamples':cpu['weight'],'applicationCpuSamples':appcpu,'rawInclusiveCpuSamples':len(selected),'applicationRawInclusiveCpuSamples':len(app),'applicationNodeLeafSamples':len(node),'frameTypeCounts':dict(types),'unknownLineSamples':unknown_line,'unknownBciSamples':unknown_bci,'nodeLeafDistribution':[{'lineNumber':l,'bytecodeIndex':b,'frameType':ft,'samples':w} for (l,b,ft),w in sorted(dist.items())]};rows.append(row)
  group=q['id'].split('-')[-1];ag=combined.setdefault(group,{'applicationCpuSamples':0,'rawInclusive':0,'nodeLeaf':0,'frameTypes':Counter()});ag['applicationCpuSamples']+=appcpu;ag['rawInclusive']+=len(app);ag['nodeLeaf']+=len(node);ag['frameTypes'].update(types)
 assert len(assigned)==len(d['events']) and all(x==1 for x in assigned.values())
 assert all_original_cpu==a['attributedEventCounts']['jdk.ExecutionSample']
 inputs.append({'recording':i,'jfr':str(jfr),'jfrSha256':sha(jfr),'tsvSha256':sha(tsv),'originalAnalysisSha256':sha(O/f'cpu-{i}-analysis-v1/analysis.json'),'captureCommandPath':str(O/f'cpu-{i}-command.json'),'captureCommandSha256':sha(O/f'cpu-{i}-command.json'),'jmhLogSha256':sha(log),'jmhVmOptionsMatchCommandExactly':True,'rawEvents':len(d['events']),'rawEventsAssignedExactlyOnce':len(assigned),'allCpuEvents':d['cpuEventsAllRecording'],'originalWindowCpuSamplesConserved':all_original_cpu,'exactOuterTraces':34})
# Match comparison scope: aggregate both initial and provenance for phase-traced dense queries.
phase=json.loads(Path('/private/tmp/graphite-main-raw-source-lines/source-mapping.json').read_text());phase_comparison={}
for r in phase['rows']:
 group=r['id'].split('-')[-1];ag=phase_comparison.setdefault(group,{'nodeLeaf':0,'frameTypes':Counter()});ag['nodeLeaf']+=r['nodeLeafSamples'];ag['frameTypes'].update(r['frameTypes'])
result={'result':'pass','scope':'Offline analysis of earlier outer-execute-only traces, not an unprofiled control or causal experiment. Whole-query node leaf compared with whole-query node leaf, preserving phase-traced initial+provenance for dense.','newRecordings':0,'noMeasuredJarOrProductionChanges':True,'frozenMain':cat['baselineRef'],'jar':str(jar),'jarSha256':jarhash,'classSha256':classhash,'catalogSha256':sha(O/'query-catalog.json'),'inputs':inputs,'queryWindowsVerified':len(windows),'fullRawEventsVerified':total_raw,'rows':rows,'outerOnlyAggregate':combined,'phaseTraceWholeQueryAggregate':phase_comparison,'windows':windows,'bindingLimit':'Existing per-recording command paths plus frozen catalog JAR hash and current matching JAR/class establish retained input identity. JFR itself does not cryptographically embed the application JAR hash; no independent per-recording before/after JAR receipt was found for cpu-3/4/5.','interpretation':'Interpreted/C1 labels also occur in older outer-only recordings; added projectSource/provenance tracing is not necessary for their presence. The recordings differ in time/JIT history and are not paired trace-on/off controls, so no causal overhead or unprofiled compilation-state conclusion follows.','limitations':['macOS native CPU recording ActiveSetting event=cpu, engine=wall; not Linux hardware cycles.','Leaf frame labels and BCI are reported metadata, not exclusive instruction timing.','C2 label absence from sampled node leaves does not prove no C2 execution or compilation.','Full raw events exported; non-raw CPU recording count verified and original full collapsed-window totals preserved, not re-exported with frame metadata.']}
(P/'summary.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n');print('PASS',len(windows),total_raw);print(combined);print('phase whole-query',phase_comparison)
