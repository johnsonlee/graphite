#!/usr/bin/env python3
"""64-real-graph root HTTP experiment, with mandatory catalog and response parity."""
import argparse, concurrent.futures, csv, hashlib, json, math, pathlib, platform, random, subprocess, time
from http_parity import fetch, signature

MAIN='4e328b0109e13c896b74004823fb049fcb19251a'
def digest(path):
 h=hashlib.sha256()
 with path.open('rb') as stream:
  for block in iter(lambda:stream.read(1024*1024),b''):h.update(block)
 return h.hexdigest()
def percentiles(values):
 values=sorted(values)
 return {f'p{p}Ms':values[max(0,math.ceil(len(values)*p/100)-1)] if len(values)>=200 else None for p in (50,95,99)}
def process_sample(pid):
 return subprocess.run(['ps','-p',str(pid),'-o','pid=,time=,rss='],text=True,capture_output=True).stdout.strip()
def main():
 p=argparse.ArgumentParser(description=__doc__)
 p.add_argument('--baseline-url',required=True);p.add_argument('--candidate-url')
 p.add_argument('--baseline-revision',required=True);p.add_argument('--candidate-revision')
 p.add_argument('--baseline-artifact',required=True,type=pathlib.Path);p.add_argument('--candidate-artifact',type=pathlib.Path)
 p.add_argument('--baseline-pid',required=True,type=int);p.add_argument('--candidate-pid',type=int)
 p.add_argument('--fixture-root',required=True,type=pathlib.Path)
 p.add_argument('--baseline-fixture-root',required=True,type=pathlib.Path);p.add_argument('--candidate-fixture-root',type=pathlib.Path)
 p.add_argument('--manifest',required=True,type=pathlib.Path);p.add_argument('--output',required=True,type=pathlib.Path)
 p.add_argument('--samples',type=int,default=200);p.add_argument('--warmups',type=int,default=3)
 p.add_argument('--concurrency',type=int,choices=(1,4),required=True);p.add_argument('--seed',type=int,default=20260907)
 p.add_argument('--capture-first-replay',action='store_true',help='Only when servers are freshly started and have received no queries')
 a=p.parse_args()
 if a.baseline_revision!=MAIN:p.error('Baseline must be the pinned remote main revision')
 if a.samples<200 or a.warmups<1:p.error('Need at least200 samples/case and1 warmup')
 if a.candidate_url and not all([a.candidate_revision,a.candidate_artifact,a.candidate_pid,a.candidate_fixture_root]):p.error('Candidate requires exact revision/artifact/pid/fixture-root')
 manifest=json.loads(a.manifest.read_text());cases=manifest['cases']
 expected_ids=manifest.get('requiredGraphIds',[])
 if manifest.get('graphCount')!=64 or len(expected_ids)!=64 or len(set(expected_ids))!=64:p.error('Full64 catalog required')
 if len(cases)!=42 or any(c['path']!='/api/cypher' or c.get('phase')!='query' for c in cases):p.error('Complete42-case root cross-graph workload required; no scoped/fanout substitute')
 if digest(a.fixture_root/'graphs.tsv')!=manifest['fixtureManifestSha256'] or digest(a.fixture_root/'fixture-provenance.tsv')!=manifest['fixtureProvenanceSha256']:p.error('Fixture identity mismatch')
 sources={r['graphId']:r for r in csv.DictReader((a.fixture_root/'fixture-provenance.tsv').open(),delimiter='\t')}
 if set(sources)!=set(expected_ids):p.error('Provenance must contain precisely64 required IDs')
 a.output.mkdir(parents=True,exist_ok=True)
 runtimes={'baseline':(a.baseline_url,a.baseline_pid,a.baseline_fixture_root)}
 if a.candidate_url:runtimes['candidate']=(a.candidate_url,a.candidate_pid,a.candidate_fixture_root)
 catalogs={}
 for runtime,(url,pid,root) in runtimes.items():
  catalog=fetch(url,{'path':'/api/graphs'},'')
  catalogs[runtime]=catalog
  data=catalog.get('json',{})
  if catalog['status']!=200 or data.get('count')!=64 or [x['id'] for x in data.get('graphs',[])]!=expected_ids:p.error(runtime+' does not serve exactly64 required sorted IDs')
  for graph in data['graphs']:
   source=sources[graph['id']]
   if graph['nodes']!=int(source['nodeCount']) or graph['callSites']!=int(source['callSiteCount']):p.error(runtime+' graph stats differ from real fixture provenance')
   if pathlib.Path(graph['path']).resolve()!=(root/graph['id']).resolve():p.error(runtime+' graph path differs from declared fixture')
  if not process_sample(pid):p.error(runtime+' process is not live')
 if a.candidate_url:
  keys=('id','loadMode','nodes','edges','methods','callSites')
  projected=lambda c:[{k:g[k] for k in keys} for g in c['json']['graphs']]
  if projected(catalogs['baseline'])!=projected(catalogs['candidate']):p.error('Catalog stats/load mode differ between runtimes')
 (a.output/'catalogs.json').write_text(json.dumps(catalogs,indent=2)+'\n')
 files=[]
 # Authenticate the original complete fixture against the served copies. Generated
 # runtime sidecars may be extra but cannot substitute for original core files.
 for graph_id in expected_ids:
  for path in sorted((a.fixture_root/graph_id).iterdir()):
   if not path.is_file():continue
   identity=digest(path)
   for runtime,(_,_,root) in runtimes.items():
    copy=root/graph_id/path.name
    if not copy.is_file() or digest(copy)!=identity:p.error(runtime+' fixture byte mismatch: '+str(copy))
   files.append({'graphId':graph_id,'file':path.name,'bytes':path.stat().st_size,'sha256':identity})
 metadata={'baselineRevision':a.baseline_revision,'candidateRevision':a.candidate_revision,'baselineArtifactSha256':digest(a.baseline_artifact),'candidateArtifactSha256':digest(a.candidate_artifact) if a.candidate_artifact else None,'manifestSha256':digest(a.manifest),'fixtureFiles':files,'platform':platform.platform(),'machine':platform.machine(),'python':platform.python_version(),'seed':a.seed,'concurrency':a.concurrency,'samplesPerCasePerRuntime':a.samples,'warmups':a.warmups,'graphCount':64,'baselineOnly':not bool(a.candidate_url),'requestModel':'Closed-loop urllib workers; each sample includes connection/full body reading/UTF8+JSON decoding; response verification is outside the timer','coldState':'First replay of fresh server, OS page cache uncontrolled' if a.capture_first_replay else 'Not claimed; previously used process','acceptance':'Never implied by baseline-only or one warm/concurrency stratum'}
 (a.output/'metadata.json').write_text(json.dumps(metadata,indent=2)+'\n')
 oracle={};initial=[]
 for case in cases:
  record={'case':case}
  for runtime,(url,_,_) in runtimes.items():
   started=time.perf_counter_ns();response=fetch(url,case,'');elapsed=(time.perf_counter_ns()-started)/1e6
   record[runtime]={'durationMs':elapsed,'response':response}
   if response['status']!=200:raise RuntimeError('Preflight HTTP failure: '+case['name'])
   if runtime=='baseline':oracle[case['name']]=signature(response,case)
   elif signature(response,case)!=oracle[case['name']]:
    (a.output/'correctness-failure.json').write_text(json.dumps(record,indent=2)+'\n');raise RuntimeError('Cross-graph response mismatch: '+case['name'])
  initial.append(record)
 (a.output/'initial-replay.json').write_text(json.dumps(initial,indent=2)+'\n')
 for iteration in range(a.warmups):
  for case in cases:
   for runtime,(url,_,_) in runtimes.items():
    response=fetch(url,case,'')
    if response['status']!=200 or signature(response,case)!=oracle[case['name']]:raise RuntimeError('Warmup correctness failed')
 samples={c['name']:{runtime:[] for runtime in runtimes} for c in cases};errors=[];process_samples=[];rng=random.Random(a.seed)
 def timed(runtime,case,iteration):
  started=time.perf_counter_ns()
  try:response=fetch(runtimes[runtime][0],case,'');failure=None
  except Exception as error:response={'status':0,'body':''};failure=repr(error)
  elapsed=(time.perf_counter_ns()-started)/1e6
  correct=not failure and response['status']==200 and signature(response,case)==oracle[case['name']]
  record={'iteration':iteration,'case':case['name'],'runtime':runtime,'durationMs':elapsed,'status':response['status'],'correct':correct,'responseSha256':hashlib.sha256(response['body'].encode()).hexdigest()}
  if not correct:record['failure']=failure;record['response']=response
  return record
 with (a.output/'samples.jsonl').open('w') as out,concurrent.futures.ThreadPoolExecutor(max_workers=a.concurrency) as pool:
  for iteration in range(a.samples):
   order=list(runtimes)
   if iteration%2:order.reverse()
   batch=list(cases);rng.shuffle(batch)
   # Run one runtime's entire block at a time: opposing server remains idle.
   for runtime in order:
    for record in pool.map(lambda c:timed(runtime,c,iteration),batch):
     out.write(json.dumps(record)+'\n');out.flush();samples[record['case']][runtime].append(record['durationMs'])
     if not record['correct']:errors.append(record)
   if iteration%10==0:
    process_samples.append({'iteration':iteration,**{r:process_sample(v[1]) for r,v in runtimes.items()}})
    print('Completed iteration',iteration+1,'/',a.samples,'errors',len(errors),flush=True)
 results=[]
 for name,values in samples.items():
  row={'name':name,**{r:percentiles(v) for r,v in values.items()}}
  if a.candidate_url:row['p95Speedup']=row['baseline']['p95Ms']/row['candidate']['p95Ms']
  results.append(row)
 pooled={r:percentiles([v for item in samples.values() for v in item[r]]) for r in runtimes}
 result={'results':results,'pooledEqualWeightMix':pooled,'incorrectOrErrorSamples':len(errors),'processSamples':process_samples,'completeGoalAchieved':False,'limitations':'Only this warm concurrency stratum; cold replay has one observation/query and no per-query P95; ps CPU/RSS are snapshots not allocations/peak memory; full parity and both1/4 plus repeated cold/warm runs remain required'}
 if a.candidate_url:result['pooledP95Speedup']=pooled['baseline']['p95Ms']/pooled['candidate']['p95Ms'];result['tenfoldEveryCase']=not errors and all(x['p95Speedup']>=10 for x in results)
 (a.output/'results.json').write_text(json.dumps(result,indent=2)+'\n')
 (a.output/'errors.json').write_text(json.dumps(errors,indent=2)+'\n')
 print(json.dumps({'pooled':pooled,'errors':len(errors)}),flush=True)
 return 1 if errors else 0
if __name__=='__main__':raise SystemExit(main())
