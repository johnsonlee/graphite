"""Full real64 diagnostic profile from frozen source; never an acceptance run."""
import argparse
import datetime
import difflib
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import time
import sys
sys.dont_write_bytecode = True

SCRIPT_BASE = Path(__file__).resolve().parent
ROOT = SCRIPT_BASE.parents[3]
EVIDENCE_ROOT = ROOT/'docs/go-server-baseline'
GO = Path('/opt/homebrew/Cellar/go/1.22.0/libexec/bin/go')
SELECTED = ['global-six-or-zero','global-six-or-targeted','global-six-or-dense','global-six-or-wrapped-zero','global-six-or-wrapped-targeted','global-six-or-wrapped-dense']
CONFIG = {
 'profile-before': (Path('/Users/johnsonlee/.codex/benchmarks/graphite/generic-string-go-baseline-513e2b96-v1/module'), EVIDENCE_ROOT/'native-generic-string-disjunction/go-baseline/compiled-source.json', '513e2b96d4cd5136560461d06f3b804e6aa67c41'),
 'profile-after': (Path('/Users/johnsonlee/.codex/benchmarks/graphite/generic-string-acb06574-build-v1/source'), Path('/Users/johnsonlee/.codex/benchmarks/graphite/generic-string-acb06574-build-v1/module-source.json'), 'acb06574'),
}
FROZEN, EXPECTED_SOURCE_MANIFEST, SOURCE_REVISION = CONFIG[SCRIPT_BASE.name]
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output',type=Path,required=True,help='Fresh absolute external output directory; never reused')
BASE=parser.parse_args().output.resolve()
if BASE == FROZEN or FROZEN in BASE.parents:
    parser.error('Output must be outside the immutable source')
EXTERNAL=BASE

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()
def write(path, value):
    path.write_text(json.dumps(value, indent=2)+'\n')
def load_module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec); spec.loader.exec_module(mod); return mod

EXTERNAL.mkdir(parents=True,exist_ok=False)
expected_source=json.loads(EXPECTED_SOURCE_MANIFEST.read_text())
if isinstance(expected_source,list):expected_source={x['path']:x['sha256'] for x in expected_source}
actual_source={str(p.relative_to(FROZEN)):sha(p) for p in FROZEN.rglob('*') if p.is_file()}
assert actual_source==expected_source,'Frozen source differs from its authoritative full-module manifest'
workload=json.loads((FROZEN/'internal/benchmarkcase/testdata/main64.json').read_text())
assert len(workload['cases'])==1267 and [workload['cases'][i]['id'] for i in range(883,889)]==SELECTED
write(BASE/'selected-cases.json',[dict(index=i,case=workload['cases'][i]) for i in range(883,889)])
source = EXTERNAL/'source'
subprocess.run(['/bin/cp','-cRp',str(FROZEN),str(source)],check=True)
source_hashes={str(p.relative_to(FROZEN)):sha(p) for p in FROZEN.rglob('*') if p.is_file()}
assert all(sha(source/p)==h for p,h in source_hashes.items())
write(BASE/'frozen-source.json',dict(source=str(FROZEN),revision=SOURCE_REVISION,expectedSourceManifest=str(EXPECTED_SOURCE_MANIFEST),expectedSourceManifestSHA256=sha(EXPECTED_SOURCE_MANIFEST),files=source_hashes))
main=source/'cmd/graphite-benchmark-latency/main.go';original=main.read_text()
old='response, elapsed, timedOut, err := r.measure(ctx, input)'
assert original.count(old)==1
changed=original.replace(old,'response, elapsed, timedOut, err := r.profileMeasure(ctx, input, c.ID)')
assert changed.count('"performanceMeasurement": true')==1
changed=changed.replace('"performanceMeasurement": true','"performanceMeasurement": false, "diagnosticProfiling": true, "allRunLatenciesProfileContaminated": true')
main.write_text(changed)
helper=source/'cmd/graphite-benchmark-latency/diagnostic_profile.go';helper.write_bytes((SCRIPT_BASE/'diagnostic_profile.go').read_bytes())
subprocess.run([str(GO.with_name('gofmt')),'-w',str(main),str(helper)],check=True)
(BASE/'main-overlay.diff').write_text(''.join(difflib.unified_diff(original.splitlines(True),main.read_text().splitlines(True),fromfile='frozen/main.go',tofile='diagnostic/main.go')))
(BASE/'main-overlay.go').write_bytes(main.read_bytes())
(BASE/'helper-overlay.go').write_bytes(helper.read_bytes())
for name,h in source_hashes.items():
    if name!='cmd/graphite-benchmark-latency/main.go':assert sha(source/name)==h,name
