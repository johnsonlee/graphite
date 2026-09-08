"""Declare bounded public-executor prefix scenarios. Synthetic correctness only."""
from pathlib import Path
import json
HERE=Path(__file__).resolve().parent
variants={'empty':dict(call=False,locals=[])}
cases=[]
predicate=' OR '.join("toLower(coalesce(n.%s, '')) CONTAINS $term"%p for p in ['class','name','caller_class','caller_name','callee_class','callee_name'])
def add(name,first_call,first_names,probe_call,probe_names,bad=None,limit=2,property='name'):
 variants[name+'-first']=dict(call=True,matchingCall=first_call,locals=first_names)
 variants[name+'-probe']=dict(call=probe_call,locals=probe_names,badIndex=bad)
 fixtures=[name+'-first',name+'-probe']+['empty']*38
 cases.append(dict(name=name,query='MATCH (n) WHERE '+predicate+' RETURN DISTINCT n.'+property+' LIMIT '+str(limit),parameters={'term':'hit'},fixtures=fixtures,scoped=False,sources=[dict(fixture=v,id='g%02d'%i) for i,v in enumerate(fixtures)]))
add('raw-complete-first-nonselected',True,[],True,['hit-C','hit-bad'],1,1)
add('raw-complete-first-duplicate',True,[],True,['hit-C','hit-bad'],1,1,'class')
add('raw-complete-first-bad',True,[],True,['hit-bad'],0,1)
add('raw-complete-no-generic',True,[],True,[],None,1)
add('raw-plus-generic-completes',True,['hit-A'],True,['hit-A','hit-bad'],1)
add('raw-plus-nonselected-then-selected',True,['hit-A'],True,['hit-C','hit-A','hit-bad'],2)
add('raw-plus-nonselected-then-bad',True,['hit-A'],True,['hit-C','hit-bad'],1)
add('no-raw-hit-generic-completes',False,['hit-A'],True,['hit-A','hit-bad'],1,1)
add('no-callsite-generic-completes',False,['hit-A'],False,['hit-A','hit-bad'],1,1)
add('no-raw-hit-duplicate-then-completes',False,['hit-A','hit-B'],True,['hit-A','hit-A','hit-B','hit-bad'],3)
add('no-raw-hit-duplicate-then-bad',False,['hit-A','hit-B'],True,['hit-A','hit-A','hit-bad'],2)
add('no-raw-hit-nonselected-then-completes',False,['hit-A','hit-B'],True,['hit-C','hit-A','hit-B','hit-bad'],3)
add('incomplete-exhaustion',False,['hit-A','hit-B'],True,['hit-A'],None)
for name,value in [('cases.json',cases),('fixture-specs.json',variants)]:
 (HERE/name).write_text(json.dumps(value,indent=2)+'\n')
