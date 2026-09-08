#!/usr/bin/env python3
"""Archive two completed correctness captures; does not execute or edit the engine."""
import gzip,hashlib,json,pathlib,shutil,subprocess
HERE=pathlib.Path(__file__).resolve().parent
ORACLE=HERE.parent
ROOT=HERE.parents[3]
BASE=pathlib.Path('/Users/johnsonlee/.codex/benchmarks/graphite')
def sha(b):return hashlib.sha256(b).hexdigest()
def dump(p,v):p.write_text(json.dumps(v,indent=2)+'\n')
def rows(p):return json.loads(p.read_text())
def sources(p):return {v['path']:v for v in rows(p)}
baseline=sources(ORACLE/'go-baseline/source-before.json')
identity=rows(HERE/'frozen-candidate.json')
for p in identity['production']:
 assert sha((ROOT/'graphite-server'/p['path']).read_bytes())==p['candidateSHA256']
 assert sha(subprocess.check_output(['git','show',identity['head']+':graphite-server/'+p['path']],cwd=ROOT))==p['baselineSHA256']
records=[]
for version in ('v1','v2'):
 source=BASE/('generic-string-candidate-513e2b96-'+version);capture=source/'capture';out=HERE/version;out.mkdir(exist_ok=True)
 for name in ('inputs.json','input.json','source-before.json','compiled-source.json','source-after.json','source.tar.gz','compile.stdout','compile.stderr','capture.stdout','capture.stderr','commands.json','go.json','comparison.json','artifact-manifest.json'):
  shutil.copy2(capture/name,out/name)
 for name in ('fixtures-before.json','fixtures-after.json','diagnostic.test'):
  (out/(name+'.gz')).write_bytes(gzip.compress((capture/name).read_bytes(),mtime=0))
 for name in ('controller.json','controller.log'):
  if (source/name).exists():shutil.copy2(source/name,out/name)
 if version=='v2':dump(out/'controller.json',{'exitCode':1,'observedTerminalSession':76258,'captureAndCompileExitCode':0,'receiptSource':'Unified exec terminal result, subsequently independently recomputed by verify.py','performanceMeasurements':0})
 for entry in rows(capture/'inputs.json'):
  p=pathlib.Path(entry['path']);assert p.stat().st_size==entry['size'] and sha(p.read_bytes())==entry['sha256']
 pre=sources(capture/'source-before.json');compiled=sources(capture/'compiled-source.json');post=sources(capture/'source-after.json');assert compiled==post
 for name,entry in post.items():
  p=source/'module'/name;assert p.stat().st_size==entry['size'] and sha(p.read_bytes())==entry['sha256']
 changed=[name for name in sorted(set(baseline)&set(pre))if baseline[name]!=pre[name]];added=sorted(set(pre)-set(baseline));missing=sorted(set(baseline)-set(pre))
 assert changed==['internal/query/indexed_distinct.go','internal/query/main_string_candidates.go']
 assert added==([] if version=='v1' else ['internal/query/generic_disjunction_test.go']) and missing==[]
 if version=='v2':
  for entry in identity['production']:assert pre[entry['path']]['sha256']==entry['candidateSHA256']
 records.append({'version':version,'head':identity['head'],'moduleFilesBeforeHelper':len(pre),'compiledModuleFiles':len(compiled),'changedFromBaseline':changed,'addedFromBaseline':added,'missingFromBaseline':missing,'allExplicitInputsUnchanged':True,'compiledModuleUnchanged':True,'production':[pre[name]for name in changed]})
dump(HERE/'source-audit.json',records)
for name in ('run.py','replay_test.go'):shutil.copy2(ORACLE/'go-baseline'/name,HERE/name)
# Compare source baseline dependency identities against final candidate inputs;
# compiler and external dependency files are unchanged; workspace source differences are audited above.
deps=rows(ORACLE/'go-baseline/dependency-inputs.json');external=[]
for x in deps['files']:
 if '/generic-string-go-baseline-513e2b96-v1/module/' not in x['path']:
  p=pathlib.Path(x['path']);assert p.stat().st_size==x['size'] and sha(p.read_bytes())==x['sha256'];external.append(x)
dump(HERE/'external-dependencies.json',{'source':'../go-baseline/dependency-inputs.json','recordedAfterCandidateBuild':True,'files':external})
print(json.dumps(records,indent=2))
