#!/usr/bin/env python3
"""Real main/JAR/native argument expansion correctness; no benchmark."""
import hashlib,json,os,pathlib,re,subprocess,tempfile,time,urllib.request
here=pathlib.Path(__file__).resolve().parent
w=pathlib.Path(json.loads((here/'source.json').read_text())['archive'])
out=here/'literal-args-final';out.mkdir()
records=[]
jars=[('main',pathlib.Path('/tmp/graphite-go-main-baseline-clone-4e328b0/graphite-explore/build/libs/graphite-explore.jar'),[]),('native-explore',w/'graphite-explore/build/libs/graphite-explore.jar',[]),('native-unified',w/'graphite-query/build/libs/graphite.jar',['serve'])]
for name,jar,prefix in jars:
 for case,args,wanted in [('escaped',['--data','@@literal','--port','0'],'@literal'),('double-literal',['--data','@@@literal','--port','0'],'@@literal'),('single-at',['--data','@','--port','0'],'@'),('nested',['@args'],'@literal')]:
  with tempfile.TemporaryDirectory(prefix='graphite literal at ') as tmp:
   folder=pathlib.Path(tmp);(folder/'literal').write_text('--help\n');(folder/'args').write_text('--data @@literal --port 0\n')
   cmd=['java','-Dfile.encoding=UTF-8','-Xmx128m','-XX:ActiveProcessorCount=2','-jar',str(jar),*prefix,*args]
   with (folder/'stdout').open('w') as stdout,(folder/'stderr').open('w') as stderr:
    child=subprocess.Popen(cmd,cwd=folder,env={**os.environ,'GRAPHITE_SERVER_BINARY':str(w/'native-server')},stdout=stdout,stderr=stderr)
    catalog=None;deadline=time.monotonic()+15
    try:
     while time.monotonic()<deadline:
      message=(folder/'stderr').read_text();match=re.search(r'http://localhost:(\d+)',message)
      if match:
       with urllib.request.urlopen('http://localhost:'+match[1]+'/api/graphs',timeout=3) as f:catalog=json.load(f)
       break
      if child.poll() is not None:break
      time.sleep(.025)
     record={'name':name,'case':case,'command':cmd,'jarSHA256':hashlib.sha256(jar.read_bytes()).hexdigest(),'catalog':catalog,'expectedData':str((folder/wanted).resolve()),'exitBeforeCleanup':child.poll(),'stdout':(folder/'stdout').read_text(),'stderr':(folder/'stderr').read_text()}
    finally:
     if child.poll() is None:child.terminate()
     try:child.wait(timeout=10)
     except subprocess.TimeoutExpired:child.kill();child.wait()
    record['exitAfterCleanup']=child.returncode;records.append(record)
    (out/'observations.partial.json').write_text(json.dumps(records,indent=2)+'\n')
    assert catalog is not None,record
    expected={'data':str((folder/wanted).resolve()),'loadMode':'MAPPED','count':0,'totals':{'nodes':0,'edges':0,'methods':0,'callSites':0},'graphs':[]}
    assert catalog==expected,(record,expected)
    assert record['exitBeforeCleanup'] is None,record
    print(name,case,'PASS',flush=True)
(out/'summary.json').write_text(json.dumps({'cases':len(records),'required':12,'allPassed':len(records)==12,'nativeSHA256':hashlib.sha256((w/'native-server').read_bytes()).hexdigest(),'purpose':'Functional main/JAR/native argument parsing only; no performance.'},indent=2)+'\n')
