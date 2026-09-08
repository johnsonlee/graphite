"""Full real64 diagnostic profile from frozen source; never an acceptance run."""
import datetime
import difflib
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import time

BASE = Path(__file__).resolve().parent
ROOT = BASE.parents[3]
FROZEN = ROOT/'graphite-server'
EXTERNAL = Path('/Users/johnsonlee/.codex/benchmarks/graphite/regex-quoted-contains-profile-after-bc708-v1')
GO = Path('/opt/homebrew/Cellar/go/1.22.0/libexec/bin/go')

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()
def write(path, value):
    path.write_text(json.dumps(value, indent=2)+'\n')
def load_module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec); spec.loader.exec_module(mod); return mod

EXTERNAL.mkdir(exist_ok=False)
source = EXTERNAL/'source'
subprocess.run(['/bin/cp','-cRp',str(FROZEN),str(source)],check=True)
source_hashes={str(p.relative_to(FROZEN)):sha(p) for p in FROZEN.rglob('*') if p.is_file()}
assert all(sha(source/p)==h for p,h in source_hashes.items())
write(BASE/'frozen-source.json',dict(source=str(FROZEN),files=source_hashes))
main=source/'cmd/graphite-benchmark-latency/main.go';original=main.read_text()
old='response, elapsed, timedOut, err := r.measure(ctx, input)'
assert original.count(old)==1
changed=original.replace(old,'response, elapsed, timedOut, err := r.profileMeasure(ctx, input, c.ID)')
assert changed.count('"performanceMeasurement": true')==1
changed=changed.replace('"performanceMeasurement": true','"performanceMeasurement": false, "diagnosticProfiling": true, "allRunLatenciesProfileContaminated": true')
main.write_text(changed)
helper=source/'cmd/graphite-benchmark-latency/diagnostic_profile.go';helper.write_bytes((BASE/'diagnostic_profile.go').read_bytes())
subprocess.run([str(GO.with_name('gofmt')),'-w',str(main),str(helper)],check=True)
(BASE/'main-overlay.diff').write_text(''.join(difflib.unified_diff(original.splitlines(True),main.read_text().splitlines(True),fromfile='frozen/main.go',tofile='diagnostic/main.go')))
(BASE/'main-overlay.go').write_bytes(main.read_bytes())
(BASE/'helper-overlay.go').write_bytes(helper.read_bytes())
for name,h in source_hashes.items():
    if name!='cmd/graphite-benchmark-latency/main.go':assert sha(source/name)==h,name
env=json.loads(subprocess.check_output([str(GO),'env','-json'],cwd=source,text=True))
deps=subprocess.check_output([str(GO),'list','-deps','-json','./cmd/graphite-benchmark-latency'],cwd=source,text=True)
(BASE/'dependencies.jsonstream').write_text(deps)
paths=list(source.rglob('*.go'))+[source/'go.mod',source/'go.sum',source/'internal/benchmarkcase/testdata/main64.json',GO,Path(__file__),BASE/'diagnostic_profile.go']
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
inputs={str(p):sha(p) for p in sorted(set(paths)) if p.is_file()}
binary=EXTERNAL/'graphite-benchmark-profile'
command=[str(GO),'build','-o',str(binary),'./cmd/graphite-benchmark-latency']
with (BASE/'build.log').open('x') as log:subprocess.run(command,cwd=source,stdout=log,stderr=subprocess.STDOUT,check=True)
assert all(sha(Path(p))==h for p,h in inputs.items());inputs[str(binary)]=sha(binary)
write(BASE/'inputs.json',inputs)
write(BASE/'build.json',dict(source=str(source),frozenSource=str(FROZEN),nativeBinary=str(binary),binarySHA256=sha(binary),command=command,goEnvironment=env,internalFilesUnchanged=True,changedFrozenFiles=['cmd/graphite-benchmark-latency/main.go'],addedCommandFile='diagnostic_profile.go',diagnosticProfiling=True,performanceMeasurement=False))
fixtures=load_module('profile_fixture_audit',BASE.parents[1]/'native64-fullcase-replay/audit-fixtures.py')
reference=Path(json.loads((BASE.parents[1]/'native64-fullcase-replay/main-cold-preflight.json').read_text())['reference'])
def audit(path,name):
    result=fixtures.audit(path);write(BASE/name,result)
    assert result['matched']==result['originalFiles']==1152 and not any(result[k] for k in ['changed','missing','added'])
audit(reference,'reference-preflight.json')
clone=EXTERNAL/'fixture';subprocess.run(['/bin/cp','-cRp',str(reference),str(clone)],check=True)
audit(clone,'clone-preflight.json')
lines=[]
for line in (clone/'graphs.tsv').read_text().splitlines():
    if not line.strip() or line.lstrip().startswith('#'):lines.append(line);continue
    fields=line.split('\t');assert len(fields)==6;fields[1]=str(clone/fields[0]);lines.append('\t'.join(fields))
manifest=BASE/'graphs-relocated.tsv';manifest.write_text('\n'.join(lines)+'\n')
profiles=BASE/'profiles';profiles.mkdir()
runtime_env=os.environ.copy();runtime_env['GRAPHITE_DIAGNOSTIC_PROFILE_DIR']=str(profiles)
command=[str(binary),'--graphs',str(manifest),'--state','cold','--workload',str(source/'internal/benchmarkcase/testdata/main64.json'),'--output',str(BASE/'observations.jsonl')]
receipt=dict(command=command,status='starting',diagnosticProfiling=True,performanceMeasurement=False,allRunLatenciesProfileContaminated=True,clone=str(clone),inputsSHA256=sha(BASE/'inputs.json'),binarySHA256=sha(binary),manifestSHA256=sha(manifest),mayOverlapOtherCorrectnessWork=True,environment={k:runtime_env.get(k) for k in ['GOGC','GOMEMLIMIT','GOMAXPROCS','GODEBUG','GRAPHITE_DIAGNOSTIC_PROFILE_DIR']})
(BASE/'host-before.txt').write_text(subprocess.check_output(['ps','-axo','pid,pcpu,pmem,comm'],text=True))
with (BASE/'process.log').open('x') as log:
    child=subprocess.Popen(command,stdout=log,stderr=subprocess.STDOUT,env=runtime_env,cwd=source)
    receipt.update(status='running',pid=child.pid,startedAtNanos=time.time_ns(),startedAt=datetime.datetime.now(datetime.timezone.utc).isoformat());write(BASE/'process.json',receipt);print('diagnostic child PID',child.pid,flush=True)
    code=child.wait()
receipt.update(status='exited',exitCode=code,finishedAtNanos=time.time_ns(),finishedAt=datetime.datetime.now(datetime.timezone.utc).isoformat())
(BASE/'host-after.txt').write_text(subprocess.check_output(['ps','-axo','pid,pcpu,pmem,comm'],text=True))
assert all(sha(Path(p))==h for p,h in inputs.items());assert sha(manifest)==receipt['manifestSHA256'];receipt['inputsUnchanged']=True
audit(clone,'postrun-fixtures.json');receipt['fixtureAuditPassed']=True;write(BASE/'process.json',receipt)
assert code==1,'Expected original all-success gate failure'
print('diagnostic process terminal',code,flush=True)
