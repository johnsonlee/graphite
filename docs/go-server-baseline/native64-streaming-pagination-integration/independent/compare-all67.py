import json,pathlib,hashlib
root=pathlib.Path(__file__).resolve().parent

def diff(a,b,p='$'):
 if type(a)!=type(b): return [{'path':p,'base':a,'candidate':b,'reason':'type'}]
 if isinstance(a,dict):
  if a.keys()!=b.keys(): return [{'path':p,'baseKeys':sorted(a),'candidateKeys':sorted(b),'reason':'keys'}]
  return [d for k in a for d in diff(a[k],b[k],p+'.'+k)]
 if isinstance(a,list):
  if len(a)!=len(b): return [{'path':p,'baseLength':len(a),'candidateLength':len(b),'reason':'length'}]
  return [d for i,(x,y) in enumerate(zip(a,b)) for d in diff(x,y,f'{p}[{i}]')]
 return [] if a==b else [{'path':p,'base':a,'candidate':b,'reason':'value'}]
records=[]
for base,candidate in [('captured-output/baseline-output/original-corpus','captured-output/final-output/original-corpus'),('captured-output/baseline-output/history','captured-output/final-output/history')]:
 a,b=root/base,root/candidate
 assert {p.relative_to(a) for p in a.rglob('*.json')} <= {p.relative_to(b) for p in b.rglob('*.json')}
 for x in sorted(a.rglob('*.json')):
  y=b/x.relative_to(a); ax=json.loads(x.read_text()); by=json.loads(y.read_text()); d=diff(ax,by)
  records.append({'base':str(x.relative_to(root)),'candidate':str(y.relative_to(root)),'entries':len(ax),'baseSHA256':hashlib.sha256(x.read_bytes()).hexdigest(),'candidateSHA256':hashlib.sha256(y.read_bytes()).hexdigest(),'byteEqual':x.read_bytes()==y.read_bytes(),'typeSensitiveEqual':not d,'differences':d})
report={'normalization':'none; object member order is not compared, all array order/types/nulls/errors/metadata/state values are compared','artifacts':len(records),'equal':sum(r['typeSensitiveEqual'] for r in records),'records':records}
(root/'comparison.json').write_text(json.dumps(report,ensure_ascii=True,indent=2)+'\n')
print(json.dumps({k:v for k,v in report.items() if k!='records'},indent=2))
for r in records:
 if r['differences']: print(r['base'],len(r['differences']),r['differences'][:3])
raise SystemExit(0 if report['equal']==report['artifacts'] else 1)
