from pathlib import Path
import json,hashlib,datetime
here=Path(__file__).resolve().parent
assert json.loads((here/'http-shipping/completion.json').read_text())['complete']
for phase in ['pagination21','regression42']:
 x=json.loads((here/'http-shipping'/phase/'server-exit.json').read_text());assert x['returnCode']==143 and not x['forcedKill']
frozen=json.loads((here.parent/'native64-profile-a7de0bec/fixture-files.json').read_text())
def digest(p):
 h=hashlib.sha256()
 with p.open('rb') as f:
  for b in iter(lambda:f.read(1024*1024),b''):h.update(b)
 return h.hexdigest()
checks=[]
for root in [Path('/tmp/pr113-exp037-fixture.nXn4fg'),Path('/tmp/graphite-pagination-http-real64-62b92d20')]:
 for x in frozen:
  p=root/x['graphId']/x['file'];assert p.stat().st_size==x['bytes'] and digest(p)==x['sha256'],str(p)
 actual={str(p.relative_to(root)) for g in {x['graphId'] for x in frozen} for p in (root/g).rglob('*') if p.is_file()}
 assert actual=={x['graphId']+'/'+x['file'] for x in frozen}
 checks.append({'root':str(root),'files':len(frozen),'allHashesMatch':True,'noExtraGraphFiles':True})
(here/'http-shipping/fixture-after.json').write_text(json.dumps({'verifiedUTC':datetime.datetime.now(datetime.timezone.utc).isoformat(),'afterBothServersExited':True,'checks':checks},indent=2)+'\n')
print('Original and HTTP clone1152 hashes unchanged after both exits',flush=True)
