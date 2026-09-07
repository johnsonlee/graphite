from pathlib import Path
import json,hashlib,shutil,subprocess,datetime
repo=Path('/Users/johnsonlee/.codex/worktrees/112399f5-4ea0-42da-af34-5ab6ef682c3d/graphite');r=repo/'docs/go-server-baseline/native64-label-alias-attempt16';r.mkdir()
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
components=[]
for src,mp,dest,expected in [('/tmp/graphite-label-alias-attempt16-evidence','freeze/manifest.json','provider','f9fcb153796c725c79e4591ce05808bc7a8e94671d34ba2133d83d177a0da318'),('/tmp/graphite-go-a16-on-b-62b92d20/integration','manifest.json','integration','021051bbcfad0403bda0864037c94d4f263c4ba6f92be10940c4e43075ba4b06'),('/tmp/graphite-cde-e-regression-readonly-audit','manifest.json','diagnosis','1e83b404f4c9bd5ffda80b0be91432f848e858a7dc849e7ef1425c0c1f821273')]:
 src=Path(src);m=src/mp;assert sha(m)==expected;d=json.loads(m.read_text());files=d['files'];entries=files if isinstance(files,list) else [dict(v,path=k) if isinstance(v,dict) else {'path':k,'sha256':v} for k,v in files.items()];out=r/dest;out.mkdir()
 for v in entries:
  p=src/v['path'];assert sha(p)==v['sha256'],v['path'];q=out/v['path'];q.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(p,q)
 (out/mp).parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(m,out/mp);components.append({'component':dest,'manifestSHA256':expected,'allFilesRehashedAndCopied':len(entries)})
patch=r/'integration/attempt16.patch';assert sha(patch)=='b86c4e149afe22e083c06725475ffa0b383dc73570c037169afe3788e8dc4ee3'
complete=json.loads((r/'integration/candidate-inputs.json').read_text());candidate={k.removeprefix('graphite-server/'):v['sha256'] for k,v in complete.items() if k.startswith('graphite-server/')};assert len(candidate)==2434
base=json.loads((repo/'docs/go-server-baseline/native64-streaming-pagination-integration/tested-source.json').read_text());assert len(base)==2433
for p,h in base.items():assert sha(repo/'graphite-server'/p)==h,p
worktrees={phase:'/tmp/graphite-go-label-'+phase+'-4e94017f' for phase in ['base','candidate','http']}
for phase,dest in worktrees.items():
 w=Path(dest);assert not w.exists()
 with (r/(phase+'-worktree.log')).open('w') as log:
  subprocess.run(['git','worktree','add','--detach','--no-checkout',str(w),'4e94017f'],cwd=repo,stdout=log,stderr=subprocess.STDOUT,check=True)
  subprocess.run(['git','restore','--source=HEAD','--staged','--worktree','--','graphite-server','graphite-explore','CONVENTIONS.md'],cwd=w,stdout=log,stderr=subprocess.STDOUT,check=True)
  if phase!='base':
   subprocess.run(['git','apply','--check',str(patch)],cwd=w,stdout=log,stderr=subprocess.STDOUT,check=True)
   subprocess.run(['git','apply',str(patch)],cwd=w,stdout=log,stderr=subprocess.STDOUT,check=True)
 module=w/'graphite-server';actual={str(p.relative_to(module)):sha(p) for p in module.rglob('*') if p.is_file()};assert actual==(base if phase=='base' else candidate),phase
 if phase!='base':
  for p,v in complete.items():assert sha(w/p)==v['sha256'],p
clones={phase:'/tmp/graphite-label-'+phase+'-real64-4e94017f' for phase in ['native','http']}
for dest in clones.values():
 assert not Path(dest).exists();subprocess.run(['/bin/cp','-cRp','/tmp/pr113-exp037-fixture.nXn4fg',dest],check=True)
old=repo/'docs/go-server-baseline/native64-streaming-pagination-integration';cfg=json.loads((old/'config.json').read_text());assert len(cfg['queries'])==21
for g in cfg['graphs']:g['path']=clones['native']+'/'+g['id']
for name in ['run-profile.py','profile-query.go','server-profile-export.go','store-profile-state.go']:shutil.copyfile(old/name,r/name)
(r/'config.json').write_text(json.dumps(cfg,indent=2)+'\n');(r/'tested-source.json').write_text(json.dumps(candidate,indent=2)+'\n');(r/'base-source.json').write_text(json.dumps(base,indent=2)+'\n')
(r/'preparation.json').write_text(json.dumps({'createdUTC':datetime.datetime.now(datetime.timezone.utc).isoformat(),'base':'4e94017f','worktrees':worktrees,'clones':clones,'components':components,'all2434CandidateInputsMatchFullModuleTestedCombination':True,'externalInputsAlsoMatch':True,'base2433InputsMatchCommittedB':True,'knownCoTenancy':'AllthreeagentsconfirmedCPUheavyexecutionstoppedbeforepair; externalhostactivitynotcontrolled.'},indent=2)+'\n')
shutil.copyfile('/tmp/prepare-graphite-a16-root-4e94017f.py',r/'prepare.py')
print('A16 verified/prepared: 2433 base,2434 candidate inputs, actual21 config; starting paired runs',flush=True)
results=[]
for phase in ['base','candidate']:
 argv=['python3',str(r/'run-profile.py'),'--worktree',worktrees[phase],'--out',str(r/phase)]
 with (r/(phase+'-runner.log')).open('w') as log:p=subprocess.run(argv,stdout=log,stderr=subprocess.STDOUT)
 results.append({'phase':phase,'argv':argv,'exitCode':p.returncode,'finishedUTC':datetime.datetime.now(datetime.timezone.utc).isoformat()});(r/'run-results.json').write_text(json.dumps(results,indent=2)+'\n');assert p.returncode==0,phase
 print(phase,'completed with all21 full responses',flush=True)
