"""Independent original-output validation; reads receipts, catalog, JSONL and TSV only."""
from pathlib import Path
import base64, csv, hashlib, json
P=Path(__file__).resolve().parent
C=P/'v3-control'
def sha(p):
    h=hashlib.sha256()
    with Path(p).open('rb') as f:
        for b in iter(lambda:f.read(1024*1024),b''):h.update(b)
    return h.hexdigest()
def read(n):return json.loads((C/n).read_text())
def tsv(n):
    with (C/n).open() as f:
        r=csv.DictReader(f,delimiter='\t');assert len(r.fieldnames)==len(set(r.fieldnames));rows=list(r)
    assert all(None not in x and None not in x.values() for x in rows)
    return rows
run=read('run.json');cat=read('catalog.json');work=tsv('workloads.tsv');obs=tsv('fork-001.tsv')
actual=[json.loads(s) for s in (C/'fork-001-rows.jsonl').read_text().splitlines()]
assert run['status']=='complete' and run['queryCount']==36 and run['completedVerifiedForks']==run['requestedForks']==1
assert cat['schema']==run['catalogSchema']=='graphite-wide-query-oracle-v3'
assert len(cat['logicalCases'])==18 and len(cat['queries'])==36
assert run['performanceGate'] is False and run['resetMode']=='per-query-cold'
inputs={}
for name,entry in run['inputs'].items():
    current=sha(entry['path']);assert current==entry['sha256'];inputs[name]={'path':entry['path'],'sha256':current}
assert sha(C/'catalog.json')==inputs['catalog']['sha256']
assert sha(C/'workloads.tsv')==inputs['workloads']['sha256']
assert inputs['runtimeJar']['sha256']==json.loads((P/'build-receipt.json').read_text())['jmhJarSha256']
assert inputs['trustedJar']['sha256']=='a5c2db2b0020798488916ec86902459d1044a7dcef606a73e00055883cdf5abe'
assert cat['jarSha256']==inputs['trustedJar']['sha256'] and cat['manifestSha256']==inputs['manifest']['sha256']
ids=[q['id'] for q in cat['queries']]
assert len(set(ids))==36
for rows in (work,obs,actual,run['queries']):assert [r['id'] for r in rows]==ids
columns=['n.caller_class','n.caller_name','n.callee_class','n.callee_name']
receipts=[];total=0
for q,w,o,a,summary in zip(cat['queries'],work,obs,actual,run['queries']):
    text=base64.b64decode(w['queryBase64'],validate=True).decode()
    assert text==q['query'] and hashlib.sha256(text.encode()).hexdigest()==o['workloadIdentity']
    assert w['distinct']==str(q['distinct']).lower()
    assert (w['expectedHitGraphIds'].split(',') if w['expectedHitGraphIds'] else [])==q['expectedHitGraphIds']
    assert int(w['totalMatches'])==q['totalMatches']
    assert a['columns']==columns and set(a)=={'id','columns','rows'}
    assert a['rows']==q['expectedRows']
    assert len(a['rows'])==int(o['rowCount'])
    digest=json.dumps(a['rows'],ensure_ascii=False,separators=(',',':')).replace('\\b','\\u0008').replace('\\f','\\u000c')
    assert hashlib.sha256(digest.encode()).hexdigest()==o['digest']
    returned=sorted({g for row in a['rows'] for g in row['graphIds']})
    assert o['hitGraphIds']==','.join(returned)
    assert o['outcome']=='success' and int(o['latencyNanos'])>0 and o['inputSourceCount']=='64' and o['resetMode']=='per-query-cold'
    assert o['family']=='multi-keyword' and o['shape']==q['id']
    assert o['projection']==('distinct-properties' if q['distinct'] else 'properties')
    assert summary['latencyNanosInForkOrder']==[int(o['latencyNanos'])] and summary['empiricalP95LatencyNanos'] is None
    logical=next(l for l in cat['logicalCases'] if l['id']==q['logicalId'])
    full=[cat['inputGraphs'][i] for i,n in enumerate(logical['perGraphMatchingCounts']) if n]
    assert full==q['expectedHitGraphIds'] and sum(logical['perGraphMatchingCounts'])==q['totalMatches']
    total+=len(a['rows'])
    receipts.append({'id':q['id'],'rows':len(a['rows']),'digest':o['digest'],'fullHitGraphCount':len(full),'returnedGraphCount':len(returned),'work':int(o['graphWorkUnits'])})
assert total==6171
before=read('graph-content-before.json');after=read('graph-content-after.json')
assert before==after and sha(C/'graph-content-before.json')==run['graphContentSha256']
assert [g['id'] for g in before]==cat['inputGraphs'] and len(before)==64
for name,h in run['compiledClasses'].items():assert sha(C/'classes'/name)==h
cmd=read('fork-001-command.json')
assert cmd[cmd.index('-cp')+1]==str(C/'classes')+':'+inputs['runtimeJar']['path']
assert '-XX:ActiveProcessorCount=4' in cmd and cmd[-2:]==['all','per-query-cold']
assert cmd[cmd.index('MultiKeywordProfileRunner')+1:cmd.index('MultiKeywordProfileRunner')+3]==[inputs['manifest']['path'],inputs['workloads']['path']]
assert read('fork-001-reference-check.json')['passed']
out={'passed':True,'queries':36,'fullRowsVerified':total,'valuesOrderProvenanceDigestAllExact':True,'perQuery':receipts,'inputsVerifiedNow':inputs,'graphReceiptsEqual':True,'graphFileRecords':sum(len(g['files']) for g in before),'graphContentReceiptSha256':run['graphContentSha256'],'hashes':{n:sha(C/n) for n in ('run.json','catalog.json','workloads.tsv','fork-001.tsv','fork-001-rows.jsonl','fork-001-command.json','fork-001-reference-check.json','graph-content-before.json','graph-content-after.json')},'scriptSha256':sha(__file__),'limitations':['One correctness control is not a performance acceptance result or per-query P95.', 'Graph before/after file hashes were independently compared as recorded receipts; live graph content was not reread during the parent timed run.', 'Catalog rows were treated as the hash-pinned independently derived oracle; this audit did not repeat graph export or oracle derivation.']}
(P/'independent-control-audit.json').write_text(json.dumps(out,indent=2)+'\n')
print(json.dumps({'passed':True,'queries':36,'rows':total,'graphFileRecords':out['graphFileRecords']}))
