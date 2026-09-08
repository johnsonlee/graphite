"""Independently verify archived profile inputs, bytes, counters, and case gate."""
import argparse
import gzip
import hashlib
import json
from pathlib import Path
import tarfile

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--directory',type=Path,required=True)
BASE=parser.parse_args().directory.resolve()
def read(p):return json.loads(p.read_text())
def sha(p):
    h=hashlib.sha256()
    with p.open('rb') as s:
        for block in iter(lambda:s.read(1024*1024),b''):h.update(block)
    return h.hexdigest()
manifest=read(BASE/'artifact-manifest.json')
for item in manifest:
    p=BASE/item['path'];assert p.stat().st_size==item['bytes'] and sha(p)==item['sha256'],item['path']
expected=read(BASE/'build-source-inputs.json')
with tarfile.open(BASE/'build-source.tar.gz') as t:
    actual={}
    for item in t.getmembers():
        if item.isfile():actual[item.name.removeprefix('source/')]=hashlib.sha256(t.extractfile(item).read()).hexdigest()
assert actual==expected
build=read(BASE/'build.json');binary=gzip.decompress((BASE/'graphite-benchmark-profile.gz').read_bytes())
assert hashlib.sha256(binary).hexdigest()==build['binarySHA256']
correctness=read(BASE/'correctness.json');assert correctness['passed'] and correctness['caseCount']==1267 and correctness['successCount']==1266 and correctness['originalFailureCount']==1 and correctness['timeoutCount']==0
process=read(BASE/'process.json');assert process['status']=='exited' and process['exitCode']==1
assert all(process[k] for k in ['inputsUnchanged','fixtureAuditPassed','completeModuleUnchanged','frozenSourceUnchanged'])
for name in ['reference-preflight.json','clone-preflight.json','postrun-fixtures.json']:
    audit=read(BASE/name);assert audit['matched']==audit['originalFiles']==1152 and not any(audit[k] for k in ['changed','missing','added'])
records=[json.loads(line) for line in (BASE/'observations.jsonl').read_text().splitlines()]
assert len(records)==1269
case_rows=records[1:-1];assert [r['index']for r in case_rows]==list(range(1267))
failures=[r for r in case_rows if r['outcome']!='SUCCESS']
assert len(failures)==1 and failures[0]['index']==821 and failures[0]['error']=='IllegalStateException' and failures[0]['message']=='Unsafe expression reached parallel string projection'
assert all(not r['censoredTimeout']for r in case_rows)
summary=read(BASE/'profile-summary.json');selected=read(BASE/'selected-cases.json')
assert len(summary['profiles'])==len(selected)==6
brackets=[]
for i,row in enumerate(summary['profiles']):
    assert row['index']==883+i==selected[i]['index'] and row['id']==selected[i]['case']['id']
    c=read(BASE/'profiles'/row['id']/'counters.json')
    assert row['diagnosticMeasureNanos']==c['originalMeasureElapsedNanos']==case_rows[883+i]['latencyNanos']
    for dst,src in [('totalAllocDelta','TotalAlloc'),('mallocsDelta','Mallocs'),('freesDelta','Frees'),('numGCDelta','NumGC'),('pauseTotalNsDelta','PauseTotalNs')]:
        assert c[dst]==c['memStatsAfter'][src]-c['memStatsBefore'][src]==row[dst],(row['id'],dst)
    cpu_microseconds=sum((c['rusageAfter'][k]['Sec']-c['rusageBefore'][k]['Sec'])*1000000+c['rusageAfter'][k]['Usec']-c['rusageBefore'][k]['Usec']for k in ['Utime','Stime'])
    assert abs(cpu_microseconds/1000000-row['processCPUSecondsDelta'])<1e-9
    assert c['forcedGCAdded'] is False and c['timedOut'] is False
    brackets.append(dict(id=row['id'],totalAllocDelta=c['totalAllocDelta'],processCPUMicroseconds=cpu_microseconds,
                         sampledCPUSeconds=row['cpu']['totalValue']/1e9,gcSampleCount=row['cpu']['groups']['GCStacks']['count'],
                         numGCDelta=c['numGCDelta'],note='Sample totals are not exact process CPU shares; all groups overlap and runtime-wait samples dominate.'))
print(json.dumps(dict(artifactFiles=len(manifest),completeModuleFiles=len(actual),caseCount=1267,successCount=1266,originalFailures=1,performanceMeasurement=False,verifiedBrackets=brackets),indent=2))