env=json.loads(subprocess.check_output([str(GO),'env','-json'],cwd=source,text=True))
deps=subprocess.check_output([str(GO),'list','-deps','-json','./cmd/graphite-benchmark-latency'],cwd=source,text=True)
(BASE/'dependencies.jsonstream').write_text(deps)
paths=list(source.rglob('*.go'))+[source/'go.mod',source/'go.sum',source/'internal/benchmarkcase/testdata/main64.json',GO,Path(__file__),SCRIPT_BASE/'diagnostic_profile.go',SCRIPT_BASE/'analyze-profile.py',SCRIPT_BASE/'archive-profile.py',EXPECTED_SOURCE_MANIFEST,EVIDENCE_ROOT/'native64-profile-a7de0bec/fixture-files.json',EVIDENCE_ROOT/'native64-fullcase-replay/audit-fixtures.py',EVIDENCE_ROOT/'native64-latency-pilot/verify-pilot.py']
decoder=json.JSONDecoder();position=0
while position<len(deps):
    if deps[position].isspace():position+=1;continue
    package,position=decoder.raw_decode(deps,position)
    assert not any(package.get(k) for k in ['CgoFiles','SwigFiles','SwigCXXFiles'])
    if package['ImportPath']=='unsafe':continue
    for key in ['GoFiles','SFiles','HFiles','CFiles','SysoFiles','EmbedFiles']:
        paths.extend(Path(package['Dir'])/p for p in package.get(key,[]))
    if package.get('Module',{}).get('GoMod'):paths.append(Path(package['Module']['GoMod']))
goroot=Path(env['GOROOT']);paths.append(goroot/'VERSION')
paths.extend(p for parent in ['pkg/tool','pkg/include'] for p in (goroot/parent).rglob('*') if p.is_file())
paths.extend(p for p in source.rglob('*') if p.is_file())
inputs={str(p):sha(p) for p in sorted(set(paths)) if p.is_file()}
build_source={str(p.relative_to(source)):sha(p) for p in source.rglob('*') if p.is_file()}
write(BASE/'build-source-inputs.json',build_source)
write(BASE/'inputs-before-build.json',inputs)
binary=EXTERNAL/'graphite-benchmark-profile'
command=[str(GO),'build','-o',str(binary),'./cmd/graphite-benchmark-latency']
with (BASE/'build.log').open('x') as log:subprocess.run(command,cwd=source,stdout=log,stderr=subprocess.STDOUT,check=True)
assert all(sha(Path(p))==h for p,h in inputs.items());inputs[str(binary)]=sha(binary)
write(BASE/'inputs.json',inputs)
write(BASE/'build.json',dict(source=str(source),frozenSource=str(FROZEN),nativeBinary=str(binary),binarySHA256=sha(binary),command=command,goEnvironment=env,internalFilesUnchanged=True,changedFrozenFiles=['cmd/graphite-benchmark-latency/main.go'],addedCommandFile='diagnostic_profile.go',diagnosticProfiling=True,performanceMeasurement=False))
fixtures=load_module('profile_fixture_audit',EVIDENCE_ROOT/'native64-fullcase-replay/audit-fixtures.py')
reference=Path(json.loads((EVIDENCE_ROOT/'native64-fullcase-replay/main-cold-preflight.json').read_text())['reference'])
def audit(path,name):
    result=fixtures.audit(path);write(BASE/name,result)
    assert result['matched']==result['originalFiles']==1152 and not any(result[k] for k in ['changed','missing','added'])
