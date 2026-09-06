import pathlib,shutil,subprocess,json
b=pathlib.Path(__file__).parent;jar='/tmp/graphite-go-main-baseline-clone-4e328b0/graphite-explore/build/libs/graphite-explore.jar';results=[]
for filename in ['backward.graph','backward.offsets','backward.properties']:
 for action in ['missing','corrupt']:
  for mode in ['MAPPED','EAGER']:
   row={'case':action+'-'+filename,'mode':mode}
   for runtime in ['go','jvm']:
    p=b/'backward-cases'/row['case']/mode/runtime;shutil.copytree(b/'base',p,dirs_exist_ok=True)
    if action=='missing':(p/filename).unlink(missing_ok=True)
    else:(p/filename).write_bytes(b'BAD!')
    cmd=[str(b/'go-probe'),str(p),mode] if runtime=='go' else ['java','-Xmx128m','-cp',str(b)+':'+jar,'Probe',str(p),mode]
    r=subprocess.run(cmd,capture_output=True,text=True,timeout=25);(p.parent/(runtime+'.stdout')).write_text(r.stdout);(p.parent/(runtime+'.stderr')).write_text(r.stderr);row[runtime]=json.loads(r.stdout.splitlines()[-1])
   results.append(row);print(row['case'],mode,row['jvm'].get('incoming'),row['go'].get('incoming'),flush=True)
(b/'backward-results.json').write_text(json.dumps(results,indent=2)+'\n')
