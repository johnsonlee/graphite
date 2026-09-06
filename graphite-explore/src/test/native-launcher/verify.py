#!/usr/bin/env python3
"""Actual JAR/process correctness. No remote release or performance experiment."""
from pathlib import Path
import os,subprocess,json,sys,tempfile,time,signal,hashlib,urllib.request,re,platform,zipfile,shutil
repo=Path(sys.argv[1]).resolve();build=Path(sys.argv[2]).resolve();native=Path(sys.argv[3]).resolve();out=Path(sys.argv[4]).resolve();out.mkdir(parents=True,exist_ok=True)
helper=out/'child';helper_command=['go','build','-o',str(helper),str(repo/'graphite-explore/src/test/native-launcher/child.go')]
subprocess.run(helper_command,check=True,env={**os.environ,'CGO_ENABLED':'0'})
jars={'unified':build/'graphite-query/build/libs/graphite.jar','explore':build/'graphite-explore/build/libs/graphite-explore.jar'}
results=[]
def record_result(value):
 results.append(value)
 (out/'results.json').write_text(json.dumps(results,indent=2)+'\n')
def exception_hook(kind,value,tb):
 (out/'failure.json').write_text(json.dumps({'type':kind.__name__,'error':str(value),'completedChecks':len(results)},indent=2)+'\n')
 sys.__excepthook__(kind,value,tb)
sys.excepthook=exception_hook
java=['java','-Dfile.encoding=UTF-8','-Xmx128m','-XX:ActiveProcessorCount=2']
def waitfile(path,p):
 deadline=time.monotonic()+15
 while not path.exists():
  if p.poll() is not None:raise AssertionError('parent exited before child ready')
  if time.monotonic()>deadline:raise AssertionError('child did not start')
  time.sleep(.02)
def alive(pid):
 try:os.kill(pid,0);return True
 except ProcessLookupError:return False
