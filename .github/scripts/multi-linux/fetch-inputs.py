"""Run only on authorized diagnostic runner; authenticated immutable inputs, no Java."""
from pathlib import Path,PurePosixPath
import argparse,hashlib,json,subprocess,tarfile,urllib.request,zipfile
S=Path(__file__).resolve().parent
p=argparse.ArgumentParser();p.add_argument('--mode',choices=['global'],default='global');p.add_argument('--workspace',type=Path,default=Path.cwd());p.add_argument('--receipts',type=Path,default=Path('diagnostic-output/input-receipts'));a=p.parse_args();R=a.receipts.resolve();R.mkdir(parents=True,exist_ok=True);W=a.workspace.resolve()
pins=json.loads((S/'fetch-pins.json').read_text());repo=pins['repository']
def sha(path):
 h=hashlib.sha256()
 with path.open('rb') as f:
  for b in iter(lambda:f.read(1024*1024),b''):h.update(b)
 return h.hexdigest()
def write(n,v):(R/n).write_text(json.dumps(v,indent=2)+'\n')
def api(endpoint):return json.loads(subprocess.check_output(['gh','api',endpoint],text=True))
def safe_name(name):
 q=PurePosixPath(name)
 assert not q.is_absolute() and '..' not in q.parts and '\\' not in name, name
 return q
artifacts=pins['globalArtifacts'] if a.mode=='global' else json.loads((S/'method/pins.json').read_text())['artifacts']
for artifact in artifacts:
 ident=artifact['id'];meta=api(f'repos/{repo}/actions/artifacts/{ident}');write(str(ident)+'-metadata.json',meta)
 assert not meta['expired'] and meta['id']==ident and meta['name']==artifact['name']
 assert meta['digest']==artifact['digest'] and meta['workflow_run']['id']==pins['runId'] and meta['workflow_run']['head_sha']==pins['head']
 if a.mode=='global':dest=W/('shared-fixture64' if ident==9984020128 else 'global-evidence/benchmark-global-wide-116-1')
 else:dest=W/'method-inputs'/artifact['name']
 dest.mkdir(parents=True,exist_ok=False);arc=W/(str(ident)+'.zip')
 assert not arc.exists();cmd=['gh','api',f'repos/{repo}/actions/artifacts/{ident}/zip'];write(str(ident)+'-command.json',cmd)
 with arc.open('wb') as f:r=subprocess.run(cmd,stdout=f,stderr=subprocess.PIPE)
 (R/(str(ident)+'-stderr.log')).write_bytes(r.stderr);digest=sha(arc)
 write(str(ident)+'-download.json',{'exitCode':r.returncode,'sha256':digest,'bytes':arc.stat().st_size,'metadataDigest':meta['digest'],'destination':str(dest)})
 assert r.returncode==0 and 'sha256:'+digest==meta['digest'] and arc.stat().st_size==meta['size_in_bytes']
 with zipfile.ZipFile(arc) as z:
  for info in z.infolist():
   safe_name(info.filename);assert ((info.external_attr>>16)&0o170000)!=0o120000,'symlink zip member'
  z.extractall(dest)
 # Original archive stays in workspace, upload only its signed-metadata/digest receipt.
write('complete.json',{'downloadVerified':True,'mode':a.mode,'runId':pins['runId'],'head':pins['head'],'doesNotProveFixtureSemanticIdentity':True})
