import pathlib,tempfile,subprocess,shutil,json
here=pathlib.Path(__file__).resolve().parent
java=pathlib.Path('/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home/bin')
jar='/tmp/graphite-go-main-baseline-clone-4e328b0/graphite-explore/build/libs/graphite-explore.jar'
commands=[]
with tempfile.TemporaryDirectory(prefix='graphite-distinct-additional-') as tmp:
 work=pathlib.Path(tmp);classes=work/'classes';classes.mkdir()
 subprocess.run([str(java/'javac'),'-cp',jar,'-d',str(classes),str(here/'PlannerOracle.java')],check=True)
 for fixture in ['annotation','mixed','annotation-after']:
  graph=work/fixture;shutil.copytree(here/fixture if fixture=='annotation-after' else here.parent/'candidate-index'/fixture,graph)
  command=[str(java/'java'),'-Xmx256m','-cp',str(classes)+':'+jar,'PlannerOracle',str(graph),str(here/'additional-cases.json'),str(here/(fixture+'-main.json'))]
  with (here/(fixture+'-main.log')).open('w') as log:r=subprocess.run(command,stdout=log,stderr=log)
  commands.append(dict(fixture=fixture,command=command,exitCode=r.returncode))
(here/'additional-commands.json').write_text(json.dumps(commands,indent=2)+'\n')
print(commands)

def wire(v):
 if isinstance(v,str):return v.encode('utf-16-le',errors='surrogatepass').decode('utf-16-le',errors='surrogatepass').encode('utf-8',errors='replace').decode('utf-8')
 if isinstance(v,list):return [wire(x) for x in v]
 if isinstance(v,dict):return {wire(k):wire(x) for k,x in v.items()}
 return v
for fixture in ['annotation','mixed','annotation-after']:
 (here/(fixture+'-wire-main.json')).write_text(json.dumps(wire(json.loads((here/(fixture+'-main.json')).read_text())),indent=2)+'\n')
