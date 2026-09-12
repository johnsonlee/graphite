import collections, hashlib, json, pathlib

root = pathlib.Path(__file__).parent / 'mechanism'
assert json.loads((root / 'completed.json').read_text())['status'] == 'complete'
result = {'diagnosticOnly': True, 'notP95': True, 'notAcceptanceEvidence': True, 'recordingsPerRevision':1, 'repetitionsPerProjectionInOneJvm':40, 'groups':[]}
for side in ['base', 'candidate']:
    folder = root / (side+'-analysis')
    data = json.loads((folder/'analysis.json').read_text())
    assert data['validation']['passed'] and data['validation']['queryCount'] == 80
    for kind in ['rows', 'distinct']:
        qs = [q for q in data['queries'] if '-'+kind+'-' in q['id']]
        assert len(qs) == 40
        counters = collections.Counter()
        for q in qs:
            for metric in ['cpuSamples', 'allocationSampledBytes']:
                m = q['metrics'][metric]
                assert sum(t['weight'] for t in m['threads'].values()) == m['weight']
                assert sum(t['eventCount'] for t in m['threads'].values()) == m['eventCount']
                collapsed = (folder/q['collapsed'][metric]).read_text().splitlines()
                assert sum(int(line.rsplit(' ',1)[1]) for line in collapsed) == m['weight']
                counters[metric+'.missingStacks'] += m['missingStackEvents']
                counters[metric+'.truncatedStacks'] += m['truncatedStackEvents']
                for line in collapsed:
                    stack, weight = line.rsplit(' ',1); weight = int(weight)
                    leaf = stack.rsplit(';',1)[-1]
                    validator = 'io.johnsonlee.graphite.webgraph.PersistentIndexViewValidator.' in stack
                    boxed = leaf.startswith('java.lang.Integer.valueOf(') or leaf.startswith('java.lang.Long.valueOf(')
                    counters[metric+'.all'] += weight
                    if validator: counters[metric+'.validator'] += weight
                    if boxed: counters[metric+'.boxedLeaf'] += weight
                    if validator and boxed: counters[metric+'.validatorBoxedLeaf'] += weight
                    if validator and leaf.startswith('java.nio.HeapByteBuffer.<init>('): counters[metric+'.validatorHeapByteBufferLeaf'] += weight
        result['groups'].append({'revision':side, 'projection':kind, 'queries':40, 'counters':dict(counters)})
result['limitations'] = ['One CPU/allocation capture per revision; repetitions share a JVM and fixed order.', 'Weighted allocation samples are not exact allocated bytes or object counts. Absence of samples alone is not proof of zero allocation.', 'Inclusive validator samples describe stack membership; remaining hotspots are not a proposal for another optimization.', 'Only the preplanned unprofiled pairs and exact-head CI determine acceptance.']
(root/'summary.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps(result,indent=2))
