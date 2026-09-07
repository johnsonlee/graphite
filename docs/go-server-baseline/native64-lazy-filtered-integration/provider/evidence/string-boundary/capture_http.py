import pathlib,subprocess,json,urllib.request,time,os,hashlib
p=pathlib.Path(__file__).parent;jar=pathlib.Path('/tmp/graphite-go-main-baseline-clone-4e328b0/graphite-explore/build/libs/graphite-explore.jar');fixture='/tmp/graphite-go-lazy-cde-evidence/main-fixtures/clean-MAPPED'
argv=['/opt/homebrew/opt/openjdk@17/bin/java','-Xmx512m','-jar',str(jar),'--graph','tiny:'+fixture,'--port','18863']
env=dict(os.environ)
for k in ['GRAPHITE_PROFILE','GRAPHITE_NATIVE_CPU_PROFILE','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS']:env.pop(k,None)
with (p/'http-server.log').open('w') as log:
 s=subprocess.Popen(argv,stdout=log,stderr=subprocess.STDOUT,env=env)
 try:
  for i in range(100):
   if s.poll() is not None:raise RuntimeError('startup exit '+str(s.returncode))
   try:
    with urllib.request.urlopen('http://127.0.0.1:18863/api/graphs',timeout=1) as f:catalog=f.read()
    break
   except OSError:time.sleep(.1)
  else:raise RuntimeError('not ready')
  (p/'catalog.raw').write_bytes(catalog)
  cases=["MATCH (n:CallSiteNode) WHERE n.id=17 RETURN n.caller_class AS field,n AS whole","MATCH (n:CallSiteNode) WHERE n.id>=0 RETURN [n,n.id] AS x LIMIT 3"]
  records=[]
  for i,q in enumerate(cases):
   body=json.dumps({'query':q},separators=(',',':')).encode();request=urllib.request.Request('http://127.0.0.1:18863/api/graphs/tiny/cypher',data=body,headers={'Content-Type':'application/json'})
   with urllib.request.urlopen(request,timeout=10) as f:raw=f.read();status=f.status;headers=dict(f.headers)
   (p/f'http-{i}.request').write_bytes(body);(p/f'http-{i}.body').write_bytes(raw)
   records.append({'query':q,'status':status,'headers':headers,'bodySHA256':hashlib.sha256(raw).hexdigest()})
 finally:
  s.terminate()
  try:s.wait(timeout=10)
  except subprocess.TimeoutExpired:s.kill();s.wait()
(p/'http-receipt.json').write_text(json.dumps({'argv':argv,'jarSHA256':hashlib.sha256(jar.read_bytes()).hexdigest(),'records':records,'processExit':s.returncode},indent=2)+'\n')
print((p/'http-0.body').read_bytes())
