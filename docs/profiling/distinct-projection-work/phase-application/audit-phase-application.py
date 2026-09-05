from pathlib import Path
from collections import Counter
import json,hashlib,copy,difflib
p=Path('/private/tmp/graphite-distinct-phase-details'); old=Path('/private/tmp/graphite-distinct-phase-profiling.by0z0asb')
summary=json.loads((p/'application-summary.json').read_text()); receipt=json.loads((p/'receipt.json').read_text())
prefixes=('broad-query-pressure-worker [','graphite-cypher-scan-','graphite-callsite-scan-','graphite-callsite-segment-')
raw='io.johnsonlee.graphite.webgraph.MappedWebGraphBackedGraph.parallelRawDistinctCallSiteStringProjection'
matching='io.johnsonlee.graphite.webgraph.MappedCallSiteStringIndexView.exactMatchingStringIds'
validator='io.johnsonlee.graphite.webgraph.PersistentIndexViewValidator.'
def sha(f): return hashlib.sha256(Path(f).read_bytes()).hexdigest()
def kind(t):
 if t.startswith(prefixes): return 'application'
 if t.startswith(('Java: C1 CompilerThread','Java: C2 CompilerThread')): return 'jit'
 return 'other'
def fullmetric(metric):
 groups=Counter(); categories=Counter(); leaves=Counter(); inclusive=Counter(); threads={}; rawleaves=Counter(); matchleaves=Counter()
 for thread,stacks in metric['threadStacks'].items():
  k=kind(thread); total=sum(stacks.values()); groups[k]+=total; threads[thread]={'class':k,'weight':total}
  assert total==metric['summary']['threads'][thread]['weight'],thread
  for stack,w in stacks.items():
   frames=stack.split(';')
   if k!='application': continue
   flags={'raw':any(f.startswith(raw) for f in frames),'matching':any(f.startswith(matching) for f in frames),'validator':any(f.startswith(validator) for f in frames)}
   for flag,yes in flags.items():
    if yes: categories[flag]+=w
   categories['rawAndMatching']+=w*(flags['raw'] and flags['matching'])
   categories['rawOrMatching']+=w*(flags['raw'] or flags['matching'])
   categories['neitherRawNorMatching']+=w*(not flags['raw'] and not flags['matching'])
   leaves[frames[-1]]+=w
   for f in set(frames): inclusive[f]+=w
   if flags['raw']: rawleaves[frames[-1]]+=w
   if flags['matching']: matchleaves[frames[-1]]+=w
 assert sum(groups.values())==metric['summary']['weight']
 assert sum(leaves.values())==groups['application']
 assert categories['rawOrMatching']==categories['raw']+categories['matching']-categories['rawAndMatching']
 return groups,categories,leaves,inclusive,threads,rawleaves,matchleaves
