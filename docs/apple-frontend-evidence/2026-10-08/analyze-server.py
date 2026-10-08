import json,pathlib,collections,statistics,math,ctypes
root=pathlib.Path('/Users/johnsonlee/.codex/benchmarks/apple-validation')
allrows={n:[json.loads(l)for l in(root/('lifecycle-'+n+'-final')/'samples.jsonl').read_text().splitlines()]for n in ['loading','query']}
protocol=json.loads((root/'lifecycle-query-final/protocol.json').read_text());variants=['baseline','candidate'];rounds=range(3);scope=['signal-ios','swiftpm','signal-server']
class Timebase(ctypes.Structure):_fields_=[('numer',ctypes.c_uint32),('denom',ctypes.c_uint32)]
tb=Timebase();lib=ctypes.CDLL('/usr/lib/libSystem.B.dylib');assert lib.mach_timebase_info(ctypes.byref(tb))==0
assert (tb.numer,tb.denom)==(125,3);factor=tb.numer/tb.denom
# Original harness stored Mach ticks divided by 1e9. Invert that operation, retaining
# the original float and reconstructed integer ticks in derived-only evidence.
def ticks(v):
 n=round(v*1e9);assert abs(v*1e9-n)<0.00001;return n
def cpu(record):return (ticks(record['user_seconds'])+ticks(record['system_seconds']))*tb.numer/tb.denom/1e9
def batchcpu(r):return cpu(r['cpu_after'])-cpu(r['cpu_before'])
def stats(a,aggregation='median'):
 return {'samples':a,'min':min(a),'max':max(a),'aggregate':max(a)if aggregation=='max'else statistics.median(a),'aggregation':aggregation}
def delta(b,c):return {'absolute':c-b,'percent':(c/b-1)*100}
def quantile(a,q):return sorted(a)[max(0,math.ceil(len(a)*q)-1)]
def comparison(data,keys):return {k:delta(data['baseline'][k]['aggregate'],data['candidate'][k]['aggregate'])for k in keys}
corrections=[];failure_summary={};fingerprints={}
for op,rows in allrows.items():
 requests=[x for x in rows if x['kind']=='request'];unexpected=[];refused=[]
 for x in requests:
  if x['ok']:continue
  expected=x.get('phase')=='readiness' and 'Connection refused' in x.get('error','')
  future=any(y['kind']=='request' and y.get('phase')=='readiness' and y['ok'] and (y['variant'],y['round'])==(x['variant'],x['round']) and y['recorded_ns']>x['recorded_ns'] for y in rows)
  (refused if expected and future else unexpected).append(x)
 other_failures=[x for x in rows if x['kind']!='request' and x.get('ok')is False]
 servers=[x for x in rows if x['kind']=='server_resources'];assert len(servers)==6 and all(x['exit_code']==-15 and x['ok']for x in servers)
 phases=collections.Counter((x['phase'],x['ok'])for x in requests)
 failure_summary[op]={'totalRequests':len(requests),'byPhase':[{'phase':p,'ok':ok,'count':n}for(p,ok),n in sorted(phases.items())],'expectedInitialConnectionRefused':len(refused),'unexpectedRequestFailures':unexpected,'otherFailedRecords':other_failures,'serverExpectedSIGTERMShutdownCount':6,'rssSamplingErrors':[e for x in rows if x['kind']=='query_batch' for e in x.get('rss_sample_errors',[])]}
 for x in rows:
  if x['kind']=='graph_identity':fingerprints[(op,x['variant'],x['graph']['id'])]=x['files']
 for v in variants:
  for r in rounds:
   rowsvr=[x for x in rows if x.get('variant')==v and x.get('round')==r]
   resource=next(x for x in rowsvr if x['kind']=='server_resources')
   last=next(x for x in rowsvr if x['kind']=='load')if op=='loading'else [x for x in rowsvr if x['kind']=='query_batch'][-1]['cpu_after']
   corrected=cpu(last);reported=last['user_seconds']+last['system_seconds']
   residual=resource['cpu_seconds']-corrected
   assert 0<=residual<.1,(op,v,r,residual)
   corrections.append({'operation':op,'variant':v,'round':r,'originalLiveCpuReportedSeconds':reported,'reconstructedRawTicks':{'user':ticks(last['user_seconds']),'system':ticks(last['system_seconds'])},'derivedLiveCpuSeconds':corrected,'wait4LifetimeCpuSeconds':resource['cpu_seconds'],'shutdownRemainderCpuSeconds':residual})
for graph in scope:
 f=fingerprints[('loading','baseline',graph)]
 assert all(fingerprints[(op,v,graph)]==f for op in allrows for v in variants)
