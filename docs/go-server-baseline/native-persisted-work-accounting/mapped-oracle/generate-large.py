"""One actual-main writer run; public repetitions later reuse its immutable bytes."""
from pathlib import Path
import datetime,hashlib,json,subprocess,tarfile
HERE=Path(__file__).resolve().parent
JAR=Path('/tmp/graphite-go-main-baseline-clone-4e328b0/graphite-explore/build/libs/graphite-explore.jar')
JDK=Path('/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home')
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def dump(p,x):p.write_text(json.dumps(x,indent=2)+'\n')
def run(out):
 out.mkdir(parents=True,exist_ok=False);(out/'classes').mkdir()
 paths=[HERE/'generate-large.py',HERE/'LargeMappedFixture.java',HERE/'large-fixture-specs.json',JAR,JDK/'bin/java',JDK/'bin/javac',JDK/'lib/modules',JDK/'lib/server/libjvm.dylib',JDK/'release']
 inputs={str(p):sha(p)for p in paths};dump(out/'inputs.json',inputs);commands=[]
 for phase,args in [('compile',[str(JDK/'bin/javac'),'-cp',str(JAR),'-d',str(out/'classes'),str(HERE/'LargeMappedFixture.java')]),('generate',[str(JDK/'bin/java'),'-Xmx2g','-cp',str(out/'classes')+':'+str(JAR),'LargeMappedFixture',str(HERE/'large-fixture-specs.json'),str(out/'variants'),str(out/'writer-mutations.json')])]:
  start=datetime.datetime.now(datetime.timezone.utc).isoformat()
  with (out/(phase+'.stdout')).open('x')as stdout,(out/(phase+'.stderr')).open('x')as stderr:r=subprocess.run(args,stdout=stdout,stderr=stderr)
  commands.append(dict(phase=phase,command=args,startUTC=start,endUTC=datetime.datetime.now(datetime.timezone.utc).isoformat(),exitCode=r.returncode));dump(out/'commands.json',commands)
  if r.returncode:raise RuntimeError(phase+' failed')
 rows=[dict(file=str(p.relative_to(out/'variants')),bytes=p.stat().st_size,sha256=sha(p))for p in sorted((out/'variants').rglob('*'))if p.is_file()];dump(out/'fixture-variants.json',rows)
 with tarfile.open(out/'fixtures.tar.gz','w:gz')as t:
  for r in rows:t.add(out/'variants'/r['file'],arcname=r['file'],recursive=False)
 assert all(sha(Path(p))==h for p,h in inputs.items())
 dump(out/'receipt.json',dict(inputsUnchanged=True,commands=commands,mainRevision='4e328b0109e13c896b74004823fb049fcb19251a',fixtureFiles=len(rows),fixtureArchiveSHA256=sha(out/'fixtures.tar.gz'),performanceMeasurements=0))
 print(out)
if __name__=='__main__':
 import argparse
 p=argparse.ArgumentParser();p.add_argument('output',type=Path);run(p.parse_args().output.resolve())
