#!/usr/bin/env python3
"""Extend the proven HTML fix to all current production go:embed resource inputs."""
import fnmatch,importlib.util,json,pathlib,re,shlex,subprocess,tempfile,os,urllib.request,signal,difflib
here=pathlib.Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('html_probe',here/'verify-incremental.py');p=importlib.util.module_from_spec(spec);spec.loader.exec_module(p);p.observationFile='embed-observations.json'
resource='graphite-server/internal/server/spec/openapi.json';original=(p.source/resource).read_bytes();(here/'original-openapi.json').write_bytes(original)
parsed=json.loads(original);oldTitle=parsed['info']['title'];newTitle=oldTitle+' incremental embed input probe';parsed['info']['title']=newTitle;modified=(json.dumps(parsed,ensure_ascii=False,indent=2)+'\n').encode()
htmlFixed=(here/'html-fixed-gradle.kts').read_bytes();combined=htmlFixed.replace(b'"internal/profiling/report.html",',b'"internal/profiling/report.html", "internal/server/spec/**", "internal/javaregex/*.json", "internal/javaregex/*.json.gz",');assert combined!=htmlFixed
# The previous phase left HTML restored and the HTML-only fix built/up-to-date.
assert (p.build/p.html).read_bytes()==p.original and (p.build/p.gradle).read_bytes()==htmlFixed
before=p.sourceHashes();p.write(resource,modified);changed=[f for f,h in p.sourceHashes().items() if before.get(f)!=h];assert changed==[resource]
a=p.run('12-htmlfix-json-only-untracked',True);assert all(v=='UP-TO-DATE' for v in a['tasks'].values())
previous=json.loads((here/'observations.json').read_text())[-1];assert a['binarySHA256']==previous['binarySHA256'] and a['jars']==previous['jars']
p.write(resource,original);p.write(p.gradle,combined)
b=p.run('13-all-embed-inputs-original');assert b['tasks'][':explore:prepareNativeServer']=='EXECUTED'
c=p.run('14-all-embed-inputs-no-change');assert all(v=='UP-TO-DATE' for v in c['tasks'].values())
before=p.sourceHashes();p.write(resource,modified);changed=[f for f,h in p.sourceHashes().items() if before.get(f)!=h];assert changed==[resource]
d=p.run('15-all-embed-inputs-json-only',True);assert all(v=='EXECUTED' for v in d['tasks'].values());assert d['binarySHA256']!=c['binarySHA256']
# One inexpensive endpoint verifies the changed JSON is used by the binary, not just packaged.
binary=p.build/'graphite-explore/build/generated/native-resources/graphite-native/darwin-arm64/graphite-server';env=os.environ.copy();env.pop('GRAPHITE_NATIVE_CPU_PROFILE',None);env.pop('GRAPHITE_PROFILE',None)
with tempfile.TemporaryDirectory(prefix='graphite-openapi-embed-') as directory:
 proc=subprocess.Popen([str(binary),'--data',directory,'--port','0'],env=env,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True);startup=[]
 try:
  while True:
   line=proc.stderr.readline();startup.append(line);match=re.search(r'http://localhost:(\d+)',line)
   if match:break
   assert line,'startup failed'
  with urllib.request.urlopen('http://127.0.0.1:'+match.group(1)+'/openapi.json',timeout=10) as response:result=json.load(response)
  assert result['info']['title']==newTitle
  proc.send_signal(signal.SIGTERM);stdout,stderr=proc.communicate(timeout=20);assert proc.returncode==0
  (here/'probe-openapi.json').write_text(json.dumps({'returncode':proc.returncode,'stdout':stdout,'stderr':''.join(startup)+stderr,'response':result,'note':'One endpoint correctness request; no CPU workload or performance measurement.'},indent=2)+'\n')
 finally:
  if proc.poll() is None:proc.kill();proc.communicate()
f=p.run('16-all-embed-inputs-modified-no-change');assert all(v=='UP-TO-DATE' for v in f['tasks'].values());assert f['jars']==d['jars']
p.write(resource,original)
g=p.run('17-all-embed-inputs-restored');assert g['tasks'][':explore:prepareNativeServer']=='EXECUTED';assert g['binarySHA256']==previous['binarySHA256']
h=p.run('18-all-embed-inputs-restored-no-change');assert all(v=='UP-TO-DATE' for v in h['tasks'].values());assert h['jars']==g['jars']
# Enumerate every production directive and prove every resolved resource matches static inputs.
includeLine=next(line for line in combined.decode().splitlines() if 'include("**/*.go"' in line);patterns=re.findall(r'"([^"]+)"',includeLine);declarations=[]
for gofile in sorted((p.source/'graphite-server').rglob('*.go')):
 if gofile.name.endswith('_test.go') or 'testdata' in gofile.parts:continue
 for lineno,line in enumerate(gofile.read_text().splitlines(),1):
  if not line.startswith('//go:embed '):continue
  for pattern in shlex.split(line[len('//go:embed '):]):
   matched=[]
   for item in gofile.parent.glob(pattern):
    candidates=[item] if item.is_file() else list(item.rglob('*'))
    for f in candidates:
     if not f.is_file():continue
     rel=str(f.relative_to(p.source/'graphite-server'));coverage=[rule for rule in patterns if fnmatch.fnmatchcase(rel,rule)];assert coverage,(gofile,pattern,rel)
     matched.append({'path':rel,'sha256':p.sha(f.read_bytes()),'inputRules':coverage})
   assert matched,(gofile,pattern)
   declarations.append({'file':str(gofile.relative_to(p.source)),'line':lineno,'pattern':pattern,'resources':matched})
(here/'embed-input-audit.json').write_text(json.dumps({'staticInputRules':patterns,'declarations':declarations,'allResolvedResourcesCovered':True},indent=2)+'\n')
for name,old,new in [('embed-after-html.patch',htmlFixed,combined),('combined.patch',p.baseGradle,combined)]:
 (here/name).write_text(''.join(difflib.unified_diff(old.decode().splitlines(True),new.decode().splitlines(True),fromfile='a/graphite-explore/build.gradle.kts',tofile='b/graphite-explore/build.gradle.kts')))
(here/'embed-verification.json').write_text(json.dumps({'passed':True,'phases':len(p.runs),'goEmbedDirectives':len(declarations),'allCurrentResourcesCovered':True,'JSONOnlyChangedFiles':changed,'missingJSONReproduced':True,'JSONChangeTriggersNativeAndBothJars':True,'unchangedIsUpToDate':True,'restoredBinaryMatchesOriginal':True,'hostOnly':'darwin-arm64','performanceWorkload':False},indent=2)+'\n')
