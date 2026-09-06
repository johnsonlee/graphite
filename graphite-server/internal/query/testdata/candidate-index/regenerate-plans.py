#!/usr/bin/env python3
"""Independent tiny main oracle for dictionary candidate selection; no timing."""
import json,pathlib,subprocess,sys,tempfile
root=pathlib.Path(__file__).resolve().parent;jar=pathlib.Path(sys.argv[1]).resolve()
cases=[]
def add(name,query,params=None):
 case={'name':name,'query':query}
 if params is not None:case['params']=params
 cases.append(case)
for op in ['=','CONTAINS','STARTS WITH','ENDS WITH']:
 add('raw-'+op,'MATCH (n) WHERE n.caller_name '+op+" 'other' RETURN id(n) AS id ORDER BY id")
 add('wrapped-'+op,"MATCH (n) WHERE toLower(toString(coalesce(n.caller_class,''))) "+op+" 'example.other' RETURN id(n) AS id ORDER BY id")
for label in ['',':CallSiteNode']:
 for wrapped in [False,True]:
  props=['caller_class','caller_name','callee_class','callee_name'];operands=['n.'+p for p in props]
  if wrapped:operands=["toLower(toString(coalesce("+p+",'')))" for p in operands]
  add(('typed' if label else 'untyped')+('-four-wrapped' if wrapped else '-four-raw'),'MATCH (n'+label+') WHERE '+" OR ".join(o+" CONTAINS 'other'" for o in operands)+' RETURN id(n) AS id ORDER BY id')
add('empty-null-wrapper',"MATCH (n) WHERE coalesce(n.caller_name,'') CONTAINS '' RETURN id(n) AS id ORDER BY id")
add('raw-empty',"MATCH (n) WHERE n.caller_name CONTAINS '' RETURN id(n) AS id ORDER BY id")
add('or-overlap-count',"MATCH (n) WHERE n.caller_name='other' OR n.callee_name='invoke' RETURN count(n) AS count")
add('distinct-provenance',"MATCH (n) WHERE n.caller_name='other' RETURN DISTINCT n.caller_class AS caller ORDER BY caller LIMIT 1")
add('available-empty',"MATCH (n) WHERE n.caller_name='absent' RETURN id(n) AS id")
add('half-surrogate',r"MATCH (n) WHERE n.caller_class CONTAINS '\ud800' RETURN id(n) AS id ORDER BY id")
add('java-lower',r"MATCH (n) WHERE toLower(n.caller_class) CONTAINS 'i\u0307d' RETURN id(n) AS id ORDER BY id")
add('lower-rhs-unchanged',"MATCH (n) WHERE toLower(n.caller_class) CONTAINS 'OTHER' RETURN id(n) AS id")
for name,params in [('string',{'term':'other'}),('null',{'term':None}),('number',{'term':3}),('missing',{})]:add('parameter-'+name,"MATCH (n) WHERE n.caller_name CONTAINS $term RETURN id(n) AS id ORDER BY id",params)
for name,where in [('extra-argument',"toString(n.caller_name,1/0)='other'"),('throwing-fallback',"coalesce(n.caller_name,1/0)='other'"),('throwing-or',"n.caller_name='other' OR substring('x','bad')='x'"),('incomplete-or',"n.caller_name='absent' OR true")]:add(name,'MATCH (n) WHERE '+where+' RETURN id(n) AS id ORDER BY id')
add('optional',"OPTIONAL MATCH (n) WHERE n.caller_name='absent' RETURN id(n) AS id")
add('unwind',"UNWIND ['other','caller'] AS term MATCH (n) WHERE n.caller_name=term RETURN term,id(n) AS id ORDER BY term,id")
add('old-binding',"WITH {caller_name:'other'} AS n MATCH (n) WHERE n.caller_name='other' RETURN n")
add('inline-ordered',"MATCH (n {id:-1,caller_name:1/0}) WHERE n.caller_name='other' RETURN id(n) AS id")
(root/'plan-cases.json').write_text(json.dumps(cases,indent=2)+'\n')
with tempfile.TemporaryDirectory(prefix='graphite-plan-oracle-') as classes:
 subprocess.run(['javac','-cp',str(jar),'-d',classes,str(root/'PlannerOracle.java'),str(root/'GenerateMixed.java')],check=True)
 cp=classes+':'+str(jar)
 subprocess.run(['java','-cp',cp,'GenerateMixed',str(root/'clean'),str(root)],check=True,capture_output=True)
 for fixture in ['clean','mixed','annotation']:
  subprocess.run(['java','-Dfile.encoding=UTF-8','-cp',cp,'PlannerOracle',str(root/fixture),str(root/'plan-cases.json'),str(root/(fixture+'-plan-main.json'))],check=True,capture_output=True)
