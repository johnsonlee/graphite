from pathlib import Path
import argparse, datetime, hashlib, json, shutil, subprocess

parser=argparse.ArgumentParser()
parser.add_argument('--integration',type=Path,required=True)
parser.add_argument('--manifest-sha256',required=True)
args=parser.parse_args()
repo=Path('/Users/johnsonlee/.codex/worktrees/112399f5-4ea0-42da-af34-5ab6ef682c3d/graphite')
out=repo/'docs/go-server-baseline/native64-atom-predicate-attempt19'
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
manifest=args.integration/'manifest.json';assert sha(manifest)==args.manifest_sha256
data=json.loads(manifest.read_text());files=data['files'];entries=files if isinstance(files,list) else [dict(v,path=k) if isinstance(v,dict) else {'path':k,'sha256':v} for k,v in files.items()]
dest=out/'integration';dest.mkdir()
for v in entries:
    p=args.integration/v['path'];assert sha(p)==v['sha256'],p
    q=dest/v['path'];q.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(p,q)
shutil.copyfile(manifest,dest/'manifest.json')
complete=json.loads((dest/'candidate-inputs.json').read_text())
expected=json.loads((out/'tested-source.json').read_text())
measured={k.removeprefix('graphite-server/'):v['sha256'] for k,v in complete.items() if k.startswith('graphite-server/')}
assert measured==expected and len(expected)==2439
prep=json.loads((out/'preparation.json').read_text())
for phase in ['candidate','http']:
    w=Path(prep['worktrees'][phase])
    for name,v in complete.items():assert sha(w/name)==v['sha256'],(phase,name)
prep.update({'independentIntegrationVerified':True,'all2498CombinedInputsMatchIndependentlyTestedCandidate':True,'performanceNotStarted':False,'knownCoTenancy':'All three known agents confirmed heavy execution terminal before pair; external host activity uncontrolled.'})
prep['components'].append({'component':'integration','manifestSHA256':args.manifest_sha256,'allFilesRehashedAndCopied':len(entries)})
(out/'preparation.json').write_text(json.dumps(prep,indent=2)+'\n')
shutil.copyfile(__file__,out/'run-pair.py')
results=[]
for phase in ['base','candidate']:
    argv=['python3',str(out/'run-profile.py'),'--worktree',prep['worktrees'][phase],'--out',str(out/phase)]
    started=datetime.datetime.now(datetime.timezone.utc).isoformat()
    with (out/(phase+'-runner.log')).open('w') as log:
        result=subprocess.run(argv,stdout=log,stderr=subprocess.STDOUT)
    results.append({'phase':phase,'argv':argv,'exitCode':result.returncode,'startedUTC':started,'finishedUTC':datetime.datetime.now(datetime.timezone.utc).isoformat()})
    (out/'run-results.json').write_text(json.dumps(results,indent=2)+'\n')
    assert result.returncode==0,phase
    print(phase,'completed all21 full responses',flush=True)
print('Both actual profile processes terminal0; ready for independent verifier and release of quiet window.',flush=True)
