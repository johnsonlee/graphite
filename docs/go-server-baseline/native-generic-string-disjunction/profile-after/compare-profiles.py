"""Compare two completed diagnostic captures; no statistical performance claim."""
import argparse
import hashlib
import json
from pathlib import Path

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--before',type=Path,required=True)
parser.add_argument('--after',type=Path,required=True)
parser.add_argument('--output',type=Path,required=True)
args=parser.parse_args()
assert not args.output.exists()
def read(p):return json.loads(p.read_text())
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def records(p):return [json.loads(line) for line in (p/'observations.jsonl').read_text().splitlines()]
a,b=args.before,args.after
pa,pb=read(a/'process.json'),read(b/'process.json')
assert pa['status']==pb['status']=='exited' and pa['exitCode']==pb['exitCode']==1
assert pa['finishedAtNanos']<=pb['startedAtNanos'],'Before and after profiling processes overlapped'
assert all(p['inputsUnchanged'] and p['fixtureAuditPassed'] and p['completeModuleUnchanged'] and p['frozenSourceUnchanged'] for p in [pa,pb])
for name in ('main-overlay.go','helper-overlay.go','selected-cases.json'):
 assert (a/name).read_bytes()==(b/name).read_bytes(),name
ra,rb=records(a),records(b)
assert len(ra)==len(rb)==1269
# Profile clocks/resource deltas are diagnostic; query identities/results remain exact.
# Every case field except its explicitly contaminated diagnostic clock is compared.
public_differences=[]
for x,y in zip(ra[1:-1],rb[1:-1]):
 differing=[k for k in sorted(set(x)|set(y)) if k!='latencyNanos' and (k in x,x.get(k))!=(k in y,y.get(k))]
 if differing:public_differences.append({'index':x.get('index'),'id':x.get('id'),'fields':differing,'before':x,'after':y})
assert not public_differences
sa,sb=read(a/'profile-summary.json'),read(b/'profile-summary.json')
profiles=[]
for x,y in zip(sa['profiles'],sb['profiles']):
 assert x['id']==y['id'] and x['index']==y['index']
 assert all(x[k]==y[k]for k in ['outcome','rowCount','digest'])
 profiles.append({'id':x['id'],'index':x['index'],'beforeTotalAllocDelta':x['totalAllocDelta'],'afterTotalAllocDelta':y['totalAllocDelta'],
                  'observedAllocationDeltaReductionFraction':1-y['totalAllocDelta']/x['totalAllocDelta'],
                  'beforeProcessCPUSeconds':x['processCPUSecondsDelta'],'afterProcessCPUSeconds':y['processCPUSecondsDelta'],
                  'beforeCPUSamples':x['cpu']['totalCount'],'afterCPUSamples':y['cpu']['totalCount'],
                  'beforeInclusiveGroups':x['cpu']['groups'],'afterInclusiveGroups':y['cpu']['groups']})
assert len(profiles)==6
source_a=read(a/'frozen-source.json')['files'];source_b=read(b/'frozen-source.json')['files']
changed=[p for p in sorted(set(source_a)&set(source_b))if source_a[p]!=source_b[p]]
report={'performanceMeasurement':False,'perCaseP95Available':False,'statisticalSpeedupClaim':False,
        'beforeProcessFinishedNanos':pa['finishedAtNanos'],'afterProcessStartedNanos':pb['startedAtNanos'],
        'processesDidNotOverlap':True,'commandAndHelperBytesIdentical':True,'full1267PublicResultsEqual':True,
        'originalFailureRetained':True,'originalFixtureFilesUnchangedEachRuntime':1152,
        'beforeSource':read(a/'frozen-source.json')['source'],'afterSource':read(b/'frozen-source.json')['source'],
        'frozenSourceChangedFiles':changed,'frozenSourceAddedFiles':sorted(set(source_b)-set(source_a)),
        'frozenSourceRemovedFiles':sorted(set(source_a)-set(source_b)),
        'inputs':{str(p):sha(p)for d in [a,b]for p in [d/'profile-summary.json',d/'process.json',d/'correctness.json',d/'selected-cases.json',d/'main-overlay.go',d/'helper-overlay.go',d/'frozen-source.json']},
        'profiles':profiles,
        'limitations':['Exactly one diagnostic invocation per source. No confidence interval or causal/statistical speedup estimate.',
                      'Every latency is profile-contaminated and excluded from acceptance P95.',
                      'MemStats and Rusage deltas are exact process-wide brackets including profiler/runtime/background work.',
                      'Inclusive CPU groups overlap; sampled alloc/heap differences can lag GC and include earlier query allocations.',
                      'Full ordered workload and all failures are retained; no synthetic graph, added forced GC, or warmup.']}
args.output.write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(profiles,indent=2))
