"""Two original JVM captures over the immutable main-written raw-work fixture."""
from pathlib import Path
import argparse,datetime,gzip,hashlib,json,shutil,subprocess,tarfile
HERE=Path(__file__).resolve().parent
ORIGINAL=HERE.parents[1]
MAIN=Path('/tmp/graphite-go-main-baseline-clone-4e328b0')
JAR=MAIN/'graphite-explore/build/libs/graphite-explore.jar'
JDK=Path('/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home')
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def dump(p,x):p.write_text(json.dumps(x,indent=2)+'\n')
def files(p):return [dict(file=str(f.relative_to(p)),bytes=f.stat().st_size,sha256=sha(f)) for f in sorted(p.rglob('*')) if f.is_file()]
def run(out):
 out=out.resolve();out.mkdir(parents=True,exist_ok=False);(out/'classes').mkdir();(out/'variants').mkdir()
 assert sha(JAR)=='91c3a1d154ca96004c55df195d9f752e077cab3e33ca1570b2c88b872d9bc34d'
 assert sha(ORIGINAL/'fixtures.tar.gz')=='31a7d70c9833dcf84322d541744ae007f1b5b182920eac26b45ede372283092a'
 paths=[JAR,HERE/'run.py',HERE/'RawWorkOracle.java',HERE/'cases.json',ORIGINAL/'fixtures.tar.gz',ORIGINAL/'fixture-variants.json']+[JDK/p for p in ['bin/java','bin/javac','release','lib/modules','lib/server/libjvm.dylib']]+[MAIN/'graphite-cypher/src/main/kotlin/io/johnsonlee/graphite/cypher'/p for p in ['QueryPipeline.kt','CypherExecutor.kt','CypherExecutionBudget.kt']]
 inputs={str(p):sha(p) for p in paths};dump(out/'inputs.json',inputs)
 with tarfile.open(ORIGINAL/'fixtures.tar.gz')as source:
  members=[m for m in source if m.name.startswith('local-first/')];assert members and all(m.isfile() and '..' not in Path(m.name).parts for m in members)
  for m in members:
   target=out/'variants'/m.name;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(source.extractfile(m).read())
 original={r['file']:r for r in json.loads((ORIGINAL/'fixture-variants.json').read_text())};variants=files(out/'variants');assert all(v==original[v['file']] for v in variants);dump(out/'fixture-variants.json',variants)
 with tarfile.open(out/'fixtures.tar.gz','w:gz')as t:
  for v in variants:t.add(out/'variants'/v['file'],arcname=v['file'],recursive=False)
 for c in json.loads((HERE/'cases.json').read_text()):
  for i,s in enumerate(c['sources']):shutil.copytree(out/'variants'/s['fixture'],out/'fixtures'/c['name']/('store'+str(i)))
 before=files(out/'fixtures');dump(out/'fixture-before.json',before);commands=[]
 for name,args in [('compile',[str(JDK/'bin/javac'),'-cp',str(JAR),'-d',str(out/'classes'),str(HERE/'RawWorkOracle.java')]),('run',[str(JDK/'bin/java'),'-Xmx512m','-cp',str(out/'classes')+':'+str(JAR),'RawWorkOracle',str(HERE/'cases.json'),str(out/'fixtures'),str(out/'main.json')])]:
  start=datetime.datetime.now(datetime.timezone.utc).isoformat()
  with (out/(name+'.stdout')).open('x')as stdout,(out/(name+'.stderr')).open('x')as stderr:r=subprocess.run(args,stdout=stdout,stderr=stderr)
  commands.append(dict(phase=name,command=args,exitCode=r.returncode,startUTC=start,endUTC=datetime.datetime.now(datetime.timezone.utc).isoformat()));dump(out/'commands.json',commands);assert r.returncode==0
 after=files(out/'fixtures');dump(out/'fixture-after.json',after);assert before==after
 assert files(out/'variants')==variants and all(sha(Path(p))==h for p,h in inputs.items())
 dump(out/'receipt.json',dict(mainRevision='4e328b0109e13c896b74004823fb049fcb19251a',inputsUnchanged=True,fixtureFilesUnchanged=len(before),fixtureVariantFiles=len(variants),commands=commands,classes=files(out/'classes'),performanceMeasurements=0))
 print(out)
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('output',type=Path);run(p.parse_args().output)
