#!/usr/bin/env python3
"""Real binary profiling contract checks, not a performance benchmark."""
import hashlib,json,os,pathlib,re,signal,subprocess,sys,tempfile,urllib.request
root=pathlib.Path(__file__).resolve().parent;out=root/'evidence';out.mkdir(exist_ok=True);binary=pathlib.Path(sys.argv[1]).resolve();base=os.environ.copy();base.pop('GRAPHITE_NATIVE_CPU_PROFILE',None);base.pop('GRAPHITE_PROFILE',None);observations=[]
def embedded(path):
 data=path.read_text();m=re.search(r'<script id="profile-data" type="application/json">(.*?)</script>',data,re.S);assert m;return json.loads(m.group(1))
def cli(name,args,enabled,path,cwd):
 env=base.copy();env['GRAPHITE_NATIVE_CPU_PROFILE']=enabled
 if path is not None:env['GRAPHITE_PROFILE']=str(path)
 p=subprocess.run([str(binary),*args],cwd=cwd,env=env,capture_output=True,text=True,timeout=20)
 observations.append({'name':name,'args':args,'enabled':enabled,'output':str(path),'returncode':p.returncode,'stdout':p.stdout,'stderr':p.stderr})
 return p
with tempfile.TemporaryDirectory(prefix='graphite-profile-cli-') as tmp:
 tmp=pathlib.Path(tmp)
 for enabled in ['', '0','true']:
  path=tmp/'disabled.html';p=cli('disabled-help-'+enabled,['--help'],enabled,path,tmp);assert p.returncode==0 and not path.exists()
 p=cli('enabled-help-default',['--help'],'1',None,tmp);assert p.returncode==0;embedded(tmp/'profile.html');(out/'help-default.html').write_bytes((tmp/'profile.html').read_bytes())
 path=out/'invalid-args.html';p=cli('invalid-args-flush',['--not-an-option'],'1',path,tmp);assert p.returncode!=0;embedded(path)
 p=cli('start-output-failure',['--help'],'1',tmp/'missing'/'report.html',tmp);assert p.returncode!=0 and 'start CPU profile' in p.stderr
 target=tmp/'existing-directory';target.mkdir();(target/'keep').write_text('old');p=cli('stop-output-failure',['--help'],'1',target,tmp);assert p.returncode!=0 and 'save CPU report' in p.stderr and (target/'keep').read_text()=='old'

query='UNWIND range(1,1000) AS n RETURN sum(sin(n)+cos(n)+sqrt(n)) AS total'
for enabled in [False,True]:
 with tempfile.TemporaryDirectory(prefix='graphite-profile-serve-') as tmp:
  tmp=pathlib.Path(tmp);path=out/('enabled-server.html' if enabled else 'disabled-server.html');env=base.copy();env['GRAPHITE_PROFILE']=str(path)
  if enabled:env['GRAPHITE_NATIVE_CPU_PROFILE']='1'
  p=subprocess.Popen([str(binary),'--data',str(tmp/'data'),'--port','0'],env=env,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
  startup=[];port=None
  try:
   for _ in range(10):
    line=p.stderr.readline();startup.append(line);m=re.search(r'http://localhost:(\d+)',line)
    if m:port=int(m.group(1));break
    if not line:raise AssertionError('startup failed '+''.join(startup))
   assert port
   responses=[]
   # CPU sampling evidence only: no timing, throughput, latency, or speedup assertion.
   for _ in range(30):
    request=urllib.request.Request(f'http://127.0.0.1:{port}/api/cypher',data=json.dumps({'query':query}).encode(),headers={'Content-Type':'application/json'})
    with urllib.request.urlopen(request,timeout=20) as response:responses.append(json.load(response))
   assert all(r==responses[0] for r in responses)
   p.send_signal(signal.SIGTERM);stdout,stderr=p.communicate(timeout=20);assert p.returncode==143
   record={'name':'serve-'+str(enabled),'enabled':enabled,'returncode':p.returncode,'signal':'SIGTERM','query':query,'requestCount':30,'allResponsesEqual':True,'response':responses[0],'stdout':stdout,'stderr':''.join(startup)+stderr}
   if enabled:
    report=embedded(path);assert int(report['root']['value'])>0;record['sampledCPUNanos']=report['root']['value'];record['reportSHA256']=hashlib.sha256(path.read_bytes()).hexdigest()
   else:assert not path.exists()
   observations.append(record)
  finally:
   if p.poll() is None:p.kill();p.communicate()
(out/'binary-observations.json').write_text(json.dumps(observations,indent=2)+'\n');print('binary checks',len(observations),'passed')
