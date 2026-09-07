from pathlib import Path
import datetime, hashlib, json, shutil, subprocess

repo=Path('/Users/johnsonlee/.codex/worktrees/112399f5-4ea0-42da-af34-5ab6ef682c3d/graphite')
out=repo/'docs/go-server-baseline/native64-atom-predicate-attempt19'
out.mkdir()
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
components=[]
for src,dest,expected in [('/tmp/graphite-atom-attempt19-evidence','provider','46e5e952f4323e7a31e766636b0c7015837f8ce1b1f916ac2f82237e4a863ad7'),('/tmp/graphite-a17-dense-allocation-audit','diagnosis','12ec5886143f85ae9ff94c0af236bd2381b13e2e16dbf4d0184ee7817a7bda3e')]:
    src=Path(src);manifest=src/'manifest.json';assert sha(manifest)==expected
    data=json.loads(manifest.read_text());files=data['files'];entries=files if isinstance(files,list) else [dict(v,path=k) if isinstance(v,dict) else {'path':k,'sha256':v} for k,v in files.items()]
    dest=out/dest;dest.mkdir()
    for entry in entries:
        p=src/entry['path'];assert sha(p)==entry['sha256'],p
        q=dest/entry['path'];q.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(p,q)
    shutil.copyfile(manifest,dest/'manifest.json')
    components.append({'component':dest.name,'manifestSHA256':expected,'allFilesRehashedAndCopied':len(entries)})
old=repo/'docs/go-server-baseline/native64-lazy-slot-attempt18'
base=json.loads((old/'tested-source.json').read_text());assert len(base)==2437
for name,h in base.items():assert sha(repo/'graphite-server'/name)==h,name
candidate=dict(base)
for item in json.loads((out/'provider/changed-files.json').read_text()):candidate[item['path'].removeprefix('graphite-server/')]=item['sha256']
assert len(candidate)==2439
patch=out/'provider/attempt19.patch';assert sha(patch)=='dfadcbdadfc32ff638bfcce71b0774e60cdf60241e09e710d2c1512cb60c0dfc'
worktrees={phase:'/tmp/graphite-go-atom-'+phase+'-39eedb33' for phase in ['base','candidate','http']}
for phase,dest in worktrees.items():
    w=Path(dest);assert not w.exists()
    with (out/(phase+'-worktree.log')).open('w') as log:
        subprocess.run(['git','worktree','add','--detach','--no-checkout',str(w),'39eedb33'],cwd=repo,stdout=log,stderr=subprocess.STDOUT,check=True)
        subprocess.run(['git','restore','--source=HEAD','--staged','--worktree','--','graphite-server','graphite-explore','CONVENTIONS.md'],cwd=w,stdout=log,stderr=subprocess.STDOUT,check=True)
        if phase!='base':
            subprocess.run(['git','apply','--check',str(patch)],cwd=w,stdout=log,stderr=subprocess.STDOUT,check=True)
            subprocess.run(['git','apply',str(patch)],cwd=w,stdout=log,stderr=subprocess.STDOUT,check=True)
    module=w/'graphite-server';actual={p.relative_to(module).as_posix():sha(p) for p in module.rglob('*') if p.is_file()}
    assert actual==(base if phase=='base' else candidate),phase
clones={phase:'/tmp/graphite-atom-'+phase+'-real64-39eedb33' for phase in ['native','http']}
for dest in clones.values():
    assert not Path(dest).exists();subprocess.run(['/bin/cp','-cRp','/tmp/pr113-exp037-fixture.nXn4fg',dest],check=True)
cfg=json.loads((old/'config.json').read_text());assert len(cfg['queries'])==21
for g in cfg['graphs']:g['path']=clones['native']+'/'+g['id']
for name in ['run-profile.py','profile-query.go','server-profile-export.go','store-profile-state.go','verify-pair.py']:
    shutil.copyfile(old/name,out/name)
(out/'config.json').write_text(json.dumps(cfg,indent=2)+'\n')
(out/'tested-source.json').write_text(json.dumps(candidate,indent=2)+'\n')
(out/'base-source.json').write_text(json.dumps(base,indent=2)+'\n')
(out/'preparation.json').write_text(json.dumps({'createdUTC':datetime.datetime.now(datetime.timezone.utc).isoformat(),'base':'39eedb33','worktrees':worktrees,'clones':clones,'components':components,'all2439CandidateInputsMatchRootPlusExactAuthorDelta':True,'independentIntegrationVerified':False,'performanceNotStarted':True},indent=2)+'\n')
http=out/'http-shipping';http.mkdir()
for name in ['build.py','launch.py','replay-http.py','verify.py']:
    text=(old/'http-shipping'/name).read_text().replace('fb4434df','39eedb33').replace('graphite-go-lazyslot-','graphite-go-atom-').replace('graphite-lazyslot-','graphite-atom-').replace('18883','18885').replace('18884','18886')
    (http/name).write_text(text)
httpcfg=json.loads(json.dumps(cfg))
for g in httpcfg['graphs']:g['path']=clones['http']+'/'+g['id']
(http/'config.json').write_text(json.dumps(httpcfg,indent=2)+'\n')
shutil.copyfile(old/'http-shipping/pagination-main.json',http/'pagination-main.json')
(out/'verify-http-fixtures.py').write_text((old/'verify-http-fixtures.py').read_text().replace('graphite-lazyslot-http-real64-fb4434df','graphite-atom-http-real64-39eedb33'))
shutil.copyfile(__file__,out/'prepare.py')
print('Prepared A19 source/worktrees/clones; no performance process started. Await independent integration and quiet acknowledgements.',flush=True)
