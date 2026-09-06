#!/usr/bin/env python3
"""Correctness-only offline main oracle; never invokes HTTP or a benchmark."""
import hashlib,json,pathlib,subprocess,sys,tempfile
root=pathlib.Path(__file__).resolve().parent
jar=pathlib.Path(sys.argv[1]).resolve()
values=['','a','ab','abc','ABC','aaz','ab[','aaa','acc','XΟΣΑ','AΣ12','Σ12','XΟΣ','İab','i\u0307ab','中文测试','中文测','😀ab','😀','\ud800ab','\udc00ab','\ud800','\udc00','a😀b','a\ud800b','neutral','MiXeD','école','e\u0301cole','a-b.c','１２３','xΣ\u0301a','XΟΣ\u0301']
needles=['','a','ab','abc','ABC','aaz','ab[','aaa','acc','XΟΣ','Σ12','xοσ','ς12','İab','i\u0307a','中文测','😀a','😀','\ud800ab','\udc00ab','\ud800','\udc00','\ude00ab','MiX','mix','éco','e\u0301c','１２３']
cases=[]
for n,needle in enumerate(needles):
 for typed in [False,True]:
  for lower in [False,True]:
   props=['n.'+p for p in ['caller_class','caller_name','callee_class','callee_name']]
   if lower:props=["toLower(toString(coalesce("+p+",'')))" for p in props]
   query='MATCH (n'+(':CallSiteNode' if typed else '')+') WHERE '+' OR '.join(p+' CONTAINS $term' for p in props)+' RETURN id(n) AS id ORDER BY id'
   cases.append({'name':f'{n}-'+('typed' if typed else 'untyped')+('-wrapped' if lower else '-raw'),'query':query,'params':{'term':needle}})
for op in ['STARTS WITH','ENDS WITH']:
 for text,needle in [('XΟΣΑ','XΟΣ'),('AΣ12','Σ12')]:
  cases.append({'name':op+'-'+text,'query':'RETURN $actual '+op+' $term AS raw, toLower($actual) '+op+' $term AS wrapped','params':{'actual':text,'term':needle}})
(root/'inputs.json').write_text(json.dumps({'values':values,'cases':cases},ensure_ascii=True,indent=2)+'\n')
with tempfile.TemporaryDirectory(prefix='graphite-trigram-oracle-') as classes:
 subprocess.run(['javac','-cp',str(jar),'-d',classes,str(root/'TrigramOracle.java')],check=True)
 subprocess.run(['java','-Dfile.encoding=UTF-8','-cp',classes+':'+str(jar),'TrigramOracle',str(root)],check=True)
print('cases',len(cases)*2,'values',len(values))