rows=[]; verification=[]; allmetriccount=0; allthreadcount=0
for i in range(1,4):
 new=json.loads((p/f'phase-{i}.json').read_text()); original=json.loads((old/f'phase-{i}-phases.json').read_text()); stripped=copy.deepcopy(new)
 for q in stripped['queries']:
  for name,metrics in q['metrics'].items():
   for key,m in list(metrics.items()): metrics[key]=m['summary']
 assert stripped==original,('original changed',i)
 r=receipt['inputs'][i-1]; assert r['recording']==i
 assert sha(p/f'phase-{i}.json')==r['outputSha256']
 assert sha(new['jfr'])==r['jfrSha256']
 assert sha(old/f'phase-{i}.tsv')==r['tsvSha256']
 for q in new['queries']:
  for phase,metrics in q['metrics'].items():
   for key,m in metrics.items():
    g,c,l,inc,t,rl,ml=fullmetric(m); allmetriccount+=1; allthreadcount+=len(t)
    assert m['summary']['missingStackEvents']==m['summary']['truncatedStackEvents']==0
    selected=next((x for x in summary['rows'] if (x['recording'],x['query'],x['phase'],x['metric'])==(i,q['id'],phase,key)),None)
    if selected is None: continue
    expected=selected['counts']; actual=dict(g)
    for k,v in [('applicationRawProjectionInclusive',c['raw']),('applicationMappedStringDiscoveryInclusive',c['matching']),('applicationValidatorInclusive',c['validator'])]:
     if v: actual[k]=v
    assert actual==expected,(i,q['id'],phase,key,actual,expected)
    assert selected['total']==sum(g.values())
    for field,counts in [('topApplicationLeaves',l),('topApplicationInclusive',inc)]:
     stated=selected[field]
     for f in stated: assert counts[f['frame']]==f['weight'],(field,f)
     if stated:
      listed={f['frame'] for f in stated}; cutoff=min(f['weight'] for f in stated)
      assert all(v<=cutoff for k,v in counts.items() if k not in listed),('not top',field)
    rows.append({'recording':i,'query':q['id'],'phase':phase,'metric':key,'total':sum(g.values()),'threadClasses':dict(g),'categories':dict(c),'threadWeights':t,'applicationLeaves':[{'frame':f,'weight':v} for f,v in l.most_common()],'rawProjectionLeaves':[{'frame':f,'weight':v} for f,v in rl.most_common()],'matchingDiscoveryLeaves':[{'frame':f,'weight':v} for f,v in ml.most_common()],'statedCountsAndTopFrameWeightsMatch':True})
 verification.append({'recording':i,'all34OriginalQuerySummariesExactlyEqual':True,'inputAndOutputReceiptHashesMatch':True,'summarySha256':sha(p/f'phase-{i}.json')})
assert len(rows)==len(summary['rows'])==26
newsrc=(p/'DistinctPhaseDetails.java').read_text().replace('DistinctPhaseDetails','DistinctPhaseWindows')
oldsrc=(old/'DistinctPhaseWindows.java').read_text()
changes=list(difflib.ndiff(oldsrc.splitlines(),newsrc.splitlines())); minus=[x[2:] for x in changes if x.startswith('- ')]; plus=[x[2:] for x in changes if x.startswith('+ ')]
assert len(minus)==len(plus)==1 and 'metrics.forEach' in minus[0] and 'threadStacks' in plus[0]
data={'result':'pass','offlineOnly':True,'newCaptureOrJavaProcess':False,'sourceChange':'After class rename, exactly one serialization line changed to wrap original metric json under summary and expose full thread stacks.','applicationThreadPrefixes':list(prefixes),'jitThreadPrefixes':['Java: C1 CompilerThread','Java: C2 CompilerThread'],'otherThreadPolicy':'Every other named/unnamed thread, including GC and resource sampler, excluded from application denominator.','metricPartitionsVerified':allmetriccount,'threadMetricPartitionsVerified':allthreadcount,'originalQuerySummariesVerified':102,'applicationRowsVerified':26,'rawDiscoveryIntersectionRule':'Count each stack once for each category; intersection explicit; union = raw + matching - intersection. Inclusive frames deduplicated within each stack.','allocationUnit':'Sampled TLAB or outside-TLAB byte weights, not exact object counts or physical memory.','verification':verification,'rows':rows,'limitations':['No independent JFR decoding rerun; source delta and serialized full stack identity/conservation independently audited.','Phase intervals already established by original analyzer; sample time cooccurrence is not causal attribution.','Small sample sizes and method tracing prevent a precise speedup or latency decomposition claim.']}
(p/'phase-application-audit.json').write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n')
print({'result':'pass','metrics':allmetriccount,'threadMetrics':allthreadcount,'summaryRows':len(rows)})
for r in rows:
 if r['metric']=='cpuSamples' and r['phase'] in ['initial','provenance']: print(r['recording'],r['query'].split('-')[-1],r['phase'],r['threadClasses'],r['categories'])
