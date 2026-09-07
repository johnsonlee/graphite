import pathlib,subprocess,tempfile,shutil,json
h=pathlib.Path(__file__).resolve().parent;jdk=pathlib.Path('/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home/bin');jar='/tmp/graphite-go-main-baseline-clone-4e328b0/graphite-explore/build/libs/graphite-explore.jar';commands=[]
with tempfile.TemporaryDirectory(prefix='ordinary-annotation-main-')as tmp:
 work=pathlib.Path(tmp);classes=work/'classes';classes.mkdir();subprocess.run([str(jdk/'javac'),'-cp',jar,'-d',str(classes),str(h.parent/'indexed-distinct/PlannerOracle.java')],check=True)
 for name in ['annotation','mixed','annotation-after']:
  original=h.parent/'indexed-distinct'/name if name=='annotation-after'else h.parent/'candidate-index'/name
  fixture=work/name;shutil.copytree(original,fixture);command=[str(jdk/'java'),'-Xmx256m','-cp',str(classes)+':'+jar,'PlannerOracle',str(fixture),str(h/'annotation-cases.json'),str(h/(name+'-main.json'))]
  with(h/(name+'-main.log')).open('w')as log:r=subprocess.run(command,stdout=log,stderr=log)
  commands.append(dict(name=name,command=command,exitCode=r.returncode))
(h/'annotation-commands.json').write_text(json.dumps(commands,indent=2)+'\n');print([x['exitCode']for x in commands])
