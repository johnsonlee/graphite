from pathlib import Path
import json,hashlib,shutil,subprocess,datetime
repo=Path('/Users/johnsonlee/.codex/worktrees/112399f5-4ea0-42da-af34-5ab6ef682c3d/graphite');r=repo/'docs/go-server-baseline/native64-projection-context-attempt17';r.mkdir()
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
components=[]
for src,mp,dest,expected in [('/tmp/graphite-projection-context-attempt17-evidence','manifest.json','provider','de59ed37748ee8c108b029142f61455f5c7a7818700e29e916333269a462dbef'),('/tmp/graphite-go-a17-on-a16-b/integration','manifest.json','integration','384788c19801786ceb8f715b4b0e0232e8ca0d45a2b5b0990c1c42973ef60584'),('/tmp/graphite-a15-mutex-root-62b92d20','manifest.json','diagnosis','7d161c4a8bdb1a77b798d1f28a8e3b085210a4c756b55c5a204ce42910405d58'),('/tmp/graphite-a15-mutex-diagnostic','manifest.json','diagnostic-helper','73a60c2cf55fdac5fe8264fc475d0afd7ddb0a93670505a7122ffc58cd387dd6')]:
 src=Path(src);m=src/mp;assert sha(m)==expected;d=json.loads(m.read_text());files=d['files'];entries=files if isinstance(files,list) else [dict(v,path=k) if isinstance(v,dict) else {'path':k,'sha256':v} for k,v in files.items()];out=r/dest;out.mkdir()
 for v in entries:
  p=src/v['path'];assert sha(p)==v['sha256'],v['path'];q=out/v['path'];q.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(p,q)
 (out/mp).parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(m,out/mp);components.append({'component':dest,'manifestSHA256':expected,'allFilesRehashedAndCopied':len(entries)})
helper=Path('/tmp/graphite-a15-mutex-diagnostic')
for p,h in json.loads((helper/'manifest.json').read_text())['overlay'].items():
 assert sha(helper/p)==h;q=r/'diagnostic-helper'/p;q.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(helper/p,q)
patch=r/'provider/attempt17.patch';assert sha(patch)=='271f166fc60570ee329ebbd8cb1b1786194328bb1bfc3c82c5d343316605905d'
complete=json.loads((r/'integration/candidate-inputs.json').read_text());candidate={k.removeprefix('graphite-server/'):v['sha256'] for k,v in complete.items() if k.startswith('graphite-server/')};assert len(candidate)==2436
base=json.loads((repo/'docs/go-server-baseline/native64-label-alias-attempt16/tested-source.json').read_text());assert len(base)==2434
for p,h in base.items():assert sha(repo/'graphite-server'/p)==h,p
worktrees={phase:'/tmp/graphite-go-context-'+phase+'-b7bb15a2' for phase in ['base','candidate','http']}
for phase,dest in worktrees.items():
 w=Path(dest);assert not w.exists()
 with (r/(phase+'-worktree.log')).open('w') as log:
  subprocess.run(['git','worktree','add','--detach','--no-checkout',str(w),'b7bb15a2'],cwd=repo,stdout=log,stderr=subprocess.STDOUT,check=True)
  subprocess.run(['git','restore','--source=HEAD','--staged','--worktree','--','graphite-server','graphite-explore','CONVENTIONS.md'],cwd=w,stdout=log,stderr=subprocess.STDOUT,check=True)
  if phase!='base':
   subprocess.run(['git','apply','--check',str(patch)],cwd=w,stdout=log,stderr=subprocess.STDOUT,check=True)
   subprocess.run(['git','apply',str(patch)],cwd=w,stdout=log,stderr=subprocess.STDOUT,check=True)
 module=w/'graphite-server';actual={str(p.relative_to(module)):sha(p) for p in module.rglob('*') if p.is_file()};assert actual==(base if phase=='base' else candidate),phase
 if phase!='base':
  for p,v in complete.items():assert sha(w/p)==v['sha256'],p
clones={phase:'/tmp/graphite-context-'+phase+'-real64-b7bb15a2' for phase in ['native','http']}
for dest in clones.values():
 assert not Path(dest).exists();subprocess.run(['/bin/cp','-cRp','/tmp/pr113-exp037-fixture.nXn4fg',dest],check=True)
old=repo/'docs/go-server-baseline/native64-label-alias-attempt16';cfg=json.loads((old/'config.json').read_text());assert len(cfg['queries'])==21
for g in cfg['graphs']:g['path']=clones['native']+'/'+g['id']
for name in ['run-profile.py','profile-query.go','server-profile-export.go','store-profile-state.go']:shutil.copyfile(old/name,r/name)
(r/'config.json').write_text(json.dumps(cfg,indent=2)+'\n');(r/'tested-source.json').write_text(json.dumps(candidate,indent=2)+'\n');(r/'base-source.json').write_text(json.dumps(base,indent=2)+'\n')
(r/'preparation.json').write_text(json.dumps({'createdUTC':datetime.datetime.now(datetime.timezone.utc).isoformat(),'base':'b7bb15a2','worktrees':worktrees,'clones':clones,'components':components,'all2436CandidateInputsMatchFullModuleTestedCombination':True,'externalInputsAlsoMatch':True,'base2434InputsMatchCommittedA16':True,'knownCoTenancy':'AllthreeagentsconfirmedCPUheavyexecutionstoppedbeforepair; externalhostactivitynotcontrolled.'},indent=2)+'\n')
shutil.copyfile('/tmp/prepare-graphite-a17-root-b7bb15a2.py',r/'prepare.py')
print('A17 verified/prepared: 2434 base,2436 candidate inputs, actual21 config; starting paired runs',flush=True)
results=[]
for phase in ['base','candidate']:
 argv=['python3',str(r/'run-profile.py'),'--worktree',worktrees[phase],'--out',str(r/phase)]
 with (r/(phase+'-runner.log')).open('w') as log:p=subprocess.run(argv,stdout=log,stderr=subprocess.STDOUT)
 results.append({'phase':phase,'argv':argv,'exitCode':p.returncode,'finishedUTC':datetime.datetime.now(datetime.timezone.utc).isoformat()});(r/'run-results.json').write_text(json.dumps(results,indent=2)+'\n');assert p.returncode==0,phase
 print(phase,'completed with all21 full responses',flush=True)
