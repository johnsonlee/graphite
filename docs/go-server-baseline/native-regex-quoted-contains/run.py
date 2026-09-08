"""Original Java17 Pattern and pinned-main correctness only, on fixture copies."""
from pathlib import Path
import argparse,hashlib,json,shutil,subprocess,zipfile
HERE=Path(__file__).resolve().parent;REPO=HERE.parents[2]
MAIN=Path('/tmp/graphite-go-main-baseline-clone-4e328b0')
JAR=MAIN/'graphite-explore/build/libs/graphite-explore.jar'
SOURCE=REPO/'graphite-server/internal/query/testdata/main-string-source/all-types'
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def write(path,value):path.write_text(json.dumps(value,indent=2)+'\n')
def manifest(root):return [dict(file=str(f.relative_to(root)),bytes=f.stat().st_size,sha256=sha(f)) for f in sorted(root.rglob('*')) if f.is_file()]
def run(destination):
 out=destination.resolve();out.mkdir(parents=True,exist_ok=False)
 assert sha(JAR)=='91c3a1d154ca96004c55df195d9f752e077cab3e33ca1570b2c88b872d9bc34d'
 public=json.loads((HERE/'public-cases.json').read_text());source_files=manifest(SOURCE)
 for spec in public:shutil.copytree(SOURCE,out/'fixtures'/spec['name']/'store0')
 before=manifest(out/'fixtures');write(out/'fixture-before.json',before);write(out/'source-fixture.json',dict(root=str(SOURCE),files=source_files))
 version=subprocess.run(['java','-XshowSettings:properties','-version'],text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,check=True).stdout
 java_home=Path(next(l.split('=',1)[1].strip() for l in version.splitlines() if l.strip().startswith('java.home =')))
 paths=[JAR,HERE/'RegexQuotedOracle.java',HERE/'run.py',HERE/'prepare.py',HERE/'pattern-cases.json',HERE/'public-cases.json',HERE/'actual-main64.json',REPO/'graphite-server/internal/benchmarkcase/testdata/main64.json']
 for name in ['ExpressionEvaluator.kt','CypherDslAdapter.kt']:paths.append(MAIN/'graphite-cypher/src/main/kotlin/io/johnsonlee/graphite/cypher'/name)
 for name in ['bin/java','lib/modules','release','lib/server/libjvm.dylib']:paths.append(java_home/name)
 inputs={str(p):sha(p) for p in paths};classes=out/'classes';classes.mkdir()
 commands=dict(compile=['javac','-cp',str(JAR),'-d',str(classes),str(HERE/'RegexQuotedOracle.java')],
               run=[str(java_home/'bin/java'),'-Xmx512m','-cp',str(classes)+':'+str(JAR),'RegexQuotedOracle',str(HERE/'pattern-cases.json'),str(HERE/'public-cases.json'),str(out/'fixtures'),str(out/'capture')])
 codes={}
 for phase,command in commands.items():
  with (out/(phase+'.stdout')).open('x') as stdout,(out/(phase+'.stderr')).open('x') as stderr:result=subprocess.run(command,stdout=stdout,stderr=stderr)
  codes[phase]=result.returncode
  if result.returncode:write(out/'receipt.json',dict(commands=commands,exitCodes=codes,inputs=inputs));raise RuntimeError(f'{phase} failed: {out}')
 after=manifest(out/'fixtures');write(out/'fixture-after.json',after);bm={e['file']:e for e in before};am={e['file']:e for e in after}
 audit=dict(originalFiles=len(bm),changed=[f for f,e in bm.items() if am.get(f)!=e],missing=sorted(bm.keys()-am.keys()),added=sorted(am.keys()-bm.keys()))
 assert not audit['changed'] and not audit['missing'] and all(Path(f).name in ['graph.nodeoffsets','graph.typeindex'] for f in audit['added'])
 assert manifest(SOURCE)==source_files and all(sha(Path(p))==h for p,h in inputs.items())
 write(out/'fixture-audit.json',audit)
 with zipfile.ZipFile(JAR) as jar:
  entries={name:hashlib.sha256(jar.read(name)).hexdigest() for name in ['io/johnsonlee/graphite/cypher/ExpressionEvaluator.class','io/johnsonlee/graphite/cypher/JavaCypherRegex.class','io/johnsonlee/graphite/cypher/CypherExecutor.class']}
 write(out/'receipt.json',dict(commands=commands,exitCodes=codes,inputs=inputs,inputsUnchanged=True,javaVersion=version,mainRevision='4e328b0109e13c896b74004823fb049fcb19251a',actualMainJarEntries=entries,oracleClassSha256=sha(classes/'RegexQuotedOracle.class'),fixtureAudit=audit,performanceMeasurements=0))
 print('Actual Java Pattern and main public correctness capture complete:',out)
if __name__=='__main__':
 parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('output',type=Path);args=parser.parse_args();run(args.output)
