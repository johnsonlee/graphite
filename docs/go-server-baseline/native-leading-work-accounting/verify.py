"""Read-only verification of original captures, source snapshots and persisted fixtures."""
from pathlib import Path
import gzip,hashlib,json,tarfile
HERE=Path(__file__).resolve().parent
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def read(p):return json.loads(p.read_text())
def execution(c):return {k:v for k,v in c.items()if k not in ['fixtureBeforeLoad','fixtureAfterClose']}
def main():
 manifest=read(HERE/'oracle-manifest.json')
 for name,h in manifest['files'].items():assert sha(HERE/name)==h,name
 copies=read(HERE/'archive-copy-verification.json')['copied']
 for row in copies:
  p=HERE/row['file'];assert p.stat().st_size==row['bytes'] and sha(p)==row['sha256']
 for label,count,ops,variants,files in [('prepared-main-capture',24,48,219,15432),('prepared-repeat-capture',24,48,219,15432),('main-capture',26,52,208,16672),('repeat-capture',26,52,208,16672)]:
  p=HERE/label;receipt=read(p/'receipt.json');assert receipt['inputsUnchanged'] and all(c['exitCode']==0 for c in receipt['commands']);assert receipt['fixtureAudit']==dict(originalFiles=files,changed=[],added=[],missing=[])
  before=json.loads(gzip.decompress((p/'fixture-before.json.gz').read_bytes()));after=json.loads(gzip.decompress((p/'fixture-after.json.gz').read_bytes()));assert before==after and len(before)==files
  variant={v['file']:v for v in read(p/'fixture-variants.json')};assert len(variant)==variants
  with tarfile.open(p/'fixtures.tar.gz')as t:
   members=t.getmembers();assert len(members)==variants
   for m in members:assert m.isfile() and m.size==variant[m.name]['bytes'] and hashlib.sha256(t.extractfile(m).read()).hexdigest()==variant[m.name]['sha256']
  if label in ['main-capture','repeat-capture']:assert not any(Path(k).name=='graph.callsite-string-index'for k in variant)
  inputs=read(p/'inputs.json')
  with tarfile.open(p/'input-sources.tar.gz')as t:
   for m in t:assert m.isfile() and hashlib.sha256(t.extractfile(m).read()).hexdigest()==inputs['/'+m.name]
  data=read(p/'main.json');assert len(data['cases'])==count and sum(len(c['operations'])for c in data['cases'])==ops
  assert data['configuredDirectStringParallelism'] is None and data['performanceMeasurements']==0
  for case in data['cases']:
   assert case['fixtureBeforeLoad']==case['fixtureAfterClose']
   assert len(case['spec']['sources'])in [39,40]
   for op in case['operations']:
    assert op['spec']['op']in ['execute','newContext','executePrelude']
    assert len(op['after']['diagnostics'])==8
    assert all(s['callSiteParallelScanCount']==0 for s in op['after']['storage'])
 for left,right in [('prepared-main-capture','prepared-repeat-capture'),('main-capture','repeat-capture')]:assert [execution(c)for c in read(HERE/left/'main.json')['cases']]==[execution(c)for c in read(HERE/right/'main.json')['cases']]
 assert [c['spec']for c in read(HERE/'main.json')['cases']]==read(HERE/'expanded-cases.json')
 for name in ['main.json','fixtures.tar.gz','fixture-variants.json','mutations.json']:assert sha(HERE/name)==sha(HERE/'main-capture'/name)
 removed=[m for m in read(HERE/'mutations.json')if m.get('action')=='remove-generated-sidecar'];assert len(removed)==11
 with tarfile.open(HERE/'prepared-main-capture/fixtures.tar.gz')as t:
  for row in removed:assert hashlib.sha256(t.extractfile(row['fixture']+'/'+row['file']).read()).hexdigest()==row['sha256']
 differences=read(HERE/'prepared-comparison.json');assert len(differences['differentCases'])==6
 print(json.dumps(dict(verified=True,manifestFiles=len(manifest['files']),rawCopiedArtifacts=len(copies),finalCases=26,finalOperations=52,fixtureRegularFiles=208,preparedCases=24,preparedOperations=48,preparedDifferentCases=6,sidecarsExplicitlyRemoved=11,allExecutionRepeatRecordsEqual=True,performanceMeasurements=0),indent=2))
if __name__=='__main__':main()