for op,rows in allrows.items():
 for x in rows:
  if x['kind']=='request':assert x['scope']==scope
parity=[]
for name in [protocol['protocol']['readiness']['name']]+[x['name']for x in protocol['protocol']['cases']]:
 entries={v:sorted({(x['digest'],x['row_count'])for rows in allrows.values()for x in rows if x['kind']=='request' and x['ok'] and x['case']==name and x['variant']==v})for v in variants}
 assert entries['baseline']==entries['candidate'] and len(entries['baseline'])==1
 parity.append({'case':name,'ok':True,'values':entries})
loading={}
for v in variants:
 records=sorted([x for x in allrows['loading']if x['kind']=='load' and x['variant']==v],key=lambda x:x['round'])
 resources=sorted([x for x in allrows['loading']if x['kind']=='server_resources' and x['variant']==v],key=lambda x:x['round'])
 loading[v]={'wall_ms':stats([x['wall_ms']for x in records]),'cpu_original_mislabeled_seconds':stats([x['cpu_seconds']for x in records]),'cpu_through_consumption_derived_seconds':stats([cpu(x)for x in records]),'cpu_lifetime_wait4_seconds':stats([x['cpu_seconds']for x in resources]),'lifetime_peak_rss_bytes':stats([x['peak_process_rss_bytes']for x in resources],'max')}
loading['delta']=comparison(loading,['wall_ms','cpu_through_consumption_derived_seconds','cpu_lifetime_wait4_seconds','lifetime_peak_rss_bytes'])
loading['acceptance']={'wallNotAboveBaseline':loading['delta']['wall_ms']['absolute']<=0,'cpuThroughConsumptionWithin5Percent':loading['delta']['cpu_through_consumption_derived_seconds']['percent']<=5,'cpuLifetimeWithin5Percent':loading['delta']['cpu_lifetime_wait4_seconds']['percent']<=5,'rssWithin5Percent':loading['delta']['lifetime_peak_rss_bytes']['percent']<=5}
queries=[];batch_derivations=[];measured=[x for x in allrows['query']if x['kind']=='request' and x.get('phase')=='measured'];assert len(measured)==4800
for case in protocol['protocol']['cases']:
 for concurrency in [1,4]:
  item={'case':case['name'],'concurrency':concurrency,'scope':scope}
  for v in variants:
   req=[x for x in measured if(x['variant'],x['case'],x['concurrency'])==(v,case['name'],concurrency)];assert len(req)==300 and all(x['ok']for x in req)
   byround=[]
   for r in rounds:
    rr=[x for x in req if x['round']==r];assert len(rr)==100 and {x['sample']for x in rr}==set(range(100))
    byround.append({'round':r,'samples':100,'p50_ms':quantile([x['wall_ms']for x in rr],.5),'p95_ms':quantile([x['wall_ms']for x in rr],.95)})
   batches=sorted([x for x in allrows['query']if x['kind']=='query_batch' and (x['variant'],x['case'],x['concurrency'])==(v,case['name'],concurrency)],key=lambda x:x['round']);assert len(batches)==3
   for b in batches:
    assert b['samples']==100 and b['failures']==0 and b['ok']
    batch_derivations.append({'variant':v,'case':case['name'],'concurrency':concurrency,'round':b['round'],'originalReportedCpuSeconds':b['cpu_seconds'],'before':{'original':b['cpu_before'],'rawUserTicks':ticks(b['cpu_before']['user_seconds']),'rawSystemTicks':ticks(b['cpu_before']['system_seconds'])},'after':{'original':b['cpu_after'],'rawUserTicks':ticks(b['cpu_after']['user_seconds']),'rawSystemTicks':ticks(b['cpu_after']['system_seconds'])},'derivedCorrectedCpuSeconds':batchcpu(b)})
   item[v]={'samples':300,'p50_ms':quantile([x['wall_ms']for x in req],.5),'p95_ms':quantile([x['wall_ms']for x in req],.95),'rounds':byround,'round_p50_range_ms':[min(x['p50_ms']for x in byround),max(x['p50_ms']for x in byround)],'round_p95_range_ms':[min(x['p95_ms']for x in byround),max(x['p95_ms']for x in byround)],'batch_cpu_original_mislabeled_seconds':stats([x['cpu_seconds']for x in batches]),'batch_cpu_derived_seconds':stats([batchcpu(x)for x in batches]),'batch_wall_ms':stats([x['wall_ms']for x in batches])}
  item['delta']={k:delta(item['baseline'][k],item['candidate'][k])for k in ['p50_ms','p95_ms']}
  item['delta']['batch_cpu_seconds']=delta(item['baseline']['batch_cpu_derived_seconds']['aggregate'],item['candidate']['batch_cpu_derived_seconds']['aggregate'])
  item['delta']['batch_wall_ms']=delta(item['baseline']['batch_wall_ms']['aggregate'],item['candidate']['batch_wall_ms']['aggregate'])
  item['acceptance']={'p50NotAboveBaseline':item['delta']['p50_ms']['absolute']<=0,'p95NotAboveBaseline':item['delta']['p95_ms']['absolute']<=0,'cpuWithin5Percent':item['delta']['batch_cpu_seconds']['percent']<=5,'perCasePeakRss':'not isolated; only whole mixed-workload lifetime peak recorded'}
  item['status']='pass-observed'if all(item['acceptance'][k]for k in ['p50NotAboveBaseline','p95NotAboveBaseline','cpuWithin5Percent'])else'not-passed'
  queries.append(item)
