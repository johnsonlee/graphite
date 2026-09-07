import pathlib,tempfile,subprocess,shutil,json,struct
here=pathlib.Path(__file__).resolve().parent
java=pathlib.Path('/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home/bin');jar='/tmp/graphite-go-main-baseline-clone-4e328b0/graphite-explore/build/libs/graphite-explore.jar'
cases=[]
for count in [9,40]:
 for name,q in [
 ('caller',"MATCH (n) WHERE n.caller_name='other' RETURN DISTINCT n.caller_name AS x LIMIT 1"),
 ('tuple',"MATCH (n) WHERE n.caller_name='other' RETURN DISTINCT n.caller_name AS x,n.callee_name AS y LIMIT 2"),
 ('graph',"MATCH (n) WHERE n.caller_name='other' RETURN DISTINCT n.caller_name AS x,n.graphId AS g LIMIT 1"),
 ('skip',"MATCH (n) WHERE n.caller_name='other' RETURN DISTINCT n.caller_name AS x,n.callee_name AS y SKIP 1 LIMIT 1"),
 ('nohit',"MATCH (n) WHERE n.caller_name CONTAINS 'absent-long-needle-for-preflight' RETURN DISTINCT n.callee_name AS x LIMIT 1")]:cases.append(dict(name=name,query=q,sources=count))
(here/'split-cases.json').write_text(json.dumps(cases,indent=2)+'\n')
commands=[]
with tempfile.TemporaryDirectory(prefix='graphite-distinct-split-') as tmp:
 work=pathlib.Path(tmp);classes=work/'classes';classes.mkdir()
 subprocess.run([str(java/'javac'),'-cp',jar,'-d',str(classes),str(here/'GenerateSplit.java'),str(here/'SourcesOracle.java')],check=True)
 clean=here/'split-clean'
 if not clean.exists():subprocess.run([str(java/'java'),'-Xmx256m','-cp',str(classes)+':'+jar,'GenerateSplit',str(here.parent/'candidate-index'/'clean'),str(clean)],check=True)
 for mode in ['clean','bad-count','bad-tag','bad-callee','missing-index']:
  fixture=work/mode;shutil.copytree(clean,fixture)
  if mode=='missing-index':(fixture/'graph.callsite-string-index').unlink()
  if mode.startswith('bad-'):
   data=bytearray((fixture/'graph.nodedata').read_bytes());offsets=(fixture/'graph.nodeoffsets').read_bytes();offset=struct.unpack_from('>q',offsets,8+3649*8)[0]-1
   if mode=='bad-tag':data[offset+4]=255
   elif mode=='bad-count':struct.pack_into('>i',data,offset+13,2147483640)
   else:struct.pack_into('>i',data,offset+21,2147483647)
   (fixture/'graph.nodedata').write_bytes(data)
  command=[str(java/'java'),'-Xmx512m','-cp',str(classes)+':'+jar,'SourcesOracle',str(fixture),str(here/'split-cases.json'),str(here/('split-'+mode+'-main.json'))]
  with (here/('split-'+mode+'-main.log')).open('w')as log:r=subprocess.run(command,stdout=log,stderr=log)
  commands.append(dict(mode=mode,command=command,exitCode=r.returncode))
(here/'split-commands.json').write_text(json.dumps(commands,indent=2)+'\n');print([(x['mode'],x['exitCode'])for x in commands])
