"""Compile/audit only. This helper never loads a graph or invokes a query engine."""
from pathlib import Path
import argparse,gzip,hashlib,json,os,re,shutil,subprocess
BASE=Path(__file__).resolve().parent
ROOT=BASE.parents[3]
DOCS=BASE.parents[1]
PREVIOUS=DOCS/'native64-fullcase-replay'
REVISION='4e328b0109e13c896b74004823fb049fcb19251a'
WORKLOAD=ROOT/'graphite-server/internal/benchmarkcase/testdata/main64.json'
WORKLOAD_SHA='378c200c5ab3053c53962f9d87c59924f732d0c012fcaff6009842a58e547023'
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def write(path,value):path.write_text(json.dumps(value,indent=2)+'\n')
def cp_inputs(cp):
 result={}
 for item in cp.split(os.pathsep):
  path=Path(item);assert path.exists(),path
  for f in sorted(path.rglob('*')) if path.is_dir() else [path]:
   if f.is_file():result[str(f)]=sha(f)
 return result
def verify():
 receipt=json.loads((BASE/'build-verification.json').read_text())
 assert cp_inputs(receipt['originalClasspath'])==receipt['originalInputs']
 for name,expected in receipt['toolingHashes'].items():assert sha(BASE/name)==expected,name
 assert (BASE/'classpath.txt').read_text().strip()==str(BASE/'classes')+os.pathsep+receipt['originalClasspath']
 for name,expected in receipt['referenceInputs'].items():assert sha(Path(name))==expected,name
 print('Frozen launcher, reference captures and all original classpath inputs verified; no engine runtime')
