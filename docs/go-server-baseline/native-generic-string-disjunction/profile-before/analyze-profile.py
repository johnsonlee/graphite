"""Read-only post-run profile analysis; latencies are contaminated diagnostics."""
import argparse
import csv
import gzip
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import re
import subprocess
import sys
sys.dont_write_bytecode=True

SCRIPT_BASE=Path(__file__).resolve().parent
EVIDENCE_ROOT=SCRIPT_BASE.parents[1]
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--directory',type=Path,required=True)
BASE=parser.parse_args().directory.resolve()
GO='/opt/homebrew/Cellar/go/1.22.0/libexec/bin/go'
def read(p):return json.loads(p.read_text())
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def write(p,v):p.write_text(json.dumps(v,indent=2)+'\n')
process=read(BASE/'process.json');build=read(BASE/'build.json')
assert process['status']=='exited' and process['exitCode']==1 and process['inputsUnchanged'] and process['fixtureAuditPassed']
inputs=read(BASE/'inputs.json');assert all(sha(Path(p))==v for p,v in inputs.items())
source=Path(build['source']);workload=read(source/'internal/benchmarkcase/testdata/main64.json')
path=EVIDENCE_ROOT/'native64-latency-pilot/verify-pilot.py'
spec=importlib.util.spec_from_file_location('profile_canonical_verify',path);original=importlib.util.module_from_spec(spec);spec.loader.exec_module(original)
reference,archive=original.reference_cases('cold',workload)
main_path=EVIDENCE_ROOT/'native64-p95-sampling/evidence/smoke-3b5ecae5-v1/cold-0001/main/capture/main-observations.tsv.gz'
main=list(csv.DictReader(io.StringIO(gzip.decompress(main_path.read_bytes()).decode()),delimiter='\t'))
records=[json.loads(line) for line in (BASE/'observations.jsonl').read_text().splitlines()]
assert len(records)==1269 and records[0]['diagnosticProfiling'] is True and records[0]['performanceMeasurement'] is False
assert records[0]['allRunLatenciesProfileContaminated'] is True and records[0]['workTrackingEnabled'] is True
verified=original.compare_cases(workload,reference,main,records[1:-1]);assert len(verified)==1267
write(BASE/'correctness.json',dict(passed=True,caseCount=1267,successCount=1266,originalFailureCount=1,timeoutCount=0,originalFailure=verified[821],canonicalReference=str(archive),canonicalReferenceSHA256=sha(archive),mainLedger=str(main_path),mainLedgerSHA256=sha(main_path),allRunLatenciesProfileContaminated=True,diagnosticProfiling=True,performanceMeasurement=False))
binary=build['nativeBinary'];commands=[]
def pprof(target,options,profile):
    command=[GO,'tool','pprof',*options,binary,str(profile)]
    result=subprocess.run(command,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,check=True)
    target.write_text(result.stdout);commands.append(dict(command=command,output=str(target),exitCode=result.returncode));return result.stdout
def attribution(raw):
    sample_text,loc_text=raw.split('Locations\n',1)
    loc_text=loc_text.split('Mappings\n',1)[0]
    locations={};current=None
    for line in loc_text.splitlines():
        match=re.match(r'\s*(\d+):',line)
        if match:current=int(match.group(1));locations[current]=line
        elif current is not None:locations[current]+=' '+line
    samples=[]
    header=sample_text.split('Samples:\n',1)[1].splitlines()[0].replace('[dflt]','').split()
    for line in sample_text.splitlines():
        match=re.match(r'\s*((?:-?\d+\s+)+-?\d+):\s+([0-9 ]+)',line)
        if match:samples.append(([int(v) for v in match.group(1).split()],[int(v) for v in match.group(2).split()]))
    index=header.index('cpu/nanoseconds') if 'cpu/nanoseconds' in header else header.index('alloc_space/bytes')
    count_index=header.index('samples/count') if 'samples/count' in header else header.index('alloc_objects/count')
    total=sum(v[index] for v,stack in samples);count=sum(v[count_index] for v,stack in samples)
    groups={'ProjectionCandidateNode':lambda t:'.(*Store).ProjectionCandidateNode ' in t,'ProjectionPropertyStringID':lambda t:'.(*Store).ProjectionPropertyStringID ' in t,'ProjectionArrayString':lambda t:'.(*Store).ProjectionArrayString ' in t,'genericStringCandidates':lambda t:'.mainGenericStringCandidates' in t or '.mainStringCandidatesExcludingCallSites' in t,'distinctAtomMatches':lambda t:'.distinctAtomMatches ' in t,'stringCase':lambda t:'/internal/javastring.Case ' in t,'GCStacks':lambda t:any(s in t for s in ['runtime.gcDrain ','runtime.gcAssistAlloc ','runtime.gcBgMarkWorker '])}
    out={}
    for name,predicate in groups.items():
        chosen=[(v,stack) for v,stack in samples if any(predicate(locations[loc]) for loc in stack)]
        value=sum(v[index] for v,stack in chosen);n=sum(v[count_index] for v,stack in chosen)
        out[name]={'value':value,'count':n,'fraction':value/total if total else None}
    return {'valueType':header[index],'countType':header[count_index],'totalValue':total,'totalCount':count,'groups':out,'note':'Inclusive stack attribution; each group counts a sample once. Groups overlap and must not be summed. Memory snapshot deltas can include earlier-query allocations published by a later GC and omit recent allocations; these are not exact per-query allocation attribution.'}