resources={}
for v in variants:
 rows=sorted([x for x in allrows['query']if x['kind']=='server_resources' and x['variant']==v],key=lambda x:x['round'])
 resources[v]={'lifetime_peak_rss_bytes':stats([x['peak_process_rss_bytes']for x in rows],'max'),'lifetime_cpu_wait4_seconds':stats([x['cpu_seconds']for x in rows]),'lifetime_wall_ms':stats([x['wall_ms']for x in rows])}
resources['delta']=comparison(resources,['lifetime_peak_rss_bytes','lifetime_cpu_wait4_seconds','lifetime_wall_ms']);resources['acceptance']={'rssWithin5Percent':resources['delta']['lifetime_peak_rss_bytes']['percent']<=5,'lifetimeCpuWithin5Percent':resources['delta']['lifetime_cpu_wait4_seconds']['percent']<=5}
out={'sourceFiles':{op:str(root/('lifecycle-'+op+'-final')/'samples.jsonl')for op in allrows},'method':{'rounds':3,'order':['baseline,candidate','candidate,baseline','baseline,candidate'],'quantiles':'nearest-rank over all 300 repeated-request latencies per case/concurrency/variant; separate 100-request round quantiles and ranges retained','cpuAggregation':'median across three 100-request batch deltas; raw mislabeled values and timebase-corrected values both retained','load':'spawn through HTTP readiness and complete consumption of all four cases; wall and live CPU end before shutdown; separate wait4 CPU/RSS cover whole lifetime including shutdown','rss':'max wait4 process RSS across all rounds; query RSS applies to the whole mixed workload, never attributed to one case','cache':protocol['protocol']['cache_state'],'scope':scope,'limitations':'Three fixed closed-loop rounds, not sustained saturation. No averaging across cases/concurrency to offset failures. Overlapping ranges mean causal/statistical confidence remains limited, but do not satisfy a strict non-regression gate.'},'identicalPersistedGraphFingerprints':True,'parity':parity,'failureStatistics':failure_summary,'cpuUnitCorrection':{'measurementBug':'Original proc_pid_rusage code treated Mach absolute CPU ticks as nanoseconds. Historical cpu_seconds and cpu_before/after seconds are underreported; wait4 CPU is unaffected.','machTimebase':{'numer':tb.numer,'denom':tb.denom},'correctionFactor':factor,'derivation':'Recover each integer tick counter as round(original_float * 1e9), then ticks * numer / denom / 1e9. For batch deltas subtract corrected after and before values. Original samples and summaries are not modified.','counterSources':['https://github.com/apple-oss-distributions/xnu/blob/main/osfmk/kern/bsd_kern.c#L1187-L1197','https://github.com/apple-oss-distributions/xnu/blob/main/osfmk/kern/task.c#L6387-L6392'],'wait4Consistency':corrections,'batchDerivations':batch_derivations},'loading':loading,'queryCases':queries,'wholeMixedQueryWorkloadResources':resources,'overallPerformanceAcceptance':'not-passed','reason':'Every pooled query p50 and p95 is above baseline; some per-case batch CPU increases exceed 5%, and whole query-lifetime CPU also exceeds 5%. Correctness/parity success does not imply performance acceptance.'}
(root/'server-analysis.json').write_text(json.dumps(out,indent=2)+'\n')
print('LOAD',json.dumps(loading))
for x in queries:
 print(x['case'],x['concurrency'],{k:[x['baseline'][k],x['candidate'][k],x['delta'][k]]for k in ['p50_ms','p95_ms']},'CPU',x['baseline']['batch_cpu_derived_seconds']['aggregate'],x['candidate']['batch_cpu_derived_seconds']['aggregate'],x['delta']['batch_cpu_seconds'],x['acceptance'])
print('RESOURCES',json.dumps(resources));print('FAILURES',{op:{k:v for k,v in d.items()if k!='byPhase'}for op,d in failure_summary.items()})
