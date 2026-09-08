"""Read-only artifact verification; no JVM, Go or benchmark execution."""
from pathlib import Path
import hashlib,json,tarfile
HERE=Path(__file__).resolve().parent
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def read(p):return json.loads(p.read_text())
def main():
 d=read(HERE/'oracle-manifest.json')
 for f,h in d['files'].items():assert sha(HERE/f)==h,f
 for row in read(HERE/'archive-copy-verification.json')['copied']:
  p=HERE/row['file'];assert p.stat().st_size==row['bytes'] and sha(p)==row['sha256']
 for label,count in [('initial-ten-capture',10),('main-capture',16),('repeat-capture',16)]:
  p=HERE/label;r=read(p/'receipt.json');assert r['inputsUnchanged'] and all(x['exitCode']==0 for x in r['commands']);assert r['fixtureFilesUnchanged']==count*16
  before=read(p/'fixture-before.json');assert before==read(p/'fixture-after.json') and len(before)==count*16
  variants={v['file']:v for v in read(p/'fixture-variants.json')}
  with tarfile.open(p/'fixtures.tar.gz')as t:
   members=t.getmembers();assert len(members)==16
   for m in members:assert m.isfile() and m.size==variants[m.name]['bytes'] and hashlib.sha256(t.extractfile(m).read()).hexdigest()==variants[m.name]['sha256']
  inputs=read(p/'inputs.json')
  with tarfile.open(p/'input-sources.tar.gz')as t:
   for m in t:assert m.isfile() and hashlib.sha256(t.extractfile(m).read()).hexdigest()==inputs['/'+m.name]
 a=read(HERE/'main-capture/main.json');assert a==read(HERE/'repeat-capture/main.json')==read(HERE/'main.json');assert len(a['cases'])==16 and sum(len(c['operations'])for c in a['cases'])==16
 for c in a['cases']:
  o=c['operations'][0];assert o['outcome']=='SUCCESS' and o['value']['rows']==[] and o['after']['diagnostics']['workUnitsConsumed']==0 and len(o['after']['diagnostics'])==8
 assert read(HERE/'all-cases.json')==[c['spec']for c in a['cases']]
 print(json.dumps(dict(verified=True,manifestFiles=len(d['files']),cases=16,operations=16,fixtureFiles=16,completeRepeatEqual=True,performanceMeasurements=0),indent=2))
if __name__=='__main__':main()
