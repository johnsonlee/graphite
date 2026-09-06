import json,pathlib,hashlib,subprocess,os,time,importlib.util,urllib.request,datetime
root=pathlib.Path('/Users/johnsonlee/.codex/worktrees/112399f5-4ea0-42da-af34-5ab6ef682c3d/graphite')
out=root/'docs/go-server-baseline/native64-streaming-attempt2/full42';out.mkdir()
spec=importlib.util.spec_from_file_location('parity',root/'graphite-server/scripts/http_parity.py');parity=importlib.util.module_from_spec(spec);spec.loader.exec_module(parity)
cfg=json.loads((root/'docs/go-server-baseline/native64-profile-a7de0bec/config.json').read_text())
source=root/'docs/go-server-baseline/native64-preflight/queries/observations.json';frozen=json.loads(source.read_text())
binary=pathlib.Path('/tmp/graphite-go-stream-match-server-final')
cmd=[str(binary),'--data','/tmp/pr113-exp037-fixture.nXn4fg','--port','18854','--max-concurrent-cypher','4']
for g in cfg['graphs']:cmd+=['--graph',g['id']+':'+g['path']]
def save(name,value):(out/name).write_text(json.dumps(value,indent=2)+'\n')
save('identity.json',{'command':cmd,'binarySHA256':hashlib.sha256(binary.read_bytes()).hexdigest(),'sourceBase':'a7de0bec67ae4dbd8b1dde621b6d9ce7cecef8d0','sourceDelta':'../final-candidate.patch and ../final-scan.go','frozenMainResponses':str(source),'frozenMainResponsesSHA256':hashlib.sha256(source.read_bytes()).hexdigest(),'environment':{k:os.getenv(k) for k in ['GOGC','GODEBUG','GOMEMLIMIT','GOMAXPROCS']},'purpose':'Correctness only. Complete42 HTTP replay against previously captured pinned-main responses; no latency data.'})
p=subprocess.Popen(cmd,stdout=open(out/'server.log','w'),stderr=subprocess.STDOUT)
records=[];started=datetime.datetime.now(datetime.timezone.utc).isoformat()
try:
 for i in range(300):
  if p.poll() is not None:raise RuntimeError('server stopped')
  try:
   with urllib.request.urlopen('http://127.0.0.1:18854/api/graphs',timeout=3) as f:catalog=json.load(f)
   break
  except (OSError,TimeoutError):time.sleep(1)
 else:raise RuntimeError('server not ready')
 assert catalog['count']==64 and catalog['totals']==cfg['totals'],catalog
 for actual,want in zip(catalog['graphs'],cfg['graphs']):
  for k in ['id','nodes','edges','methods','callSites']:assert actual[k]==want[k],(actual,want,k)
 save('catalog.json',catalog)
 for old in frozen:
  c=old['case'];actual=parity.fetch('http://127.0.0.1:18854',c,'')
  equal=parity.signature(old['baseline'],c)==parity.signature(actual,c)
  headers={k:{'expected':old['baseline']['headers'].get(k),'actual':actual['headers'].get(k)} for k in ['Content-Type','Retry-After'] if old['baseline']['headers'].get(k)!=actual['headers'].get(k)}
  records.append({'case':c,'baseline':old['baseline'],'candidate':actual,'equal':equal,'headerDifferences':headers});save('observations.partial.json',records)
  print(c['name'],actual['status'],equal,headers,flush=True)
 save('observations.json',records)
 save('summary.json',{'cases':len(records),'required':42,'complete':len(records)==42,'mismatches':[r['case']['name'] for r in records if not r['equal'] or r['headerDifferences']],'started':started,'finished':datetime.datetime.now(datetime.timezone.utc).isoformat(),'purpose':'Correctness only; no P95 result'})
finally:
 p.terminate()
 try:p.wait(timeout=15)
 except subprocess.TimeoutExpired:p.kill();p.wait()
