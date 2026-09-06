"""Independent file-only verification; never imports runners or executes a comparator/JVM."""
from pathlib import Path
from collections import defaultdict
import base64,csv,hashlib,json,math,shlex
R=Path(__file__).resolve().parent; V=R/'v3-control-final'; D=R/'old34-pairs'
PRIOR=Path('/private/tmp/graphite-mapped-tuple-evidence.t2461mo1'); hashes={}
def sha(p):
 p=Path(p);h=hashlib.sha256(p.read_bytes()).hexdigest();hashes[str(p)]=h;return h
def load(p):
 p=Path(p);sha(p);return json.loads(p.read_text())
def rows(p):
 p=Path(p);sha(p)
 with p.open(newline='') as f:
  reader=csv.DictReader(f,delimiter='\t');rs=list(reader);assert len(reader.fieldnames)==len(set(reader.fieldnames));assert all(None not in r and None not in r.values() for r in rs);return rs
build=load(R/'final-build-receipt.json');run=load(V/'run.json');catalog=load(V/'catalog.json');work=rows(V/'workloads.tsv');obs=rows(V/'fork-001.tsv');sha(V/'fork-001-rows.jsonl');actual=[json.loads(s) for s in (V/'fork-001-rows.jsonl').read_text().splitlines()]
assert run['schema']=='graphite-multi-keyword-profile-v2' and run['catalogSchema']==catalog['schema']=='graphite-wide-query-oracle-v3'
assert run['status']=='complete' and run['requestedForks']==run['completedVerifiedForks']==1 and run['queryCount']==36 and not run['performanceGate']
assert len(catalog['logicalCases'])==18 and len(catalog['inputGraphs'])==64
ids=[q['id'] for q in catalog['queries']];assert len(ids)==len(set(ids))==36
for collection in (work,obs,actual,run['queries']):assert [q['id'] for q in collection]==ids
assert len(obs[0])==15
count=0
for q,w,o,a,summary in zip(catalog['queries'],work,obs,actual,run['queries']):
 query=base64.b64decode(w['queryBase64'],validate=True).decode();assert query==q['query'];assert o['workloadIdentity']==hashlib.sha256(query.encode()).hexdigest()
 assert a['columns']==['n.caller_class','n.caller_name','n.callee_class','n.callee_name'];assert set(a)=={'id','columns','rows'};assert a['rows']==q['expectedRows']
 assert int(o['rowCount'])==len(a['rows']);count+=len(a['rows']);assert o['digest']==hashlib.sha256(json.dumps(a['rows'],ensure_ascii=False,separators=(',',':')).encode()).hexdigest()
 returned=sorted({g for row in a['rows'] for g in row['graphIds']});assert o['hitGraphIds']==','.join(returned)
 assert o['outcome']=='success' and int(o['latencyNanos'])>0 and o['inputSourceCount']=='64' and o['resetMode']=='per-query-cold'
 assert w['distinct']==str(q['distinct']).lower() and w['expectedHitGraphIds']==','.join(q['expectedHitGraphIds']) and int(w['totalMatches'])==q['totalMatches']
 assert summary['sampleCount']==1 and summary['latencyNanosInForkOrder']==[int(o['latencyNanos'])] and summary['empiricalP95LatencyNanos'] is None
 case=next(c for c in catalog['logicalCases'] if c['id']==q['logicalId']);assert [g for g,n in zip(catalog['inputGraphs'],case['perGraphMatchingCounts']) if n]==q['expectedHitGraphIds'];assert sum(case['perGraphMatchingCounts'])==q['totalMatches']
 if q['distinct']:assert len({tuple(r['values']) for r in a['rows']})==len(a['rows'])
assert count==6171
assert run['inputs']['runtimeJar']['sha256']==build['jars']['webgraph']['sha256'];assert run['inputs']['trustedJar']['sha256']=='a5c2db2b0020798488916ec86902459d1044a7dcef606a73e00055883cdf5abe'
for k,v in run['inputs'].items():
 if 'path' in v and k not in ('trustedJar','runtimeJar'):assert sha(v['path'])==v['sha256'],k
