from pathlib import Path
from collections import Counter
import json,hashlib,csv,shlex
root=Path(__file__).parent
sha=lambda f:hashlib.sha256(Path(f).read_bytes()).hexdigest()
prefixes=('broad-query-pressure-worker [','graphite-cypher-scan-','graphite-callsite-scan-','graphite-callsite-segment-')
categoryPrefixes={'findId':'io.johnsonlee.graphite.webgraph.StringTable.findId$webgraph(', 'selectedTupleStringIds':'io.johnsonlee.graphite.webgraph.MappedCallSiteStringIndexView.selectedTupleStringIds(', 'selectedProjectionHits':'io.johnsonlee.graphite.webgraph.MappedCallSiteStringIndexView.selectedProjectionHits(', 'rawProjection':'io.johnsonlee.graphite.webgraph.MappedWebGraphBackedGraph.parallelRawDistinctCallSiteStringProjection', 'matchingStringDiscovery':'io.johnsonlee.graphite.webgraph.MappedCallSiteStringIndexView.exactMatchingStringIds(', 'postingValidation':'io.johnsonlee.graphite.webgraph.MappedCallSiteStringIndexView.validatedPostingCursor('}
def kind(thread):
 if thread.startswith(prefixes): return 'application'
 if thread.startswith(('Java: C1 CompilerThread','Java: C2 CompilerThread')): return 'jit'
 return 'other'
def analyze(m):
 groups=Counter(); cats=Counter(); leaves=Counter(); inc=Counter(); findLeaves=Counter(); patterns=Counter()
 for t,stacks in m['threadStacks'].items():
  k=kind(t); total=sum(stacks.values()); groups[k]+=total; assert total==m['summary']['threads'][t]['weight']
  for stack,w in stacks.items():
   if k!='application': continue
   fs=stack.split(';'); labels=tuple(label for label,prefix in categoryPrefixes.items() if any(f.startswith(prefix) for f in fs)); patterns[labels]+=w
   for label in labels: cats[label]+=w
   leaves[fs[-1]]+=w
   for frame in set(fs): inc[frame]+=w
   if 'findId' in labels: findLeaves[fs[-1]]+=w
 assert sum(groups.values())==m['summary']['weight']
 assert sum(leaves.values())==groups['application']
 return groups,cats,leaves,inc,findLeaves,patterns
