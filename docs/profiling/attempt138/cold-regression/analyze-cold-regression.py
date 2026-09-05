import collections, json, pathlib, hashlib
root = pathlib.Path(__file__).parent / 'cold-regression-profile'
patterns = {
 'validator': 'io.johnsonlee.graphite.webgraph.PersistentIndexViewValidator.',
 'mappedLoad': 'io.johnsonlee.graphite.webgraph.MappedCallSiteStringIndexView$Companion.load(',
 'selectedProjection': 'io.johnsonlee.graphite.webgraph.MappedCallSiteStringIndexView.selectedProjectionHits(',
 'selectedStringIds': 'io.johnsonlee.graphite.webgraph.MappedCallSiteStringIndexView.selectedTupleStringIds(',
 'findId': 'io.johnsonlee.graphite.webgraph.StringTable.findId$webgraph(',
 'selectedAnchor': 'io.johnsonlee.graphite.webgraph.MappedCallSiteStringIndexView.selectedTupleAnchor(',
 'postingValidation': 'io.johnsonlee.graphite.webgraph.MappedCallSiteStringIndexView.validatedPostingCursor(',
 'candidateDiscovery': 'io.johnsonlee.graphite.webgraph.MappedCallSiteStringIndexView.exactMatchingStringIds(',
 'initialLimitProbe': 'io.johnsonlee.graphite.webgraph.MappedCallSiteStringIndexView.exactMatchesCanFillLimit(',
 'rawProjectionIncludingWorkers': 'io.johnsonlee.graphite.webgraph.MappedWebGraphBackedGraph.parallelRawDistinctCallSiteStringProjection',
 'rawProjection': 'io.johnsonlee.graphite.webgraph.MappedWebGraphBackedGraph.parallelRawDistinctCallSiteStringProjection(',
 'genericExpression': 'io.johnsonlee.graphite.cypher.ExpressionEvaluator.evaluate(',
}
def application(t):
 return any(t.startswith(s) for s in ['broad-query-pressure-worker ', 'graphite-cypher-scan-', 'graphite-callsite-segment-', 'graphite-callsite-scan-'])
summary = {'diagnosticOnly': True, 'notAcceptanceRerun': True, 'recordingsPerRevision': 1, 'queriesPerProjection': 40, 'methodPatterns': patterns, 'groups': []}
for side in ['base', 'candidate']:
 folder = root / (side + '-analysis'); d = json.loads((folder/'analysis.json').read_text())
 assert d['validation']['passed'] and d['validation']['queryCount'] == 80
 for kind in ['rows', 'distinct']:
  qs = [q for q in d['queries'] if '-' + kind + '-' in q['id']]; assert len(qs) == 40
  counts = collections.Counter(); leaves = {}; perQuery=[]
  for q in qs:
   qc=collections.Counter()
   for metric in ['cpuSamples', 'allocationSampledBytes']:
    m=q['metrics'][metric]; rows=(folder/q['collapsed'][metric]).read_text().splitlines()
    assert sum(int(x.rsplit(' ',1)[1]) for x in rows) == m['weight'] == sum(t['weight'] for t in m['threads'].values())
    byThread=collections.Counter()
    for line in rows:
     stack, weight=line.rsplit(' ',1); weight=int(weight);thread=stack.split(';',1)[0];byThread[thread]+=weight
     app=application(thread);qc[metric+'.all']+=weight;qc[metric+('.application' if app else '.background')]+=weight
     if not app: continue
     leaf=stack.rsplit(';',1)[-1]
     for name, pattern in patterns.items():
      if pattern in stack:
       qc[metric+'.'+name]+=weight
       leaves.setdefault(metric+'.'+name,collections.Counter())[leaf]+=weight
     names=[name for name,pattern in patterns.items() if pattern in stack]
     for i,a in enumerate(names):
      for b in names[i+1:]:qc[metric+'.intersection.'+a+'+'+b]+=weight
    assert dict(byThread)=={t:m['weight'] for t,m in m['threads'].items() if m['weight']}
    qc[metric+'.missingStackEvents']+=m['missingStackEvents'];qc[metric+'.truncatedStackEvents']+=m['truncatedStackEvents']
   counts.update(qc);perQuery.append({'id':q['id'],'counters':dict(qc)})
  summary['groups'].append({'revision':side,'projection':kind,'counters':dict(counts),'topLeavesByMethod':{k:v.most_common(12) for k,v in leaves.items()},'perQuery':perQuery})
summary['limitations']=['Inclusive method counts overlap; intersection counters and leaves retained.', 'Application thread taxonomy excludes JIT, GC, recorder and resource-sampler threads.', 'One fresh diagnostic JVM per revision with 40 same-query repetitions; profiled latency is not acceptance evidence.', 'Sampled allocation weights are not exact bytes or allocation counts.', 'No regression decision or gate is changed by this diagnostic.']
(root/'summary.json').write_text(json.dumps(summary,indent=2)+'\n')
for g in summary['groups']: print(g['revision'],g['projection'],json.dumps({k:v for k,v in g['counters'].items() if 'intersection' not in k}))