assert sha(V/'catalog.json')==run['inputs']['catalog']['sha256'];assert sha(V/'workloads.tsv')==run['inputs']['workloads']['sha256']
for name,h in run['compiledClasses'].items():assert sha(V/'classes'/name)==h
before=load(V/'graph-content-before.json');assert before==load(V/'graph-content-after.json');assert len(before)==64 and sum(len(g['files']) for g in before)==1088
cmd=load(V/'fork-001-command.json');assert '-XX:ActiveProcessorCount=4' in cmd and '-Xmx8g' in cmd and cmd[-2:]==['all','per-query-cold'];assert cmd[cmd.index('-cp')+1]==str(V/'classes')+':'+str(R/'candidate-final-jmh.jar')
ref=load(V/'fork-001-reference-check.json');assert ref['passed'] and ref['queryCount']==36 and ref['verifiedFullRowsAndProvenance'] and not ref['errors']
out={'attempt':146,'auditPassed':True,'acceptance':False,'v3':{'status':'complete','queries':36,'fullRowsValuesOrderProvenance':count,'queryHashesAndDigestsVerified':True,'schema':catalog['schema'],'forks':1,'perQueryP95Reported':False,'performanceGate':False,'graphManifestRecords':1088,'inputBytes':sum(f['size'] for g in before for f in g['files']),'jarHash':run['inputs']['runtimeJar']['sha256']},'old34':{'status':'awaiting terminal local-progress receipt'},'hashes':hashes}
fields='id family shape selectivity operator boundary projection targetGraphId workloadIdentity limit outcome rowCount responseBytes digest'.split()
def quant(rs,f):return sorted(int(r['latencyNanos']) for r in rs)[math.ceil(len(rs)*f)-1]
if (D/'local-progress.json').exists():
 progress=load(D/'local-progress.json');assert not progress['accepted'] and not progress['ciRun'] and progress['inputsUnchanged'];assert progress['jarHashes']['candidate']==build['jars']['webgraph']['sha256'];assert progress['jarHashes']['base']==run['inputs']['trustedJar']['sha256']
 assert load(D/'graph-input-before.json')==load(D/'graph-input-after.json')==before
 oracle=(D/'oracle.correctness').read_text().splitlines();assert len(oracle)==34 and sha(D/'oracle.correctness')==sha(PRIOR/'oracle.correctness');reference=rows(PRIOR/'oracle.tsv')
 summaries=[];observations=[];repeated=defaultdict(list);all_nonlatency=[];signature_count=0;seenpaths=set();template=load(PRIOR/'oracle-command.json')
 for pr in progress['pairs']:
  pair=pr['pair'];assert pair==len(summaries)+1;assert pr['order']==(['candidate','base'] if pair%2 else ['base','candidate'])
  data={};metrics={};sides={}
  for side in pr['order']:
   p=D/f'{side}-global-wide-{pair}';seenpaths.add(str(p.with_suffix('.tsv')));rs=rows(p.with_suffix('.tsv'));data[side]=rs
   assert len(rs)==34 and len(rs[0])==34 and [r['id'] for r in rs]==[r['id'] for r in reference]
   assert ['|'.join(r[k] for k in fields) for r in rs]==oracle;signature_count+=34
   for r,b in zip(rs,reference):
    changes={k:[b[k],r[k]] for k in r if k!='latencyNanos' and b[k]!=r[k]}
    if changes:all_nonlatency.append({'pair':pair,'side':side,'id':r['id'],'changes':changes})
   raw=load(p.with_suffix('.json'));assert len(raw)==1;raw=raw[0];assert raw['benchmark']=='io.johnsonlee.graphite.webgraph.LargeBroadQueryPressureBenchmark.replayBroadQueries';assert raw['warmupIterations']==0 and raw['measurementIterations']==raw['forks']==1;assert raw['params']=={'coverageFamily':'global-wide','graphCount':'64','indexState':'cold','timeoutMillis':'300000'}
   m={k:v['score'] for k,v in raw['secondaryMetrics'].items()};metrics[side]=m
   for k,v in dict(queryCount=34,successCount=34,timeoutCount=0,failureCount=0,availableProcessors=4,graphWorkerCount=2,segmentWorkerCount=2,graphCount=64).items():assert m[k]==v
   for k,v in pr['measurements'][side].items():assert m[k]==v
   for frac,k in [(.5,'p50LatencyNanos'),(.95,'p95LatencyNanos'),(1,'maxLatencyNanos')]:assert m[k]==quant(rs,frac)
   for k,c in dict(graphWorkUnits='graphWorkUnits',callSiteParallelScanCount='parallelScanCount',callSiteStringIndexLookupCount='indexLookupCount',totalRows='rowCount',inputSourceCount='inputSourceCount',accessedGraphCount='accessedGraphCount',filteredNodeLimitFastPathExecutions='filteredNodeLimitFastPathExecutions',generalFallbackExecutions='generalFallbackExecutions').items():assert m[k]==sum(int(r[c]) for r in rs)
   expected=template.copy();expected[2]=run['inputs']['trustedJar']['path'] if side=='base' else str(R/'candidate-final-jmh.jar');expected[expected.index('-rff')+1]=str(p)+'.json';expected[-1]=expected[-1].replace('correctness.mode=record','correctness.mode=verify').replace('pressure.output='+str(PRIOR/'oracle.correctness'),'pressure.correctness.oracle='+str(D/'oracle.correctness')).replace(str(PRIOR/'oracle.tsv'),str(p)+'.tsv')
   command=load(Path(str(p)+'-command.json'));assert command==expected;assert raw['jvmArgs']==shlex.split(command[-1])
   sides[side]={k:m[k] for k in ('p50LatencyNanos','p95LatencyNanos','maxLatencyNanos','processCpuNanos','peakUsedHeapBytes','peakResidentSetBytes','graphScanPeakActiveWorkers','segmentScanPeakActiveWorkers','graphWorkUnits')};sides[side]['p95Query']=[r['id'] for r in rs if int(r['latencyNanos'])==m['p95LatencyNanos']];sides[side]['top3']=[{'id':r['id'],'latencyNanos':int(r['latencyNanos'])} for r in sorted(rs,key=lambda r:int(r['latencyNanos']),reverse=True)[:3]]
  for b,c in zip(data['base'],data['candidate']):
   bn,cn=int(b['latencyNanos']),int(c['latencyNanos']);above=cn*100>bn*115 and cn-bn>1000000
   observation=dict(pair=pair,id=b['id'],baseNanos=bn,candidateNanos=cn,deltaPercent=100*(cn/bn-1),above15PercentAnd1ms=above,nonLatencyChanges={k:[b[k],c[k]] for k in b if k!='latencyNanos' and b[k]!=c[k]});observations.append(observation)
   if above:repeated[b['id']].append(pair)
  resources={k:{'base':metrics['base'][k],'candidate':metrics['candidate'][k],'deltaPercent':100*(metrics['candidate'][k]/metrics['base'][k]-1),'passed':metrics['candidate'][k]<=metrics['base'][k]*1.15} for k in ('processCpuNanos','peakUsedHeapBytes','peakResidentSetBytes')}
  strict=metrics['candidate']['p95LatencyNanos']<metrics['base']['p95LatencyNanos'];peaks=metrics['candidate']['graphScanPeakActiveWorkers']==metrics['candidate']['segmentScanPeakActiveWorkers']==2
  assert strict==pr['strictProgress'] and peaks==pr['originalWorkerPeakCondition'] and all(v['passed'] for v in resources.values())==pr['originalResourceConditions']
  summaries.append(dict(pair=pair,order=pr['order'],sides=sides,strictProgress=strict,peaksPassed=peaks,resources=resources))
 comparator=(D/'comparison-command.json').exists();eligible=len(summaries)==3 and all(p['strictProgress'] and p['peaksPassed'] and all(v['passed'] for v in p['resources'].values()) for p in summaries)
 assert {str(p) for p in D.glob('*-global-wide-*.tsv')}==seenpaths
 assert progress['strictProgressEveryPair']==(len(summaries)==3 and all(p['strictProgress'] for p in summaries))
 out['old34']={'status':'terminal','completedPairs':len(summaries),'signaturesVerified':signature_count,'oracleFields':fields,'pairs':summaries,'observations':observations,'nonLatencyDifferencesFromFrozenOracle':all_nonlatency,'repeatedDoubleThresholdQueries':{k:v for k,v in repeated.items() if len(v)>=2},'comparatorExecuted':comparator,'driverWouldInvokeComparator':eligible,'notRunPairs':list(range(len(summaries)+1,4)),'ciRun':False,'strictProgressEveryPair':progress['strictProgressEveryPair']}
 if comparator:
  exit_receipt=load(D/'comparison-exit.json');status=load(D/'global-wide-status.json');comparison_command=load(D/'comparison-command.json')
  assert len(summaries)==3 and exit_receipt['exitCode']==0 and status['passed'] and status['regressionPassed'] and not status['errors']
  assert status['regressionOnly'] and status['minimumSpeedup']==10 and not status['targetAchieved']
  assert '--regression-only' in comparison_command and comparison_command[comparison_command.index('--minimum-speedup')+1]=='10'
  assert sha(comparison_command[1])==sha(R/'candidate/.github/scripts/benchmark-gate.mjs')
  assert not out['old34']['repeatedDoubleThresholdQueries'] and all(v['passed'] for p in summaries for v in p['resources'].values())
  wrapped=[];shape_regressions=defaultdict(list);target_misses=0
  for p,record in zip(summaries,status['runs']):
   pair=p['pair'];assert record['order']=='-'.join(p['order'])
   for side,prefix in [('base','base'),('candidate','')]:
    for key,field in [('P50LatencyNanos','p50LatencyNanos'),('P95LatencyNanos','p95LatencyNanos'),('ProcessCpuNanos','processCpuNanos'),('PeakUsedHeapBytes','peakUsedHeapBytes'),('PeakResidentSetBytes','peakResidentSetBytes')]:
     k=prefix+key if prefix else key[0].lower()+key[1:];assert record[k]==p['sides'][side][field]
   bn,cn=p['sides']['base']['p95LatencyNanos'],p['sides']['candidate']['p95LatencyNanos'];assert record['p95Speedup']==bn/cn;target_misses+=bn/cn<10
   if cn>bn*1.15 and cn-bn>1000000:shape_regressions['aggregate P95'].append(pair)
   for w in record['wrappedShapeRuns']:
    values={side:max(int(r['latencyNanos']) for r in rows(D/f'{side}-global-wide-{pair}.tsv') if r['shape']==w['shape']) for side in ('base','candidate')}
    assert values['base']==w['baseLatencyNanos'] and values['candidate']==w['latencyNanos'] and w['speedup']==values['base']/values['candidate'];target_misses+=w['speedup']<10
    if values['candidate']>values['base']*1.15 and values['candidate']-values['base']>1000000:shape_regressions[w['shape']].append(pair)
    wrapped.append({'pair':pair,**w})
  assert not any(len(v)>=2 for v in shape_regressions.values());assert target_misses==len(status['targetErrors'])==9
  out['old34'].update(comparatorExit=exit_receipt,comparatorStatus=status,wrappedRecomputed=wrapped,aggregateOrWrappedRepeatedFailures={k:v for k,v in shape_regressions.items() if len(v)>=2})
  if not eligible:assert 'existing six recordings after driver stopped' in exit_receipt['scope']
 for p in D.iterdir():
  if p.is_file():sha(p)
