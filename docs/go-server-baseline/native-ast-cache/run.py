"""Reproduce original-main AST cache correctness evidence; never benchmark."""
from pathlib import Path
import argparse,hashlib,json,shutil,subprocess,zipfile
HERE=Path(__file__).resolve().parent
REPO=HERE.parents[2]
MAIN=Path('/tmp/graphite-go-main-baseline-clone-4e328b0')
JAR=MAIN/'graphite-explore/build/libs/graphite-explore.jar'
JAR_SHA='91c3a1d154ca96004c55df195d9f752e077cab3e33ca1570b2c88b872d9bc34d'
SOURCE=REPO/'graphite-server/internal/query/testdata/main-string-source/all-types'
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def write(path,obj):path.write_text(json.dumps(obj,indent=2)+'\n')
def files(root):return [dict(file=str(f.relative_to(root)),bytes=f.stat().st_size,sha256=sha(f)) for f in sorted(root.rglob('*')) if f.is_file()]
def run(destination,heap):
 out=destination.resolve();out.mkdir(parents=True,exist_ok=False)
 assert sha(JAR)==JAR_SHA
 source_files=files(SOURCE)
 for i in range(2):shutil.copytree(SOURCE,out/'fixtures'/f'store{i}')
 before=files(out/'fixtures');write(out/'fixture-before.json',before)
 write(out/'source-fixture.json',dict(root=str(SOURCE),files=source_files))
 classes=out/'classes';classes.mkdir()
 commands=dict(compile=['javac','-cp',str(JAR),'-d',str(classes),str(HERE/'MainAstCacheOracle.java')],
               run=['java','-Xmx'+heap,'-cp',str(classes)+':'+str(JAR),'MainAstCacheOracle',str(out/'fixtures'),str(out/'main.json')])
 inputs=[JAR,HERE/'MainAstCacheOracle.java',HERE/'run.py']
 for name in ['CypherDslAdapter.kt','ImmutableCypherAst.kt','CypherClause.kt','ExpressionEvaluator.kt']:
  inputs.append(MAIN/'graphite-cypher/src/main/kotlin/io/johnsonlee/graphite/cypher'/name)
 inputs.append(MAIN/'graphite-cypher/src/test/kotlin/io/johnsonlee/graphite/cypher/CypherDslAdapterTest.kt')
 frozen={str(p):sha(p) for p in inputs}
 jar_entries={}
 with zipfile.ZipFile(JAR) as jar:
  for name in ['CypherDslAdapter','ImmutableCypherAstKt','CypherClause','parser/CypherParser','parser/CypherLexer']:
   file='io/johnsonlee/graphite/cypher/'+name+'.class';data=jar.read(file);jar_entries[file]=hashlib.sha256(data).hexdigest()
 codes={}
 for phase,command in commands.items():
  with (out/(phase+'.stdout')).open('x') as stdout,(out/(phase+'.stderr')).open('x') as stderr:
   result=subprocess.run(command,stdout=stdout,stderr=stderr)
  codes[phase]=result.returncode
  if result.returncode:write(out/'receipt.json',dict(commands=commands,exitCodes=codes,inputs=frozen));raise RuntimeError(f'{phase} failed: {out}')
 after=files(out/'fixtures');write(out/'fixture-after.json',after)
 bm={x['file']:x for x in before};am={x['file']:x for x in after}
 audit=dict(originalFiles=len(before),changed=[f for f,e in bm.items() if am.get(f)!=e],missing=sorted(bm.keys()-am.keys()),added=sorted(am.keys()-bm.keys()))
 write(out/'fixture-audit.json',audit)
 assert not audit['changed'] and not audit['missing'] and all(Path(f).name in ['graph.nodeoffsets','graph.typeindex'] for f in audit['added'])
 assert files(SOURCE)==source_files and all(sha(Path(p))==h for p,h in frozen.items())
 write(out/'receipt.json',dict(mainRevision='4e328b0109e13c896b74004823fb049fcb19251a',commands=commands,exitCodes=codes,inputs=frozen,actualJarEntries=jar_entries,oracleClassSha256=sha(classes/'MainAstCacheOracle.class'),inputsUnchanged=True,fixtureAudit=audit,performanceMeasurements=0))
 print('Actual JVM parser correctness capture complete:',out)
if __name__=='__main__':
 parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('output',type=Path);parser.add_argument('--heap',choices=['512m','8g'],default='512m');args=parser.parse_args();run(args.output,args.heap)
