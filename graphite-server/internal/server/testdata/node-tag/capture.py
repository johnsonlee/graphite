"""Disposable tiny HTTP correctness oracle; no timing or benchmark workload."""
import argparse,json,pathlib,shutil,subprocess,tempfile,time,urllib.request,urllib.error,socket
p=argparse.ArgumentParser();p.add_argument('--runtime',choices=['main','native'],required=True);p.add_argument('--exe',required=True);p.add_argument('--out',required=True);p.add_argument('--index-from');args=p.parse_args()
out=pathlib.Path(args.out);out.mkdir(parents=True,exist_ok=True);module=pathlib.Path(__file__).resolve().parents[4];fixture_source=module/'internal/query/testdata/traversal';all_results=[];commands=[]
def request(port,path,method='GET',body=None):
 data=None if body is None else json.dumps(body).encode();req=urllib.request.Request(f'http://127.0.0.1:{port}'+path,data=data,method=method,headers={'Accept':'application/json','Content-Type':'application/json'})
 try:r=urllib.request.urlopen(req,timeout=10)
 except urllib.error.HTTPError as e:r=e
 with r:
  raw=r.read().decode();return {'status':r.status,'contentType':r.headers.get('Content-Type'),'rawBody':raw}
for mode in ['MAPPED','EAGER']:
 work=pathlib.Path(tempfile.mkdtemp(prefix='graphite-node-tag-http-'));fixture=work/'fixture';shutil.copytree(fixture_source,fixture)
 if args.index_from:shutil.copy(args.index_from,fixture/'graph.nodeindex')
 with socket.socket() as s:s.bind(('127.0.0.1',0));port=s.getsockname()[1]
 base=['/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home/bin/java','-Xmx256m','-jar',args.exe]if args.runtime=='main'else[args.exe]
 cmd=base+['--data',str(work),'--port',str(port),'--load-mode',mode,'--graph',f'a:{fixture}'];commands.append({'mode':mode,'command':cmd})
 with (out/(mode+'.log')).open('w')as log:
  proc=subprocess.Popen(cmd,stdout=log,stderr=log)
  try:
   for _ in range(100):
    try:request(port,'/api/graphs');break
    except OSError:
     if proc.poll()is not None:raise RuntimeError('startup failed')
     time.sleep(.1)
   else:raise RuntimeError('startup timeout')
   def capture(name,path,method='GET',body=None):
    all_results.append({'mode':mode,'tag':tag,'name':name,'request':{'path':path,'method':method,'body':body},'fixture':str(fixture),'response':request(port,path,method,body)})
   for tag in [16,127,128,255]:
    with (fixture/'graph.nodedata').open('r+b')as f:f.seek(75);f.write(bytes([tag]))
    capture('node','/api/graphs/a/node/7');capture('subgraph','/api/graphs/a/subgraph?center=7&depth=0');capture('missing-node','/api/graphs/a/node/999');capture('outgoing','/api/graphs/a/node/7/outgoing')
    for name,path,body in [('scoped','/api/graphs/a/cypher',{}),('root','/api/cypher',{}),('selected','/api/cypher/graphs',{'graphs':['a']}),('fanout','/api/cypher/graphs',{'graphs':['a'],'mode':'fanout'})]:
     capture(name,path,'POST',dict(body,query='MATCH (n:IntConstant) RETURN n LIMIT 8'))
    capture('bounded-before-tail','/api/graphs/a/cypher','POST',{'query':'MATCH (n:IntConstant) RETURN n.id AS id LIMIT 1'})
    capture('projection-before-tail','/api/graphs/a/cypher','POST',{'query':"MATCH (n:IntConstant) RETURN substring('x','bad') AS x LIMIT 1"})
    for load_mode in ['MAPPED','EAGER','AUTO']:capture('load-'+load_mode,'/api/graphs/new'+str(tag)+load_mode,'PUT',{'path':str(fixture),'loadMode':load_mode})
  finally:
   proc.terminate();proc.wait(timeout=10);commands[-1]['exitCodeAfterTermination']=proc.returncode
(out/'commands.json').write_text(json.dumps(commands,indent=2)+'\n');(out/'observations.json').write_text(json.dumps(all_results,indent=2)+'\n');print('observations',len(all_results))
