"""Correctness-only raw HTTP oracle against pinned main and one tiny fixture."""
import argparse, base64, hashlib, http.client, json, pathlib, shutil, subprocess, tempfile, time
parser=argparse.ArgumentParser();parser.add_argument('--out',type=pathlib.Path,required=True);args=parser.parse_args()
ROOT=pathlib.Path.cwd(); OUT=args.out;OUT.mkdir(parents=True,exist_ok=False)
JAVA='/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home/bin/java'
JAR=pathlib.Path('/tmp/graphite-go-main-baseline-clone-4e328b0/graphite-explore/build/libs/graphite-explore.jar')
PORT=19871
base='"query":"RETURN 1 AS x","graphs":["a"]'
payloads=[]
def add(name,text):payloads.append((name,text.encode() if isinstance(text,str) else text))
add('valid','{'+base+'}')
add('empty','');add('whitespace',' \t\r\n');add('nul',b'\0')
add('empty-object','{}');add('null','null');add('array','[]');add('top-string','"hi"')
add('line-comment','// leading\n{'+base+'}')
add('block-comment','/* leading */{'+base+'}/* trailing */')
add('hash-comment','# leading\n{'+base+'}')
add('comment-inside','{/*query*/'+base+'}')
add('comment-unclosed','/* unclosed')
add('single-quotes',"{'query':'RETURN 1 AS x','graphs':['a']}")
add('unquoted-keys','{query:"RETURN 1 AS x",graphs:["a"]}')
add('unquoted-string','{query:RETURN 1 AS x,graphs:[a]}')
add('equals-separators','{query="RETURN 1 AS x";graphs=["a"]}')
add('arrow-separators','{query=>"RETURN 1 AS x",graphs=>["a"]}')
add('trailing-object-comma','{'+base+',}')
add('trailing-array-comma','{"query":"RETURN 1 AS x","graphs":["a",]}')
add('leading-array-comma','{"query":"RETURN 1 AS x","graphs":[,"a"]}')
add('array-semicolon','{"query":"RETURN 1 AS x","graphs":["a";]}')
add('multiple-top-level','{'+base+'} {}')
add('trailing-garbage','{'+base+'}xyz')
add('nonexecute-prefix',")]}'\n{"+base+'}')
add('bom',b'\xef\xbb\xbf'+('{'+base+'}').encode())
add('invalid-escape',r'{"query":"RETURN \q","graphs":["a"]}')
add('invalid-unicode-escape',r'{"query":"RETURN \u12G4","graphs":["a"]}')
add('short-unicode-escape',r'{"query":"RETURN \u12')
add('raw-newline-string','{"query":"RETURN\n1 AS x","graphs":["a"]}')
add('escaped-newline-string','{"query":"RETURN\\\n1 AS x","graphs":["a"]}')
add('eof-object','{'+base);add('eof-key','{"query"');add('eof-colon','{"query":');add('eof-string','{"query":"RETURN 1')
add('eof-array','{"query":"RETURN 1 AS x","graphs":["a"')
add('missing-colon','{"query" "RETURN 1 AS x","graphs":["a"]}')
add('missing-comma','{"query":"RETURN 1 AS x" "graphs":["a"]}')
add('duplicate-query','{"query":"RETURN 0 AS x","query":"RETURN 1 AS x","graphs":["a"]}')
add('uppercase-null','{"query":NULL,"graphs":["a"]}')
add('timeout-leading-zero','{'+base+',"timeoutMs":01000}')
add('timeout-single-quotes','{'+base+",'timeoutMs':'1000'}")
add('timeout-invalid-escape','{'+base+r',"timeoutMs":"\q"}')
add('timeout-nan','{'+base+',"timeoutMs":NaN}')
add('timeout-plus-number','{'+base+',"timeoutMs":+1000}')
add('query-trailing-array','{"query":["RETURN 1 AS x",],"graphs":["a"]}')
add('unpaired-surrogate-query',r'{"query":"RETURN \'\ud800\' AS x","graphs":["a"]}')
add('invalid-utf8',b'{"query":"RETURN \'\xff\' AS x","graphs":["a"]}')
manifest=[]
for surface,path in [('root','/api/cypher'),('scoped','/api/graphs/a/cypher'),('selected','/api/cypher/graphs')]:
 for method in ['GET','POST']:
  for name,raw in payloads:
   manifest.append({'name':f'{surface}-{method}-{name}','surface':surface,'method':method,'path':path+'?query=RETURN%202%20AS%20x&graph=a','payloadName':name,'bodyBase64':base64.b64encode(raw).decode(),'bodyUTF8':raw.decode(errors='replace')})
(OUT/'cases.json').write_text(json.dumps({'cases':manifest},indent=2,ensure_ascii=True)+'\n')
work=pathlib.Path(tempfile.mkdtemp(prefix='graphite-gson-raw-'));shutil.copytree(ROOT/'graphite-server/internal/store/testdata/jvm-v3',work/'fixture')
cmd=[JAVA,'-Xmx512m','-jar',str(JAR),'--data',str(work),'--port',str(PORT),'--graph',f'a:{work}/fixture']
(OUT/'main-command.json').write_text(json.dumps(cmd,indent=2)+'\n')
observations=[]
def request(case):
 conn=http.client.HTTPConnection('127.0.0.1',PORT,timeout=10)
 try:
  raw=base64.b64decode(case['bodyBase64'])
  conn.request(case['method'],case['path'],body=raw,headers={'Accept':'application/json','Content-Type':'application/json'})
  response=conn.getresponse();body=response.read();decoded=body.decode(errors='replace')
  result={'status':response.status,'headers':response.getheaders(),'bodyBase64':base64.b64encode(body).decode(),'bodyUTF8':decoded}
  try:result['json']=json.loads(decoded)
  except ValueError:pass
  return result
 finally:conn.close()
started=time.time()
with (OUT/'main-server.log').open('w') as log:
 process=subprocess.Popen(cmd,stdout=log,stderr=log)
 try:
  for i in range(200):
   try:request({'method':'GET','path':'/api/graphs','bodyBase64':''});break
   except OSError:
    if process.poll() is not None:raise RuntimeError('main startup failed')
    time.sleep(.05)
  else:raise RuntimeError('main startup timeout')
  for i,case in enumerate(manifest):
   observations.append({'case':case,'response':request(case)})
   (OUT/'observations.partial.json').write_text(json.dumps(observations,indent=2,ensure_ascii=True)+'\n')
   print(case['name'],observations[-1]['response']['status'],flush=True)
 finally:
  process.terminate();process.wait(timeout=15)
(OUT/'observations.json').write_text(json.dumps(observations,indent=2,ensure_ascii=True)+'\n')
(OUT/'observations.partial.json').unlink()
metadata={'purpose':'Raw-body correctness oracle only; no performance traffic','mainRevision':'4e328b0109e13c896b74004823fb049fcb19251a','javaVersion':'17.0.18','jarSha256':hashlib.sha256(JAR.read_bytes()).hexdigest(),'cases':len(manifest),'processPid':process.pid,'processExitCode':process.returncode,'dedicatedServerStopped':True,'startedAtUnixSeconds':started,'finishedAtUnixSeconds':time.time(),'fixture':str(work/'fixture'),'fixtureFiles':[{'path':str(f.relative_to(work/'fixture')),'bytes':f.stat().st_size,'sha256':hashlib.sha256(f.read_bytes()).hexdigest()} for f in sorted((work/'fixture').rglob('*')) if f.is_file()]}
(OUT/'metadata.json').write_text(json.dumps(metadata,indent=2)+'\n')
print(json.dumps({'cases':len(manifest),'serverStopped':True}))
