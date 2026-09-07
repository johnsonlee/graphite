import pathlib,tempfile,subprocess,shutil,json,struct
here=pathlib.Path(__file__).resolve().parent
java=pathlib.Path('/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home/bin');jar='/tmp/graphite-go-main-baseline-clone-4e328b0/graphite-explore/build/libs/graphite-explore.jar';commands=[]
with tempfile.TemporaryDirectory(prefix='ordinary-rolling-main-')as tmp:
 work=pathlib.Path(tmp);classes=work/'classes';classes.mkdir();fixtures=work/'fixtures';fixtures.mkdir()
 for name,original in [('clean','clean'),('nohit','clean'),('bad','bad-matched')]:shutil.copytree(here.parent/'candidate-index'/original,fixtures/name)
 raw=fixtures/'nohit'/'graph.nodedata';data=bytearray(raw.read_bytes());offsets=(fixtures/'nohit'/'graph.nodeoffsets').read_bytes()
 for at in range(8,len(offsets),8):
  offset=struct.unpack_from('>q',offsets,at)[0]-1
  if offset>=0 and data[offset+4]==12:struct.pack_into('>i',data,offset+9,0)
 raw.write_bytes(data)
 for name in ['graph.callsite-string-index','graph.callsite-string-content.identity']:(fixtures/'nohit'/name).unlink()
 subprocess.run([str(java/'javac'),'-cp',jar,'-d',str(classes),str(here/'HistoryOracle.java'),str(here/'RollingSourceOracle.java')],check=True)
 for repeat in range(3):
  command=[str(java/'java'),'-Xmx256m','-cp',str(classes)+':'+jar,'RollingSourceOracle',str(fixtures),str(here/('rolling-main-'+str(repeat)+'.json'))]
  with (here/('rolling-main-'+str(repeat)+'.log')).open('w')as log:r=subprocess.run(command,stdout=log,stderr=log)
  commands.append(dict(command=command,exitCode=r.returncode))
(here/'rolling-commands.json').write_text(json.dumps(commands,indent=2)+'\n');print([c['exitCode']for c in commands])
