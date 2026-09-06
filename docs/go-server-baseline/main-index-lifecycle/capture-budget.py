"""Pinned-main HTTP serialization oracle; correctness only."""
import concurrent.futures,argparse,json,pathlib,subprocess,tempfile,shutil,time,http.client,hashlib,base64
parser=argparse.ArgumentParser();parser.add_argument('--out',type=pathlib.Path,required=True);parser.add_argument('--budget',default=None);args=parser.parse_args()
root=pathlib.Path.cwd();out=args.out;out.mkdir(parents=True,exist_ok=False);work=pathlib.Path(tempfile.mkdtemp(prefix='graphite-surrogate-mapkeys-'));shutil.copytree(root/'graphite-server/internal/store/testdata/jvm-v3',work/'fixture')
jar='/tmp/graphite-go-main-baseline-clone-4e328b0/graphite-explore/build/libs/graphite-explore.jar'
cmd=['/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home/bin/java','-Xmx512m','-jar',jar,'--data',str(work),'--port','19874','--graph',f'a:{work}/fixture']
if args.budget is not None:cmd.insert(2,'-Dgraphite.webgraph.callSiteStringIndexBudgetBytes='+args.budget)
(out/'main-command.json').write_text(json.dumps(cmd,indent=2)+'\n')
queries=['MATCH (n:CallSiteNode) RETURN n.caller_class AS caller,n.callee_name AS callee', "MATCH (n:CallSiteNode) WHERE n.callee_name CONTAINS 'cal' RETURN n.callee_name AS name", "MATCH (n:CallSiteNode) WHERE toLower(n.callee_name) CONTAINS 'cal' OR toLower(n.caller_class) STARTS WITH 'exa' RETURN DISTINCT n.callee_name AS name", "MATCH (n:CallSiteNode) WHERE n.callee_name CONTAINS 'missing-never' RETURN DISTINCT n.callee_name AS name", "MATCH (n:CallSiteNode) WHERE n.callee_name CONTAINS 'cal' RETURN count(n) AS count", 'MATCH (n:CallSiteNode) RETURN DISTINCT n.caller_class AS caller,n.callee_name AS callee']
cases=[]
for surface,path in [('root','/api/cypher'),('scoped','/api/graphs/a/cypher'),('selected','/api/cypher/graphs'),('fanout','/api/cypher/graphs?mode=fanout&includeGraphRows=true')]:
 for i,q in enumerate(queries):cases.append({'name':f'{surface}-{i}','method':'POST','path':path,'body':{'query':q,'graphs':['a']}})
(out/'cases.json').write_text(json.dumps({'cases':cases},indent=2)+'\n')
def request(case):
 c=http.client.HTTPConnection('127.0.0.1',19874,timeout=10)
 try:
  c.request(case['method'],case['path'],json.dumps(case['body']).encode(),{'Accept':'application/json','Content-Type':'application/json'});r=c.getresponse();body=r.read();x={'status':r.status,'headers':r.getheaders(),'bodyBase64':base64.b64encode(body).decode(),'bodyUTF8':body.decode()}
  try:x['json']=json.loads(body)
  except ValueError:pass
  return x
 finally:c.close()
obs=[]
with (out/'main-server.log').open('w') as log:
 p=subprocess.Popen(cmd,stdout=log,stderr=log)
 try:
  for i in range(200):
   try:request({'method':'GET','path':'/api/graphs','body':None});break
   except OSError:
    if p.poll()!=None:raise RuntimeError('startup failed')
    time.sleep(.05)
  else:raise RuntimeError('startup timeout')
  def capture(case):return {'case':case,'response':request(case)}
  with concurrent.futures.ThreadPoolExecutor(max_workers=4) as workers:
   obs=list(workers.map(capture,cases))
  for row in obs:print(row['case']['name'],row['response']['status'],repr(row['response']['bodyUTF8']),flush=True)
 finally:p.terminate();p.wait(timeout=15)
(out/'observations.json').write_text(json.dumps(obs,indent=2)+'\n');(out/'metadata.json').write_text(json.dumps({'purpose':'Correctness only; no performance','mainRevision':'4e328b0109e13c896b74004823fb049fcb19251a','jarSha256':hashlib.sha256(pathlib.Path(jar).read_bytes()).hexdigest(),'cases':len(cases),'pid':p.pid,'exitCode':p.returncode,'dedicatedServerStopped':True},indent=2)+'\n')