def build():
 assert sha(WORKLOAD)==WORKLOAD_SHA
 cp=(PREVIOUS/'capture-classpath-complete.txt').read_text().strip()
 frozen=json.loads((PREVIOUS/'main-cold-complete-classpath-inputs.json').read_text())
 assert cp_inputs(cp)==frozen and len(frozen)==330
 reference=PREVIOUS/'main-cold-complete/main-correctness.tsv'
 lines=[l for l in reference.read_text().splitlines() if l and not l.startswith('#')]
 expected=[l.split('|') for l in lines]
 assert len(expected)==1267 and all(len(r)==14 for r in expected)
 captures=[(PREVIOUS/'main-cold-complete','replay'),(DOCS/'native64-index-states/main-warm','warmup'),(DOCS/'native64-index-states/main-startup-prepared','replay')]
 references={str(WORKLOAD):sha(WORKLOAD),str(reference):sha(reference)}
 workload=json.loads(WORKLOAD.read_text())
 assert workload['mainRevision']==REVISION
 for folder,phase in captures:
  assert json.loads((folder/'actual-cases.json').read_text())==workload['cases']
  references[str(folder/'actual-cases.json')]=sha(folder/'actual-cases.json')
  archive=folder/'responses.jsonl.gz';references[str(archive)]=sha(archive)
  with gzip.open(archive,'rt') as stream:rows=[r for line in stream if (r:=json.loads(line))['kind']=='case']
  assert len(rows)==1267
  for index,(row,columns) in enumerate(zip(rows,expected)):
   assert row['phase']==phase and row['index']==index and row['id']==columns[0]==workload['cases'][index]['id']
   if 'canonical' in row:
    data=row['canonical'].encode();assert columns[10:]==['success',str(len(row['rows'])),str(len(data)),hashlib.sha256(data).hexdigest()]
   else:
    assert index==821 and row['id']=='four-or-graph-id-targeted'
    assert row['errorClass']=='java.lang.IllegalStateException' and row['message']=='Unsafe expression reached parallel string projection'
    assert columns[10:]==['failed','0','0','java.lang.IllegalStateException']
 shutil.copyfile(reference,BASE/'diagnostic-reference.tsv')
 shutil.copyfile(WORKLOAD,BASE/'expected-workload.json')
 classes=BASE/'classes';classes.mkdir(exist_ok=True)
 command=['javac','-cp',cp,'-d',str(classes),str(BASE/'MainLatencyCapture.java')]
 result=subprocess.run(command,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
 (BASE/'compile.log').write_text(result.stdout);result.check_returncode()
 fullcp=str(classes)+os.pathsep+cp
 bytecode=subprocess.check_output(['javap','-c','-p','-classpath',fullcp,'MainLatencyCapture'],text=True)
 (BASE/'launcher-bytecode.txt').write_text(bytecode)
 forbidden=['java/lang/reflect/Field.set:','MainReplayCapture','AbstractExecutorService','ExecutorService.submit:',
            'java/util/concurrent/Future.get:','java/lang/System.nanoTime:', 'java/lang/System.gc:',
            'java/lang/Thread.sleep:', 'CrossGraphCypherExecutor','pressureQueryExecutor']
 assert not any(token in bytecode for token in forbidden)
 calls=['setupTrial:()V','setupInvocation:()V','replayBroadQueries:','tearDownTrial:()V','java/lang/reflect/Field.get:']
 assert all(token in bytecode for token in calls)
 reflected=['replay','enforceCorrectness','resetCallSiteScanMetrics','forcePressureGc','writeCorrectnessManifest','writeObservations','clearStringPropertyIndexes']
 source=(BASE/'MainLatencyCapture.java').read_text()
 assert all('"'+name+'"' in source for name in reflected)
 assert 'new Class<?>[]{boolean.class}, true' in source
 assert source.count('benchmark.replayBroadQueries(')==1
 boundary=[]
 for classname,methods in [
  ('io.johnsonlee.graphite.webgraph.LargeBroadQueryPressureBenchmark',['setupInvocation','replayBroadQueries','replay','enforceCorrectness','writeCorrectnessManifest','writeObservations','resetCallSiteScanMetrics']),
  ('io.johnsonlee.graphite.webgraph.LargeBroadQueryPressureBenchmarkKt',['forcePressureGc']),
  ('io.johnsonlee.graphite.webgraph.BroadQueryResourceSampler',['start']),
  ('io.johnsonlee.graphite.webgraph.MappedWebGraphBackedGraph',['clearStringPropertyIndexes'])]:
  output=subprocess.check_output(['javap','-c','-p','-classpath',cp,classname],text=True)
  for method in methods:
   chunks=re.split(r'(?=^  (?:public|private|protected) )',output,flags=re.M)
   selected=[c for c in chunks if re.search(r'\s'+method+r'(?:\$\w+)?\(',c.split('\n',1)[0])]
   assert len(selected)==1,(classname,method)
   boundary.append(classname+'\n'+selected[0])
 (BASE/'original-boundary-bytecode.txt').write_text('\n'.join(boundary))
 (BASE/'classpath.txt').write_text(fullcp+'\n')
 files=['MainLatencyCapture.java','build.py','classes/MainLatencyCapture.class','launcher-bytecode.txt','original-boundary-bytecode.txt',
        'compile.log','classpath.txt','diagnostic-reference.tsv','expected-workload.json']
 write(BASE/'build-verification.json',dict(mainRevision=REVISION,compileCommand=command,compileExitCode=result.returncode,
  originalClasspath=cp,originalInputs=frozen,originalClasspathInputCount=len(frozen),originalClasspathMatchesVerifiedCapture=True,
  referenceInputs=references,referenceCaseCount=1267,referenceSuccessCount=1266,referenceFailureCount=1,
  referenceThreeStateCanonicalEquality=True,referenceIsAllSuccessOracle=False,forbiddenBytecodeTokensAbsent=forbidden,
  originalLifecycleCallsPresent=calls,originalReflectedMethodsPresent=reflected,
  toolingHashes={f:sha(BASE/f) for f in files},runtimeLaunched=False,graphLoads=0,queryExecutions=0))
 verify()
if __name__=='__main__':
 parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--verify-only',action='store_true');args=parser.parse_args()
 verify() if args.verify_only else build()
