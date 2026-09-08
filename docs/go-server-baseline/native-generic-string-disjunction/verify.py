"""Independent evidence checks; preserve observed JVM row-order variation."""
from pathlib import Path
import collections,gzip,hashlib,io,json,tarfile
HERE=Path(__file__).resolve().parent
def j(p):return json.loads(p.read_text())
def key(o):return json.dumps(o,ensure_ascii=True,sort_keys=True)
def sha(b):return hashlib.sha256(b).hexdigest()
a=j(HERE/'main.json');b=json.loads(gzip.decompress((HERE/'repeat-main.json.gz').read_bytes()));specs=j(HERE/'cases.json');assert len(a['cases'])==len(b['cases'])==len(specs)==201
assert [c['spec'] for c in a['cases']]==specs==[c['spec'] for c in b['cases']]
assert a['performanceMeasurements']==b['performanceMeasurements']==0
changes=[];exact=0
for x,y in zip(a['cases'],b['cases']):
 if x==y:exact+=1;continue
 fields=[k for k in x.keys()|y.keys() if x.get(k)!=y.get(k)];assert set(fields)=={'rows','rowsUTF16'},(x['name'],fields)
 assert x['name'] in ['six-empty-1','six-empty-2']
 for field in fields:assert collections.Counter(map(key,x[field]))==collections.Counter(map(key,y[field]))
 changes.append(dict(name=x['name'],differingFields=sorted(fields),rowMultisetsEqual=True,rawRowOrderPreserved=True))
receipts=[j(HERE/(t+'-receipt.json')) for t in ['main','repeat']]
for r in receipts:
 assert r['exitCodes']==dict(compile=0,prepare=0,run=0) and r['inputsUnchanged']
 assert not r['fixtureAudit']['changed'] and not r['fixtureAudit']['missing']
 assert all(Path(p).name=='graph.callsite-string-index' for p in r['fixtureAudit']['added'])
assert receipts[0]['inputs']==receipts[1]['inputs']
for p,h in receipts[0]['inputs'].items():
 if str(HERE) in p:assert sha(Path(p).read_bytes())==h,p
variants=j(HERE/'fixture-variants.json');expected={m['file']:m for m in variants['files']}
with tarfile.open(fileobj=io.BytesIO(gzip.decompress((HERE/'fixtures.tar.gz').read_bytes()))) as tar:
 assert set(tar.getnames())==set(expected)
 for name,m in expected.items():data=tar.extractfile(name).read();assert len(data)==m['bytes'] and sha(data)==m['sha256']
actual=j(HERE/'actual-main64.json');workload=HERE.parents[2]/'graphite-server/internal/benchmarkcase/testdata/main64.json';assert sha(workload.read_bytes())==actual['workloadSha256']
w=j(workload)
for item in actual['cases']:assert item['case']==w['cases'][item['index']]
out=dict(cases=len(specs),publicCases=sum('providerType' not in s for s in specs),directProviderCases=sum('providerType' in s for s in specs),outcomes=dict(collections.Counter(c['outcome'] for c in a['cases'])),exactRepeatCases=exact,repeatOrderVariations=changes,fixtureVariants=len(variants['variants']),fixtureFiles=len(expected),originalFilesPerRun=[r['fixtureAudit']['originalFiles'] for r in receipts],allOriginalFilesUnchanged=True,actualMainInputsEqual=True,performanceMeasurements=0)
(HERE/'verification.json').write_text(json.dumps(out,indent=2)+'\n');print(json.dumps(out,indent=2))