out['processExits']={name:load(R/name) for name in ('old34-exit.json','v3-control-exit.json') if (R/name).exists()}
for p in (R/'run-old34-pairs.py',R/'v3-control-command.json',R/'candidate/.github/scripts/benchmark-gate.mjs'):sha(p)
out['limitations']=['File-only audit: graph before/after per-file receipts matched; no reread of 10GB graph payload or whole JAR hashing during timing.','V3 is one complete correctness replay, no 20-fork per-query P95 or performance acceptance.','Original34 P95 is rank33 across34 heterogeneous query observations, not a percentile across paired forks.','Driver break on progress/resource/peak failure can exit0; full comparator is conditional on3 pairs passing those preliminary conditions.','Repeated row gate requires >15% AND >1ms in at least2 paired forks; unrecorded forks cannot confirm or dismiss a single exceedance.','Recorded graph/segment maxima2/2 are separate counters, not proof of four simultaneously alive workers.','No new measurement, comparator, Java, build, CI invocation or source modification.']
(R/'control-and-gate-audit.json').write_text(json.dumps(out,indent=2)+'\n')
print(json.dumps({'v3':out['v3'],'old34':{k:v for k,v in out['old34'].items() if k not in ('observations','comparatorStatus')},'filesHashed':len(hashes)},indent=2))
