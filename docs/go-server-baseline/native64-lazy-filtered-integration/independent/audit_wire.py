import pathlib,json,hashlib
source=pathlib.Path('/tmp/graphite-go-lazy-cde-87aaf0ad/graphite-server/internal/query/testdata/lazy-filtered')
root=pathlib.Path(__file__).parent
names=['clean-MAPPED','bad-first-unmatched-MAPPED','bad-last-unmatched-MAPPED','bad-matched-MAPPED','clean-EAGER']
changes=[];identities={};records=0
def walk(a,b,path):
 if isinstance(a,dict) and isinstance(b,dict):
  assert a.keys()==b.keys(),path
  for k in a:walk(a[k],b[k],path+[k])
 elif isinstance(a,list) and isinstance(b,list):
  assert len(a)==len(b),path
  for i,(x,y) in enumerate(zip(a,b)):walk(x,y,path+[i])
 elif a!=b:
  assert isinstance(a,str) and isinstance(b,str),(path,a,b)
  canonical=a.encode('utf-16','surrogatepass').decode('utf-16','surrogatepass')
  assert canonical.encode('utf-8','replace').decode('utf-8')==b,(path,repr(a),repr(b))
  assert path[2]=='rows',path
  changes.append({'path':path,'semanticCodePoints':[ord(c) for c in a],'wireCodePoints':[ord(c) for c in b]})
for name in names:
 semantic=source/(name+'-main.json');wire=source/(name+'-wire.json')
 a=json.loads(semantic.read_text());b=json.loads(wire.read_text());assert len(a)==len(b)==96
 records+=len(a);walk(a,b,[name])
 for p in [semantic,wire]:identities[p.name]=hashlib.sha256(p.read_bytes()).hexdigest()
assert records==480 and len(changes)==12
http=pathlib.Path('/tmp/graphite-go-lazy-cde-evidence/string-boundary')
r=json.loads((http/'http-receipt.json').read_text())
for i,record in enumerate(r['records']):
 body=(http/f'http-{i}.body').read_bytes()
 assert hashlib.sha256(body).hexdigest()==record['bodySHA256'] and record['status']==200
 assert b'\xef\xbf\xbd' not in body and b'\xed\xa0\x80' not in body
 assert 'example.İD?Caller' in body.decode()
(root/'wire-audit.json').write_text(json.dumps({'fullDenominator':records,'changedLeafCount':len(changes),'inputOrErrorOrColumnChanges':0,'changes':changes,'identities':identities,'actualHTTPReceipt':r},indent=2)+'\n')
print('480 complete records,12 result-only surrogate changes,2 actual HTTP bodies verified')
