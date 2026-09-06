#!/usr/bin/env python3
"""Capture main Constant membership independently; local correctness only."""
import hashlib,json,pathlib,subprocess,sys,tempfile
root=pathlib.Path(__file__).resolve().parent
jar=pathlib.Path(sys.argv[1]).resolve()
queries=[
 "MATCH (n:Constant) RETURN id(n) AS id ORDER BY id",
 "MATCH (n:ConstantNode) RETURN id(n) AS id ORDER BY id",
 "MATCH (n:CONSTANT) RETURN id(n) AS id ORDER BY id",
 "MATCH (n:Constant {id:28}) RETURN n AS node,labels(n) AS labels,n.value AS value,n.type AS type",
 "MATCH (n:ConstantNode {id:28}) RETURN id(n) AS id",
 "MATCH (n:Constant:ResourceValue) RETURN id(n) AS id",
 "MATCH (n:ResourceValue:Constant) RETURN id(n) AS id",
 "MATCH (n:Constant) WHERE id(n)=28 RETURN id(n) AS id",
 "OPTIONAL MATCH (n:Constant {id:28}) RETURN id(n) AS id",
 "UNWIND [28,30] AS wanted OPTIONAL MATCH (n:Constant) WHERE id(n)=wanted RETURN wanted,id(n) AS id",
 "MATCH (n:Constant) RETURN count(n) AS count",
 "MATCH (n:ResourceFile:Constant) RETURN id(n) AS id",
 "MATCH (n:ResourceValue) RETURN labels(n) AS labels,n AS node",
]
cases=[{'name':str(i)+('-cross' if cross else '-scoped'),'query':q,'cross':cross} for i,q in enumerate(queries) for cross in [False,True]]
with tempfile.TemporaryDirectory(prefix='graphite-constant-membership-') as classes:
 subprocess.run(['javac','-cp',str(jar),'-d',classes,str(root/'FunctionsOracle.java')],check=True)
 output=subprocess.run(['java','-Dfile.encoding=UTF-8','-cp',classes+':'+str(jar),'FunctionsOracle',str(root.parent.parent/'store/testdata/jvm-v3')],input=''.join(json.dumps(c)+'\n' for c in cases*3),text=True,capture_output=True,check=True)
 results=[json.loads(line) for line in output.stdout.splitlines() if line.startswith('{')]
 assert len(results)==len(cases)*3
 for i,c in enumerate(cases):
  assert results[i]==results[i+len(cases)]==results[i+len(cases)*2],c
  results[i]['name']=c['name']
 data={'mainCommit':'4e328b0109e13c896b74004823fb049fcb19251a','jarSHA256':hashlib.sha256(jar.read_bytes()).hexdigest(),'java':'17.0.18+0 Homebrew ARM64','fixture':'../store/testdata/jvm-v3','repetitionsPerCase':3,'cases':results[:len(cases)]}
 (root/'constant-membership-jvm-oracle.json').write_text(json.dumps(data,indent=2)+'\n')
