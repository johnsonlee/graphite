"""Independently frame reconstructed values/provenance using the existing JMH digest format."""
import csv,hashlib,json,pathlib
p=pathlib.Path(__file__).resolve().parent
rows=json.loads((p/'selected-tuples.json').read_text())
columns=['n.caller_class','n.caller_name','n.callee_class','n.callee_name']
def frame(tag,value):return f'{tag}:{len(value.encode("utf-8"))}:{value}'
def sequence(tag,values):return frame(tag,str(len(values))+''.join(frame('item',v) for v in values))
def canonical(v):
 if v is None:return 'null'
 if isinstance(v,str):return frame('string',v)
 if isinstance(v,list):return sequence('iterable',[canonical(x) for x in v])
 if isinstance(v,dict):return sequence('map',[item for pair in sorted((canonical(k),canonical(v)) for k,v in v.items()) for item in pair])
 raise ValueError(type(v))
rendered=[]
for row in rows:
 value=dict(zip(columns,row['values']));value['$metadata']={'graphIds':sorted(row['graphIds'])}
 rendered.append(sequence('row',[item for k,v in sorted(value.items()) for item in [canonical(k),canonical(v)]]))
encoded=(sequence('columns',[canonical(c) for c in columns])+sequence('rows',rendered)).encode()
actual=hashlib.sha256(encoded).hexdigest()
expected=[]
for i in [3,4,5]:
 with (p.parent/f'cpu-{i}.tsv').open() as f:
  r=next(x for x in csv.DictReader(f,delimiter='\t') if x['id']=='global-wide-wrapped-case-insensitive-distinct-dense')
 expected.append({'capture':i,'digest':r['digest'],'responseBytes':int(r['responseBytes'])})
assert all(r['digest']==actual and r['responseBytes']==len(encoded) for r in expected),(actual,len(encoded),expected)
receipt={'passed':True,'reconstructedHarnessDigest':actual,'reconstructedResponseBytes':len(encoded),'matchesExistingCaptures':expected}
(p/'harness-digest-check.json').write_text(json.dumps(receipt,indent=2)+'\n');print(json.dumps(receipt))
