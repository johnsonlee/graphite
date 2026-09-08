"""Bounded ordered-property route correctness from actual main public execution."""
from pathlib import Path
import json
HERE=Path(__file__).resolve().parent
cases=[]
PREFIX='MATCH (n:LocalVariable) RETURN n.name AS name ORDER BY name '
def add(name,query,budget='4',fixture='locals',parameters=None):
 cases.append(dict(name=name,mode='context',budget=budget,fixture=fixture,operations=[dict(op='execute',query=query,parameters=parameters or {})]))
for name,limit in [('zero','0'),('one','1'),('two','2'),('max-admitted','10000'),('beyond-max','10001'),('negative','-1'),('numeric-string',"'2'"),('bad-string',"'bad'"),('parameter','$limit'),('expression','1 + 1')]:
 add('ordered-limit-'+name,PREFIX+'ASC LIMIT '+limit,parameters={'limit':2} if name=='parameter' else {})
add('ordered-descending',PREFIX+'DESC LIMIT 2')
add('ordered-multiple-tie-columns','MATCH (n:LocalVariable) RETURN n.class AS kind, n.name AS name ORDER BY kind DESC, name DESC LIMIT 2')
add('ordered-equal-key-encounter','MATCH (n:LocalVariable) RETURN n.class AS kind, n.name AS name ORDER BY kind ASC LIMIT 2')
add('ordered-duplicate-alias','MATCH (n:LocalVariable) RETURN n.name AS x, n.id AS x ORDER BY x LIMIT 2')
add('ordered-where','MATCH (n:LocalVariable) WHERE n.id >= 0 RETURN n.name AS name ORDER BY name LIMIT 2')
add('ordered-with','MATCH (n:LocalVariable) WITH n RETURN n.name AS name ORDER BY name LIMIT 2')
add('ordered-skip-zero',PREFIX+'ASC SKIP 0 LIMIT 2')
add('ordered-missing-first',PREFIX+'ASC LIMIT 2',fixture='missing-first')
add('ordered-bad-first',PREFIX+'ASC LIMIT 2',fixture='bad-first')
add('ordered-budget-three',PREFIX+'ASC LIMIT 2',budget='3')
add('ordered-all-missing',PREFIX+'ASC LIMIT 2',budget='1',fixture='missing-all')
add('ordered-bad-first-zero',PREFIX+'ASC LIMIT 0',fixture='bad-first')
add('ordered-property-expression','MATCH (n:LocalVariable) RETURN n.name AS name ORDER BY n.name LIMIT 2')
add('ordered-float-limit',PREFIX+'ASC LIMIT 1.9')
add('ordered-boolean-limit',PREFIX+'ASC LIMIT true')
(HERE/'cases.json').write_text(json.dumps(cases,indent=2)+'\n')
print(len(cases),'ordered scenarios')
