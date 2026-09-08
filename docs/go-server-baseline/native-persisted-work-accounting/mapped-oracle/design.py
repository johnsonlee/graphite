from pathlib import Path
import json
HERE=Path(__file__).resolve().parent
Q="MATCH (n:CallSite) WHERE n.caller_name CONTAINS 'absentzz' RETURN n.caller_name AS name LIMIT 1"
def execute():return dict(op='execute',query=Q,parameters={})
def new(b):return dict(op='newContext',budget=str(b))
cases=[]
def case(name,f,b):cases.append(dict(name=name,mode='context',budget=str(b),sources=[dict(fixture=f if i==0 else 'empty',graphId='source'+str(i))for i in range(40)],operations=[execute(),new(100000),execute(),new(1),execute()]))
for b in [1,4,5,36,37,42,43,44,172,173,1862,1863]:case('M01-valid-'+str(b),'hit64',b)
for f,b in [('bad-magic',1),('bad-version',1),('truncated-int',1),('truncated-identity',1),('bad-identity',1),('bad-unique-id',43),('bad-posting-end',44),('bad-node-order',1861),('bad-checksum',1861),('trailing-byte',1)]:
 for budget in [b,100000]:case('M02-'+f+'-'+str(budget),f,budget)
for f,budgets in [('no-graph-identity',[127,128]),('no-identities',[128,129,257]),('short-graph-identity',[100000]),('wrong-graph-identity',[100000])]:
 for b in budgets:case('M03-'+f+'-'+str(b),f,b)
for f in ['hit64-bad-caller_name','hit64-bad-callee_name']:case('M04-P06-reference-'+f,f,100000)
for b in [1,100000]:case('M05-truncated-layout-'+str(b),'truncated-layout',b)
case('M06-node-order-not-validated-by-view','bad-node-order-valid-crc',100000)
(HERE/'cases.json').write_text(json.dumps(cases,indent=2)+'\n');print(len(cases),'cases',sum(len(c['operations'])for c in cases),'ops')