audit(reference,'reference-preflight.json')
clone=EXTERNAL/'fixture';subprocess.run(['/bin/cp','-cRp',str(reference),str(clone)],check=True)
audit(clone,'clone-preflight.json')
lines=[];source_order=[]
for line in (clone/'graphs.tsv').read_bytes().splitlines(keepends=True):
    body=line.rstrip(b'\r\n');ending=line[len(body):]
    if not body.strip() or body.lstrip().startswith(b'#'):lines.append(line);continue
    fields=body.split(b'\t');assert len(fields)==6
    graph_id=fields[0].decode();assert Path(graph_id).name==graph_id and graph_id not in ('.','..')
    target=clone/graph_id;assert clone.resolve() in target.resolve().parents and reference.resolve() not in target.resolve().parents
    source_order.append(graph_id);fields[1]=str(target).encode();lines.append(b'\t'.join(fields)+ending)
assert source_order==workload['sourceOrder']
manifest=BASE/'graphs-relocated.tsv';manifest.write_bytes(b''.join(lines))
profiles=BASE/'profiles';profiles.mkdir()
runtime_env=os.environ.copy();runtime_env['GRAPHITE_DIAGNOSTIC_PROFILE_DIR']=str(profiles)
command=[str(binary),'--graphs',str(manifest),'--state','cold','--workload',str(source/'internal/benchmarkcase/testdata/main64.json'),'--output',str(BASE/'observations.jsonl')]
receipt=dict(command=command,status='starting',diagnosticProfiling=True,performanceMeasurement=False,allRunLatenciesProfileContaminated=True,clone=str(clone),inputsSHA256=sha(BASE/'inputs.json'),binarySHA256=sha(binary),manifestSHA256=sha(manifest),mayOverlapOtherCorrectnessWork=False,serialBeforeAfterProtocol=True,environment={k:runtime_env.get(k) for k in ['GOGC','GOMEMLIMIT','GOMAXPROCS','GODEBUG','GRAPHITE_DIAGNOSTIC_PROFILE_DIR']})
(BASE/'host-before.txt').write_text(subprocess.check_output(['ps','-axo','pid,pcpu,pmem,comm'],text=True))
with (BASE/'process.log').open('x') as log:
    child=subprocess.Popen(command,stdout=log,stderr=subprocess.STDOUT,env=runtime_env,cwd=source)
    receipt.update(status='running',pid=child.pid,startedAtNanos=time.time_ns(),startedAt=datetime.datetime.now(datetime.timezone.utc).isoformat());write(BASE/'process.json',receipt);print('diagnostic child PID',child.pid,flush=True)
    code=child.wait()
receipt.update(status='exited',exitCode=code,finishedAtNanos=time.time_ns(),finishedAt=datetime.datetime.now(datetime.timezone.utc).isoformat())
(BASE/'host-after.txt').write_text(subprocess.check_output(['ps','-axo','pid,pcpu,pmem,comm'],text=True))
write(BASE/'process.json',receipt)
assert all(sha(Path(p))==h for p,h in inputs.items());assert sha(manifest)==receipt['manifestSHA256'];receipt['inputsUnchanged']=True
assert {str(p.relative_to(source)):sha(p) for p in source.rglob('*') if p.is_file()}==build_source
assert {str(p.relative_to(FROZEN)):sha(p) for p in FROZEN.rglob('*') if p.is_file()}==expected_source
receipt['completeModuleUnchanged']=True;receipt['frozenSourceUnchanged']=True
audit(clone,'postrun-fixtures.json');receipt['fixtureAuditPassed']=True;write(BASE/'process.json',receipt)
assert code==1,'Expected original all-success gate failure'
print('diagnostic process terminal',code,flush=True)

write(BASE/'controller.json',dict(status='terminal',exitCode=0,runtimeExitCode=code,performanceMeasurement=False,profilesSelected=SELECTED,fullOrderedCaseCount=1267))
