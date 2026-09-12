from pathlib import Path
import csv,json,math,hashlib,collections
R=Path(__file__).parent;D=R/'old34-pairs'
def load(p):return json.loads(p.read_text())
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def read(p):return list(csv.DictReader(p.open(),delimiter='\t'))
def quant(rs,q):return sorted(int(x['latencyNanos']) for x in rs)[math.ceil(q*len(rs))-1]
keys='id family shape selectivity operator boundary projection targetGraphId workloadIdentity limit outcome rowCount responseBytes digest'.split()
oracle=(D/'oracle.correctness').read_text().splitlines();assert len(oracle)==34
oldoracle=Path('/private/tmp/graphite-attempt137.dcywsuq7/old34-pairs/oracle.correctness');assert oldoracle.read_bytes()==(D/'oracle.correctness').read_bytes()
status=load(D/'global-wide-status.json');progress=load(D/'local-progress.json');out={'schema':'independent-attempt139-old34-audit-v1','passed':True,'correctnessResultCount':204,'oracleSha256':sha(D/'oracle.correctness'),'matchesPrior137FrozenOracle':True,'pairs':[],'alignedRows':[],'inputSha256':{},'publishedStatus':{k:status[k] for k in ['passed','regressionPassed','targetAchieved','errors','targetErrors']},'recordedJarHashes':progress['jarHashes']}
for p in D.iterdir():
 if p.suffix in ['.json','.tsv'] or p.name=='oracle.correctness':out['inputSha256'][p.name]=sha(p)
counts=collections.Counter();fullchanges=[];aligned=collections.defaultdict(list)
for i in range(1,4):
 pair={'pair':i,'order':status['runs'][i-1]['order'],'sides':{}};sets={}
 for side in ['base','candidate']:
  rs=read(D/f'{side}-global-wide-{i}.tsv');sets[side]=rs
  assert len(rs)==34 and len({r['id'] for r in rs})==34
  assert ['|'.join(r[k] for k in keys) for r in rs]==oracle
  assert all(r['outcome']=='success' and int(r['latencyNanos'])>0 for r in rs)
  raw=load(D/f'{side}-global-wide-{i}.json');assert len(raw)==1
  assert raw[0]['benchmark']=='io.johnsonlee.graphite.webgraph.LargeBroadQueryPressureBenchmark.replayBroadQueries'
  m={k:v['score'] for k,v in raw[0]['secondaryMetrics'].items()}
  assert m['queryCount']==m['successCount']==34 and m['failureCount']==m['timeoutCount']==0 and m['graphCount']==64
  summary={k:int(m[k]) for k in ['processCpuNanos','peakUsedHeapBytes','peakResidentSetBytes','graphWorkUnits']}
  assert sum(int(r['graphWorkUnits']) for r in rs)==summary['graphWorkUnits']
  for q,k in [(.5,'p50LatencyNanos'),(.95,'p95LatencyNanos')]:
   summary[k]=quant(rs,q);assert summary[k]==m[k]
  for k in ['p50LatencyNanos','p95LatencyNanos','processCpuNanos','peakUsedHeapBytes','peakResidentSetBytes']:
   reported=('base'+k[0].upper()+k[1:]) if side=='base' else k;assert summary[k]==status['runs'][i-1][reported]
  summary['p95QueryIds']=[r['id'] for r in rs if int(r['latencyNanos'])==summary['p95LatencyNanos']]
  summary['top3']=[{k:r[k] for k in ['id','latencyNanos','graphWorkUnits','parallelScanCount','indexLookupCount']} for r in sorted(rs,key=lambda r:int(r['latencyNanos']),reverse=True)[:3]]
  pair['sides'][side]=summary
  cmd=load(D/f'{side}-global-wide-{i}-command.json');assert '-prof' in cmd and cmd[cmd.index('-prof')+1]=='gc'
  assert 'graphCount=64' in cmd and 'coverageFamily=global-wide' in cmd and 'indexState=cold' in cmd
  assert '-XX:ActiveProcessorCount=4' in cmd[-1] and 'correctness.mode=verify' in cmd[-1]
  assert 'oracle.correctness' in cmd[-1]
 for b,c in zip(sets['base'],sets['candidate']):
  bn,cn=int(b['latencyNanos']),int(c['latencyNanos']);reg=cn>bn*1.15 and cn>bn+1000000
  changes={k:[b[k],c[k]] for k in b if k not in keys+['latencyNanos'] and b[k]!=c[k]}
  row={'pair':i,'baseNanos':bn,'candidateNanos':cn,'deltaNanos':cn-bn,'deltaPercent':100*(cn/bn-1),'slower':cn>bn,'above15PercentAnd1ms':reg,'nonLatencyChanges':changes}
  aligned[b['id']].append(row)
  if reg:counts[b['id']]+=1
  if changes:fullchanges.append({'pair':i,'id':b['id'],'changes':changes})
 pair['wrappedShapes']=[]
 for published in status['runs'][i-1]['wrappedShapeRuns']:
  shape=published['shape'];bv=quant([r for r in sets['base'] if r['shape']==shape],.95);cv=quant([r for r in sets['candidate'] if r['shape']==shape],.95)
  assert bv==published['baseLatencyNanos'] and cv==published['latencyNanos']
  pair['wrappedShapes'].append({'shape':shape,'baseP95Nanos':bv,'candidateP95Nanos':cv,'speedup':bv/cv})
 pair['p95Speedup']=pair['sides']['base']['p95LatencyNanos']/pair['sides']['candidate']['p95LatencyNanos'];assert pair['p95Speedup']==status['runs'][i-1]['p95Speedup']
 pair['resourceIncreasePercent']={k:100*(pair['sides']['candidate'][k]/pair['sides']['base'][k]-1) for k in ['processCpuNanos','peakUsedHeapBytes','peakResidentSetBytes']}
 pair['resourcesAbove15Percent']=[k for k,v in pair['resourceIncreasePercent'].items() if v>15]
 pair['strictP95Improvement']=pair['p95Speedup']>1
 out['pairs'].append(pair)
