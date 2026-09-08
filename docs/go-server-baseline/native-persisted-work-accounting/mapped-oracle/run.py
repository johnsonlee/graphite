"""Run each declared public-executor case in fresh copied main-written fixtures."""
from pathlib import Path
import argparse,datetime,gzip,hashlib,json,shutil,subprocess,tarfile
HERE=Path(__file__).resolve().parent
MAIN=Path('/tmp/graphite-go-main-baseline-clone-4e328b0')
JAR=MAIN/'graphite-explore/build/libs/graphite-explore.jar'
JDK=Path('/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home')
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def dump(p,v):p.write_text(json.dumps(v,indent=2)+'\n')
def manifest(p):return [dict(file=str(f.relative_to(p)),bytes=f.stat().st_size,sha256=sha(f)) for f in sorted(p.rglob('*')) if f.is_file()]
def run(out):
 out=out.resolve();out.mkdir(parents=True,exist_ok=False);(out/'classes').mkdir()
 assert sha(JAR)=='91c3a1d154ca96004c55df195d9f752e077cab3e33ca1570b2c88b872d9bc34d'
 paths=[JAR,HERE/'run.py',HERE/'MappedWorkOracle.java',HERE/'cases.json',HERE/'prepare.py',HERE/'design.py',HERE.parent/'fixtures.tar.gz']
 paths += [JDK/p for p in ['bin/java','bin/javac','bin/javap','lib/modules','release','lib/server/libjvm.dylib']]
 paths += [MAIN/p for p in ['graphite-cypher/src/main/kotlin/io/johnsonlee/graphite/cypher/CypherExecutionBudget.kt','graphite-cypher/src/main/kotlin/io/johnsonlee/graphite/cypher/CypherExecutor.kt','graphite-cypher/src/main/kotlin/io/johnsonlee/graphite/cypher/QueryPipeline.kt','graphite-webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/MappedWebGraphBackedGraph.kt','graphite-webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/MappedCallSiteStringIndex.kt','graphite-webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/MappedCallSiteStringIndexView.kt','graphite-webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/StringTable.kt','graphite-webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/NodeSerializer.kt','graphite-webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/NodeOffsetIndex.kt','graphite-webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/NodeTypeIndex.kt']]
 paths += [MAIN/'graphite-core/src/main/kotlin/io/johnsonlee/graphite/graph/Graph.kt'];inputs={str(p):sha(p) for p in paths};dump(out/'inputs.json',inputs)
 commands=[]
 def command(phase,args):
  start=datetime.datetime.now(datetime.timezone.utc).isoformat()
  with (out/(phase+'.stdout')).open('x') as stdout,(out/(phase+'.stderr')).open('x') as stderr:r=subprocess.run(args,stdout=stdout,stderr=stderr)
  commands.append(dict(phase=phase,command=args,exitCode=r.returncode,startUTC=start,endUTC=datetime.datetime.now(datetime.timezone.utc).isoformat()))
  dump(out/'commands.json',commands)
  if r.returncode:raise RuntimeError(phase+' failed')
 cp=str(out/'classes')+':'+str(JAR)
 command('javap',[str(JDK/'bin/javap'),'-classpath',str(JAR),'io.johnsonlee.graphite.cypher.CypherExecutionContext','io.johnsonlee.graphite.cypher.CypherWorkTracker','io.johnsonlee.graphite.cypher.CypherCancellationSignal','io.johnsonlee.graphite.webgraph.BufferedGraphWorkConsumer','io.johnsonlee.graphite.graph.GraphWorkBatchConsumer'])
 command('compile',[str(JDK/'bin/javac'),'-cp',str(JAR),'-d',str(out/'classes'),str(HERE/'MappedWorkOracle.java')])
 command('prepare',['/usr/bin/python3',str(HERE/'prepare.py'),str(out/'variants')])
 variants=manifest(out/'variants');dump(out/'fixture-variants.json',variants)
 with tarfile.open(out/'fixtures.tar.gz','w:gz') as t:
  for e in variants:t.add(out/'variants'/e['file'],arcname=e['file'],recursive=False)
 specs=json.loads((HERE/'cases.json').read_text())
 for spec in specs:
  for i,source in enumerate(spec['sources']):shutil.copytree(out/'variants'/source['fixture'],out/'fixtures'/spec['name']/('store'+str(i)))
  if 'prelude'in spec:shutil.copytree(out/'variants'/spec['prelude']['fixture'],out/'fixtures'/spec['name']/'prelude')
 before=manifest(out/'fixtures')
 with gzip.open(out/'fixture-before.json.gz','wt') as f:json.dump(before,f)
 command('run',[str(JDK/'bin/java'),'-Xmx512m','-cp',cp,'MappedWorkOracle',str(HERE/'cases.json'),str(out/'fixtures'),str(out/'main.json')])
 after=manifest(out/'fixtures')
 with gzip.open(out/'fixture-after.json.gz','wt') as f:json.dump(after,f)
 bm={e['file']:e for e in before};am={e['file']:e for e in after};audit=dict(originalFiles=len(bm),changed=[k for k,v in bm.items() if am.get(k)!=v],added=sorted(am.keys()-bm.keys()),missing=sorted(bm.keys()-am.keys()))
 dump(out/'fixture-audit.json',audit)
 # Persist exact before/after evidence even if a fallback modifies an optional sidecar.
 assert not audit['missing'],audit
 assert manifest(out/'variants')==variants
 assert all(sha(Path(p))==h for p,h in inputs.items())
 dump(out/'receipt.json',dict(mainRevision='4e328b0109e13c896b74004823fb049fcb19251a',inputsUnchanged=True,commands=commands,classes=manifest(out/'classes'),fixtureAudit=audit,performanceMeasurements=0))
 print(out)
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('output',type=Path);run(p.parse_args().output)
