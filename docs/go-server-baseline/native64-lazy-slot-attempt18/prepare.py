from pathlib import Path
import datetime, hashlib, json, shutil, subprocess

repo=Path('/Users/johnsonlee/.codex/worktrees/112399f5-4ea0-42da-af34-5ab6ef682c3d/graphite')
out=repo/'docs/go-server-baseline/native64-lazy-slot-attempt18'
out.mkdir()
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
components=[]
for src,dest,expected in [('/tmp/graphite-lazy-slot-attempt18-evidence','provider','5565f5e27c2fac6367ef1054fc34372110769c3671d80a5d341230893580db0b'),('/tmp/graphite-cde-slot-readonly-audit','diagnosis','988c4e1fe77d5c10f4bf19325549d148f0f149fb6e4d4f72dec91df571d7227a')]:
    src=Path(src);manifest=src/'manifest.json';assert sha(manifest)==expected
    data=json.loads(manifest.read_text());files=data['files'];entries=files if isinstance(files,list) else [dict(v,path=k) if isinstance(v,dict) else {'path':k,'sha256':v} for k,v in files.items()]
    dest=out/dest;dest.mkdir()
    for entry in entries:
        p=src/entry['path'];assert sha(p)==entry['sha256'],p
        q=dest/entry['path'];q.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(p,q)
    shutil.copyfile(manifest,dest/'manifest.json')
    components.append({'component':dest.name,'manifestSHA256':expected,'allFilesRehashedAndCopied':len(entries)})
old=repo/'docs/go-server-baseline/native64-projection-context-attempt17'
base=json.loads((old/'tested-source.json').read_text());assert len(base)==2436
for name,h in base.items():assert sha(repo/'graphite-server'/name)==h,name
candidate=dict(base)
for item in json.loads((out/'provider/changed-files.json').read_text()):candidate[item['path']]=item['sha256']
assert len(candidate)==2437
patch=out/'provider/attempt18.patch';assert sha(patch)=='259d1be1cef97757ec5a012f20d24c597c862e9c6b29d3840b34a268e973a26d'
worktrees={phase:'/tmp/graphite-go-lazyslot-'+phase+'-fb4434df' for phase in ['base','candidate','http']}
for phase,dest in worktrees.items():
    w=Path(dest);assert not w.exists()
    with (out/(phase+'-worktree.log')).open('w') as log:
        subprocess.run(['git','worktree','add','--detach','--no-checkout',str(w),'fb4434df'],cwd=repo,stdout=log,stderr=subprocess.STDOUT,check=True)
        subprocess.run(['git','restore','--source=HEAD','--staged','--worktree','--','graphite-server','graphite-explore','CONVENTIONS.md'],cwd=w,stdout=log,stderr=subprocess.STDOUT,check=True)
        if phase!='base':
            subprocess.run(['git','apply','--check',str(patch)],cwd=w,stdout=log,stderr=subprocess.STDOUT,check=True)
            subprocess.run(['git','apply',str(patch)],cwd=w,stdout=log,stderr=subprocess.STDOUT,check=True)
    module=w/'graphite-server';actual={p.relative_to(module).as_posix():sha(p) for p in module.rglob('*') if p.is_file()}
    assert actual==(base if phase=='base' else candidate),phase
clones={phase:'/tmp/graphite-lazyslot-'+phase+'-real64-fb4434df' for phase in ['native','http']}
for dest in clones.values():
    assert not Path(dest).exists();subprocess.run(['/bin/cp','-cRp','/tmp/pr113-exp037-fixture.nXn4fg',dest],check=True)
cfg=json.loads((old/'config.json').read_text());assert len(cfg['queries'])==21
for g in cfg['graphs']:g['path']=clones['native']+'/'+g['id']
for name in ['run-profile.py','profile-query.go','server-profile-export.go','store-profile-state.go','verify-pair.py']:
    shutil.copyfile(old/name,out/name)
(out/'config.json').write_text(json.dumps(cfg,indent=2)+'\n')
(out/'tested-source.json').write_text(json.dumps(candidate,indent=2)+'\n')
(out/'base-source.json').write_text(json.dumps(base,indent=2)+'\n')
(out/'preparation.json').write_text(json.dumps({'createdUTC':datetime.datetime.now(datetime.timezone.utc).isoformat(),'base':'fb4434df','worktrees':worktrees,'clones':clones,'components':components,'all2437CandidateInputsMatchRootPlusExactAuthorDelta':True,'independentIntegrationVerified':False,'performanceNotStarted':True},indent=2)+'\n')
http=out/'http-shipping';http.mkdir()
for name in ['build.py','launch.py','replay-http.py','verify.py']:
    text=(old/'http-shipping'/name).read_text().replace('b7bb15a2','fb4434df').replace('graphite-go-context-','graphite-go-lazyslot-').replace('graphite-context-','graphite-lazyslot-').replace('18881','18883').replace('18882','18884')
    (http/name).write_text(text)
httpcfg=json.loads(json.dumps(cfg))
for g in httpcfg['graphs']:g['path']=clones['http']+'/'+g['id']
(http/'config.json').write_text(json.dumps(httpcfg,indent=2)+'\n')
shutil.copyfile(old/'http-shipping/pagination-main.json',http/'pagination-main.json')
(out/'verify-http-fixtures.py').write_text((old/'verify-http-fixtures.py').read_text().replace('graphite-context-http-real64-b7bb15a2','graphite-lazyslot-http-real64-fb4434df'))
shutil.copyfile(__file__,out/'prepare.py')
print('Prepared A18 source/worktrees/clones; no performance process started. Await independent integration and quiet acknowledgements.',flush=True)
