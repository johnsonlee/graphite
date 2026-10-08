from pathlib import Path
import json,math,statistics,hashlib,collections,datetime
V=Path(__file__).parent; P=V/'lifecycle-query-aa-diagnostic'
assert (P/'summary.json').exists(), 'wait for completed summary'
rows=[json.loads(x) for x in (P/'samples.jsonl').read_text().splitlines()]
proto=json.loads((P/'protocol.json').read_text()); original=json.loads((V/'server-analysis.json').read_text())
def q(v,p): return sorted(v)[max(0,math.ceil(len(v)*p)-1)]
def rng(v): return {'min':min(v),'max':max(v),'span':max(v)-min(v)}
req=[x for x in rows if x['kind']=='request']; measured=[x for x in req if x.get('phase')=='measured']; warm=[x for x in req if x.get('phase')=='warmup']; batches=[x for x in rows if x['kind']=='query_batch']; resources=[x for x in rows if x['kind']=='server_resources']
assert len(measured)==4800 and len(warm)==120 and len(batches)==48 and len(resources)==6
assert all(x['ok'] for x in measured+warm+batches+resources)
assert all(x['cpu_before']['cpu_counter']=='proc_pid_rusage-v2-mach-ticks-converted-to-seconds' and x['cpu_after']['cpu_counter']=='proc_pid_rusage-v2-mach-ticks-converted-to-seconds' for x in batches)
for x in batches:
 d=x['cpu_after'];b=x['cpu_before']; expected=(sum(d['raw_cpu_ticks'].values())-sum(b['raw_cpu_ticks'].values()))*d['mach_timebase']['numer']/d['mach_timebase']['denom']/1e9
 assert math.isclose(expected,x['cpu_seconds'],abs_tol=1e-10)
assert proto['variants']['baseline']==proto['variants']['candidate']
exe=Path(proto['variants']['baseline']['server_command'][0]); actual=hashlib.sha256(exe.read_bytes()).hexdigest(); assert actual==proto['variants']['baseline']['server_binary_sha256']
ids=[x for x in rows if x['kind']=='graph_identity']; origrows=[json.loads(x) for x in (V/'lifecycle-query-final/samples.jsonl').read_text().splitlines()]; origids=[x for x in origrows if x['kind']=='graph_identity']
for g in {x['graph']['id'] for x in ids}:
 vals=[x['files'] for x in ids+origids if x['graph']['id']==g]; assert all(x==vals[0] for x in vals)
parity=[]
for name in sorted({x['case'] for x in req if x['ok']}):
 found={(x['digest'],x['row_count']) for x in req if x['ok'] and x['case']==name}; prior={(x['digest'],x['row_count']) for x in origrows if x['kind']=='request' and x['ok'] and x['case']==name};assert len(found)==1 and found==prior
 parity.append({'case':name,'digest':next(iter(found))[0],'rowCount':next(iter(found))[1],'allSuccessfulRequestsAndOriginalABMatch':True})
results=[]
for name in [x['name'] for x in proto['protocol']['cases']]:
 for c in [1,4]:
  case={'case':name,'concurrency':c,'scope':['signal-ios','swiftpm','signal-server']}
  for label in ['baseline','candidate']:
   a=[x for x in measured if x['case']==name and x['concurrency']==c and x['variant']==label]; per=[]
   for r in range(3):
    s=[x for x in a if x['round']==r]; assert len(s)==100 and sorted(x['sample'] for x in s)==list(range(100)); b=next(x for x in batches if x['case']==name and x['concurrency']==c and x['variant']==label and x['round']==r)
    per.append({'round':r,'samples':len(s),'p50_ms':q([x['wall_ms'] for x in s],.5),'p95_ms':q([x['wall_ms'] for x in s],.95),'cpu_seconds':b['cpu_seconds'],'batch_wall_ms':b['wall_ms'],'processIdentity':b['cpu_before']['identity'],'batchEndNs':b['recorded_ns']})
   case[label]={'samples':len(a),'pooled_p50_ms':q([x['wall_ms'] for x in a],.5),'pooled_p95_ms':q([x['wall_ms'] for x in a],.95),'rounds':per,'cpu_median_seconds':statistics.median(x['cpu_seconds'] for x in per)}
  case['pairedDeltas']=[{'round':r,**{k:case['candidate']['rounds'][r][k]-case['baseline']['rounds'][r][k] for k in ['p50_ms','p95_ms','cpu_seconds','batch_wall_ms']}} for r in range(3)]
  case['pooledLabelDelta']={k:case['candidate']['pooled_'+k]-case['baseline']['pooled_'+k] for k in ['p50_ms','p95_ms']}
  six=case['baseline']['rounds']+case['candidate']['rounds'];case['sixProcessRange']={k:rng([x[k] for x in six]) for k in ['p50_ms','p95_ms','cpu_seconds','batch_wall_ms']}
  ab=next(x for x in original['queryCases'] if x['case']==name and x['concurrency']==c);case['originalAB']={'baseline_p50_ms':ab['baseline']['p50_ms'],'candidate_p50_ms':ab['candidate']['p50_ms'],'baseline_p95_ms':ab['baseline']['p95_ms'],'candidate_p95_ms':ab['candidate']['p95_ms'],'p95_delta_ms':ab['delta']['p95_ms']['absolute'],'cpu_delta_percent':ab['delta']['batch_cpu_seconds']['percent'],'status':ab['status']}
  results.append(case)
