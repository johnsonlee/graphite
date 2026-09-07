import pathlib,json,hashlib
here=pathlib.Path(__file__).resolve().parent
read=lambda p:json.loads(p.read_text())
def sha(p):
 h=hashlib.sha256()
 with p.open('rb') as f:
  for block in iter(lambda:f.read(1024*1024),b''):h.update(block)
 return h.hexdigest()
rows=[]
for group in ['']:
 folder=here/group;cfg=read(folder/'config.json')
 for label in ['base','candidate']:
  run=folder/label;identity=read(run/'identity.json');assert read(run/'completion.json')['exitCode']==0
  module=pathlib.Path('/tmp/graphite-go-cde-'+('base' if label=='base' else 'root')+'-9ada2bf1')/'graphite-server'
  for item in read(run/'source-manifest.json'):assert sha(module/item['path'])==item['sha256'],item['path']
  assert sha(pathlib.Path(identity['command'][0]))==identity['binarySHA256']
  assert read(run/'profiles/catalog.json')['totals']==cfg['totals']
 for q in cfg['queries']:
  a,b=[read(folder/label/'profiles'/q['name']/'receipt.json') for label in ['base','candidate']]
  assert a['fullOutputMatchesExpected'] and b['fullOutputMatchesExpected']
  assert a['outputSha256']==b['outputSha256']==a['expectedSha256']==b['expectedSha256']
  rows.append({'group':group or 'global64','name':q['name'],'completeResponseEqual':True,'baseSeconds':a['executeAndMarshalSecondsDiagnostic'],'candidateSeconds':b['executeAndMarshalSecondsDiagnostic'],'baseCPU':sum(a['requestDeltas'][k] for k in ['userCPUSeconds','systemCPUSeconds']),'candidateCPU':sum(b['requestDeltas'][k] for k in ['userCPUSeconds','systemCPUSeconds']),'baseAllocationBytes':a['requestDeltas']['totalAllocBytes'],'candidateAllocationBytes':b['requestDeltas']['totalAllocBytes']})
assert len(rows)==13
(here/'comparison.json').write_text(json.dumps({'all13CompleteResponsesEqual':True,'sourceAndBinaryUnchanged':True,'observations':rows,'scope':'Single sequential instrumented observations, not P95 or main-relative speedup.'},indent=2)+'\n')
for r in rows:print(r['group'],r['name'],r['baseSeconds'],r['candidateSeconds'],r['baseAllocationBytes'],r['candidateAllocationBytes'])
manifest=read(here.parent/'native64-profile-a7de0bec/fixture-files.json');checks=[]
for root in [pathlib.Path('/tmp/pr113-exp037-fixture.nXn4fg'),pathlib.Path('/tmp/graphite-cde-native-real64-9ada2bf1'),pathlib.Path('/tmp/graphite-cde-main-real64-9ada2bf1')]:
 expected={str(pathlib.Path(x['graphId'])/x['file']) for x in manifest}
 for x in manifest:
  p=root/x['graphId']/x['file'];assert p.stat().st_size==x['bytes'] and sha(p)==x['sha256'],str(p)
 actual={str(p.relative_to(root)) for g in {x['graphId'] for x in manifest} for p in (root/g).rglob('*') if p.is_file()}
 assert actual==expected
 checks.append({'root':str(root),'files':len(manifest),'allHashesMatch':True,'noExtraGraphFiles':True})
(here/'post-profile-fixture-verification.json').write_text(json.dumps(checks,indent=2)+'\n')
