import pathlib,tempfile,subprocess,shutil,json
here=pathlib.Path(__file__).resolve().parent
java=pathlib.Path('/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home/bin');jar='/tmp/graphite-go-main-baseline-clone-4e328b0/graphite-explore/build/libs/graphite-explore.jar';commands=[]
with tempfile.TemporaryDirectory(prefix='ordinary-required-main-')as tmp:
 work=pathlib.Path(tmp);classes=work/'classes';classes.mkdir();subprocess.run([str(java/'javac'),'-cp',jar,'-d',str(classes),str(here/'HistoryOracle.java'),str(here/'RequiredHistoryOracle.java')],check=True)
 for spec in json.loads((here.parent/'indexed-distinct'/'required-mutations.json').read_text()):
  for missing in [False,True]:
   name=spec['name']+('-missing'if missing else '');fixture=work/name;shutil.copytree(here.parent/'indexed-distinct'/'required'/spec['name'],fixture)
   if missing:(fixture/'graph.callsite-string-index').unlink(missing_ok=True)
   out=here/'required';out.mkdir(exist_ok=True)
   command=[str(java/'java'),'-Xmx256m','-cp',str(classes)+':'+jar,'RequiredHistoryOracle',str(fixture),str(out/(name+'-main.json'))]
   with (out/(name+'-main.log')).open('w')as log:r=subprocess.run(command,stdout=log,stderr=log)
   commands.append(dict(name=name,command=command,exitCode=r.returncode))
(here/'required-commands.json').write_text(json.dumps(commands,indent=2)+'\n');print([c['exitCode']for c in commands])
