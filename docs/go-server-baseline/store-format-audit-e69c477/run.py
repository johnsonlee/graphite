import pathlib,shutil,subprocess,json,struct
b=pathlib.Path(__file__).parent
jar='/tmp/graphite-go-main-baseline-clone-4e328b0/graphite-explore/build/libs/graphite-explore.jar'
cases=[('pristine',lambda p:None)]
files=['graph.nodeindex','graph.nodeoffsets','graph.typeindex','graph.labelprefix','graph.strings.identity','graph.classoverview','graph.resources','graph.comparisons','graph.metadata','forward.offsets','graph.labels']
for f in files:
 cases.append(('missing-'+f,lambda p,f=f:(p/f).unlink(missing_ok=True)))
 cases.append(('corrupt-'+f,lambda p,f=f:(p/f).write_bytes(b'BAD!')))
cases += [('truncated-metadata-body',lambda p:(p/'graph.metadata').write_bytes((p/'graph.metadata').read_bytes()[:8])),('named-default-flags',lambda p:(p/'forward.properties').write_text((p/'forward.properties').read_text().replace('compressionflags=','compressionflags=OUTDEGREES_GAMMA')))]
results=[]
for name,mutate in cases:
 for mode in ['MAPPED','EAGER']:
  row={'case':name,'mode':mode}
  for runtime in ['go','jvm']:
   target=b/'cases'/name/mode/runtime
   shutil.copytree(b/'base',target,dirs_exist_ok=True);mutate(target)
   cmd=[str(b/'go-probe'),str(target),mode] if runtime=='go' else ['java','-Xmx128m','-cp',str(b)+':'+jar,'Probe',str(target),mode]
   p=subprocess.run(cmd,capture_output=True,text=True,timeout=25)
   (target.parent/(runtime+'.stdout')).write_text(p.stdout);(target.parent/(runtime+'.stderr')).write_text(p.stderr)
   try:row[runtime]=json.loads(p.stdout.splitlines()[-1])
   except Exception:row[runtime]={'probeFailure':p.returncode,'stdout':p.stdout,'stderr':p.stderr}
  results.append(row)
  print(name,mode,'Go',row['go'].get('load'),'JVM',row['jvm'].get('load'),flush=True)
(b/'results.json').write_text(json.dumps(results,indent=2)+'\n')
