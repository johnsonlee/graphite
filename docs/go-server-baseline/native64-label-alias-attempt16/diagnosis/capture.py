from pathlib import Path
import json,shutil,hashlib,subprocess
r=Path(__file__).resolve().parent
original=Path('/Users/johnsonlee/.codex/worktrees/112399f5-4ea0-42da-af34-5ab6ef682c3d/graphite/docs/go-server-baseline/native64-lazy-filtered-integration')
commands=[];identities=[];summary=[]
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
for side,tree in [('base','/tmp/graphite-go-cde-base-9ada2bf1'),('candidate','/tmp/graphite-go-cde-root-9ada2bf1')]:
 out=r/side;out.mkdir(exist_ok=True)
 ident=json.loads((original/side/'identity.json').read_text());binary=Path(ident['command'][0]);assert sha(binary)==ident['binarySHA256']
 identities.append({'side':side,'binary':str(binary),'sha256':sha(binary)})
 for name in ['identity.json','source-manifest.json']:
  shutil.copyfile(original/side/name,out/name)
 for p in (original/side/'profiles/e-generic-distinct').iterdir():
  if p.is_file():shutil.copyfile(p,out/p.name)
 receipt=json.loads((out/'receipt.json').read_text());summary.append({'side':side,'query':receipt['query'],'elapsed':receipt['executeAndMarshalSecondsDiagnostic'],'deltas':receipt['requestDeltas'],'outputSHA256':receipt['outputSha256'],'fullOutputMatchesExpected':receipt['fullOutputMatchesExpected']})
 for label,args in [('alloc-top',['-top','-nodecount=35']),('alloc-cum',['-top','-cum','-nodecount=30']),('alloc-list',['-list','lazyFiltered|lazyProject|lazyGenericNodes|ProjectionCandidateNode'])]:
  cmd=['go','tool','pprof','-sample_index=alloc_space','-base',str(out/'heap-before.pprof'),*args,str(binary),str(out/'heap-after.pprof')]
  p=subprocess.run(cmd,cwd=Path(tree)/'graphite-server',capture_output=True)
  (out/(label+'.txt')).write_bytes(p.stdout);(out/(label+'.stderr')).write_bytes(p.stderr)
  commands.append({'cwd':tree+'/graphite-server','command':cmd,'exit':p.returncode});(r/'commands.json').write_text(json.dumps(commands,indent=2)+'\n')
(r/'commands.json').write_text(json.dumps(commands,indent=2)+'\n');(r/'identities.json').write_text(json.dumps(identities,indent=2)+'\n');(r/'summary.json').write_text(json.dumps(summary,indent=2)+'\n')
