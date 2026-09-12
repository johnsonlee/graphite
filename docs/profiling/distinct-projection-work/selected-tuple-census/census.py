import base64, collections, csv, gzip, hashlib, json, pathlib, subprocess, sys

root = pathlib.Path(__file__).parent
profile = pathlib.Path('/private/tmp/graphite-main-profiling-n50joikp')
repo = pathlib.Path('/Users/johnsonlee/.codex/worktrees/ac7b5da2-2450-48c5-894c-5fd84ab6cb7d/graphite')
sys.path.insert(0, str(repo/'.github/scripts/wide-query-profile'))
from verify_run import digest_rows

sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
auth = json.loads((profile/'oracle-v3/catalog.json').read_text())
catalog = json.loads((profile/'query-catalog.json').read_text())
export = profile/'multi/callsites.tsv.gz'
manifest = pathlib.Path(catalog['graphManifest'])
assert sha(export) == auth['exportSha256']
assert sha(manifest) == auth['manifestSha256']
query = next(q for q in catalog['queries'] if q['id'] == 'global-wide-wrapped-case-insensitive-distinct-dense')
assert query['actualKeywords'] == ['get'] and query['limit'] == 200
selected = []
seen = set()
with gzip.open(export, 'rt', encoding='utf8') as f:
    for line in f:
        cells = line.rstrip('\n').split('\t'); assert len(cells) == 5
        values = tuple(cells[1:])
        if any('get' in v.lower() for v in values) and values not in seen:
            selected.append(values); seen.add(values)
            if len(selected) == 200:
                selection_last_graph = int(cells[0]); break
assert len(selected) == 200 and selection_last_graph == 0
properties = ['caller_class','caller_name','callee_class','callee_name']
wanted = [{v[k] for v in selected} for k in range(4)]
counts = [0]*64
matching = [0]*64
postings = [[collections.Counter() for _ in range(4)] for _ in range(64)]
tuples = [collections.Counter() for _ in range(64)]
previous = -1
with gzip.open(export, 'rt', encoding='utf8') as f:
    for line in f:
        cells = line.rstrip('\n').split('\t'); assert len(cells) == 5
        g = int(cells[0]); assert previous <= g < 64; previous = g
        values = tuple(cells[1:]); counts[g] += 1
        matching[g] += int(any('get' in v.lower() for v in values))
        for k,v in enumerate(values):
            if v in wanted[k]: postings[g][k][v] += 1
        if values in seen: tuples[g][values] += 1
assert sum(counts) == 5046935
graphs = auth['inputGraphs']
provenance = list(csv.DictReader((manifest.parent/'fixture-provenance.tsv').open(), delimiter='\t'))
assert counts == [int(r['callSiteCount']) for r in provenance]
expected = [{'values':list(values),'graphIds':[graphs[g] for g in range(64) if tuples[g][values]]} for values in selected]
# The verifier uses canonical graph-ID ordering inside a result row.
for row in expected: row['graphIds'].sort()
graph_results = []
for g in range(64):
    anchor_counts = [[postings[g][k][values[k]] for k in range(4)] for values in selected]
    eligible = [min(values) for values in anchor_counts if all(values)]
    graph_results.append({'graphId':graphs[g], 'callSiteCount':counts[g], 'matchingGetNodes':matching[g], 'selectedTupleHits':len(tuples[g]), 'selectedNodeHits':sum(tuples[g].values()), 'selectedTuplesWithAllFourValuesPresent':len(eligible), 'sumShortestPropertyPostingLengthsForEligibleTuples':sum(eligible), 'selectedTuplePropertyPostingLengths':anchor_counts})
result = {'referenceOnly':True,'queryId':query['id'],'query':query['query'],'selectedTupleCount':200,'selectionCompletedInGraph':graphs[selection_last_graph],'totalCallSites':sum(counts),'matchingGetNodes':sum(matching),'totalSelectedNodeHits':sum(sum(t.values()) for t in tuples),'hitGraphIds':[graphs[g] for g in range(64) if tuples[g]],'sumShortestPropertyPostingLengthsForEligibleTuples':sum(g['sumShortestPropertyPostingLengthsForEligibleTuples'] for g in graph_results),'sourceCompletionOnlyShortestPostingSum':sum(g['sumShortestPropertyPostingLengthsForEligibleTuples'] for g in graph_results[1:]),'exportSha256':sha(export),'manifestSha256':sha(manifest),'graphs':graph_results,'expectedRows':expected,'limitations':['Logical reference posting cardinalities only, not actual index access counts, measured CPU/latency, or a speedup prediction.','A tuple can have all four individual values present without a complete tuple match. Anchor posting lengths deliberately retain these false candidates and duplicates.','Whole-view integrity, complete selected-posting physical-order validation, budgets/cancellation and provenance still apply; no existing API is assumed to expose this cardinality strategy.','This reference census does not select or implement a new optimization.']}
(root/'census.json').write_text(json.dumps(result,indent=2)+'\n')
print({k:v for k,v in result.items() if k not in ['graphs','query','expectedRows','limitations']},flush=True)

# One frozen-main correctness control; no performance comparison or P95.
workload = root/'workload.tsv'
workload.write_text('id\tqueryBase64\tdistinct\texpectedHitGraphIds\ttotalMatches\n'+query['id']+'\t'+base64.b64encode(query['query'].encode()).decode()+'\ttrue\t'+','.join(graphs[g] for g in range(64) if matching[g])+'\t'+str(sum(matching))+'\n')
template = json.loads((profile/'control-v2-26/fork-001-command.json').read_text())
prefix = root/'control'
cmd = [str(workload) if x==str(profile/'oracle-v2/workloads.tsv') else str(prefix) if x==str(profile/'control-v2-26/fork-001') else x for x in template]
(root/'control-command.json').write_text(json.dumps(cmd,indent=2)+'\n')
with (root/'control.log').open('w') as log: subprocess.run(cmd,stdout=log,stderr=subprocess.STDOUT,check=True)
actual = [json.loads(line) for line in (root/'control-rows.jsonl').read_text().splitlines()]
obs = list(csv.DictReader((root/'control.tsv').open(),delimiter='\t'))
assert len(actual) == len(obs) == 1
assert actual[0]['id'] == query['id'] and actual[0]['rows'] == expected
assert actual[0]['columns'] == ['n.'+p for p in properties]
assert obs[0]['digest'] == digest_rows(expected) and obs[0]['rowCount'] == '200' and obs[0]['outcome'] == 'success'
(root/'correctness-receipt.json').write_text(json.dumps({'passed':True,'queryCount':1,'full200RowsOrderAndProvenanceEqual':True,'frozenMainOnly':True,'notPerformanceEvidence':True},indent=2)+'\n')
print('Frozen-main full200 rows/order/provenance verified',flush=True)
