from pathlib import Path
import json
HERE=Path(__file__).resolve().parent
Q="MATCH (n:CallSite) WHERE n.caller_name CONTAINS 'absentzz' RETURN n.caller_name AS name LIMIT 1"
def execute():return dict(op='execute',query=Q,parameters={})
def new(b):return dict(op='newContext',budget=str(b))
cases=[]
for f,budgets in [('large',[262188,262189,4000000]),('large-bad-first-chunk',[45,4000000]),('large-bad-second-chunk',[262189,4000000])]:
 for b in budgets:cases.append(dict(name='ML-'+f+'-'+str(b),mode='context',budget=str(b),sources=[dict(fixture=f if i==0 else 'empty',graphId='source'+str(i))for i in range(40)],operations=[execute(),new(4000000),execute(),new(1),execute()]))
(HERE/'cases.json').write_text(json.dumps(cases,indent=2)+'\n');print(len(cases),'cases',sum(len(c['operations'])for c in cases),'ops')
