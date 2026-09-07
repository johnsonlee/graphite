import pathlib,tempfile,subprocess,shutil,json,struct
here=pathlib.Path(__file__).resolve().parent
java=pathlib.Path('/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home/bin');jar='/tmp/graphite-go-main-baseline-clone-4e328b0/graphite-explore/build/libs/graphite-explore.jar'
commands=[]
with tempfile.TemporaryDirectory(prefix='graphite-ordinary-dense-history-')as tmp:
 work=pathlib.Path(tmp);classes=work/'classes';classes.mkdir()
 subprocess.run([str(java/'javac'),'-cp',jar,'-d',str(classes),str(here/'HistoryOracle.java'),str(here/'DenseHistoryOracle.java')],check=True)
 for mutation in ['clean','return-sid','callee-sid']:
  for missing in [False,True]:
   name=mutation+('-missing'if missing else '');fixture=work/name;shutil.copytree(here.parent/'indexed-distinct'/'split-clean',fixture)
   if mutation!='clean':
    data=bytearray((fixture/'graph.nodedata').read_bytes());offset=struct.unpack_from('>q',(fixture/'graph.nodeoffsets').read_bytes(),8+1*8)[0]-1
    struct.pack_into('>i',data,offset+(33 if mutation=='return-sid'else 21),2147483647);(fixture/'graph.nodedata').write_bytes(data)
   if missing:(fixture/'graph.callsite-string-index').unlink()
   command=[str(java/'java'),'-Xmx256m','-cp',str(classes)+':'+jar,'DenseHistoryOracle',str(fixture),str(here/('dense-'+name+'-main.json'))]
   with (here/('dense-'+name+'-main.log')).open('w')as log:r=subprocess.run(command,stdout=log,stderr=log)
   commands.append(dict(name=name,command=command,exitCode=r.returncode))
(here/'dense-history-commands.json').write_text(json.dumps(commands,indent=2)+'\n');print([(c['name'],c['exitCode'])for c in commands])
