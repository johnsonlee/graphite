import pathlib,shutil,subprocess,json
b=pathlib.Path(__file__).parent
jar='/tmp/graphite-go-main-baseline-clone-4e328b0/graphite-explore/build/libs/graphite-explore.jar'
results=[]
for kind in ['OUTDEGREES_DELTA','RESIDUALS_GAMMA','REFERENCES_GAMMA','OFFSETS_DELTA','window0','utf8','wrong-serialUID','trailing-serialization']:
 source=b/'formats'/kind/'source';shutil.copytree(b/'current',source,dirs_exist_ok=True)
 if kind=='wrong-serialUID':
  p=source/'graph.strings';v=bytearray(p.read_bytes());key=b'it.unimi.dsi.util.FrontCodedStringList';idx=v.index(key)+len(key);v[idx]^=1;p.write_bytes(v)
 elif kind=='trailing-serialization':
  p=source/'graph.strings';p.write_bytes(p.read_bytes()+b'BAD!')
 else:
  p=subprocess.run(['java','-Xmx128m','-cp',str(b)+':'+jar,'Formats',str(source),kind],capture_output=True,text=True,timeout=25)
  if p.returncode:raise RuntimeError(p.stderr)
 for mode in ['MAPPED','EAGER']:
  row={'case':kind,'mode':mode}
  for runtime in ['go','jvm']:
   target=b/'formats'/kind/mode/runtime;shutil.copytree(source,target,dirs_exist_ok=True)
   cmd=[str(b/'go-probe'),str(target),mode] if runtime=='go' else ['java','-Xmx128m','-cp',str(b)+':'+jar,'Probe',str(target),mode]
   p=subprocess.run(cmd,capture_output=True,text=True,timeout=25)
   (target.parent/(runtime+'.stdout')).write_text(p.stdout);(target.parent/(runtime+'.stderr')).write_text(p.stderr)
   row[runtime]=json.loads(p.stdout.splitlines()[-1])
  results.append(row);print(kind,mode,'Go',row['go'].get('load'),'JVM',row['jvm'].get('load'),flush=True)
(b/'formats-results.json').write_text(json.dumps(results,indent=2)+'\n')
