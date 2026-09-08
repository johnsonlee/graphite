"""Public capability boundaries; bounded synthetic correctness, never timing."""
from pathlib import Path
import json
HERE=Path(__file__).resolve().parent
variants={
 'empty':dict(call=False,locals=[]),
 'generic-only':dict(call=False,locals=['hit-A','hit-B']),
 'neutral-call-generic':dict(call=True,matchingCall=False,locals=['hit-A','hit-B']),
 'hit-call-generic':dict(call=True,matchingCall=True,locals=['hit-A','hit-B']),
 'neutral-call-only':dict(call=True,matchingCall=False,locals=[]),
}
predicate=' OR '.join("toLower(coalesce(n.%s, '')) CONTAINS $term"%p for p in ['class','name','caller_class','caller_name','callee_class','callee_name'])
cases=[]
def add(name,count,first='generic-only',probe=None,limit=1,skip=None,label='',projection='n.name',term='hit'):
 fixtures=[first]+(['neutral-call-generic']*(count-1))
 if probe is not None:fixtures[1]=probe
 q='MATCH (n'+(':'+label if label else '')+') WHERE '+predicate+' RETURN DISTINCT '+projection
 if skip is not None:q+=' SKIP '+str(skip)
 q+=' LIMIT '+str(limit)
 cases.append(dict(name=name,query=q,parameters=dict(term=term),fixtures=fixtures,scoped=False,sources=[dict(fixture=v,id='g%02d'%i) for i,v in enumerate(fixtures)],boundary=dict(sourceCount=count,first=first,probe=probe,limit=limit,skip=skip,label=label,projection=projection)))
for count in [1,2,39,40]:
 for first in ['empty','generic-only','neutral-call-generic','neutral-call-only']:
  add('first-'+first+'-'+str(count),count,first)
for count in [2,39,40]:
 for probe in ['empty','generic-only']:
  add('later-'+probe+'-'+str(count),count,'hit-call-generic',probe)
for count in [1,40]:
 for limit in [0,2]:add('generic-limit-'+str(limit)+'-'+str(count),count,limit=limit)
for count in [1,2,39,40]:
 for first in ['empty','generic-only']:
  add('callsite-label-'+first+'-'+str(count),count,first,label='CallSiteNode')
for count in [1,2,39,40]:
 for skip in [0,1]:add('generic-skip-'+str(skip)+'-'+str(count),count,skip=skip)
add('empty-skip-0-40',40,'empty',skip=0)
add('generic-local-label-1',1,label='LocalVariable')
add('generic-unsupported-projection-1',1,projection='n.id')
add('generic-global-miss-40',40,term='missing-entire-string-table')
for name,value in [('cases.json',cases),('fixture-specs.json',variants)]:
 (HERE/name).write_text(json.dumps(value,indent=2)+'\n')
print(len(cases),'cases',len(variants),'variants')
