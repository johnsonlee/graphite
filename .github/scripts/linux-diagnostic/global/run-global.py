#!/usr/bin/env python3
"""Fixed diagnostic protocol; never publishes a performance gate or retries a sample."""
from pathlib import Path
import argparse,csv,hashlib,json,os,shutil,subprocess,sys,time,traceback
P=argparse.ArgumentParser()
for name in ('base-tree','candidate-tree','fixture-dir','evidence-dir','async-library','output'):P.add_argument('--'+name,required=True)
a=P.parse_args();here=Path(__file__).resolve().parent;out=Path(a.output).resolve();out.mkdir(parents=True,exist_ok=True)
trees={'base':Path(a.base_tree).resolve(),'candidate':Path(a.candidate_tree).resolve()};fixture=Path(a.fixture_dir).resolve();ev=Path(a.evidence_dir).resolve();lib=Path(a.async_library).resolve()
base='4e328b0109e13c896b74004823fb049fcb19251a';candidate='23dafb3dc82b31ea78a5d399d3ed68f70de5340f';harness='graphite-webgraph/src/jmh/kotlin/io/johnsonlee/graphite/webgraph/LargeBroadQueryPressureBenchmark.kt';correctness='graphite-webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/QueryCorrectnessManifest.kt'
state={'status':'running','performanceAcceptance':False,'protocol':['control-base','control-candidate','profile-base-1','profile-candidate-1','profile-candidate-2','profile-base-2'],'completed':[],'noActiveProcessorCountOverride':True,'runnerCpuCount':os.cpu_count(),'commands':[]}
def save(): (out/'run.json').write_text(json.dumps(state,indent=2)+'\n')
def sha(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()
def run(cmd,label,cwd=None):
 cmd=list(map(str,cmd));receipt={'label':label,'command':cmd,'cwd':str(cwd) if cwd else None};state['commands'].append(receipt);save()
 with (out/(label+'.log')).open('w') as f:r=subprocess.run(cmd,cwd=cwd,stdout=f,stderr=subprocess.STDOUT)
 receipt['exitCode']=r.returncode;save();assert r.returncode==0,(label,r.returncode)
def capture(cmd):return subprocess.check_output(list(map(str,cmd)),text=True).strip()
def graph_hashes():
 return [{'path':str(p.relative_to(fixture)),'size':p.stat().st_size,'sha256':sha(p)} for p in sorted((fixture/'graphs').rglob('*')) if p.is_file()]
def readrows(path):
 with path.open(newline='') as f:return list(csv.DictReader(f,delimiter='\t'))
def java_process_inventory():
 if not sys.platform.startswith('linux') or not Path('/proc').is_dir():
  raise RuntimeError('Pre-control Java isolation requires Linux /proc')
 processes=[];warnings=[]
 for proc in sorted((p for p in Path('/proc').iterdir() if p.name.isdigit()),key=lambda p:int(p.name)):
  fields={'pid':int(proc.name)};errors={}
  for name,reader in [('comm',lambda:(proc/'comm').read_text().strip()),('argv',lambda:[x.decode('utf-8','replace') for x in (proc/'cmdline').read_bytes().split(b'\0') if x]),('exe',lambda:os.readlink(proc/'exe'))]:
   try:fields[name]=reader()
   except (FileNotFoundError,ProcessLookupError):pass
   except OSError as error:errors[name]=str(error)
  java_name=lambda value:Path(value.removesuffix(' (deleted)')).name=='java'
  matches=[name for name,value in [('comm',fields.get('comm','')),('argv0',(fields.get('argv') or [''])[0]),('exe',fields.get('exe',''))] if java_name(value)]
  if matches:
   fields['identifiedBy']=matches
   if errors:fields['readErrors']=errors
   processes.append(fields)
  elif errors and not any(name in fields for name in ('comm','argv','exe')):
   warnings.append({'pid':fields['pid'],'readErrors':errors})
 return {'javaProcesses':processes,'unclassifiedReadErrors':warnings}
def await_java_quiescence():
 receipt={'method':'Read Linux /proc PID/comm/cmdline/exe only; no environment reads, signals or process-kill commands','timeoutSeconds':30,'pollSeconds':1,'snapshots':[],'passed':False}
 start=time.monotonic();deadline=start+30;path=out/'java-quiescence.json'
 try:
  while True:
   snapshot=java_process_inventory();snapshot['elapsedSeconds']=time.monotonic()-start
   receipt['snapshots'].append(snapshot)
   if snapshot['unclassifiedReadErrors']:
    raise RuntimeError('Cannot establish Java quiescence: unreadable live process identities')
   if not snapshot['javaProcesses']:
    receipt['passed']=True;return
   remaining=deadline-time.monotonic()
   if remaining<=0:
    raise RuntimeError('Java processes remain after 30-second bounded wait; controls/profiles were not started')
   path.write_text(json.dumps(receipt,indent=2)+'\n')
   time.sleep(min(1,remaining))
 except BaseException:
  receipt['error']=traceback.format_exc();raise
 finally:
  receipt['elapsedSeconds']=time.monotonic()-start
  receipt['exitCode']=0 if receipt['passed'] else 1
  path.write_text(json.dumps(receipt,indent=2)+'\n')
try:
 prov=json.loads((ev/'provenance.json').read_text());assert prov['baseSha']==base and prov['candidateSha']==candidate
 assert capture(['git','-C',trees['base'],'rev-parse','HEAD'])==base
 assert capture(['git','-C',trees['candidate'],'rev-parse','HEAD'])==candidate
 assert sha(trees['candidate']/harness)==prov['harnessSha256'];assert sha(trees['candidate']/correctness)==prov['correctnessSha256']
 assert lib.is_file();state['asyncLibrarySha256']=sha(lib);state['nativeConfigSha256']=sha(here/'native.jfc');state['overlaySourceSha256']=sha(here/'LargeBroadQueryPressureBenchmark.kt')
 # Exact original candidate harness/correctness adapter on both production revisions, as original CI script.
 shutil.copy2(trees['candidate']/harness,trees['base']/harness);shutil.copy2(trees['candidate']/correctness,trees['base']/correctness)
 jar_dir=out/'jars';jar_dir.mkdir(exist_ok=True);jars={}
 for side in ('base','candidate'):
  tasks=[':webgraph:jmhJar']+([':webgraph:prepareBenchmarkFixtures'] if side=='candidate' else [])
  run([trees[side]/'gradlew','-p',trees[side],*tasks,'--no-daemon','-Pkotlin.compiler.execution.strategy=in-process'],'build-original-'+side)
  found=list((trees[side]/'graphite-webgraph/build/libs').glob('*-jmh.jar'));assert len(found)==1
  dest=jar_dir/f'original-{side}.jar';shutil.copy2(found[0],dest);jars['original-'+side]=dest
  canonical=capture([sys.executable,trees['candidate']/'.github/scripts/canonical-zip-sha256.py',dest])
  state[side+'OriginalJar']={'sha256':sha(dest),'canonicalContentSha256':canonical,'expectedCiCanonical':prov[side+'JarContentSha256']};save()
  assert canonical==prov[side+'JarContentSha256'],f'{side} original rebuilt JAR does not match CI artifact identity; stop and diagnose'
 run([trees['candidate']/'.github/scripts/verify-shared-fixture64.sh',jars['original-candidate'],trees['candidate']/ 'graphite-webgraph/build/benchmark-fixtures',fixture,candidate],'verify-shared-fixture64')
 before=graph_hashes();(out/'graph-content-before.json').write_text(json.dumps(before,indent=2)+'\n')
 for side in ('base','candidate'):
  shutil.copy2(here/'LargeBroadQueryPressureBenchmark.kt',trees[side]/harness)
  run([trees[side]/'gradlew','-p',trees[side],':webgraph:jmhJar','--no-daemon','-Pkotlin.compiler.execution.strategy=in-process'],'build-overlay-'+side)
  found=list((trees[side]/'graphite-webgraph/build/libs').glob('*-jmh.jar'));assert len(found)==1
  dest=jar_dir/f'overlay-{side}.jar';shutil.copy2(found[0],dest);jars['overlay-'+side]=dest
  run([sys.executable,here/'verify-overlay-jars.py','--original',jars['original-'+side],'--overlay',dest,'--canonical-hasher',trees['candidate']/'.github/scripts/canonical-zip-sha256.py','--expected-original',prov[side+'JarContentSha256'],'--output',out/(side+'-payload-verification.json')],'verify-overlay-'+side)
 oracle=ev/'base-global-wide-oracle-seed.correctness';assert sha(oracle)==prov['oracleSha256'];oracle_lines=oracle.read_text().splitlines()
 reference=readrows(ev/'base-global-wide-oracle-seed.tsv');assert len(reference)==34
 signature='id family shape selectivity operator boundary projection targetGraphId workloadIdentity limit outcome rowCount responseBytes digest'.split()
 state['javaQuiescenceReceipt']='java-quiescence.json';save()
 await_java_quiescence()
 for label in state['protocol']:
  parts=label.split('-');mode,side=parts[:2];prefix=out/label;jar=jars[('original-' if mode=='control' else 'overlay-')+side]
  jvm=['-Xmx8g',f'-Dgraphite.broad.pressure.graphs={fixture}/graphs/graphs.tsv','-Dgraphite.broad.pressure.correctness.mode=verify',f'-Dgraphite.broad.pressure.correctness.oracle={oracle}',f'-Dgraphite.broad.pressure.observations.output={prefix}.tsv']
  if mode!='control':
   config=here/'native.jfc'
   jvm += ['-XX:FlightRecorderOptions=stackdepth=128','-Dgraphite.diagnostic.queryEvents=true',f'-Dgraphite.diagnostic.runId={label}',f'-XX:StartFlightRecording=settings={config},filename={prefix}.native.jfr,dumponexit=true']
  if mode=='profile':jvm += [f'-agentpath:{lib}=start,event=cpu,interval=1000000,file={prefix}.async.jfr']
  assert all(not any(c.isspace() for c in x) for x in jvm),'JMH JVM option paths must not contain whitespace'
  cmd=['java','-jar',jar,'io.johnsonlee.graphite.webgraph.LargeBroadQueryPressureBenchmark.replayBroadQueries','-p','graphCount=64','-p','coverageFamily=global-wide','-p','indexState=cold','-p','timeoutMillis=300000','-wi','0','-i','1','-f','1','-to','30m','-foe','true','-prof','gc','-rf','json','-rff',str(prefix)+'.json','-jvmArgs',' '.join(jvm)]
  run(cmd,label)
  rows=readrows(Path(str(prefix)+'.tsv'));assert len(rows)==34 and [r['id'] for r in rows]==[r['id'] for r in reference]
  assert ['|'.join(r[k] for k in signature) for r in rows]==oracle_lines
  changes=[{'id':r['id'],'changes':{k:[b[k],r[k]] for k in r if k!='latencyNanos' and b[k]!=r[k]}} for r,b in zip(rows,reference)];changes=[x for x in changes if x['changes']]
  receipt={'all34SignaturesMatch':True,'nonTimeDifferencesFromCiOracle':changes,'jarSha256':sha(jar),'profilingMode':mode,'acceptedPerformance':False}
  (out/(label+'-correctness.json')).write_text(json.dumps(receipt,indent=2)+'\n')
  state.setdefault('observationDifferences',{})[label]=changes;save()
  # Diagnostic counts/peaks can differ; preserve every discrepancy without weakening original acceptance gates.
  state['completed'].append(label);save()
 # Complete every recording before starting export Java processes or offline analysis.
 for label in state['completed']:
  if label.startswith('control-'):continue
  prefix=out/label
  for kind in ('native','async'):
   recording=Path(str(prefix)+'.'+kind+'.jfr');assert recording.is_file() and recording.stat().st_size>0
   run(['jfr','summary',recording],label+'-'+kind+'-summary')
   run(['jfr','print','--json',recording],label+'-'+kind+'-events')
  run([sys.executable,here/'analyze-events.py','--native-events',out/(label+'-native-events.log'),'--observations',str(prefix)+'.tsv','--run-id',label,'--output',out/(label+'-analysis.json'),'--async-events',out/(label+'-async-events.log'),'--require-linux-perf'],label+'-analysis')
 state['status']='complete';save()
except BaseException:
 state['status']='failed';state['error']=traceback.format_exc();save();raise

finally:
 if 'before' in globals():
  try:
   after=graph_hashes();(out/'graph-content-after.json').write_text(json.dumps(after,indent=2)+'\n')
   state['inputsUnchanged']=after==before;save()
   if not state['inputsUnchanged'] and state['status']=='complete':
    state['error']='Graph input content changed';save();raise AssertionError(state['error'])
  except BaseException:
   # Preserve an existing capture/build/analysis error; integrity failure is additional evidence.
   state['integrityCheckError']=traceback.format_exc();save()
   if state['status']=='complete':
    state['status']='failed';save();raise
