"""Run actual Java17/main oracle on fresh copied correctness fixtures."""
from pathlib import Path
import argparse,hashlib,json,shutil,subprocess,zipfile
HERE=Path(__file__).resolve().parent;REPO=HERE.parents[2]
MAIN=Path('/tmp/graphite-go-main-baseline-clone-4e328b0');JAR=MAIN/'graphite-explore/build/libs/graphite-explore.jar'
JAVA=Path('/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home')
SOURCE=REPO/'graphite-server/internal/query/testdata/main-string-source/all-types'
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def write(p,v):p.write_text(json.dumps(v,indent=2)+'\n')
def manifest(root):return [dict(file=str(f.relative_to(root)),bytes=f.stat().st_size,sha256=sha(f)) for f in sorted(root.rglob('*')) if f.is_file()]
def run(out):
 out=out.resolve();out.mkdir(parents=True,exist_ok=False)
 assert sha(JAR)=='91c3a1d154ca96004c55df195d9f752e077cab3e33ca1570b2c88b872d9bc34d'
 source=manifest(SOURCE);write(out/'source-fixture.json',dict(root=str(SOURCE),files=source))
 specs=json.loads((HERE/'cases.json').read_text())
 for s in specs:
  for i,_ in enumerate(s['fixtures']):shutil.copytree(SOURCE,out/'fixtures'/s['name']/('store'+str(i)))
 paths=[JAR,HERE/'GenericDisjunctionOracle.java',HERE/'cases.json',HERE/'mutations.json',HERE/'actual-main64.json',HERE/'run.py',HERE/'prepare.py',REPO/'graphite-server/internal/benchmarkcase/testdata/main64.json']
 paths += [JAVA/p for p in ['bin/java','bin/javac','lib/modules','release','lib/server/libjvm.dylib']]
 paths += [MAIN/'graphite-webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph'/p for p in ['MappedWebGraphBackedGraph.kt','NodeSerializer.kt','NodeOffsetIndex.kt','NodeTypeIndex.kt','StringTable.kt']]
 inputs={str(p):sha(p) for p in paths};classes=out/'classes';classes.mkdir()
 base=[str(JAVA/'bin/java'),'-Xmx512m','-cp',str(classes)+':'+str(JAR),'GenericDisjunctionOracle']
 commands=dict(compile=[str(JAVA/'bin/javac'),'-cp',str(JAR),'-d',str(classes),str(HERE/'GenericDisjunctionOracle.java')],prepare=base+['prepare',str(HERE/'cases.json'),str(out/'fixtures'),str(HERE/'mutations.json')],run=base+['run',str(HERE/'cases.json'),str(out/'fixtures'),str(out/'main.json')])
 codes={};before=None
 for phase,command in commands.items():
  if phase=='run':before=manifest(out/'fixtures');write(out/'fixture-before.json',before)
  with (out/(phase+'.stdout')).open('x') as stdout,(out/(phase+'.stderr')).open('x') as stderr:r=subprocess.run(command,stdout=stdout,stderr=stderr)
  codes[phase]=r.returncode
  if r.returncode:write(out/'receipt.json',dict(commands=commands,exitCodes=codes,inputs=inputs));raise RuntimeError(phase+' failed: '+str(out))
 after=manifest(out/'fixtures');write(out/'fixture-after.json',after);bm={e['file']:e for e in before};am={e['file']:e for e in after};audit=dict(originalFiles=len(bm),changed=[f for f,e in bm.items() if am.get(f)!=e],missing=sorted(bm.keys()-am.keys()),added=sorted(am.keys()-bm.keys()));write(out/'fixture-audit.json',audit)
 assert not audit['changed'] and not audit['missing'] and all(Path(f).name=='graph.callsite-string-index' for f in audit['added']),audit
 assert manifest(SOURCE)==source and all(sha(Path(p))==h for p,h in inputs.items())
 write(out/'receipt.json',dict(commands=commands,exitCodes=codes,inputs=inputs,inputsUnchanged=True,mainRevision='4e328b0109e13c896b74004823fb049fcb19251a',oracleClassSha256=sha(classes/'GenericDisjunctionOracle.class'),fixtureAudit=audit,performanceMeasurements=0))
 print('Actual main oracle complete:',out)
if __name__=='__main__':
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('output',type=Path);run(p.parse_args().output)
