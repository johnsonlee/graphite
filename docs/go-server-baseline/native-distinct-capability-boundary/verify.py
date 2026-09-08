"""Independent frozen-byte, source, fixture, and repeated-public-output audit."""
from pathlib import Path
import gzip,hashlib,json,tarfile
HERE=Path(__file__).resolve().parent
def digest(data):return hashlib.sha256(data).hexdigest()
def main():
 manifest=json.loads((HERE/'oracle-manifest.json').read_text())
 for item in manifest['files']:
  data=(HERE/item['file']).read_bytes();assert len(data)==item['bytes'] and digest(data)==item['sha256'],item['file']
 for capture in ['main-capture','repeat-capture']:
  d=HERE/capture;inputs=json.loads((d/'inputs.json').read_text());receipt=json.loads((d/'receipt.json').read_text())
  assert receipt['inputsUnchanged'] and receipt['performanceMeasurements']==0
  assert all(c['exitCode']==0 for c in receipt['commands'])
  with tarfile.open(d/'input-sources.tar.gz') as t:
   for m in t:
    assert m.isfile() and not m.name.startswith('/') and '..' not in Path(m.name).parts
    assert digest(t.extractfile(m).read())==inputs['/'+m.name]
  expected={v['file']:v for v in json.loads((d/'fixture-variants.json').read_text())}
  with tarfile.open(d/'fixtures.tar.gz') as t:
   seen=set()
   for m in t:
    assert m.isfile() and m.name not in seen and not m.name.startswith('/') and '..' not in Path(m.name).parts
    data=t.extractfile(m).read();e=expected[m.name];assert len(data)==e['bytes'] and digest(data)==e['sha256'];seen.add(m.name)
   assert len(seen)==83 and seen==expected.keys()
  before={e['file']:e for e in json.loads(gzip.decompress((d/'fixture-before.json.gz').read_bytes()))}
  after={e['file']:e for e in json.loads(gzip.decompress((d/'fixture-after.json.gz').read_bytes()))}
  assert all(after.get(k)==v for k,v in before.items())
  assert all(Path(k).name=='graph.callsite-string-index' for k in after.keys()-before.keys())
 assert (HERE/'main.json').read_bytes()==(HERE/'main-capture/main.json').read_bytes()
 assert (HERE/'fixtures.tar.gz').read_bytes()==(HERE/'main-capture/fixtures.tar.gz').read_bytes()
 a=json.loads((HERE/'main.json').read_text())['cases'];b=json.loads((HERE/'repeat-capture/main.json').read_text())['cases'];specs=json.loads((HERE/'cases.json').read_text())
 assert len(a)==len(b)==len(specs)==46
 assert all(r['spec']==s for r,s in zip(a,specs))
 assert sum(r['outcome']=='SUCCESS' for r in a)==30
 assert all(r.get('errorClass')=='java.lang.IllegalStateException' and r.get('message')=='Distinct projection capability became unavailable' for r in a if r['outcome']=='FAILED')
 diffs=[]
 for x,y in zip(a,b):
  for k in x.keys()|y.keys():
   if x.get(k)!=y.get(k):diffs.append((x['name'],k));assert k=='diagnostics'
 assert diffs==[('first-neutral-call-only-40','diagnostics')]
 assert next(r for r in a if r['name']=='first-neutral-call-only-40')['diagnostics']['workUnitsConsumed']==3089
 assert next(r for r in b if r['name']=='first-neutral-call-only-40')['diagnostics']['workUnitsConsumed']==3361
 print(json.dumps(dict(verifiedArtifacts=len(manifest['files']),variantFilesPerCapture=83,cases=46,exactRepeatedPublicOutputs=46,completeOutputExactCases=45,preservedDiagnosticDifferences=diffs,performanceMeasurements=0)))
if __name__=='__main__':main()