failed=[x for x in req if not x['ok']]; unexpected=[x for x in failed if x.get('phase')!='readiness' or 'Connection refused' not in x.get('error','')]
failedOther=[x for x in rows if x['kind']!='request' and x.get('ok') is False]
out={'purpose':'Independent same-candidate-binary A/A diagnosis only. Does not replace, invalidate, or establish recovery from failed original A/B.','sourceFiles':{'samples':str(P/'samples.jsonl'),'protocol':str(P/'protocol.json'),'summary':str(P/'summary.json'),'originalAB':str(V/'server-analysis.json'),'contention':str(V/'server-diagnostic-aa-contention.jsonl'),'environment':str(V/'server-diagnostic-aa-early-environment.json')},'sampling':'3 paired rounds AB,BA,AB; 6 fresh instances of same candidate; 4 cases; 5 warmups per case; 100 measured requests/case/concurrency at1,4. Nearest-rank pooled p50/p95 and separate process/round distributions. CPU uses emitted corrected counters, independently checked against raw Mach ticks.','binary':{'sameVariantConfig':True,'sha256':actual,'path':str(exe),'revision':proto['variants']['baseline']['revision']},'identicalThreeGraphFingerprintsWithinAAAndToOriginalAB':True,'counts':{'measured':len(measured),'warmup':len(warm),'batches':len(batches),'processes':len(resources),'requestsByPhaseAndStatus':dict(collections.Counter(x['phase']+':'+str(x['ok']) for x in req))},'parity':parity,'failures':{'readinessFailures':failed,'unexpectedRequestFailures':unexpected,'otherFailedRecords':failedOther},'queryCases':results,'processResources':resources,'contentionEvidence':[json.loads(x) for x in (V/'server-diagnostic-aa-contention.jsonl').read_text().splitlines()],'environmentEvidence':json.loads((V/'server-diagnostic-aa-early-environment.json').read_text()),'limitations':['Concurrent unrelated benchmark activity was observed during this A/A; no quiet-host claim is supported. Exact overlap for every request is unknown.','The contention observations do not establish that original A/B experienced contention.','Identical executable A/A spread includes process/run/environment effects. It neither measures a code change nor proves main/candidate equivalence.','Do not pool with original A/B, discard unfavorable rounds, subtract measured spread from regressions, or substitute the best A/A run. Original acceptance remains not passed.']}
assert not unexpected and not failedOther
summary=json.loads((P/'summary.json').read_text())
assert summary['ok'] and len(summary['queries'])==16
for x in summary['queries']:
 y=next(t for t in results if t['case']==x['case'] and t['concurrency']==x['concurrency'])[x['variant']]
 assert x['samples']==y['samples'] and x['p50_ms']==y['pooled_p50_ms'] and x['p95_ms']==y['pooled_p95_ms']
 for r in x['rounds']:
  assert all(r[k]==y['rounds'][r['round']][k] for k in ['samples','p50_ms','p95_ms'])
out['independentRecomputationMatchesHarnessSummary']=True
out['binary']['buildRevision']='77ec (earlier local native CLI build)'
out['binary']['sourceEquivalence']='No Rust/Cargo source changes from77ec through357a6206; final Apple frontend is357a6206, a separate binary. Exact native artifact identity is its SHA256, not the final frontend revision.'
out['binary']['codegenEvidence']=str(V/'server-codegen-diagnosis.json')
out['limitations'].append('Six-process min/max spread is descriptive, not a confidence interval or adjustment factor for the earlier A/B deltas.')
(V/'server-aa-analysis.json').write_text(json.dumps(out,indent=2)+'\n')
for x in results: print(x['case'],x['concurrency'],'labels',*(round(x[k]['pooled_p95_ms'],3) for k in ['baseline','candidate']),'p95range',x['sixProcessRange']['p95_ms'],'CPU',x['sixProcessRange']['cpu_seconds'])
print('COUNTS',out['counts'])
