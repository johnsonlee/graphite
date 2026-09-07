import collections,hashlib,json,pathlib
root=pathlib.Path(__file__).resolve().parent.parent
review=root/'main-candidate-review';baseline=review/'baseline';final=review/'final-v4'
read=lambda p:json.loads(p.read_text())
summary=read(final/'original-summary.json');before=read(baseline/'original-summary.json')
assert summary['total']==before['total']==1048
changes=[]
for index,(a,b) in enumerate(zip(before['pairs'],summary['pairs'])):
 if a['comparison']!=b['comparison']: changes.append(dict(index=index,before=a,after=b))
assert len(changes)==26 and all(x['after']['comparison']=='equal' for x in changes)
ledger=read(pathlib.Path('/tmp/graphite-ordinary-remaining82-audit/cases.json'))
a28=[]
for row in ledger:
 if row['group'].startswith('A-'):
  name=row['suite']+'-native.json';p=final/'original-corpus'/name
  actual=read(p)[row['index']];expected=row['main']
  old=read(baseline/'original-corpus'/name)[row['index']]
  assert actual==expected,(row['id'],row['suite'])
  a28.append(dict(id=row['id'],suite=row['suite'],index=row['index'],query=expected['query'],fixture=expected.get('fixture'),cross=expected.get('cross'),baselineEqual=old==expected,finalEqual=True))
assert len(a28)==28

def diffs(a,b,p=''):
 if type(a)!=type(b): return [dict(path=p,before=a,after=b)]
 if isinstance(a,dict): return [d for k in sorted(a.keys()|b.keys()) for d in diffs(a.get(k),b.get(k),p+'/'+k)]
 if isinstance(a,list):
  if len(a)!=len(b):return [dict(path=p,beforeLength=len(a),afterLength=len(b))]
  return [d for i,(x,y) in enumerate(zip(a,b)) for d in diffs(x,y,p+'/'+str(i))]
 return [] if a==b else [dict(path=p,before=a,after=b)]
history=[]
for p in sorted((baseline/'history').rglob('*.json')):
 relative=p.relative_to(baseline/'history');q=final/'history'/relative;assert q.exists()
 ds=diffs(read(p),read(q))
 if ds: assert str(relative)=='rolling-native.json' and all(d['path'].endswith('/mappedView') for d in ds)
 history.append(dict(file=str(relative),completeEqual=not ds,differences=ds))
new=[]
keys={'query','columns','rows','error','message'}
for file in ['main.json','property-main.json','offset-main.json']:
 expected=read(root/'graphite-server/internal/query/testdata/main-string-source'/file)
 actual=read(final/'original-corpus'/('main-string-source-'+file+'-native.json'))
 repeated=read(review/'reproduced-main'/file)
 assert len(expected)==len(actual)==len(repeated)
 for a,b,c in zip(expected,actual,repeated):
  for key in ['warm','targets']:
   if key not in a: continue
   aa=a[key] if key=='targets' else [a[key]];bb=b[key] if key=='targets' else [b[key]];cc=c[key] if key=='targets' else [c[key]]
   for x,y,z in zip(aa,bb,cc):
    observed=lambda v:{k:v[k]for k in v if k in keys}
    assert observed(x)==observed(y)==observed(z),(file,a['spec']['name'])
  new.append(dict(file=file,name=a['spec']['name'],responses=len(a['targets'])+('warm'in a),nativeCompleteStateEqual=a==b,mainRepeatCompleteStateEqual=a==c,nativeStateDifferences=diffs(a,b),mainRepeatDifferences=diffs(a,c)))
assert len(new)==58 and sum(x['responses']for x in new)==122
report=dict(total=1048,baseline=before['comparison'],final=summary['comparison'],newlyMainEqual=len(changes),remaining=summary['remaining'],changes=changes,A28=a28,history=history,newOracle=new,notes=['All response comparisons retain complete rows/columns/error/message.','The baseline already fixed two of original group A; this source fixes the other 26.','Mapped-view publication after speculative task cancellation is not deterministic; raw state differences are preserved.'])
(review/'results.json').write_text(json.dumps(report,indent=2)+'\n')
print({k:report[k]for k in ['total','baseline','final','newlyMainEqual']});print('A28',len(a28),'history',len(history),'new responses',sum(x['responses']for x in new))
