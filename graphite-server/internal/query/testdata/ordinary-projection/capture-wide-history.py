import pathlib,tempfile,subprocess,shutil,json
here=pathlib.Path(__file__).resolve().parent
java=pathlib.Path('/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home/bin');jar='/tmp/graphite-go-main-baseline-clone-4e328b0/graphite-explore/build/libs/graphite-explore.jar'
commands=[]
with tempfile.TemporaryDirectory(prefix='graphite-ordinary-wide-history-')as tmp:
 work=pathlib.Path(tmp);classes=work/'classes';classes.mkdir()
 subprocess.run([str(java/'javac'),'-cp',jar,'-d',str(classes),str(here/'HistoryOracle.java'),str(here/'WideHistoryOracle.java')],check=True)
 for name in ['clean','bad-matched']:shutil.copytree(here.parent/'candidate-index'/name,work/name)
 for repeat in range(3):
  command=[str(java/'java'),'-Xmx256m','-cp',str(classes)+':'+jar,'WideHistoryOracle',str(work),str(here/('wide-history-main-'+str(repeat)+'.json'))]
  with (here/('wide-history-main-'+str(repeat)+'.log')).open('w')as log:r=subprocess.run(command,stdout=log,stderr=log)
  commands.append(dict(command=command,exitCode=r.returncode))
(here/'wide-history-commands.json').write_text(json.dumps(commands,indent=2)+'\n');print([c['exitCode']for c in commands])
