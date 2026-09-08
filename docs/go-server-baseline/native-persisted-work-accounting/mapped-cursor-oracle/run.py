from pathlib import Path
import hashlib,json,shutil,subprocess,tarfile,sys,datetime
P=Path(__file__).resolve().parent
MAIN=Path('/tmp/graphite-go-main-baseline-clone-4e328b0');JAR=MAIN/'graphite-explore/build/libs/graphite-explore.jar';JDK=Path('/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home')
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
def dump(p,v):p.write_text(json.dumps(v,indent=2)+'\n')
def files(p):return [dict(file=str(f.relative_to(p)),bytes=f.stat().st_size,sha256=sha(f))for f in sorted(p.rglob('*'))if f.is_file()]
out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=False);(out/'classes').mkdir();(out/'fixtures').mkdir()
assert sha(JAR)=='91c3a1d154ca96004c55df195d9f752e077cab3e33ca1570b2c88b872d9bc34d'
archives=[P.parent/'mapped-oracle/large-writer-capture/fixtures.tar.gz',P.parent/'fixtures.tar.gz']
inputs=[P/'run.py',P/'MappedCursorOracle.java',JAR,*archives,*[JDK/f for f in ['bin/java','bin/javac','lib/modules','release','lib/server/libjvm.dylib']],MAIN/'graphite-webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/MappedCallSiteStringIndexView.kt',MAIN/'graphite-webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/MappedCallSiteStringIndex.kt',MAIN/'graphite-webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/MappedWebGraphBackedGraph.kt',MAIN/'graphite-core/src/main/kotlin/io/johnsonlee/graphite/graph/Graph.kt']
identities={str(f):sha(f)for f in inputs};dump(out/'inputs.json',identities)
for archive,variant in zip(archives,['large','hit64']):
 with tarfile.open(archive)as t:
  for m in t:
   if m.isfile()and m.name.split('/')[0]==variant:
    f=out/'fixtures'/m.name;f.parent.mkdir(parents=True,exist_ok=True);f.write_bytes(t.extractfile(m).read())
before=files(out/'fixtures');dump(out/'fixture-before.json',before);commands=[]
def run(phase,cmd):
 started=datetime.datetime.now(datetime.timezone.utc).isoformat()
 with (out/(phase+'.stdout')).open('x')as stdout,(out/(phase+'.stderr')).open('x')as stderr:
  process=subprocess.Popen(cmd,stdout=stdout,stderr=stderr);dump(out/'live-process.json',dict(phase=phase,pid=process.pid,command=cmd));print(phase,'PID',process.pid,flush=True);rc=process.wait()
 commands.append(dict(phase=phase,command=cmd,exitCode=rc,startUTC=started,endUTC=datetime.datetime.now(datetime.timezone.utc).isoformat()));dump(out/'commands.json',commands)
 if rc:raise RuntimeError(phase+' failed, preserved')
run('compile',[str(JDK/'bin/javac'),'-cp',str(JAR),'-d',str(out/'classes'),str(P/'MappedCursorOracle.java')])
run('capture',[str(JDK/'bin/java'),'-Xmx512m','-cp',str(out/'classes')+':'+str(JAR),'MappedCursorOracle',str(out/'fixtures'),str(out/'main.json')])
after=files(out/'fixtures');dump(out/'fixture-after.json',after);assert before==after
assert all(sha(Path(p))==h for p,h in identities.items())
dump(out/'receipt.json',dict(terminal=True,exitCode=0,inputsUnchanged=True,fixtureFiles=len(before),fixturesUnchanged=True,classes=files(out/'classes'),performanceMeasurements=0));print('TERMINAL0',out,flush=True)