receipt=json.loads((root/'input-receipt.json').read_text()); preSummary=json.loads((root/'residual-summary.json').read_text()); rows=[]; verified=[]; metriccount=0; stackpartitions=0
signatureFields=['id','family','shape','selectivity','operator','boundary','projection','targetGraphId','workloadIdentity','limit','outcome','rowCount','responseBytes','digest']
for side in ['base','rejected133']:
 phases=json.loads((root/f'{side}-phases.json').read_text()); outer=json.loads((root/f'{side}-analysis/analysis.json').read_text()); cmd=json.loads((root/f'{side}-command.json').read_text()); jar=Path(cmd[cmd.index('-jar')+1]); assert sha(jar)==receipt['jarHashes'][side]
 args=shlex.split(cmd[cmd.index('-jvmArgs')+1]); props={a.split('=',1)[0]:a.split('=',1)[1] for a in args if '=' in a}; oracle=Path(props['-Dgraphite.broad.pressure.correctness.oracle']);assert sha(oracle)==receipt['oracleSha256']
 expected=oracle.read_text().splitlines(); obs=list(csv.DictReader((root/f'{side}.tsv').open(),delimiter='\t'));assert len(obs)==len(expected)==len(phases['queries'])==len(outer['queries'])==34
 assert ['|'.join(r[f] for f in signatureFields) for r in obs]==expected
 assert sha(root/f'{side}.jfr')==outer['jfrSha256'] and sha(root/f'{side}.tsv')==outer['tsvSha256']
 for q,o in zip(phases['queries'],outer['queries']):
  assert q['id']==o['id'];assert q['outerDurationNanos']==o['traceDurationNanos'];assert q['tsvLatencyNanos']==o['tsvLatencyNanos'];assert q['untracedTsvNanos']==o['latencyGapNanos'];assert q['initialUnionNanos']+q['provenanceUnionNanos']+q['otherNanos']==q['outerDurationNanos']
  full={}; events=Counter(); weights=Counter()
  for phase,metrics in q['metrics'].items():
   for metric,m in metrics.items():
    g,c,l,inc,fl,patterns=analyze(m);metriccount+=1;stackpartitions+=len(m['threadStacks']);assert m['summary']['missingStackEvents']==m['summary']['truncatedStackEvents']==0
    events[metric]+=m['summary']['eventCount'];weights[metric]+=m['summary']['weight']
    combined=full.setdefault(metric,Counter())
    for t,stacks in m['threadStacks'].items():
     for st,w in stacks.items(): combined[t.replace(';',':').replace('\n',' ')+';'+st]+=w
    if q['id'].endswith(('distinct-dense','distinct-targeted')):
     rows.append({'side':side,'query':q['id'],'phase':phase,'metric':metric,'threadClasses':dict(g),'inclusiveCategories':dict(c),'categoryIntersections':[{'categories':list(k),'weight':v} for k,v in patterns.items()],'applicationLeaves':[{'frame':f,'weight':w} for f,w in l.most_common()],'findIdLeaves':[{'frame':f,'weight':w} for f,w in fl.most_common()]})
    claimed=next((r for r in preSummary if metric=='cpuSamples' and (r['side'],r['query'],r['phase'])==(side,q['id'],phase)),None)
    if claimed:
     assert claimed['applicationCpu']==g['application'] and claimed['allCpu']==sum(g.values())
     for f in claimed['topLeaves']: assert l[f['frame']]==f['weight']
     for f in claimed['inclusive']: assert inc[f['frame']]==f['weight']
  for metric,combined in full.items():
   assert events[metric]==o['metrics'][metric]['eventCount'] and weights[metric]==o['metrics'][metric]['weight']
   exported=Counter()
   for line in (root/f'{side}-analysis'/o['collapsed'][metric]).read_text().splitlines():
    stack,w=line.rsplit(' ',1);exported[stack]+=int(w)
   assert combined==exported,(side,q['id'],metric)
 verified.append({'side':side,'queries':34,'oracleSignaturesMatch':True,'allCpuAllocationFullStacksEqualOuter':True,'allDurationPartitionsConserve':True,'jarHashMatchesInputReceipt':True,'jfrAndTsvHashesMatchOuterAnalysis':True})
manifest=Path(props['-Dgraphite.broad.pressure.graphs']); paths=dict(line.split('\t')[:2] for line in manifest.read_text().splitlines() if line and not line.startswith('#')); count=0
for graph in receipt['graphFiles']:
 for f in graph['files']:
  path=Path(paths[graph['id']])/f['path'];assert path.stat().st_size==f['size'] and sha(path)==f['sha256'];count+=1
result={'result':'pass','offlineOnly':True,'newJavaOrMeasurement':False,'rejectedAttempt133RemainsRejected':True,'verification':verified,'phaseMetricPartitions':metriccount,'threadMetricPartitions':stackpartitions,'currentGraphFilesMatchPreCaptureReceipt':count,'applicationThreadPrefixes':list(prefixes),'threadDefinition':'JIT compiler threads and all other threads excluded from application denominator; all work on application threads included.','categoryPrefixes':categoryPrefixes,'inclusiveNotAdditive':True,'rows':rows,'limitations':['One captured replay per revision; no stability, latency fraction, speedup or acceptance conclusion.','Full-stack serialization checked against independent outer collapsed files; JFR not decoded again by this audit.','Allocation weights are sampled TLAB/outside-TLAB bytes, not exact objects or total allocated bytes.','Input versus current graph/JAR hashes independently match; completed.json separately records root pre/post checks.']}
(root/'residual-audit.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n');print({'result':'pass','phaseMetrics':metriccount,'threadMetrics':stackpartitions,'graphFiles':count})
for r in rows:
 if r['query'].endswith('distinct-dense') and r['phase']=='provenance': print(r['side'],r['metric'],r['threadClasses'],r['inclusiveCategories'],r['categoryIntersections'])
