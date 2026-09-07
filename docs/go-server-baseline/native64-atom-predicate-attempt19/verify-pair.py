from pathlib import Path
import json,hashlib,datetime
from decimal import Decimal
here=Path(__file__).resolve().parent
read=lambda p:json.loads(p.read_text())
def digest(path):
 h=hashlib.sha256()
 with path.open('rb') as f:
  for block in iter(lambda:f.read(1024*1024),b''):h.update(block)
 return h.hexdigest()
def typed(x):
 if isinstance(x,dict):return ('object',tuple(sorted((k,typed(v)) for k,v in x.items())))
 if isinstance(x,list):return ('array',tuple(typed(v) for v in x))
 if isinstance(x,Decimal):return ('number',x)
 if type(x) in (int,float):return ('number',Decimal(str(x)))
 return (type(x).__name__,x)
def body(p):return typed(json.loads(p.read_text(),parse_int=Decimal,parse_float=Decimal))
cfg=read(here/'config.json');prep=read(here/'preparation.json');assert len(cfg['graphs'])==64 and len(cfg['queries'])==21
main=read(here.parent/'native64-streaming-pagination-integration/main-http/observations.json');assert len(main)==21
for q,o in zip(cfg['queries'],main):
 assert q['name']==o['case']['name'] and q['query']==o['case']['body']['query']
 assert typed(q['expected'])==typed(json.loads(o['response']['body'],parse_int=Decimal,parse_float=Decimal)) and o['response']['status']==200
for phase,manifest in [('base','base-source.json'),('candidate','tested-source.json')]:
 r=here/phase;assert read(r/'completion.json')['exitCode']==0
 identity=read(r/'identity.json');assert digest(Path(identity['command'][0]))==identity['binarySHA256'];module=Path(prep['worktrees'][phase])/'graphite-server'
 for p in read(r/'source-manifest.json'):assert digest(module/p['path'])==p['sha256'],(phase,p['path'])
 for p,h in read(here/manifest).items():assert digest(module/p)==h,(phase,p)
 assert (r/'profile.log').read_text().rstrip().endswith('COMPLETE')
rows=[];states=[]
for q in cfg['queries']:
 row={'name':q['name'],'query':q['query'],'bothCompleteTypedResponsesEqual':True}
 for phase in ['base','candidate']:
  d=here/phase/'profiles'/q['name'];r=read(d/'receipt.json');assert r['fullOutputMatchesExpected'] is True and 'error' not in r
  assert body(d/'output.json')==typed(q['expected']) and digest(d/'output.json')==r['outputSha256']
  assert read(d/'query.json')==q;delta=r['requestDeltas'];row[phase]={'seconds':r['executeAndMarshalSecondsDiagnostic'],'cpuSeconds':delta['userCPUSeconds']+delta['systemCPUSeconds'],'allocatedBytes':delta['totalAllocBytes'],'numGC':delta['numGC'],'pauseTotalNs':delta['pauseTotalNs']}
 for name in ['projection-state-before.json','projection-state-after.json']:
  a=read(here/'base/profiles'/q['name']/name);b=read(here/'candidate/profiles'/q['name']/name)
  if typed(a)!=typed(b):states.append({'name':q['name'],'state':name,'base':a,'candidate':b})
 rows.append(row)
assert not states,states
(here/'comparison.json').write_text(json.dumps({'all42CompleteTypedResponsesEqual':True,'allSourceHistoriesEqual':True,'sourceAndBinaryUnchanged':True,'bothProcessesClosedStoresAndExited0':True,'observations':rows,'scope':'Single sequential instrumented same-history measurements, allknownagentexecutionpaused; not P95/peakRSS/main-relative10x.'},indent=2)+'\n')
print('All42 complete typed responses/source histories equal; sources/binaries unchanged',flush=True)
fixture=read(here.parent/'native64-profile-a7de0bec/fixture-files.json');checks=[]
for root in [Path('/tmp/pr113-exp037-fixture.nXn4fg'),Path(prep['clones']['native'])]:
 for x in fixture:
  p=root/x['graphId']/x['file'];assert p.stat().st_size==x['bytes'] and digest(p)==x['sha256'],str(p)
 actual={str(p.relative_to(root)) for g in {x['graphId'] for x in fixture} for p in (root/g).rglob('*') if p.is_file()}
 assert actual=={x['graphId']+'/'+x['file'] for x in fixture}
 checks.append({'root':str(root),'files':len(fixture),'allHashesMatch':True,'noExtraGraphFiles':True})
(here/'post-profile-fixture-verification.json').write_text(json.dumps({'verifiedUTC':datetime.datetime.now(datetime.timezone.utc).isoformat(),'afterBothProcessesExit0':True,'checks':checks},indent=2)+'\n')
print('All1152 original/native fixture files unchanged',flush=True)
