from pathlib import Path
import json
HERE=Path(__file__).resolve().parent
Q="MATCH (n:CallSite) WHERE n.caller_name CONTAINS 'absentzz' RETURN n.caller_name AS name LIMIT 1"
def execute():return dict(op='execute',query=Q,parameters={})
def new(b):return dict(op='newContext',budget=str(b))
cases=[dict(name='BT-bad-magic-budget'+str(b),mode='context',budget=str(b),sources=[dict(fixture='bad-magic'if i==0 else'empty',graphId='source'+str(i))for i in range(40)],operations=[execute(),new(100000),execute(),new(1),execute()])for b in [258,259,387,388,516,517,518,519]]
(HERE/'cases.json').write_text(json.dumps(cases,indent=2)+'\n');print(len(cases),'cases',sum(len(c['operations'])for c in cases),'ops')
