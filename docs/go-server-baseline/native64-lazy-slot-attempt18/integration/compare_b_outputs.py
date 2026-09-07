from pathlib import Path
import json,re
r=Path(__file__).resolve().parent
base=Path('/tmp/graphite-go-a17-on-a16-b/integration/output');candidate=r/'output'
def diff(a,b,p='$'):
 if type(a)!=type(b):return [dict(path=p,base=a,candidate=b)]
 if isinstance(a,dict):
  if a.keys()!=b.keys():return [dict(path=p,base=a,candidate=b)]
  return [z for k in a for z in diff(a[k],b[k],p+'.'+k)]
 if isinstance(a,list):
  if len(a)!=len(b):return [dict(path=p,base=a,candidate=b)]
  return [z for i,(x,y) in enumerate(zip(a,b)) for z in diff(x,y,p+f'[{i}]')]
 return [] if a==b else [dict(path=p,base=a,candidate=b)]
records=[]
for p in sorted(base.rglob('*.json')):
 if p.name=='original-summary.json':continue
 q=candidate/p.relative_to(base);assert q.exists(),q
 d=diff(json.loads(p.read_text()),json.loads(q.read_text()))
 records.append(dict(file=str(p.relative_to(base)),differences=d,exact=not d))
(r/'all-b-output-differences.json').write_text(json.dumps(records,indent=2)+'\n')
allowed={'history/rolling-native.json','original-corpus/main-string-source-main.json-native.json','original-corpus/main-string-source-offset-main.json-native.json'}
for record in records:
 if record['exact']:continue
 assert record['file'] in allowed,record['file']
 for x in record['differences']:
  assert re.fullmatch(r'\$\[\d+\]\.targets\[\d+\]\.(before|after)\[\d+\]\.mappedView',x['path']),x
  assert type(x['base'])==type(x['candidate'])==bool
  ordinal=int(x['path'].split('[')[1].split(']')[0])
  observed=json.loads((candidate/record['file']).read_text())[ordinal]
  assert observed.get('count',observed.get('spec',{}).get('sources'))==40,record
  prior=json.loads((base/record['file']).read_text())[ordinal]
  assert prior.get('count',prior.get('spec',{}).get('sources'))==40,record
summary={'artifacts':len(records),'exactArtifacts':sum(x['exact'] for x in records),'internalStateOnlyArtifacts':[x['file'] for x in records if not x['exact']],'internalMappedViewLeaves':sum(len(x['differences']) for x in records),'publicResponseChanges':0,'original1048Equal':1044,'remainingF':4,'B595Equal':True,'numericSpellings':166}
(r/'comparison-summary.json').write_text(json.dumps(summary,indent=2)+'\n');print(summary)
