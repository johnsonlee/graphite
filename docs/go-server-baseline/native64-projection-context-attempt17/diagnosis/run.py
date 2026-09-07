from pathlib import Path
import json,hashlib,datetime,subprocess,os
root=Path('/tmp/graphite-a15-mutex-root-62b92d20')
provider=Path('/tmp/graphite-a15-mutex-diagnostic')
repo=Path('/Users/johnsonlee/.codex/worktrees/112399f5-4ea0-42da-af34-5ab6ef682c3d/graphite')
original=Path('/tmp/pr113-exp037-fixture.nXn4fg')
clone=Path('/tmp/graphite-mutex-real64-62b92d20')
def digest(p):
 h=hashlib.sha256()
 with p.open('rb') as f:
  for b in iter(lambda:f.read(1024*1024),b''):h.update(b)
 return h.hexdigest()
def save(p,v):p.write_text(json.dumps(v,indent=2)+'\n')
def now():return datetime.datetime.now(datetime.timezone.utc).isoformat()
m=json.loads((provider/'manifest.json').read_text());assert digest(provider/'manifest.json')=='73a60c2cf55fdac5fe8264fc475d0afd7ddb0a93670505a7122ffc58cd387dd6'
for p,v in m['files'].items(): assert digest(provider/p)==v['sha256'],p
base=json.loads((provider/'baseline/source-manifest.json').read_text())
overlay={p.removeprefix('module/'):sha for p,sha in m['overlay'].items()}
for v in base:assert digest(provider/'module'/v['path'])==overlay.get(v['path'],v['sha256']),v['path']
for p,sha in overlay.items():assert digest(provider/'module'/p)==sha,p
core=json.loads((repo/'docs/go-server-baseline/native64-binding-map-attempt15/integration/source.json').read_text())
for p,sha in core.items():assert digest(repo/'graphite-server'/p)==sha and digest(provider/'module'/p)==sha,p
save(root/'source-verification.json',{'verifiedUTC':now(),'rootRevision':'62b92d20','coreFiles':len(core),'archivedInputs':len(base),'harnessOverlay':overlay,'binarySHA256':digest(provider/'mutex-profile-query'),'providerManifest':digest(provider/'manifest.json'),'allHashesMatch':True})
assert not clone.exists()
subprocess.run(['/bin/cp','-cRp',str(original),str(clone)],check=True)
fixture=json.loads((repo/'docs/go-server-baseline/native64-profile-a7de0bec/fixture-files.json').read_text())
def verify(path):
 for x in fixture:
  f=path/x['graphId']/x['file']; assert f.stat().st_size==x['bytes'] and digest(f)==x['sha256'],str(f)
 actual={str(f.relative_to(path)) for g in {x['graphId'] for x in fixture} for f in (path/g).rglob('*') if f.is_file()}
 assert actual=={x['graphId']+'/'+x['file'] for x in fixture}
 return {'root':str(path),'files':len(fixture),'bytes':sum(x['bytes'] for x in fixture),'allHashesMatch':True,'noExtraGraphFiles':True}
save(root/'fixture-before.json',{'verifiedUTC':now(),'checks':[verify(original),verify(clone)]})
cfg=json.loads((provider/'baseline/config.json').read_text());assert len(cfg['graphs'])==64 and len(cfg['queries'])==5
for g in cfg['graphs']:g['path']=str(clone/g['id'])
save(root/'config.json',cfg)
print('Verified source, binary, original and clone; starting control',flush=True)
for name,rate in [('control',0),('rate16',16)]:
 out=root/name;assert not out.exists()
 argv=[str(provider/'mutex-profile-query'),'--config',str(root/'config.json'),'--out',str(out),'--mutex-rate',str(rate)]
 if rate:argv+=['--mutex-query','wrapped-firstLastGraphBimodalClassPrefix-repeat']
 save(root/(name+'-command.json'),{'argv':argv,'startedUTC':now(),'environment':{k:os.getenv(k) for k in ['GOGC','GOMEMLIMIT','GOMAXPROCS','GODEBUG']},'concurrency':'No other real64 process; independent correctness work may overlap; diagnostic only.'})
 with (root/(name+'.log')).open('w') as log:
  proc=subprocess.run(argv,stdout=log,stderr=subprocess.STDOUT)
 save(root/(name+'-completion.json'),{'exitCode':proc.returncode,'finishedUTC':now()});assert proc.returncode==0,name
 save(root/(name+'-fixture-after.json'),{'verifiedUTC':now(),'checks':[verify(clone)]})
 print(name,'complete with exit0 and fixture unchanged',flush=True)
save(root/'fixture-after.json',{'verifiedUTC':now(),'checks':[verify(original),verify(clone)]})
for p,sha in core.items():assert digest(provider/'module'/p)==sha,p
assert digest(provider/'mutex-profile-query')==m['binarySHA256']
for p,sha in overlay.items():assert digest(provider/'module'/p)==sha,p
save(root/'completion.json',{'finishedUTC':now(),'bothProcessesExit0':True,'sourceAndBinaryUnchanged':True,'originalAndCloneUnchanged':True})
print('COMPLETE',flush=True)
