#!/usr/bin/env python3
"""Compare complete persisted C4 models and text outputs; no timing metrics."""
import pathlib,json,hashlib,sys
root=pathlib.Path(sys.argv[1]); rows=[];stats=[]
for mode in ['MAPPED','EAGER']:
 for level in ['all','context','container','component']:
  for fmt in ['json','mermaid','plantuml','dsl']:
   a=(root/'main'/mode/(level+'.'+fmt)).read_bytes();b=(root/'native'/mode/(level+'.'+fmt)).read_bytes()
   rows.append({'mode':mode,'level':level,'format':fmt,'equal':json.loads(a)==json.loads(b) if fmt=='json' else a==b,'main_sha256':hashlib.sha256(a).hexdigest(),'native_sha256':hashlib.sha256(b).hexdigest()})
 a=json.loads((root/'main'/mode/'stats.json').read_text());b=json.loads((root/'native'/mode/'stats.json').read_text())
 checks={k:a[k]==b[k] for k in ['nodes','edges','methods','callSites','classOrigins']}
 checks['resources']=a['resources']==[{'path':v['Path'],'source':v['Source']} for v in b['resources']]
 checks['manifest']=a['manifest']=={k[0].lower()+k[1:]:v for k,v in b['manifest'].items() if v is not None}
 stats.append({'mode':mode,'equal':all(checks.values()),'checks':checks,'nodes':a['nodes'],'edges':a['edges'],'methods':a['methods'],'callSites':a['callSites'],'classOriginCount':len(a['classOrigins']),'resourceCount':len(a['resources'])})
result={'equal':all(r['equal'] for r in rows+stats),'passed_outputs':sum(r['equal'] for r in rows),'total_outputs':len(rows),'comparisons':rows,'catalog':stats}
(root/'comparison.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps({k:v for k,v in result.items() if k!='comparisons'},indent=2));sys.exit(0 if result['equal'] else 1)
