"""Bounded correctness-only matrix derived from exact original six-term cases."""
from pathlib import Path
import json,hashlib
HERE=Path(__file__).resolve().parent; REPO=HERE.parents[2]
workload=REPO/'graphite-server/internal/benchmarkcase/testdata/main64.json'
w=json.loads(workload.read_text()); actual=[dict(index=i,case=w['cases'][i]) for i in (886,887,888)]
props=['class','name','caller_class','caller_name','callee_class','callee_name']
predicate=' OR '.join("toLower(coalesce(n.%s, '')) CONTAINS $term"%p for p in props)
projection=', '.join('n.'+p for p in props)
def query(label='',limit=200,distinct=True,provenance=False):
 return 'MATCH (n'+(':'+label if label else '')+') WHERE '+predicate+' RETURN '+('DISTINCT ' if distinct else '')+('n.graphId, n.id, ' if provenance else '')+projection+' LIMIT '+str(limit)
mutations={'clean':[]}
def mut(name,*changes):mutations[name]=list(changes)
def integer(offset,value):return dict(file='graph.nodedata',offset=offset,format='int',value=value)
for kind,start,sids,tail in [('enum',72,[77,81],85),('local',119,[124,128],132),('field',152,[157,161,165],169),('annotation',298,[303,307,311],315)]:
 for i,offset in enumerate(sids):mut(kind+'-bad-sid-'+str(i),integer(offset,2147483647))
 mut(kind+'-bad-tail',integer(tail,2147483647) if kind!='field' else dict(file='graph.nodedata',offset=169,format='truncate',value=169))
 mut(kind+'-payload-tag',dict(file='graph.nodedata',offset=start+4,format='byte',value=0))
 mut(kind+'-payload-id',integer(start,1000+start))
 node={'enum':14,'local':16,'field':18,'annotation':26}[kind]
 for label,value in [('missing',0),('negative',-1),('wrapped-valid',(1<<32)+start+1),('wrapped-oob',(1<<32)+1000001)]:
  mut(kind+'-offset-'+label,dict(file='graph.nodeoffsets',offset=8+8*node,format='long',value=value))
# Genuine malformed nested EnumConstant argument kind and Annotation value kind.
mut('enum-invalid-argument-tag',dict(file='graph.nodedata',offset=89,format='byte',value=127))
mut('annotation-invalid-value-tag',dict(file='graph.nodedata',offset=323,format='byte',value=127))
cases=[]
def add(name,q,term='missing',fixtures=None,scoped=False,**extra):
 cases.append(dict(name=name,query=q,parameters=dict(term=term),fixtures=fixtures or ['clean'],scoped=scoped,**extra))
for a in actual:add('original-'+str(a['index']),a['case']['query'],originalCaseIndex=a['index']);cases[-1]['parameters']=a['case']['parameters']
for term in ['','red','x','field','example','deprecated','run','missing']:
 for sources in [1,2]:
  add('six-'+(term or 'empty')+'-'+str(sources),query(),term,['clean']*sources)
for kind,label,hit in [('enum','EnumConstant','red'),('local','LocalVariable','x'),('field','Field','example'),('annotation','Annotation','annotation')]:
 for fixture in [k for k in mutations if k.startswith(kind+'-')]:
  for match,term in [('hit',hit),('miss','never-matches-generic-oracle')]:
   add(fixture+'-'+match,query(label),term,[fixture])
  # Same mutation through exact unlabeled six-term planner family.
  if 'bad-sid' in fixture or 'bad-tail' in fixture:
   add(fixture+'-global-miss',query(),'never-matches-generic-oracle',[fixture])
 for limit in [0,1,2,200]:
  for distinct in [False,True]:add(kind+'-limit-'+str(limit)+'-'+str(distinct),query(label,limit,distinct,True),hit,['clean','clean'])
for fixture in ['field-bad-sid-1','enum-invalid-argument-tag','annotation-invalid-value-tag']:
 for order in [[fixture,'clean'],['clean',fixture]]:
  for scoped in [False,True]:add('cross-'+fixture+'-'+str(order[0]=='clean')+'-'+str(scoped),query('',1,False,True),'example',order,scoped)
# Typed fixtures replace ordered table slots only: declared malformed ordering, no perf claim.
for i,(text,term) in enumerate([('İSTANBUL','i\u0307'),('ΟΣ','ος'),('ΣΟΣ','σος'),('A😀B','😀'),('A\ud800B','\ud800'),('A\udc00B','\udc00'),('A\x00B','\x00'),('ÄÖÜ','äöü')]):
 name='unicode-'+str(i);mut(name,dict(file='graph.strings',format='string-replace',values={'5':text,'12':text,'7':text,'19':text}))
 for label in ['EnumConstant','LocalVariable','Field']:add(name+'-'+label,query(label),term,[name],unicodeTableOrder='original slots replaced; table may be unsorted')
for fixture in ['clean','field-bad-sid-0','field-bad-sid-1','field-bad-sid-2','field-offset-missing','field-offset-negative','field-offset-wrapped-valid','field-payload-tag','field-payload-id','enum-invalid-argument-tag']:
 for term in [('red' if fixture.startswith('enum') else 'example'),'never-matches-generic-oracle']:
  kind='EnumConstant' if fixture.startswith('enum') else 'FieldNode'
  add('provider-'+fixture+'-'+term, '',term,[fixture],providerType=kind,providerProperties=['name'] if kind=='EnumConstant' else ['class','name'])
for name,value in [('cases.json',cases),('mutations.json',mutations),('actual-main64.json',dict(workloadSha256=hashlib.sha256(workload.read_bytes()).hexdigest(),cases=actual))]:
 (HERE/name).write_text(json.dumps(value,ensure_ascii=True,indent=2)+'\n')
print(len(cases),'cases',len(mutations),'fixture variants')
