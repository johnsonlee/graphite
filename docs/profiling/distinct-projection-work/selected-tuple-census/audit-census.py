from pathlib import Path
from collections import Counter
import json,gzip,hashlib,csv,base64
p=Path('/private/tmp/graphite-distinct-source-census'); profile=Path('/private/tmp/graphite-main-profiling-n50joikp')
auth=json.loads((profile/'oracle-v3/catalog.json').read_text()); claimed=json.loads((p/'census.json').read_text()); exported=profile/'multi/callsites.tsv.gz'
sha=lambda f:hashlib.sha256(Path(f).read_bytes()).hexdigest()
assert sha(exported)==auth['exportSha256']==claimed['exportSha256']
manifest=Path(auth['manifest']); assert sha(manifest)==auth['manifestSha256']==claimed['manifestSha256']
manifestrows=[x.split('\t') for x in manifest.read_text().splitlines() if x and not x.startswith('#')]
graphids=[x[0] for x in manifestrows]; assert len(graphids)==64 and graphids==auth['inputGraphs']
# Select in the exported physical traversal order, independently of claimed expectedRows.
selected={}; completeat=None
with gzip.open(exported,'rt',encoding='utf8') as f:
 for n,line in enumerate(f,1):
  cells=line.rstrip('\n').split('\t'); assert len(cells)==5
  values=tuple(cells[1:])
  if any('get' in v.lower() for v in values): selected.setdefault(values,len(selected))
  if len(selected)==200:
   completeat=(int(cells[0]),n); break
assert completeat[0]==0
terms=[set(v[k] for v in selected) for k in range(4)]
posting=[Counter() for _ in range(64)]
hits=[Counter() for _ in range(64)]; allcounts=[0]*64; matchcounts=[0]*64; previous=-1
with gzip.open(exported,'rt',encoding='utf8') as f:
 for line in f:
  cells=line.rstrip('\n').split('\t'); assert len(cells)==5
  gi=int(cells[0]); assert previous<=gi<64; previous=gi
  values=tuple(cells[1:]); allcounts[gi]+=1
  if any('get' in v.lower() for v in values): matchcounts[gi]+=1
  if values in selected: hits[gi][values]+=1
  for k,v in enumerate(values):
   if v in terms[k]: posting[gi][(k,v)]+=1
assert sum(allcounts)==5046935 and allcounts==auth['perGraphCallSiteCounts']
provenance=list(csv.DictReader((manifest.parent/'fixture-provenance.tsv').open(),delimiter='\t'))
assert allcounts==[int(r['callSiteCount']) for r in provenance]
expected=[{'values':list(v),'graphIds':sorted(graphids[g] for g in range(64) if hits[g][v])} for v in selected]
assert expected==claimed['expectedRows']
newgraphs=[]
for g in range(64):
 lengths=[[posting[g][(k,v[k])] for k in range(4)] for v in selected]
 feasible=[j for j,length in enumerate(lengths) if all(length)]
 totals=[min(lengths[j]) for j in feasible]
 actual=claimed['graphs'][g]
 derived={'graphId':graphids[g],'callSiteCount':allcounts[g],'matchingGetNodes':matchcounts[g],'selectedTupleHits':len(hits[g]),'selectedNodeHits':sum(hits[g].values()),'selectedTuplesWithAllFourValuesPresent':len(feasible),'sumShortestPropertyPostingLengthsForEligibleTuples':sum(totals),'selectedTuplePropertyPostingLengths':lengths}
 assert derived==actual,(g,derived,actual)
 # Per tuple: every same-tuple node lies inside any selected anchor posting.
 for j,v in enumerate(selected): assert hits[g][v]<=min(lengths[j])
 newgraphs.append({'graphId':graphids[g],'actualSelectedTuples':len(hits[g]),'actualSelectedNodeHits':sum(hits[g].values()),'eligibleTuples':len(feasible),'eligibleButAbsentCompleteTuples':sum(v not in hits[g] for j,v in enumerate(selected) if j in feasible),'shortestPostingEncounterSum':sum(totals),'nonmatchingPerTupleAnchorEncounters':sum(totals)-sum(hits[g].values())})
