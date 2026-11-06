import json,pathlib,statistics,math,re,collections
root=pathlib.Path('/Users/johnsonlee/.codex/benchmarks/apple-validation');folder=root/'lifecycle-construction-final'
records=[json.loads(line)for line in(folder/'samples.jsonl').read_text().splitlines()]
protocol=json.loads((folder/'protocol.json').read_text());samples=[r for r in records if r['kind']=='construction'];processes=[r for r in records if r['kind']=='process']
assert len(samples)==12 and len(processes)==36
assert all(r['ok']for r in samples) and all(r['ok'] and r['exit_code']==0 for r in processes)
expected={(v,w,i)for v in ['baseline','candidate']for w in ['signal-ios','swiftpm']for i in range(3)}
assert {(r['variant'],r['workload'],r['round'])for r in samples}==expected
checks=[];heapchecks=[]
for r in samples:
 k=(r['variant'],r['workload'],r['round']);st=[x for x in processes if(x['variant'],x['workload'],x['round'])==k];assert {x['stage']for x in st}=={0,1,'verification-excluded'}
 measured=[x for x in st if isinstance(x['stage'],int)];verification=next(x for x in st if x['stage']=='verification-excluded')
 assert math.isclose(r['cpu_seconds'],sum(x['cpu_seconds']for x in measured),abs_tol=1e-10)
 assert r['max_stage_peak_process_rss_bytes']==max(x['peak_process_rss_bytes']for x in measured)
 assert r['wall_ms']>=sum(x['wall_ms']for x in measured)
 want=421262 if r['workload']=='signal-ios' and r['variant']=='candidate' else 331236 if r['workload']=='signal-ios' else 60956
 verified=json.loads(pathlib.Path(verification['log']).read_text());assert verified['columns']==['nodes'] and verified['rows']==[{'nodes':want}] and verified['rowCount']==1
 checks.append({'round':r['round'],'variant':r['variant'],'workload':r['workload'],'ok':True,'verifiedNodes':want,'verificationLog':verification['log'],'verificationExcludedFromMeasurement':True})
 for x in measured:
  if pathlib.Path(x['command'][0]).name=='java':
   sizes=[int(n)*{'':1,'k':1024,'m':1024**2,'g':1024**3}[u.lower()]for n,u in re.findall(r'-Xmx(\d+)([kKmMgG]?)',' '.join(x['command']))]
   assert sizes and all(n<=8*1024**3 for n in sizes)
   log=pathlib.Path(x['log']).read_text();overrides=[line for line in log.splitlines()if re.search(r'Picked up.*(?:JAVA_OPTIONS|JAVA_TOOL_OPTIONS)',line)]
   assert not overrides,overrides
   heapchecks.append({'round':r['round'],'variant':r['variant'],'workload':r['workload'],'maxHeapBytes':max(sizes),'allAtOrBelow8GiB':True,'logEnvironmentOverrides':overrides})
assert len(heapchecks)==12
out={'source':str(folder/'samples.jsonl'),'protocol':str(folder/'protocol.json'),'method':{'rounds':3,'order':['baseline,candidate','candidate,baseline','baseline,candidate'],'wall':'median across complete construction samples; min/max range retained, in milliseconds','cpu':'median of sum of frontend+importer user/system CPU per construction sample; min/max range retained, seconds','rss':'maximum across all three per-construction max-stage process peak RSS values, bytes; not median, not sum','boundary':'existing compiler index through production of saved graph. Source compilation is preparation. Artifact fingerprint and independent saved-graph verification are excluded.','uncertainty':'Three observations per variant/workload; full ranges reported. No significance or confidence-interval claim and no extrapolation to query latency.'},'verification':{'constructionSamples':12,'processStages':36,'allSuccess':True,'independentSavedGraphChecks':checks,'heapChecks':heapchecks},'workloads':{}}
for workload in ['signal-ios','swiftpm']:
 data={}
 for variant in ['baseline','candidate']:
  rows=sorted([r for r in samples if r['workload']==workload and r['variant']==variant],key=lambda r:r['round'])
  data[variant]={}
  for key,field in [('wall_ms','wall_ms'),('cpu_seconds','cpu_seconds'),('peak_rss_bytes','max_stage_peak_process_rss_bytes')]:
   vals=[r[field]for r in rows];data[variant][key]={'samples':vals,'min':min(vals),'max':max(vals),'aggregate':max(vals)if key=='peak_rss_bytes'else statistics.median(vals),'aggregation':'max'if key=='peak_rss_bytes'else'median'}
 data['delta']={}
 for key in ['wall_ms','cpu_seconds','peak_rss_bytes']:
  b=data['baseline'][key]['aggregate'];c=data['candidate'][key]['aggregate'];data['delta'][key]={'absolute':c-b,'percent':(c/b-1)*100}
 data['cpuAndRssObservedWithin5Percent']={key:data['delta'][key]['percent']<=5 for key in ['cpu_seconds','peak_rss_bytes']}
 if workload=='signal-ios':
  data['comparability']={'strictMatchedCoverage':False,'baselineNodes':331236,'candidateNodes':421262,'addedNodes':90026,'nodeCoveragePercent':(421262/331236-1)*100,'baselineSourceFiles':2529,'candidateSourceFiles':3821,'sourceFileCoveragePercent':(3821/2529-1)*100,'reason':'Baseline silently omitted source directories. Candidate restores source coverage and corrects signatures. These construction samples perform different work; raw resource deltas cannot establish strict 5% acceptance or performance recovery.'}
 else:
  data['comparability']={'sameSourceAndNodeCoverage':True,'baselineNodes':60956,'candidateNodes':60956,'outputExactlyEquivalent':False,'reason':'Candidate fixes @Sendable method signatures and other source-backed signature enrichment. Equal node/edge counts do not imply identical semantic output. Resource observations do not establish server query p50/p95 recovery.'}
 out['workloads'][workload]=data
(root/'construction-analysis.json').write_text(json.dumps(out,indent=2)+'\n')
for w,d in out['workloads'].items():
 print(w)
 for k in ['wall_ms','cpu_seconds','peak_rss_bytes']:
  a=d['baseline'][k];b=d['candidate'][k];delta=d['delta'][k]
  print(k,'baseline',a['aggregate'],'range',a['min'],a['max'],'candidate',b['aggregate'],'range',b['min'],b['max'],'delta',delta)
