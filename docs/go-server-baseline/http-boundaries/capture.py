import json,pathlib,subprocess,tempfile,time,urllib.request,urllib.error,shutil
root=pathlib.Path.cwd();out=root/'docs/go-server-baseline/http-boundaries';out.mkdir(exist_ok=True)
work=pathlib.Path(tempfile.mkdtemp(prefix='graphite-http-boundaries-'));shutil.copytree(root/'graphite-server/internal/store/testdata/jvm-v3',work/'fixture')
cases=[]
def add(name,path,method,body):cases.append({'name':name,'path':path,'method':method,'body':body})
for surface,path in [('scoped','/api/graphs/a/cypher'),('root','/api/cypher'),('selected','/api/cypher/graphs')]:
 for method in ['GET','POST']:
  for i,q in enumerate([None,'',' ','RETURN 1 AS x',['RETURN 1 AS x'],[],['RETURN 1 AS x','RETURN 2 AS x'],{}]):
   body={'query':q}
   if surface=='selected':body['graphs']=['a']
   add(f'{surface}-{method}-query-{i}',path+'?query=RETURN%202%20AS%20x',method,body)
  for i,body in enumerate([None,{},[],[{}],'bad',42,True]):add(f'{surface}-{method}-object-{i}',path+'?query=RETURN%202%20AS%20x&graph=a',method,body)
  for i,v in enumerate([None,0,-1,True,'',1.5,'1e3',[1000],{},'1000']):
   body={'query':'RETURN 1 AS x','timeoutMs':v}
   if surface=='selected':body['graphs']=['a']
   add(f'{surface}-{method}-timeout-{i}',path,method,body)
for key in ['graphs','allGraphs','mode','limit','perGraphLimit','includeGraphRows']:
 for i,v in enumerate([None,True,42,'a',[],['a'],{},['a','a']]):
  body={'query':'RETURN 1 AS x','graphs':['a']};body[key]=v
  add(f'selection-{key}-{i}','/api/cypher/graphs','POST',body)
for i,v in enumerate([None,True,42,'nonexistent',[],['nonexistent'],{},['a','b']]):add(f'load-path-{i}','/api/graphs/new','PUT',{'path':v})
for i,v in enumerate([None,True,42,'bogus',[],['bogus'],{}]):add(f'load-mode-{i}','/api/graphs/new','PUT',{'path':str(work/'fixture'),'loadMode':v})
(out/'cases.json').write_text(json.dumps({'cases':cases},indent=2)+'\n')
commands={'main':['java','-Xmx1g','-jar','/tmp/graphite-go-main-baseline-clone-4e328b0/graphite-explore/build/libs/graphite-explore.jar'],'native':['/tmp/graphite-server-feature-parity']}
observations={}
def request(port,c):
 req=urllib.request.Request(f'http://127.0.0.1:{port}'+c['path'],method=c['method'],data=json.dumps(c['body']).encode(),headers={'Accept':'application/json','Content-Type':'application/json'})
 try:r=urllib.request.urlopen(req,timeout=10)
 except urllib.error.HTTPError as e:r=e
 with r:
  raw=r.read().decode();body=raw
  try:body=json.loads(raw)
  except ValueError:pass
  if isinstance(body,dict) and isinstance(body.get('graph'),dict):body['graph']['loadedAt']='<dynamic>'
  return {'status':r.status,'contentType':r.headers.get('Content-Type'),'body':body}
for index,(name,base) in enumerate(commands.items()):
 port=19861+index;cmd=base+['--data',str(work),'--port',str(port),'--graph',f'a:{work}/fixture'];(out/f'{name}-command.json').write_text(json.dumps(cmd,indent=2)+'\n')
 with (out/f'{name}.log').open('w') as log:
  p=subprocess.Popen(cmd,stdout=log,stderr=log)
  try:
   for attempt in range(100):
    try:request(port,{'path':'/api/graphs','method':'GET','body':None});break
    except OSError:
     if p.poll() is not None:raise RuntimeError(name+' startup failed')
     time.sleep(.1)
   else:raise RuntimeError('startup timeout')
   observations[name]=[request(port,c) for c in cases]
  finally:p.terminate();p.wait(timeout=10)
errors=[{'case':c['name'],'baseline':a,'candidate':b} for c,a,b in zip(cases,observations['main'],observations['native']) if a!=b]
(out/'initial.json').write_text(json.dumps({'cases':cases,'observations':observations,'differences':errors},indent=2)+'\n')
print(json.dumps({'cases':len(cases),'differences':len(errors),'first':errors[:12]}))
