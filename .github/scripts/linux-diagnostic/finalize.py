"""Always-run receipt; never changes or masks the preceding Actions failure status."""
from pathlib import Path
import datetime,hashlib,json,os,platform,subprocess
R=Path('diagnostic-output');R.mkdir(exist_ok=True);S=Path(__file__).resolve().parent

def sha(p):
 h=hashlib.sha256()
 with p.open('rb') as f:
  for b in iter(lambda:f.read(1024*1024),b''):h.update(b)
 return h.hexdigest()
def command(args):
 r=subprocess.run(args,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
 return {'command':args,'exitCode':r.returncode,'output':r.stdout}
receipt={'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'jobStatusBeforeFinalization':os.getenv('DIAGNOSTIC_JOB_STATUS'),'accepted':False,'runId':os.getenv('GITHUB_RUN_ID'),'runAttempt':os.getenv('GITHUB_RUN_ATTEMPT'),'diagnosticCommit':os.getenv('GITHUB_SHA'),'ref':os.getenv('GITHUB_REF'),'platform':platform.platform(),'logicalCpuCount':os.cpu_count(),'nproc':command(['nproc']),'lscpu':command(['lscpu']),'uname':command(['uname','-a']),'javaVersion':command(['java','-version']),'git':{name:command(['git','-C',name,'rev-parse','HEAD']) for name in ['base','candidate','diagnostic']},'environment':{k:os.getenv(k) for k in ['JAVA_HOME','JAVA_OPTS','JVM_OPTS','ImageOS','ImageVersion','RUNNER_OS','RUNNER_ARCH']},'scriptHashes':{str(p.relative_to(S)):sha(p) for p in sorted(S.rglob('*')) if p.is_file() and '__pycache__' not in p.parts}}
(R/'workflow-receipt.json').write_text(json.dumps(receipt,indent=2)+'\n')
files=[{'path':str(p.relative_to(R)),'bytes':p.stat().st_size,'sha256':sha(p)} for p in sorted(R.rglob('*')) if p.is_file() and p.name!='file-manifest.json' and p.suffix!='.jar' and 'classes' not in p.parts]
(R/'file-manifest.json').write_text(json.dumps({'files':files,'count':len(files),'bytes':sum(p['bytes'] for p in files)},indent=2)+'\n')
