from pathlib import Path
import hashlib,json,tarfile
P=Path(__file__).resolve().parent;sha=lambda b:hashlib.sha256(b).hexdigest()
a=json.loads((P/'archive-verification.json').read_text())
for r in a['artifacts']:
 b=(P/r['file']).read_bytes();assert(len(b),sha(b))==(r['bytes'],r['sha256']);assert Path(r['source']).read_bytes()==b
main=json.loads((P/'main.json').read_text());assert main==json.loads((P/'main-capture/main.json').read_text())==json.loads((P/'repeat-capture/main.json').read_text())
assert len(main['cases'])==11 and sum(len(c['operations'])for c in main['cases'])==19
expected={r['file']:r for r in json.loads((P/'main-capture/fixture-before.json').read_text())}
with tarfile.open(P/'fixtures.tar.gz')as t:
 members=[m for m in t if m.isfile()];assert len(members)==len(expected)==34
 for m in members:b=t.extractfile(m).read();assert(len(b),sha(b))==(expected[m.name]['bytes'],expected[m.name]['sha256'])
for label in ['initial-capture','main-capture','repeat-capture']:
 assert json.loads((P/label/'fixture-before.json').read_text())==json.loads((P/label/'fixture-after.json').read_text())
 assert json.loads((P/label/'receipt.json').read_text())['exitCode']==0
inputs=json.loads((P/'main-capture/inputs.json').read_text())
with tarfile.open(P/'input-sources.tar.gz')as t:
 for m in t.getmembers():assert sha(t.extractfile(m).read())==inputs['/'+m.name]
print(json.dumps({'cases':11,'operations':19,'fixtureFiles':34,'allParsedFieldsRepeatEqual':True,'archiveExternalBytesVerified':len(a['artifacts']),'performanceMeasurements':0}))