summaries=[]
for id in ['global-six-or-zero', 'global-six-or-targeted', 'global-six-or-dense', 'global-six-or-wrapped-zero', 'global-six-or-wrapped-targeted', 'global-six-or-wrapped-dense']:
    folder=BASE/'profiles'/id;cpu=folder/'cpu.pprof';before=folder/'before-allocs.pprof';after=folder/'after-allocs.pprof'
    raw=pprof(folder/'cpu-raw.txt',['-raw'],cpu)
    pprof(folder/'cpu-top.txt',['-top','-nodefraction=0','-nodecount=100'],cpu)
    pprof(folder/'cpu-cum.txt',['-top','-cum','-nodefraction=0','-nodecount=100'],cpu)
    alloc=pprof(folder/'alloc-delta-raw.txt',['-raw','-sample_index=alloc_space','-base',str(before)],after)
    pprof(folder/'alloc-delta-top.txt',['-top','-sample_index=alloc_space','-base',str(before),'-nodefraction=0','-nodecount=100'],after)
    pprof(folder/'alloc-delta-cum.txt',['-top','-cum','-sample_index=alloc_space','-base',str(before),'-nodefraction=0','-nodecount=100'],after)
    for phase in ['before','after']:
        pprof(folder/(phase+'-heap-top.txt'),['-top','-sample_index=inuse_space','-nodecount=60'],folder/(phase+'-heap.pprof'))
    counter=read(folder/'counters.json')
    def seconds(v):return v['Sec']+v['Usec']/1e6
    cpu_delta=sum(seconds(counter['rusageAfter'][k])-seconds(counter['rusageBefore'][k]) for k in ['Utime','Stime'])
    rec=next(r for r in records[1:-1] if r['id']==id)
    summaries.append(dict(id=id,index=rec['index'],outcome=rec['outcome'],rowCount=rec['rowCount'],digest=rec['digest'],diagnosticMeasureNanos=rec['latencyNanos'],totalAllocDelta=counter['totalAllocDelta'],mallocsDelta=counter['mallocsDelta'],freesDelta=counter['freesDelta'],numGCDelta=counter['numGCDelta'],pauseTotalNsDelta=counter['pauseTotalNsDelta'],heapAllocBefore=counter['memStatsBefore']['HeapAlloc'],heapAllocAfter=counter['memStatsAfter']['HeapAlloc'],processCPUSecondsDelta=cpu_delta,cpu=attribution(raw),sampledAllocationDelta=attribution(alloc)))
write(BASE/'pprof-commands.json',commands)
write(BASE/'profile-summary.json',dict(diagnosticProfiling=True,performanceMeasurement=False,allRunLatenciesProfileContaminated=True,perCaseP95Available=False,forcedGCAdded=False,fullOrderedCaseCount=1267,observedSource=str(source),binary=binary,profiles=summaries,limitations=['Profile covers all goroutines in this process, including GC and profiler bookkeeping.','Before and after processes run serially with the same helper; host activity is recorded but this is not acceptance timing.','Default sampled alloc/heap profiles can lag GC; use exact MemStats counters for total allocation delta.','Before/after profile writes outside original measure still affect later history; no extra cases or forced GC added.']))
print(json.dumps([dict(id=s['id'],totalAllocDelta=s['totalAllocDelta'],processCPUSecondsDelta=s['processCPUSecondsDelta'],cpuSamples=s['cpu']['totalCount']) for s in summaries],indent=2))