assert sum(matchcounts)==claimed['matchingGetNodes']==1489740
assert sum(sum(h.values()) for h in hits)==claimed['totalSelectedNodeHits']==274
assert sum(g['shortestPostingEncounterSum'] for g in newgraphs)==claimed['sumShortestPropertyPostingLengthsForEligibleTuples']==1636
assert sum(g['shortestPostingEncounterSum'] for g in newgraphs[1:])==claimed['sourceCompletionOnlyShortestPostingSum']==53
assert [graphids[g] for g in range(64) if hits[g]]==claimed['hitGraphIds']==['fixture-android-00','fixture-tika-00']
# Full engine output and independently serialized SHA-256, no verifier import.
actual=[json.loads(x) for x in (p/'control-rows.jsonl').read_text().splitlines()]
observations=list(csv.DictReader((p/'control.tsv').open(),delimiter='\t'))
assert len(actual)==len(observations)==1
row=actual[0]; obs=observations[0]
assert row['id']==obs['id']==claimed['queryId']
assert row['columns']==['n.caller_class','n.caller_name','n.callee_class','n.callee_name']
assert row['rows']==expected
# Dataset selected strings contain none of JSON's abbreviated control escapes.
assert all(all(ord(c)>=32 for c in value) for value in terms[0]|terms[1]|terms[2]|terms[3])
digest=hashlib.sha256(json.dumps(expected,ensure_ascii=False,separators=(',',':')).encode()).hexdigest()
assert digest==obs['digest']; assert obs['outcome']=='success' and obs['rowCount']=='200' and obs['inputSourceCount']=='64'
assert obs['hitGraphIds']==','.join(sorted(claimed['hitGraphIds']))
workloads=list(csv.DictReader((p/'workload.tsv').open(),delimiter='\t')); assert len(workloads)==1
assert base64.b64decode(workloads[0]['queryBase64']).decode()==claimed['query']
assert obs['workloadIdentity']==hashlib.sha256(claimed['query'].encode()).hexdigest()
command=json.loads((p/'control-command.json').read_text()); cp=command[command.index('-cp')+1].split(':'); assert len(cp)==2
jar=Path(cp[1]); assert str(jar)==auth['jar'] and sha(jar)==auth['jarSha256']
assert command[-4:]==[str(p/'workload.tsv'),str(p/'control'),'all','per-query-cold']
assert command[-5]==str(manifest)
result={'result':'pass','referenceOnly':True,'newJavaOrPerformanceMeasurement':False,'exportAndManifestAuthenticated':True,'independentFullReferenceNodesScanned':sum(allcounts),'selectedTupleCount':200,'selectionFinishedAtGraphOrdinal':completeat[0],'selectionFinishedAtExportLine':completeat[1],'totalMatchingGetNodes':sum(matchcounts),'full64By200By4PostingCardinalitiesMatch':True,'allGraphAndTupleCountsMatch':True,'totalActualSelectedNodes':274,'sumShortestPostingLengthsAllGraphs':1636,'sumShortestPostingLengthsAfterLeadingGraph':53,'actualSelectedNodesAfterLeadingGraph':12,'nonmatchingPerTupleAnchorEncountersAfterLeadingGraph':41,'eligibleButAbsentCompleteTupleCountsAllGraphs':sum(g['eligibleButAbsentCompleteTuples'] for g in newgraphs),'graphs':newgraphs,'engineControl':{'fullRowsOrderColumnsProvenanceEqual':True,'digest':digest,'queryDigestAndManifestMatch':True,'currentJarMatchesAuthenticatedFrozenJar':True,'jarSha256':sha(jar),'singleObservationNotPerformanceEvidence':True},'fileHashes':{f:sha(p/f) for f in ['census.py','census.json','correctness-receipt.json','control-rows.jsonl','control.tsv','control-command.json']},'limits':['No actual index posting bytes inspected; these are logical per-property frequencies from the already authenticated export.','A shortest-posting sum includes repeated encounters and nonmatching nodes for each tuple separately; it is not unique candidate count, index work, speedup or an API guarantee.','No independent full source-file pre/post hash receipt was created by the existing one-query control. This audit checks current frozen JAR identity and persisted output consistency, not new execution.','Independent counter reuses the same authenticated export; frozen-main full-row output is a separate implementation control.']}
(p/'independent-census-audit.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
print({k:v for k,v in result.items() if k not in ['graphs','fileHashes','limits','engineControl']},flush=True)
