import json,pathlib,hashlib,collections,re
r=pathlib.Path(__file__).resolve().parent
src=pathlib.Path('/tmp/graphite-go-binding-map-attempt15-9ada2bf1/graphite-server/internal/query/testdata/indexed-distinct')
pairs={'primary':'audit-primary/main.json','supplementary':'audit-supplementary/main.json','source-pairs':'audit-source-pairs/main.json','late':'late-main.json'}
for v in ['clean','bad-callee','bad-count','bad-tag','missing-index']:pairs['split-'+v]='split-'+v+'-main.json'
for v in ['','-missing-index','-missing-identity']:pairs['required'+v]='required-main'+v+'.json'
for v in ['annotation','annotation-after','mixed']:pairs[v]=v+'-wire-main.json'
def equal(a,b):
 if type(a)!=type(b): return False
 if isinstance(a,dict): return a.keys()==b.keys() and all(equal(a[k],b[k]) for k in a)
 if isinstance(a,list): return len(a)==len(b) and all(equal(x,y) for x,y in zip(a,b))
 return a==b
records=[]
for suite,path in pairs.items():
 main=json.loads((src/path).read_text());base=json.loads((r/'base-complete-observations'/(suite+'-native.json')).read_text());candidate=json.loads((r/'candidate-observations'/(suite+'-native.json')).read_text())
 assert len(main)==len(base)==len(candidate)
 for i,(a,b,c) in enumerate(zip(main,base,candidate)):
  # These records include case identity; compare the same complete wire response envelope as existing audit.
  keys=['columns','rows','error','message']
  response=lambda x:{k:x[k] for k in keys if k in x}
  a,b,c=map(response,(a,b,c))
  assert equal(b,c),(suite,i)
  kind='equal' if equal(a,c) else 'main-success-native-error' if 'error' not in a and 'error' in c else 'both-error-difference' if 'error' in a and 'error' in c else 'both-success-difference'
  records.append({'suite':suite,'index':i,'comparison':kind,'main':a,'base':b,'candidate':c})
report={'total':len(records),'comparison':dict(collections.Counter(x['comparison'] for x in records)),'baseCandidateEqual':len(records),'records':records}
(r/'original1048-comparison.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps({k:v for k,v in report.items() if k!='records'},indent=2))
# Capture complete relative path identity against root integration (66 files).
root=pathlib.Path('/Users/johnsonlee/.codex/worktrees/112399f5-4ea0-42da-af34-5ab6ef682c3d/graphite/docs/go-server-baseline/native64-main-source-integration/integration')
inventory=[]
for old,new in [('indexed-native','base-complete-observations'),('ordinary-native','base-history-observations')]:
 expected={str(p.relative_to(root/old)) for p in (root/old).rglob('*.json')};actual={str(p.relative_to(r/new)) for p in (r/new).rglob('*.json')}
 assert expected==actual,(old,expected-actual,actual-expected)
 inventory.append({'root':str(root/old),'local':new,'count':len(actual),'paths':sorted(actual)})
(r/'root-path-inventory.json').write_text(json.dumps(inventory,indent=2)+'\n')
# Do not call changed raw artifacts equal: classify every differing leaf explicitly.
comparison=json.loads((r/'comparison.json').read_text());differences=[]
for item in comparison['records']:
 for d in item['differences']:
  assert re.fullmatch(r'\$\[\d+\]\.targets\[\d+\]\.(before|after)\[\d+\]\.mappedView',d['path']),d
  case=json.loads((r/item['base']).read_text())[int(d['path'].split(']')[0][2:])]
  assert case.get('count',case.get('spec',{}).get('sources'))==40
  differences.append({'artifact':item['base'],**d})
(r/'state-differences.json').write_text(json.dumps({'rawArtifacts':66,'rawExactArtifacts':comparison['equal'],'fullResponseChanges':0,'changedLeaves':len(differences),'scope':'40-source speculative mappedView state only; full raw artifacts retained, not normalized or called exact','differences':differences},indent=2)+'\n')
