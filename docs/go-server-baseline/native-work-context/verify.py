"""Verify exact archived inputs, fixtures, operation records, and repeat boundaries."""
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
   assert len(seen)==80 and seen==expected.keys()
  before={e['file']:e for e in json.loads(gzip.decompress((d/'fixture-before.json.gz').read_bytes()))}
  after={e['file']:e for e in json.loads(gzip.decompress((d/'fixture-after.json.gz').read_bytes()))}
  assert before==after
 assert (HERE/'main.json').read_bytes()==(HERE/'main-capture/main.json').read_bytes()
 assert (HERE/'fixtures.tar.gz').read_bytes()==(HERE/'main-capture/fixtures.tar.gz').read_bytes()
 a=json.loads((HERE/'main.json').read_text())['cases'];b=json.loads((HERE/'repeat-capture/main.json').read_text())['cases'];specs=json.loads((HERE/'cases.json').read_text())
 assert len(a)==len(b)==len(specs)==51
 assert all(r['spec']==s for r,s in zip(a,specs))
 assert sum(len(r['operations']) for r in a)==129
 for i,(x,y) in enumerate(zip(a,b)):
  if i!=22:assert x==y,x['name']
  else:
   assert x['name']==y['name']=='concurrent-tracker-exact-total'
   for r in [x,y]:
    value=r['operations'][0]['value'];assert value['successfulConsumes']==100 and value['budgetExceededWorkers']==4
    assert sum(w['successfulConsumes'] for w in value['workers'])==100
    for w in value['workers']:assert w['error']=='CypherBudgetExceededException' and w['maxWorkUnits']=='100'
   # Preserve every other field, including all worker errors/stacks and states.
   for j in range(4):x['operations'][0]['value']['workers'][j]['successfulConsumes']=y['operations'][0]['value']['workers'][j]['successfulConsumes']
   assert x==y
 for r in a:
  assert r['construction']['outcome']==('FAILED' if r['name'] in ['construct-0','construct--1'] else 'SUCCESS')
  for operation in r['operations']:
   assert operation['spec'] in r['spec']['operations']
   if operation['outcome']=='FAILED':assert 'value' not in operation
 print(json.dumps(dict(verifiedArtifacts=len(manifest['files']),variantFilesPerCapture=80,scenarios=51,orderedOperations=129,completeOutputExactCases=50,concurrentAggregateExact=True,performanceMeasurements=0)))
if __name__=='__main__':main()
