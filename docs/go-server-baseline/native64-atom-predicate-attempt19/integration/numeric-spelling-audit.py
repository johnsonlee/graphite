from pathlib import Path
import json
r=Path(__file__).resolve().parent
pairs=[]
for f in ['bad-first-unmatched-MAPPED','bad-last-unmatched-MAPPED','bad-matched-MAPPED','clean-MAPPED','clean-EAGER']:pairs.append(('oracle/'+f+'-wire.json','native-exact/streaming-'+f+'-native.json'))
for f in ['clean','bad-matched']:pairs.append(('oracle/'+f+'-unknown-wire.json','native-exact/streaming-unknown-'+f+'-native.json'))
pairs.append(('oracle/sources-wire.json','native-exact/streaming-sources-native.json'))
records=[]
def walk(a,b,path):
 if type(a)!=type(b):
  assert type(a) in(int,float) and type(b) in(int,float) and a==b,(path,a,b)
  return [{'path':path,'main':repr(a),'native':repr(b),'sameJSONNumberValue':True}]
 if isinstance(a,dict):return [x for k in a for x in walk(a[k],b[k],path+'.'+k)]
 if isinstance(a,list):return [x for i,(u,v) in enumerate(zip(a,b)) for x in walk(u,v,f'{path}[{i}]')]
 assert a==b,(path,a,b)
 return []
for a,b in pairs:
 diffs=walk(json.loads((Path('/private/tmp/graphite-atom-attempt19-independent-review/combined/candidate/graphite-server/internal/query/testdata/streaming-pagination')/a.removeprefix('oracle/')).read_text()),json.loads((r/'output/original-corpus'/b.removeprefix('native-exact/')).read_text()),'$')
 records.append({'main':a,'native':b,'lexicalNumberTypeDifferences':diffs})
(r/'numeric-spelling-differences.json').write_text(json.dumps(records,indent=2)+'\n')
print(sum(len(x['lexicalNumberTypeDifferences']) for x in records),'preserved numeric spelling differences')
