#!/usr/bin/env python3
"""Actual Gradle input/output correctness evidence, no performance workload."""
import hashlib,json,os,pathlib,re,subprocess,zipfile
source=pathlib.Path(__file__).resolve().parent.parent;evidence=source/'profile-gradle-freeze';build=pathlib.Path('/tmp/graphite-go-profile-gradle-input-22eff982-build')
html='graphite-server/internal/profiling/report.html';gradle='graphite-explore/build.gradle.kts';original=(evidence/'original-report.html').read_bytes();baseGradle=(evidence/'base-gradle.kts').read_bytes();marker='Graphite CPU profile — incremental input probe';modified=original.replace(b'<title>Graphite CPU profile</title>',('<title>'+marker+'</title>').encode());assert modified!=original
fixed=baseGradle.replace(b'"internal/web/assets/**",',b'"internal/web/assets/**", "internal/profiling/report.html",');assert fixed!=baseGradle
command=['./gradlew','--no-daemon','--max-workers=2',':explore:shadowJar',':query:shadowJar','-Pgraphite.nativeTargets=host','-Pversion=profile-input-check','--console=plain']
sha=lambda b:hashlib.sha256(b).hexdigest();runs=[];observationFile='observations.json'
def write(rel,data):
 (source/rel).write_bytes(data);(build/rel).write_bytes(data)
def sourceHashes():
 paths=subprocess.check_output(['git','ls-files','--cached','--others','--exclude-standard','-z'],cwd=build).decode().split('\0')
 return {p:sha((build/p).read_bytes()) for p in paths if p and (build/p).is_file()}
def run(name,info=False):
 path=evidence/(name+'.log')
 with path.open('w') as log:p=subprocess.run(command+(['--info'] if info else []),cwd=build,stdout=log,stderr=subprocess.STDOUT)
 assert p.returncode==0,path
 text=path.read_text();tasks={}
 for task in [':explore:prepareNativeServer',':explore:processResources',':explore:shadowJar',':query:shadowJar']:
  matches=re.findall(r'^> Task '+re.escape(task)+r'(.*)$',text,re.M);assert matches,(name,task);tasks[task]=matches[-1].strip() or 'EXECUTED'
 resources=build/'graphite-explore/build/generated/native-resources/graphite-native';binary=resources/'darwin-arm64/graphite-server';digest=sha(binary.read_bytes());entry='graphite-native/darwin-arm64/graphite-server';jars={}
 for module in ['explore','query']:
  jar=build/f'graphite-{module}/build/libs'/('graphite-explore.jar' if module=='explore' else 'graphite.jar')
  with zipfile.ZipFile(jar) as archive:
   resourceSHA=sha(archive.read(entry));assert resourceSHA==digest
   sums=archive.read('graphite-native/SHA256SUMS').decode();assert digest+'  darwin-arm64/graphite-server' in sums
  jars[module]={'sha256':sha(jar.read_bytes()),'nativeEntry':entry,'nativeEntrySHA256':resourceSHA}
 result={'name':name,'command':command+(['--info'] if info else []),'exitCode':p.returncode,'tasks':tasks,'htmlSHA256':sha((build/html).read_bytes()),'binarySHA256':digest,'jars':jars}
 runs.append(result);(evidence/observationFile).write_text(json.dumps(runs,indent=2)+'\n');print(name,tasks,flush=True);return result
if __name__ == "__main__":
 write(html,original);write(gradle,baseGradle)
 a=run('03-unfixed-both-initial')
 b=run('04-unfixed-no-change');assert all(b['tasks'][t]=='UP-TO-DATE' for t in b['tasks']);assert b['binarySHA256']==a['binarySHA256']
 before=sourceHashes();write(html,modified);changed=[p for p,h in sourceHashes().items() if before.get(p)!=h];assert changed==[html],changed
 c=run('05-unfixed-html-only',True);assert all(v=='UP-TO-DATE' for v in c['tasks'].values());assert c['binarySHA256']==b['binarySHA256'];assert c['jars']==b['jars']
 write(html,original);write(gradle,fixed)
 d=run('06-fixed-original-template');assert d['tasks'][':explore:prepareNativeServer']=='EXECUTED'
 f=run('07-fixed-no-change');assert all(v=='UP-TO-DATE' for v in f['tasks'].values())
 before=sourceHashes();write(html,modified);changed=[p for p,h in sourceHashes().items() if before.get(p)!=h];assert changed==[html],changed
 h=run('08-fixed-html-only',True);assert all(v=='EXECUTED' for v in h['tasks'].values()),h['tasks'];assert h['binarySHA256']!=f['binarySHA256'];assert all(h['jars'][m]['nativeEntrySHA256']!=f['jars'][m]['nativeEntrySHA256'] for m in h['jars'])
 # Parse/help correctness only, no CPU burn. Verify go:embed content is actually used.
 binary=build/'graphite-explore/build/generated/native-resources/graphite-native/darwin-arm64/graphite-server';output=evidence/'probe-generated-profile.html';env=os.environ.copy();env['GRAPHITE_NATIVE_CPU_PROFILE']='1';env['GRAPHITE_PROFILE']=str(output)
 p=subprocess.run([str(binary),'--help'],env=env,capture_output=True,text=True);assert p.returncode==0;assert '<title>'+marker+'</title>' in output.read_text()
 (evidence/'probe-cli.json').write_text(json.dumps({'args':['--help'],'returncode':p.returncode,'stdout':p.stdout,'stderr':p.stderr,'embeddedTitleObserved':marker,'note':'No CPU workload; this verifies the packaged binary uses the changed embedded template.'},indent=2)+'\n')
 i=run('09-fixed-modified-no-change');assert all(v=='UP-TO-DATE' for v in i['tasks'].values());assert i['jars']==h['jars']
 write(html,original)
 j=run('10-fixed-restored-template');assert j['tasks'][':explore:prepareNativeServer']=='EXECUTED';assert j['binarySHA256']==a['binarySHA256']
 k=run('11-fixed-restored-no-change');assert all(v=='UP-TO-DATE' for v in k['tasks'].values());assert k['jars']==j['jars']
 (evidence/'verification.json').write_text(json.dumps({'passed':True,'buildRuns':len(runs),'htmlOnlyChangedFiles':changed,'unfixedMissedHTMLChange':True,'fixedHTMLTriggeredPrepareAndBothJars':True,'noChangeUpToDate':True,'restoredTemplateBinaryIdenticalToInitial':True,'hostOnly':'darwin-arm64','performanceWorkload':False},indent=2)+'\n')