for name,jar in jars.items():
 prefix=['serve'] if name=='unified' else []
 with tempfile.TemporaryDirectory(prefix='graphite bridge space ') as tmp:
  folder=Path(tmp);record=folder/'record.json';signalfile=folder/'signal.txt'
  env={**os.environ,'GRAPHITE_SERVER_BINARY':str(helper),'GRAPHITE_BRIDGE_TEST_VALUE':'value with spaces;$(literal)','GRAPHITE_BRIDGE_TEST_RECORD':str(record),'GRAPHITE_BRIDGE_TEST_SIGNAL':str(signalfile),'GRAPHITE_BRIDGE_TEST_MODE':'normal'}
  args=['--data','space path;$(literal)','--graph','app:path with spaces']
  command=java+['-jar',str(jar)]+prefix+args
  p=subprocess.run(command,cwd=folder,env=env,input='stdin payload\n',capture_output=True,text=True,timeout=20)
  child=json.loads(record.read_text());assert child['args']==['serve']+args;assert child['stdin']=='stdin payload\n';assert Path(child['cwd']).resolve()==folder.resolve();assert child['environment']==env['GRAPHITE_BRIDGE_TEST_VALUE'];assert p.returncode==37
  assert p.stdout=='native child stdout\n' and p.stderr=='native child stderr\n'
  record_result({'name':name+'-argv-stdio-exit','command':command,'exitCode':p.returncode,'child':child,'stdout':p.stdout,'stderr':p.stderr})
  # Actual JAR resource extraction, independent of the explicit-path resolver.
  platform_name=('darwin' if platform.system()=='Darwin' else 'linux')+'-'+('arm64' if platform.machine() in ['arm64','aarch64'] else 'amd64')
  entry=platform_name+'/graphite-server'
  embedded=folder/'embedded.jar';shutil.copy2(jar,embedded)
  with zipfile.ZipFile(embedded,'a') as archive:
   archive.writestr('graphite-native/SHA256SUMS',hashlib.sha256(helper.read_bytes()).hexdigest()+'  '+entry+'\n')
   archive.write(helper,'graphite-native/'+entry)
  record.unlink();embedded_env={k:v for k,v in env.items() if k!='GRAPHITE_SERVER_BINARY'}
  embedded_command=java+['-jar',str(embedded)]+prefix+args
  p=subprocess.run(embedded_command,cwd=folder,env=embedded_env,input='embedded stdin\n',capture_output=True,text=True,timeout=20)
  child=json.loads(record.read_text());assert p.returncode==37 and child['stdin']=='embedded stdin\n'
  assert child['args']==['serve']+args and not Path(child['executable']).exists() and not Path(child['executable']).parent.exists()
  record_result({'name':name+'-embedded-resource','exitCode':p.returncode,'child':child,'extractedDirectoryRemoved':True,'stdout':p.stdout,'stderr':p.stderr})
  corrupt=folder/'corrupt.jar';shutil.copy2(jar,corrupt)
  with zipfile.ZipFile(corrupt,'a') as archive:
   archive.writestr('graphite-native/SHA256SUMS','0'*64+'  '+entry+'\n')
   archive.write(helper,'graphite-native/'+entry)
  record.unlink()
  p=subprocess.run(java+['-jar',str(corrupt)]+prefix+args,cwd=folder,env=embedded_env,capture_output=True,text=True,timeout=20)
  assert p.returncode==1 and 'checksum mismatch' in p.stderr and not record.exists()
  record_result({'name':name+'-corrupt-embedded-resource','exitCode':p.returncode,'childStarted':False,'stdout':p.stdout,'stderr':p.stderr})
  # Exact same commands without a configured binary must not start Kotlin.
  absent={k:v for k,v in os.environ.items() if k!='GRAPHITE_SERVER_BINARY'}
  p=subprocess.run(java+['-jar',str(jar)]+prefix+['--data',str(folder)],env=absent,capture_output=True,text=True,timeout=15)
  assert p.returncode==1 and 'Native server resources are missing' in p.stderr
  record_result({'name':name+'-missing-resource','exitCode':p.returncode,'stdout':p.stdout,'stderr':p.stderr})
  for target,sig,child_mode in [('parent',signal.SIGTERM,'signal'),('group',signal.SIGINT,'signal'),('group',signal.SIGTERM,'signal'),('parent',signal.SIGTERM,'ignore')]:
   record.unlink(missing_ok=True);signalfile.unlink(missing_ok=True);env['GRAPHITE_BRIDGE_TEST_MODE']=child_mode
   p=subprocess.Popen(command,cwd=folder,env=env,stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,start_new_session=True)
   childpid=None
   try:
    waitfile(record,p);childpid=json.loads(record.read_text())['pid']
    if target=='group':os.killpg(p.pid,sig)
    else:os.kill(p.pid,sig)
    stdout,stderr=p.communicate(timeout=15)
    assert not alive(childpid),f'orphan child {childpid}'
    assert signalfile.exists(),'child did not observe shutdown signal'
    assert p.returncode in [128+sig,24],(p.returncode,stdout,stderr)
    record_result({'name':name+'-'+target+'-'+sig.name+'-'+child_mode,'exitCode':p.returncode,'childSignal':signalfile.read_text(),'childGone':True,'stdout':stdout,'stderr':stderr})
   finally:
    if p.poll() is None:p.kill();p.wait()
    if childpid and alive(childpid):os.kill(childpid,signal.SIGKILL)
  # Actual native server, using the independently JVM-written four-CallSite graph.
  fixture=repo/'graphite-server/internal/store/testdata/callsite-index/store'
  env['GRAPHITE_SERVER_BINARY']=str(native)
  errors=folder/'server.stderr'
  with errors.open('w') as stderr:
   p=subprocess.Popen(java+['-jar',str(jar)]+prefix+['--id','tiny',str(fixture),'--port','0'],env=env,stdout=subprocess.PIPE,stderr=stderr,text=True)
   try:
    deadline=time.monotonic()+20;port=None
    while time.monotonic()<deadline:
     text=errors.read_text();match=re.search(r'http://localhost:(\d+)',text)
     if match:port=int(match[1]);break
     if p.poll() is not None:raise AssertionError(text)
     time.sleep(.02)
    assert port
    with urllib.request.urlopen(f'http://localhost:{port}/api/graphs',timeout=10) as response:catalog=json.load(response)
    with urllib.request.urlopen(f'http://localhost:{port}/',timeout=10) as response:ui=response.read()
    assert catalog['graphs'][0]['id']=='tiny';assert catalog['graphs'][0]['nodes']==4
    assert ui==(repo/'graphite-server/internal/web/assets/index.html').read_bytes()
    p.terminate();p.wait(timeout=15)
    record_result({'name':name+'-native-http','catalog':catalog,'uiSHA256':hashlib.sha256(ui).hexdigest(),'exitCode':p.returncode,'command':p.args})
   finally:
    if p.poll() is None:p.kill();p.wait()
(out/'results.json').write_text(json.dumps(results,indent=2)+'\n')
(out/'identity.json').write_text(json.dumps({'repo':str(repo),'buildSnapshot':str(build),'jars':{str(j):hashlib.sha256(j.read_bytes()).hexdigest() for j in jars.values()},'native':str(native),'nativeSHA256':hashlib.sha256(native.read_bytes()).hexdigest(),'helperCommand':helper_command,'checks':len(results),'purpose':'CLI/JAR/process correctness only'},indent=2)+'\n')
print(json.dumps({'checks':len(results),'passed':True}))
