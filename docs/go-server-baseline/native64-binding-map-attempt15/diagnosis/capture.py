from pathlib import Path
import json,subprocess,hashlib,shutil,datetime
root=Path(__file__).parent
source=Path('/Users/johnsonlee/.codex/worktrees/112399f5-4ea0-42da-af34-5ab6ef682c3d/graphite/docs/go-server-baseline/native64-main-source-integration')
worktrees={'base':Path('/tmp/graphite-go-main-source-base-10236487/graphite-server'),'candidate':Path('/tmp/graphite-go-main-source-root-10236487/graphite-server')}
names=['wrapped-firstLastGraphBimodalClassPrefix-repeat','global-wide-wrapped-case-insensitive-distinct-dense-repeat']
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
inputs={};binary=[];summaries=[]
def copy(src,dst):
 dst.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(src,dst);inputs[str(src)]={'sha256':sha(src),'bytes':src.stat().st_size,'copy':str(dst.relative_to(root))}
for mode in ['base','candidate']:
 manifest=json.loads((source/mode/'source-manifest.json').read_text());byname={x['path']:x['sha256'] for x in manifest}
 identity=json.loads((source/mode/'identity.json').read_text());exe=Path(identity['command'][0]);entry={'mode':mode,'path':str(exe),'recordedSHA256':identity['binarySHA256'],'available':exe.is_file()}
 if exe.is_file():entry['actualSHA256']=sha(exe);assert entry['actualSHA256']==entry['recordedSHA256']
 binary.append(entry)
 for name in ['identity.json','source-manifest.json','completion.json','binary-build-info.txt']:
  copy(source/mode/name,root/'inputs'/mode/name)
 for name in names:
  directory=source/mode/'profiles'/name
  for file in ['receipt.json','query.json','projection-state-before.json','projection-state-after.json','heap-before.pprof','heap-after.pprof']:
   copy(directory/file,root/'inputs'/mode/name/file)
  r=json.loads((directory/'receipt.json').read_text());summaries.append({'mode':mode,'name':name,'seconds':r['executeAndMarshalSecondsDiagnostic'],'deltas':r['requestDeltas'],'fullOutputMatchesExpected':r['fullOutputMatchesExpected'],'outputSha256':r['outputSha256'],'query':r['query']})
 for file in ['internal/query/generic_distinct.go','internal/query/eval.go','internal/query/candidate.go','internal/query/indexed_distinct_plan.go','internal/query/main_string_source.go','internal/query/main_string_candidates.go','internal/query/main_string_postings.go']:
  f=worktrees[mode]/file
  if f.exists():assert sha(f)==byname[file],(mode,file);copy(f,root/'sources'/mode/file)
 copy(source/mode/'profiles/runtime.json',root/'inputs'/mode/'runtime.json')
commands=[]
for mode in ['base','candidate']:
 for name in names:
  d=root/'inputs'/mode/name
  cmd=['go','tool','pprof','-sample_index=alloc_space','-top','-nodecount=20','-base',str(d/'heap-before.pprof'),str(d/'heap-after.pprof')]
  commands.append((mode+'-'+name+'-alloc-space-top',cmd))
d=root/'inputs/candidate'/names[0]
for kind in ['alloc_space','alloc_objects']:
 commands.append(('candidate-prefix-'+kind+'-line',['go','tool','pprof','-sample_index='+kind,'-list','genericDistinctScanner.*next','-base',str(d/'heap-before.pprof'),str(d/'heap-after.pprof')]))
receipts=[]
for name,argv in commands:
 completed=subprocess.run(argv,capture_output=True,text=True);(root/(name+'.stdout')).write_text(completed.stdout);(root/(name+'.stderr')).write_text(completed.stderr);receipts.append({'name':name,'argv':argv,'exit':completed.returncode});assert completed.returncode==0
(root/'commands.json').write_text(json.dumps(receipts,indent=2)+'\n')
(root/'inputs.json').write_text(json.dumps({'capturedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'files':inputs,'binaries':binary,'sourceFilesCheckedAgainstRecordedManifests':True,'profileType':'allocation only; never CPU profile'},indent=2)+'\n')
(root/'observations.json').write_text(json.dumps(summaries,indent=2)+'\n')
print('six read-only pprof commands exit0; binary availability',binary)
