import json,pathlib,hashlib,subprocess,os,time,importlib.util,urllib.request,datetime,random,math,concurrent.futures,platform
root=pathlib.Path('/Users/johnsonlee/.codex/worktrees/112399f5-4ea0-42da-af34-5ab6ef682c3d/graphite')
out=root/'docs/go-server-baseline/native64-cache-attempt3/run';out.mkdir()
spec=importlib.util.spec_from_file_location('parity',root/'graphite-server/scripts/http_parity.py');parity=importlib.util.module_from_spec(spec);spec.loader.exec_module(parity)
cfg=json.loads((root/'docs/go-server-baseline/native64-profile-a7de0bec/config.json').read_text())
source=root/'docs/go-server-baseline/native64-preflight/queries/observations.json';frozen=json.loads(source.read_text())
binary=pathlib.Path('/tmp/graphite-go-query-cache-server-8fccf511')
cmd=[str(binary),'--data','/tmp/pr113-exp037-fixture.nXn4fg','--port','18855','--max-concurrent-cypher','4','--metrics']
for g in cfg['graphs']:cmd+=['--graph',g['id']+':'+g['path']]
def save(name,value):(out/name).write_text(json.dumps(value,indent=2)+'\n')
save('identity.json',{'command':cmd,'binarySHA256':hashlib.sha256(binary.read_bytes()).hexdigest(),'sourceBase':'8fccf511','sourceDelta':'../candidate.patch and ../query_cache.go and ../query_cache_test.go','frozenMainResponses':str(source),'frozenMainResponsesSHA256':hashlib.sha256(source.read_bytes()).hexdigest(),'environment':{k:('gctrace=1' if k=='GODEBUG' else os.getenv(k)) for k in ['GOGC','GODEBUG','GOMEMLIMIT','GOMAXPROCS']},'purpose':'Isolated cache experiment. Full64+42 complete main response validation; fresh first replay descriptive, warm200 samples/query c1+c4. Host co-tenancy; not paired acceptance.'})
fixtureFiles=root/'docs/go-server-baseline/native64-profile-a7de0bec/fixture-files.json'
paths={g['id']:pathlib.Path(g['path']) for g in cfg['graphs']}
for f in json.loads(fixtureFiles.read_text()):
 path=paths[f['graphId']]/f['file'];h=hashlib.sha256()
 with path.open('rb') as stream:
  for data in iter(lambda:stream.read(1<<20),b''):h.update(data)
 assert path.stat().st_size==f['bytes'] and h.hexdigest()==f['sha256'],path
save('fixture-verification.json',{'count':len(json.loads(fixtureFiles.read_text())),'manifestSHA256':hashlib.sha256(fixtureFiles.read_bytes()).hexdigest(),'allMatch':True})
env=os.environ.copy();env['GODEBUG']='gctrace=1'
p=subprocess.Popen(cmd,stdout=open(out/'server.log','w'),stderr=subprocess.STDOUT,env=env)
save('process.json',{'pid':p.pid,'platform':platform.platform(),'machine':platform.machine(),'GODEBUG':'gctrace=1','note':'Only candidate live; other agent correctness/compilation work coexists. Cached main responses are correctness oracle, not simultaneous performance comparison.'})
records=[];started=datetime.datetime.now(datetime.timezone.utc).isoformat()
try:
 for i in range(300):
  if p.poll() is not None:raise RuntimeError('server stopped')
  try:
   with urllib.request.urlopen('http://127.0.0.1:18855/api/graphs',timeout=3) as f:catalog=json.load(f)
   break
  except (OSError,TimeoutError):time.sleep(1)
 else:raise RuntimeError('server not ready')
 assert catalog['count']==64 and catalog['totals']==cfg['totals'],catalog
 for actual,want in zip(catalog['graphs'],cfg['graphs']):
  for k in ['id','nodes','edges','methods','callSites']:assert actual[k]==want[k],(actual,want,k)
 save('catalog.json',catalog)
 for old in frozen:
  c=old['case'];start=time.perf_counter_ns();actual=parity.fetch('http://127.0.0.1:18855',c,'');first_ms=(time.perf_counter_ns()-start)/1e6
  equal=parity.signature(old['baseline'],c)==parity.signature(actual,c)
  headers={k:{'expected':old['baseline']['headers'].get(k),'actual':actual['headers'].get(k)} for k in ['Content-Type','Retry-After'] if old['baseline']['headers'].get(k)!=actual['headers'].get(k)}
  records.append({'case':c,'baseline':old['baseline'],'candidate':actual,'equal':equal,'headerDifferences':headers,'firstReplayMs':first_ms});save('observations.partial.json',records)
  print(c['name'],actual['status'],equal,headers,flush=True)
 save('observations.json',records)
 assert len(records)==42 and all(r['equal'] and not r['headerDifferences'] for r in records)
 def stats():
  with urllib.request.urlopen('http://127.0.0.1:18855/metrics') as f:metrics=f.read().decode()
  return {'time':datetime.datetime.now(datetime.timezone.utc).isoformat(),'process':subprocess.check_output(['ps','-p',str(p.pid),'-o','pid=,time=,rss='],text=True).strip(),'metrics':metrics}
 expected={r['case']['name']:parity.signature(r['baseline'],r['case']) for r in records}
 cases=[r['case'] for r in records]
 def one(task):
  case,iteration=task;start=time.perf_counter_ns();response=parity.fetch('http://127.0.0.1:18855',case,'');ms=(time.perf_counter_ns()-start)/1e6
  equal=response['status']==200 and parity.signature(response,case)==expected[case['name']]
  result={'query':case['name'],'iteration':iteration,'ms':ms,'status':response['status'],'equal':equal,'bodySHA256':hashlib.sha256(response['body'].encode()).hexdigest()}
  if not equal:result['response']=response
  return result
 for iteration in range(3):
  for case in cases:assert one((case,-iteration-1))['equal']
 for concurrency in [1,4]:
  tasks=[(c,i) for i in range(200) for c in cases];random.Random(20260907).shuffle(tasks)
  before=stats();samples=[]
  with (out/f'warm-c{concurrency}.jsonl').open('w') as raw:
   with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as pool:
    for result in pool.map(one,tasks):samples.append(result);raw.write(json.dumps(result)+'\n')
  after=stats()
  def pct(xs):
   xs=sorted(xs);return {f'p{p}Ms':xs[max(0,math.ceil(len(xs)*p/100)-1)] for p in [50,95,99]}
  summary={'concurrency':concurrency,'samples':len(samples),'errors':[x for x in samples if not x['equal']],'mix':pct([x['ms'] for x in samples]),'byQuery':{c['name']:pct([x['ms'] for x in samples if x['query']==c['name']]) for c in cases},'before':before,'after':after,'purpose':'Candidate-only warm cache diagnostic on all64. Not paired main speedup, not cold P95, not acceptance.'}
  save(f'warm-c{concurrency}-summary.json',summary);print('WARM',concurrency,summary['mix'],'errors',len(summary['errors']),flush=True)
  assert not summary['errors']
 save('summary.json',{'cases':len(records),'required':42,'complete':len(records)==42,'mismatches':[r['case']['name'] for r in records if not r['equal'] or r['headerDifferences']],'started':started,'finished':datetime.datetime.now(datetime.timezone.utc).isoformat(),'purpose':'42/42 first replay plus warm candidate diagnostics; no paired speedup acceptance'})
finally:
 p.terminate()
 try:p.wait(timeout=15)
 except subprocess.TimeoutExpired:p.kill();p.wait()
