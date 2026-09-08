import os,json,hashlib,subprocess,datetime,shutil
from pathlib import Path
out=Path(__file__).resolve().parent
root=Path('/Users/johnsonlee/.codex/worktrees/112399f5-4ea0-42da-af34-5ab6ef682c3d/graphite')
module=root/'graphite-server'
pending=Path('/Users/johnsonlee/.codex/benchmarks/graphite/persisted-work-f0838dda-annotation-pending-v1')
go='/opt/homebrew/Cellar/go/1.22.0/libexec/bin/go'
env=os.environ.copy();env['GOTOOLCHAIN']='local'
def write(name,obj): (out/name).write_text(json.dumps(obj,indent=2)+'\n')
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
receipt=json.loads((pending/'receipt.json').read_text())
for row in receipt['files']:
 p=module/row['path'];assert sha(p)==row['sha256'],row
 assert p.read_bytes()==(pending/'candidate'/row['path']).read_bytes(),row
inputs=[module/p for p in ['internal/store/projection_node.go','internal/store/projection_annotation_test.go','internal/store/projection_enum_test.go','internal/store/decode.go','internal/store/main_ordinary_entry.go','internal/query/generic_disjunction_test.go','go.mod','go.sum']]
inputs += [root/'docs/go-server-baseline/native-generic-string-disjunction'/p for p in ['main.json','repeat-main.json.gz','fixtures.tar.gz']]
inputs += [Path(go), pending/'before/internal/store/projection_node.go']
before={str(p):sha(p) for p in inputs};write('inputs-before.json',before)
for row in receipt['files']:
 target=out/'source'/row['path'];target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(module/row['path'],target)
write('baseline-overlay.json',{'Replace':{str(module/'internal/store/projection_node.go'):str(pending/'before/internal/store/projection_node.go')}})
commands=[('version',[go,'version'],0),('baseline',[go,'test','-race','-overlay',str(out/'baseline-overlay.json'),'./internal/store','-run','^TestProjectionAnnotation','-count=1','-v'],1),('candidate',[go,'test','-race','./internal/store','-run','^Test(ProjectionAnnotation|ProjectionEnum|ProjectionContext|IntegrationProjectionCandidateCloseAndOwnedValues)','-count=3','-v'],0),('public',[go,'test','-race','./internal/query','-run','^TestGenericDisjunctionMainReadSemantics/annotation-bad-tail','-count=1','-v'],0)]
write('commands.json',{'cwd':str(module),'environmentOverrides':{'GOTOOLCHAIN':'local'},'commands':[{'stage':n,'argv':c,'expectedExit':e}for n,c,e in commands]})
stages=[]
for name,command,expected in commands:
 record={'stage':name,'startedAtUTC':datetime.datetime.now(datetime.timezone.utc).isoformat(),'command':command}
 with (out/(name+'.log')).open('x') as log:
  p=subprocess.run(command,cwd=module,env=env,stdout=log,stderr=subprocess.STDOUT)
 record.update(exitCode=p.returncode,endedAtUTC=datetime.datetime.now(datetime.timezone.utc).isoformat());stages.append(record);write('stages.json',stages)
 print(name,p.returncode,flush=True)
 if p.returncode!=expected:break
after={str(p):sha(p) for p in inputs};write('inputs-after.json',after)
write('receipt.json',{'stages':stages,'inputsUnchanged':before==after,'partialSourceSnapshotOnly':True,'noFullModuleChecks':True,'noPerformanceRuns':True,'baselineIsExpectedFailure':True})
assert before==after
assert len(stages)==4 and all(s['exitCode']==c[2]for s,c in zip(stages,commands))
