from pathlib import Path
import subprocess,os,json,time,hashlib,shutil,xml.etree.ElementTree as ET
P=Path(__file__).resolve().parent;repo=P/'repo'
command=['./gradlew',':webgraph:test',':webgraph:detekt',':webgraph:jmhJar',':webgraph:verifyJmhJarExcludesTests','--no-daemon']
env_values={'JAVA_HOME':'/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home','JAVA_TOOL_OPTIONS':'-XX:ActiveProcessorCount=4'}
env=os.environ.copy();env.update(env_values)
def sha(p):
 h=hashlib.sha256()
 with p.open('rb') as f:
  for b in iter(lambda:f.read(1024*1024),b''):h.update(b)
 return h.hexdigest()
base=Path('/private/tmp/graphite-next-baseline.T2FTs9/graphite-webgraph/build/libs/webgraph-1.0.0-SNAPSHOT-jmh.jar');base_before=sha(base)
(P/'build-command.json').write_text(json.dumps({'cwd':str(repo),'command':command,'environment':env_values},indent=2)+'\n')
print('BUILD START',str(P/'build.log'),flush=True);start=time.time()
with (P/'build.log').open('w') as log:result=subprocess.run(command,cwd=repo,env=env,stdout=log,stderr=subprocess.STDOUT)
record={'attempt':140,'buildExit':result.returncode,'elapsedSeconds':time.time()-start,'buildCommandSha256':sha(P/'build-command.json'),'buildLogSha256':sha(P/'build.log'),'checks':command[1:-1],'baselineJarSha256Before':base_before,'baselineJarSha256After':sha(base),'performanceNotRun':True}
assert record['baselineJarSha256Before']==record['baselineJarSha256After']
if result.returncode==0:
 suites=[];total={k:0 for k in ['tests','failures','errors','skipped']}
 archive=P/'test-results';archive.mkdir()
 for xml in sorted((repo/'graphite-webgraph/build/test-results/test').glob('TEST-*.xml')):
  tree=ET.parse(xml).getroot();counts={k:int(tree.attrib.get(k,0)) for k in total}
  for k,v in counts.items():total[k]+=v
  shutil.copyfile(xml,archive/xml.name);suites.append({'file':str(xml.relative_to(repo)),'archive':str(archive/xml.name),'sha256':sha(xml),'counts':counts,'testNames':[e.attrib['name'] for e in tree.findall('testcase')]})
 assert suites and total['tests']==187 and total['failures']==total['errors']==total['skipped']==0
 built=repo/'graphite-webgraph/build/libs/webgraph-1.0.0-SNAPSHOT-jmh.jar';frozen=P/'candidate-jmh.jar';assert not frozen.exists();shutil.copyfile(built,frozen);frozen.chmod(0o444)
 record.update({'testResults':total,'testSuites':len(suites),'suites':suites,'builtJar':str(built),'frozenJar':str(frozen),'jmhJarSha256':sha(frozen),'frozenJarMode':oct(frozen.stat().st_mode & 0o777),'buildSuccessful':True})
 source=P/'prebuild-source-receipt.json';pre=json.loads(source.read_text());f=repo/pre['onlyChangedProductionFile'];assert sha(f)==pre['sourceAfterSha256'];record['sourceSha256']=sha(f)
 workspace=Path(pre['workspace'])
 for name,original in pre['unrelatedUntrackedSnapshot'].items():
  f=workspace/name;assert {'sha256':sha(f),'size':f.stat().st_size,'mtimeNs':f.stat().st_mtime_ns}==original
 record['unrelatedUntrackedUnchanged']=True
(P/'build-receipt.json').write_text(json.dumps(record,indent=2)+'\n')
print('BUILD TERMINAL',result.returncode,json.dumps({k:v for k,v in record.items() if k not in ('suites','checks')}),flush=True)
raise SystemExit(result.returncode)
