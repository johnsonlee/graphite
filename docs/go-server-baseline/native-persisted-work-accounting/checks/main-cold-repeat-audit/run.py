"""Independent original MainReplayCapture cold repeat. Never alters reference/comparison."""
from pathlib import Path
import datetime,hashlib,json,os,shutil,subprocess,sys
HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[4]
PREVIOUS=ROOT/'docs/go-server-baseline/native64-fullcase-replay'
FIXTURE_MANIFEST=ROOT/'docs/go-server-baseline/native64-profile-a7de0bec/fixture-files.json'
def sha(p):
 h=hashlib.sha256()
 with p.open('rb')as f:
  for b in iter(lambda:f.read(1048576),b''):h.update(b)
 return h.hexdigest()
def dump(p,x):p.write_text(json.dumps(x,indent=2)+'\n')
def now():return datetime.datetime.now(datetime.timezone.utc).isoformat()
def run(out):
 out.mkdir(parents=True,exist_ok=False)
 reference=Path(json.loads((PREVIOUS/'main-cold-preflight.json').read_text())['reference'])
 frozen=json.loads((PREVIOUS/'main-cold-complete-classpath-inputs.json').read_text())
 cp=(PREVIOUS/'capture-classpath-complete.txt').read_text().strip();actual={}
 for component in cp.split(os.pathsep):
  p=Path(component)
  for f in sorted(p.rglob('*'))if p.is_dir()else[p]:
   if f.is_file():actual[str(f)]=sha(f)
 dump(out/'classpath-inputs.json',actual);assert actual==frozen,'Original classpath changed'
 jdk=Path(os.environ['JAVA_HOME']);assert '17.0.18'in str(jdk)
 extra={k:os.environ.get(k)for k in ['JAVA_TOOL_OPTIONS','_JAVA_OPTIONS','JDK_JAVA_OPTIONS']};assert all(v is None for v in extra.values()),extra
 inputs={str(p):sha(p)for p in [Path(__file__),FIXTURE_MANIFEST,PREVIOUS/'audit-fixtures.py',PREVIOUS/'MainReplayCapture.java',PREVIOUS/'capture-classpath-complete.txt',PREVIOUS/'main-cold-complete-classpath-inputs.json',jdk/'bin/java',jdk/'lib/modules',jdk/'lib/server/libjvm.dylib',jdk/'release',Path(shutil.which('java'))]}
 dump(out/'input-identities.json',inputs)
 with (out/'java-version.txt').open('x')as f:r=subprocess.run(['java','-version'],stdout=f,stderr=subprocess.STDOUT)
 assert r.returncode==0
 dump(out/'jdk-identity.json',dict(javaHome=str(jdk),launcher=shutil.which('java'),binarySHA256=sha(jdk/'bin/java'),release=(jdk/'release').read_text(),extraOptionEnvironment=extra,historicalLimitation='Original cold receipt archived classpath hashes and java -Xmx8g command, but not a JDK binary digest. This run records the effective configured JDK explicitly.'))
 # Evaluate the original read-only audit functions without importing/writing its pycache.
 ns={'__name__':'read_only_fixture_audit','__file__':str(PREVIOUS/'audit-fixtures.py')};exec(compile((PREVIOUS/'audit-fixtures.py').read_text(),str(PREVIOUS/'audit-fixtures.py'),'exec'),ns)
 audit=ns['audit'];clone=out/'fixture';start=now()
 subprocess.run(['/bin/cp','-cRp',str(reference),str(clone)],check=True)
 check=audit(clone);dump(out/'preflight.json',check);assert check['matched']==1152 and not check['added']and not check['changed']and not check['missing']
 lines=[]
 for line in (clone/'graphs.tsv').read_text().splitlines():
  if not line.strip()or line.lstrip().startswith('#'):lines.append(line);continue
  parts=line.split('\t');assert len(parts)==6;parts[1]=str(clone/parts[0]);lines.append('\t'.join(parts))
 manifest=clone/'graphs-relocated.tsv';manifest.write_text('\n'.join(lines)+'\n')
 sourceManifestSHA=sha(reference/'graphs.tsv')
 cmd=['java','-Xmx8g','-cp',cp,'MainReplayCapture',str(manifest),'cold',str(out/'capture')]
 record=dict(command=cmd,status='starting',startedPreparationUTC=start,startedAt=now(),performanceMeasurement=False,reference=str(reference),clone=str(clone),graphsManifestSHA256=sha(manifest),originalGraphsManifestSHA256=sourceManifestSHA,classpathMatchesOriginal330Files=True)
 dump(out/'process.json',record)
 with (out/'process.log').open('x')as f:
  p=subprocess.Popen(cmd,stdout=f,stderr=subprocess.STDOUT);record.update(status='running',pid=p.pid);dump(out/'process.json',record);print('Main complete cold PID',p.pid,flush=True);code=p.wait()
 record.update(status='exited',exitCode=code,finishedAt=now(),classpathUnchanged=all(sha(Path(p))==h for p,h in actual.items()),inputsUnchanged=all(sha(Path(p))==h for p,h in inputs.items()))
 dump(out/'process.json',record);post=audit(clone);dump(out/'postrun-fixtures.json',post)
 ref=audit(reference);dump(out/'reference-postrun.json',ref)
 assert ref['matched']==1152 and not ref['changed']and not ref['missing']and not ref['added']and sha(reference/'graphs.tsv')==sourceManifestSHA
 assert post['matched']==1152 and not post['changed']and not post['missing']and not post['added']
 assert record['classpathUnchanged']and record['inputsUnchanged']
 print('Main terminal',code,'pre/post/reference matched1152',flush=True)
 raise SystemExit(code)
if __name__=='__main__':run(Path(sys.argv[1]).resolve())