out['alignedRows']=[{'id':k,'pairs':v} for k,v in aligned.items()]
out['nonLatencyChanges']=fullchanges
out['slowerObservations']=[{'id':k,**x} for k,ls in aligned.items() for x in ls if x['slower']]
out['repeatedAboveThreshold']={k:v for k,v in counts.items() if v>=2}
out['slowerEveryPair']=[k for k,ls in aligned.items() if all(x['slower'] for x in ls)]
out['attemptDecision']='rejected'
out['strictProgressEveryPair']=all(p['strictP95Improvement'] for p in out['pairs']);assert out['strictProgressEveryPair']==progress['strictProgressEveryPair']
out['aggregateP95RegressionPairs']=[p['pair'] for p in out['pairs'] if p['sides']['candidate']['p95LatencyNanos']>max(p['sides']['base']['p95LatencyNanos']*1.15,p['sides']['base']['p95LatencyNanos']+1000000)]
out['resourceRegressionCounts']=dict(collections.Counter(k for p in out['pairs'] for k in p['resourcesAbove15Percent']))
out['wrappedRepeatedRegressions']={shape:sum(x['candidateP95Nanos']>max(x['baseP95Nanos']*1.15,x['baseP95Nanos']+1000000) for p in out['pairs'] for x in p['wrappedShapes'] if x['shape']==shape) for shape in ['global-wide-wrapped-case-insensitive','global-wide-wrapped-case-insensitive-distinct']}
assert not out['repeatedAboveThreshold'] and len(out['aggregateP95RegressionPairs'])<2 and all(n<2 for n in out['resourceRegressionCounts'].values()) and all(n<2 for n in out['wrappedRepeatedRegressions'].values())
assert status['regressionPassed'] and not status['targetAchieved'] and not out['strictProgressEveryPair']
out['limitations']=['Six replay runs provide three paired observations; do not call the distribution across three runs a P95. Each reported P95 is the nearest-rank quantile across that run’s 34 queries.','Full 14-field signatures match the saved frozen oracle; no queries were re-executed during this audit.','The replay starts with cold indexes, while later queries reuse indexes initialized earlier in the same replay.','Recorded JAR hashes were compared to build receipt; whole JAR/graph contents were not rehashed while other timed runs are live.','Work/scan changes support execution-path observations, not complete attribution of measured time or proof of stable CI success.','The repeated-regression comparator passes, but mandatory strict progress in every pair fails. Attempt139 is rejected; target10x is false and additional acceptance runs are not permitted.']
assert progress['jarHashes']['candidate']==load(R/'build-receipt.json')['jmhJarSha256']
(R/'independent-old34-audit.json').write_text(json.dumps(out,indent=2)+'\n')
print(json.dumps({'pairs':out['pairs'],'slowerCount':len(out['slowerObservations']),'slowerEveryPair':out['slowerEveryPair'],'aboveThresholdSinglePairs':counts,'nonLatencyChanges':fullchanges},indent=2))
