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
def typed(value):
 if isinstance(value,dict):return ('object',tuple(sorted((k,typed(v)) for k,v in value.items())))
 if isinstance(value,list):return ('array',tuple(typed(v) for v in value))
 if isinstance(value,Decimal):return ('number',value)
 if type(value) in (int,float):return ('number',Decimal(str(value)))
 return (type(value).__name__,value)
def body(p):return typed(json.loads(p.read_text(),parse_int=Decimal,parse_float=Decimal))
cfg=read(here/'config.json');assert len(cfg['queries'])==21
main=read(here/'main-http/observations.json');assert len(main)==21
for q,o in zip(cfg['queries'],main):
 assert q['name']==o['case']['name'] and q['query']==o['case']['body']['query']
 assert typed(q['expected'])==typed(json.loads(o['response']['body'],parse_int=Decimal,parse_float=Decimal))
 assert o['response']['status']==200
assert read(here/'base-sweep/completion.json')['exitCode']==2
assert read(here/'candidate-sweep/completion.json')['exitCode']==0
rows=[];mismatches=[]
for phase in ['base-sweep','candidate-sweep']:
 r=here/phase;identity=read(r/'identity.json');assert digest(Path(identity['command'][0]))==identity['binarySHA256']
 module=Path(read(here/'preparation.json')['worktrees']['base' if phase=='base-sweep' else 'candidate'])/'graphite-server'
 for x in read(r/'source-manifest.json'):assert digest(module/x['path'])==x['sha256'],(phase,x['path'])
 assert 'COMPLETE allOutputsMatch '+('false' if phase=='base-sweep' else 'true') in (r/'profile.log').read_text()
 if phase=='base-sweep':baseSource=read(here.parent/'native64-binding-map-attempt15/integration/source.json')
 else:baseSource=read(here/'tested-source.json')
 for name,sha in baseSource.items():assert digest(module/name)==sha,(phase,name)
for q in cfg['queries']:
 row={'name':q['name'],'query':q['query']}
 for phase,label in [('base-sweep','base'),('candidate-sweep','candidate')]:
  d=here/phase/'profiles'/q['name'];r=read(d/'receipt.json');equal=body(d/'output.json')==typed(q['expected'])
  assert equal==r['fullOutputMatchesExpected'] and 'error' not in r
  assert digest(d/'output.json')==r['outputSha256']
  assert read(d/'query.json')==q
  if not equal:mismatches.append({'phase':phase,'name':q['name'],'expected':q['expected'],'actual':read(d/'output.json')})
  delta=r['requestDeltas'];row[label]={'completeTypedMainResponseEqual':equal,'seconds':r['executeAndMarshalSecondsDiagnostic'],'cpuSeconds':delta['userCPUSeconds']+delta['systemCPUSeconds'],'allocatedBytes':delta['totalAllocBytes'],'numGC':delta['numGC']}
 row['validPairedLatencyComparison']=row['base']['completeTypedMainResponseEqual'] and row['candidate']['completeTypedMainResponseEqual'];rows.append(row)
assert [(x['phase'],x['name']) for x in mismatches]==[('base-sweep','b-distinct-order-eviction-candidate')],mismatches
(here/'comparison.json').write_text(json.dumps({'all21CandidateCompleteResponsesEqual':True,'baseCompleteResponsesEqual':20,'baseFunctionalMismatches':mismatches,'allCoreSourceAndBinaryInputsUnchanged':True,'bothFullSweepsReachedStoreClose':True,'observations':rows,'scope':'Single sequential instrumented full-history observations; no P95/main-relative10x or valid speed comparison for wrong baseline output.'},indent=2)+'\n')
print('Verified full21 candidate and20/21 baseline; one actual baseline row error retained; all source/binary identities fixed',flush=True)
fixture=read(here.parent/'native64-profile-a7de0bec/fixture-files.json');prep=read(here/'preparation.json');checks=[]
for root in [Path('/tmp/pr113-exp037-fixture.nXn4fg'),Path(prep['clones']['main']),Path(prep['clones']['native'])]:
 for x in fixture:
  p=root/x['graphId']/x['file'];assert p.stat().st_size==x['bytes'] and digest(p)==x['sha256'],str(p)
 actual={str(p.relative_to(root)) for g in {x['graphId'] for x in fixture} for p in (root/g).rglob('*') if p.is_file()}
 assert actual=={x['graphId']+'/'+x['file'] for x in fixture}
 checks.append({'root':str(root),'files':len(fixture),'allHashesMatch':True,'noExtraGraphFiles':True})
(here/'post-profile-fixture-verification.json').write_text(json.dumps({'verifiedUTC':datetime.datetime.now(datetime.timezone.utc).isoformat(),'afterAllThreeProcessesExited':True,'checks':checks},indent=2)+'\n')
print('Original/main/native all1152 fixtures remain unchanged',flush=True)
