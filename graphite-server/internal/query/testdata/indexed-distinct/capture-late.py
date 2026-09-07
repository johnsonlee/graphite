import pathlib,tempfile,subprocess,shutil,json
here=pathlib.Path(__file__).resolve().parent
java=pathlib.Path('/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home/bin');jar='/tmp/graphite-go-main-baseline-clone-4e328b0/graphite-explore/build/libs/graphite-explore.jar'
cases=[]
for count in [9,40]:
 for name,projection in [('graph','n.caller_name AS x,n.graphId AS g'),('caller','n.caller_name AS x'),('null-graph','n.class AS x,n.graphId AS g')]:cases.append(dict(name=name,sources=count,query="MATCH (n) WHERE n.caller_name='other' RETURN DISTINCT "+projection+' LIMIT 1'))
(here/'late-cases.json').write_text(json.dumps(cases,indent=2)+'\n')
with tempfile.TemporaryDirectory(prefix='graphite-distinct-late-')as tmp:
 work=pathlib.Path(tmp);classes=work/'classes';classes.mkdir()
 subprocess.run([str(java/'javac'),'-cp',jar,'-d',str(classes),str(here/'SourcesOracle.java')],check=True)
 clean=work/'clean';shutil.copytree(here.parent/'candidate-index'/'clean',clean)
 bad=work/'bad';shutil.copytree(here/'required'/'caller-count-outside',bad) if (here/'required'/'caller-count-outside').exists() else shutil.copytree(here/'required'/'caller-count-1000',bad)
 (bad/'graph.callsite-string-content.identity').unlink()
 command=[str(java/'java'),'-Xmx256m','-cp',str(classes)+':'+jar,'SourcesOracle',str(clean),str(here/'late-cases.json'),str(here/'late-main.json'),str(bad)]
 with (here/'late-main.log').open('w')as log:r=subprocess.run(command,stdout=log,stderr=log)
 (here/'late-command.json').write_text(json.dumps(dict(command=command,exitCode=r.returncode),indent=2)+'\n');print(r.returncode)
