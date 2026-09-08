"""Verify retained bytes, actual inputs, fixture archive, and full JVM repetition."""
from pathlib import Path
import gzip,hashlib,json,tarfile
HERE=Path(__file__).resolve().parent
def sha(data):return hashlib.sha256(data).hexdigest()
def main():
 manifest=json.loads((HERE/'archive-manifest.json').read_text())
 for path,expected in manifest.items():assert sha((HERE/path).read_bytes())==expected,path
 records=[]
 for capture in ['main-capture','repeat-capture']:
  d=HERE/capture;receipt=json.loads((d/'receipt.json').read_text());inputs=json.loads((d/'inputs.json').read_text())
  assert receipt['inputsUnchanged'] and receipt['performanceMeasurements']==0 and all(c['exitCode']==0 for c in receipt['commands'])
  with tarfile.open(d/'input-sources.tar.gz') as t:
   for m in t:
    assert m.isfile() and not m.name.startswith('/') and '..' not in Path(m.name).parts
    assert sha(t.extractfile(m).read())==inputs['/'+m.name]
  with tarfile.open(d/'fixtures.tar.gz') as t:
   expected={e['file']:e for e in json.loads((d/'fixture-variants.json').read_text())};seen=set()
   for m in t:
    assert m.isfile() and m.name not in seen and not m.name.startswith('/') and '..' not in Path(m.name).parts
    data=t.extractfile(m).read();assert sha(data)==expected[m.name]['sha256'] and len(data)==expected[m.name]['bytes'];seen.add(m.name)
   assert len(seen)==80 and seen==expected.keys()
  before=json.loads(gzip.decompress((d/'fixture-before.json.gz').read_bytes()));after=json.loads(gzip.decompress((d/'fixture-after.json.gz').read_bytes()));assert len(before)==400 and before==after
  records.append(json.loads((d/'main.json').read_text()))
 assert records[0]==records[1]
 assert (HERE/'main.json').read_bytes()==(HERE/'main-capture/main.json').read_bytes()
 assert len(records[0]['cases'])==25 and sum(len(c['operations']) for c in records[0]['cases'])==25
 assert all(c['construction']['outcome']=='SUCCESS' for c in records[0]['cases'])
 print(json.dumps(dict(verifiedArtifacts=len(manifest),scenarios=25,operations=25,fullRecordsEqual=True,fixtureFilesPerCapture=80,unchangedCaseFixtureFilesPerCapture=400,performanceMeasurements=0)))
if __name__=='__main__':main()
