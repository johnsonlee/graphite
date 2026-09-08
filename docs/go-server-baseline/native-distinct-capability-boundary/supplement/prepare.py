"""Existing invalid sidecar and ordinary projection public controls."""
from pathlib import Path
import json
HERE=Path(__file__).resolve().parent
variants={'empty':dict(call=False,locals=[]),'generic-only':dict(call=False,locals=['hit-A','hit-B']),'invalid-index-empty':dict(call=False,locals=[],invalidIndex=True),'invalid-index-generic':dict(call=False,locals=['hit-A','hit-B'],invalidIndex=True)}
predicate=' OR '.join("toLower(coalesce(n.%s, '')) CONTAINS $term"%p for p in ['class','name','caller_class','caller_name','callee_class','callee_name'])
cases=[]
for distinct,variants_to_test in [(True,['invalid-index-empty','invalid-index-generic']),(False,['empty','generic-only'])]:
 for variant in variants_to_test:
  for count in [1,40]:
   fixtures=[variant]+['empty']*(count-1)
   cases.append(dict(name=('distinct-' if distinct else 'ordinary-')+variant+'-'+str(count),query='MATCH (n) WHERE '+predicate+' RETURN '+('DISTINCT ' if distinct else '')+'n.name LIMIT 1',parameters={'term':'hit'},fixtures=fixtures,scoped=False,sources=[dict(fixture=v,id='g%02d'%i) for i,v in enumerate(fixtures)]))
for name,value in [('cases.json',cases),('fixture-specs.json',variants)]:
 (HERE/name).write_text(json.dumps(value,indent=2)+'\n')
