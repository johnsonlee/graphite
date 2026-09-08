"""Generate a bounded named public matrix. Budgets are inputs, never expected outputs."""
from pathlib import Path
import json
HERE=Path(__file__).resolve().parent
Q="MATCH (n:CallSite) WHERE n.caller_name CONTAINS 'hit' RETURN n.caller_name AS name LIMIT 1"
def execute(q=Q):return dict(op='execute',query=q,parameters={})
def new(b):return dict(op='newContext',budget=str(b))
def sources(f):return [dict(fixture=f if i==0 else 'empty',graphId='source'+str(i))for i in range(40)]
cases=[]
def case(name,f,b,ops=None):cases.append(dict(name=name,mode='context',budget=str(b),sources=sources(f),operations=ops or[execute()]))
old=json.loads((HERE.parent/'native-leading-work-accounting/cases.json').read_text())
for c in old:
 if c['name'].startswith(('L04','L05','L12')):cases.append(c)
for b in [64,65,1088,1089,1926,1927,1928,1929]:case('P01-reader-boundary-'+str(b),'hit64',b,[execute(),new(100000),execute()])
for label,f,q in [('early','hit64',Q),('exhausted','hit64',Q.replace('LIMIT 1','LIMIT 2')),('large','hit1024',Q)]:case('P02-success-reuse-'+label,f,100000,[execute(q),new(1),execute(q),new(100000),execute(q),new(1),execute(q)])
partial={'bad-magic':1,'bad-version':2,'truncated-int':1,'truncated-identity':4,'bad-identity':36,'bad-unique-id':43,'bad-posting-end':44,'bad-node-order':46,'bad-checksum':1862,'trailing-byte':1862}
for f,n in partial.items():
 case('P03-'+f+'-flush-short',f,64+n,[execute(),new(100000),execute()])
 case('P03-'+f+'-fallback',f,100000,[execute(),new(100000),execute()])
case('P03-trailing-byte-reader-exact','trailing-byte',1927,[execute(),new(100000),execute()])
for f,budgets in [('no-graph-identity',[191,192,100000]),('no-identities',[192,193,320,321,100000]),('short-graph-identity',[100000]),('wrong-graph-identity',[100,100000])]:
 for b in budgets:case('P04-'+f+'-'+str(b),f,b,[execute(),new(100000),execute(),new(100000),execute()])
# Real prelude consumes one unit before the content-identity phase, without private tracker injection.
c= dict(name='P05-prelude-identity-exact',mode='context',budget='65',sources=sources('hit64'),prelude=dict(fixture='prelude-one'),operations=[dict(op='executePrelude',query='MATCH (n:LocalVariable) RETURN n.name AS name',parameters={}),execute(),new(100000),execute()]);cases.append(c)
(HERE/'initial-cases50.json').write_text(json.dumps(cases,indent=2)+'\n')
absent=Q.replace("'hit'","'absentzz'")
for prop in ['caller_name','callee_name']:
 q=Q.replace('RETURN n.caller_name','RETURN n.'+prop)
 ops=[execute(absent),new(100000),execute(q),new(100000),execute(q)]
 if prop=='callee_name':ops += [new(100000),execute(),new(100000),execute(q)]
 case('P06-retained-projection-bad-'+prop,'hit64-bad-'+prop,100000,ops)
(HERE/'cases.json').write_text(json.dumps(cases,indent=2)+'\n')
print(len(cases),'cases',sum(len(c['operations'])for c in cases),'operations')
