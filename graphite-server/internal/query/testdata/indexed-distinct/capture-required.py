import pathlib,subprocess,tempfile,json,shutil
here=pathlib.Path(__file__).resolve().parent;java=pathlib.Path('/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home/bin');jar='/tmp/graphite-go-main-baseline-clone-4e328b0/graphite-explore/build/libs/graphite-explore.jar';all=[];commands=[]
import argparse
a=argparse.ArgumentParser();a.add_argument('--missing-index',action='store_true');a.add_argument('--missing-identity',action='store_true');args=a.parse_args();suffix='-missing-index' if args.missing_index else ('-missing-identity' if args.missing_identity else '')
with tempfile.TemporaryDirectory(prefix='graphite-distinct-required-')as tmp:
 work=pathlib.Path(tmp);classes=work/'classes';classes.mkdir();subprocess.run([str(java/'javac'),'-cp',jar,'-d',str(classes),str(here/'PlannerOracle.java')],check=True)
 for spec in json.loads((here/'required-mutations.json').read_text()):
  name=spec['name'];fixture=work/name;shutil.copytree(here/'required'/name,fixture);out=here/('required-oracle'+suffix);out.mkdir(exist_ok=True)
  if args.missing_index:(fixture/'graph.callsite-string-index').unlink(missing_ok=True)
  if args.missing_identity:(fixture/'graph.callsite-string-content.identity').unlink(missing_ok=True)
  command=[str(java/'java'),'-Xmx256m','-cp',str(classes)+':'+jar,'PlannerOracle',str(fixture),str(here/'required-cases.json'),str(out/(name+'.json'))]
  with (out/(name+'.log')).open('w')as log:r=subprocess.run(command,stdout=log,stderr=log)
  commands.append({'mutation':name,'command':command,'exitCode':r.returncode})
  if r.returncode:continue
  for record in json.loads((out/(name+'.json')).read_text()):record['mutation']=name;all.append(record)
(here/('required-main'+suffix+'.json')).write_text(json.dumps(all,indent=2)+'\n');(here/('required-commands'+suffix+'.json')).write_text(json.dumps(commands,indent=2)+'\n');print('captured',len(all),'failures',sum(x['exitCode']!=0 for x in commands))
