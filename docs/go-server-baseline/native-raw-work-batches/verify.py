"""Read-only verification of the frozen oracle. Does not launch a runtime or update evidence."""
from pathlib import Path
import gzip,hashlib,json,tarfile
HERE=Path(__file__).resolve().parent
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def read(p):return json.loads(p.read_text())
def main():
 manifest=read(HERE/'oracle-manifest.json')
 for name,h in manifest['files'].items():assert sha(HERE/name)==h,name
 copied=read(HERE/'archive-copy-verification.json')['copied']
 for row in copied:
  p=HERE/row['file'];assert p.stat().st_size==row['bytes'] and sha(p)==row['sha256'],str(p)
 for label in ['main-capture','repeat-capture','diagnostic-main-capture','diagnostic-repeat-capture']:
  p=HERE/label;receipt=read(p/'receipt.json');assert receipt['inputsUnchanged'] and all(c['exitCode']==0 for c in receipt['commands'])
  assert receipt['fixtureAudit']==dict(originalFiles=1048,changed=[],added=[],missing=[])
  before=json.loads(gzip.decompress((p/'fixture-before.json.gz').read_bytes()));after=json.loads(gzip.decompress((p/'fixture-after.json.gz').read_bytes()));assert before==after and len(before)==1048
  inputs=read(p/'inputs.json')
  with tarfile.open(p/'input-sources.tar.gz')as t:
   for member in t:
    assert member.isfile();assert hashlib.sha256(t.extractfile(member).read()).hexdigest()==inputs['/'+member.name]
  variants={v['file']:v for v in read(p/'fixture-variants.json')}
  with tarfile.open(p/'fixtures.tar.gz')as t:
   members=t.getmembers();assert len(members)==len(variants)==329
   for m in members:
    assert m.isfile() and m.size==variants[m.name]['bytes'];assert hashlib.sha256(t.extractfile(m).read()).hexdigest()==variants[m.name]['sha256']
  public=read(p/'main.json');primitive=read(p/'buffered-main.json');assert len(public['cases'])==43 and sum(len(c['operations'])for c in public['cases'])==57;assert len(primitive['cases'])==12 and sum(len(c['operations'])for c in primitive['cases'])==39
  assert public['performanceMeasurements']==primitive['performanceMeasurements']==0
 a=read(HERE/'main-capture/main.json');b=read(HERE/'repeat-capture/main.json');differences=[]
 for x,y in zip(a['cases'],b['cases']):
  if x!=y:differences.append(x['name'])
 assert differences==['call-bad1023-budget1024','call-bad1024-budget1025']
 for output in [a,b]:
  for c in output['cases']:
   for o in c['operations']:
    assert len(o['after']['diagnostics'])==8
    for s in o['after']['storage']:assert s['callSiteParallelScanCount']==0
 assert read(HERE/'diagnostic-main-capture/main.json')==read(HERE/'diagnostic-repeat-capture/main.json')
 assert read(HERE/'main-capture/buffered-main.json')==read(HERE/'repeat-capture/buffered-main.json')
 for name in ['main.json','buffered-main.json','fixtures.tar.gz','fixture-variants.json','mutations.json']:assert sha(HERE/name)==sha(HERE/'main-capture'/name)
 print(json.dumps(dict(manifestFiles=len(manifest['files']),copiedRawArtifacts=len(copied),actualJVMCaptures=4,publicCasesPerCapture=43,publicOperationsPerCapture=57,primitiveCasesPerCapture=12,primitiveOperationsPerCapture=39,fixtureFilesPerArchive=329,rawErrorVariabilityPreserved=True,performanceMeasurements=0,verified=True),indent=2))
if __name__=='__main__':main()
