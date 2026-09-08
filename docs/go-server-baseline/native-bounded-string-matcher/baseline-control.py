#!/usr/bin/env python3
"""Restore only the two old callers in an isolated clone and require the public mismatch."""
from pathlib import Path
import subprocess,tempfile,hashlib,json,datetime
ROOT=Path(__file__).resolve().parent
REPO=ROOT.parents[2]
MODULE=REPO/'graphite-server'
BASE='1f5dd187e1142714dd3757e82d540b0c02628acb'
CALLERS=['internal/query/ordinary_leading.go','internal/query/main_string_source.go']
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def inventory(root):return {str(p.relative_to(root)):sha(p) for p in sorted(root.rglob('*')) if p.is_file() and (p.suffix=='.go' or p.name in ['go.mod','go.sum'])}
started=datetime.datetime.now(datetime.timezone.utc).isoformat()
original={f:sha(MODULE/f) for f in CALLERS}
testhash=sha(MODULE/'internal/query/bounded_string_matcher_test.go')
temp=Path(tempfile.mkdtemp(prefix='graphite-bounded-baseline-',dir='/tmp'))
copy=temp/'graphite-server'
commands=[]
def run(argv,cwd):
    r=subprocess.run(list(map(str,argv)),cwd=cwd,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
    commands.append({'argv':list(map(str,argv)),'cwd':str(cwd),'exitCode':r.returncode})
    return r
r=run(['/bin/cp','-cRp',MODULE,copy],REPO);r.check_returncode()
copied=inventory(copy);basehash={}
for f in CALLERS:
    data=subprocess.check_output(['git','show',BASE+':graphite-server/'+f],cwd=REPO)
    commands.append({'argv':['git','show',BASE+':graphite-server/'+f],'cwd':str(REPO),'exitCode':0})
    (copy/f).write_bytes(data);basehash[f]=hashlib.sha256(data).hexdigest()
frozen=inventory(copy)
assert sorted(f for f in copied if copied[f]!=frozen[f])==sorted(CALLERS)
assert sha(copy/'internal/query/bounded_string_matcher_test.go')==testhash
r=run(['go','test','./internal/query','-run','^TestBoundedMatcherPublicMain$','-count=1'],copy)
(ROOT/'baseline-control.log').write_text(r.stdout)
expected='count=64 mutation=caller-name-max repeat=0 error=IndexOutOfBoundsException want=ArrayIndexOutOfBoundsException'
root_unchanged={f:sha(MODULE/f) for f in CALLERS}==original
copy_unchanged=inventory(copy)==frozen
verified=r.returncode==1 and expected in r.stdout and root_unchanged and copy_unchanged
(ROOT/'baseline-control.json').write_text(json.dumps({'startedUtc':started,'endedUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'baseRevision':BASE,'isolatedModule':str(copy),'rootCallersSha256BeforeAndAfter':original,'rootCallersUnchanged':root_unchanged,'copiedBaselineCallerSha256':basehash,'copiedGoInputSha256':frozen,'copiedInputsUnchangedAfterTest':copy_unchanged,'publicTestSha256':testhash,'commands':commands,'testOutputSha256':sha(ROOT/'baseline-control.log'),'expectedMismatch':expected,'verified':verified,'performanceMeasurement':False},indent=2)+'\n')
assert verified,r.stdout
print('Verified isolated baseline negative control: public dense/list error mismatch; root callers unchanged.')
