from pathlib import Path
import subprocess,json,hashlib,collections,csv
R=Path(__file__).parent;repo=Path('/Users/johnsonlee/.codex/worktrees/ac7b5da2-2450-48c5-894c-5fd84ab6cb7d/graphite')
MAIN='4e328b0109e13c896b74004823fb049fcb19251a';CAND='470df7cea888240b87380f1a4a650638ea713815';catalog=Path('/private/tmp/graphite-main-profiling-n50joikp/oracle-v3/catalog.json')
def sha(b):return hashlib.sha256(b).hexdigest()
d=json.loads(catalog.read_text());q=next(x for x in d['queries'] if x['id']=='or-four-broad-distinct');l=next(x for x in d['logicalCases'] if x['id']=='or-four-broad');rows=q['expectedRows'];counts=collections.Counter()
assert len(rows)==200 and all(row['graphIds']==['fixture-android-00'] for row in rows)
assert l['ast']==['or',0,1,2,3] and len(q['expectedHitGraphIds'])==55
for row in rows:
 assert all(v.isascii() for v in row['values'])
 for i,term in enumerate(l['terms']):
  positions=[j for j,v in enumerate(row['values']) if term in v.lower()]
  if positions:counts[4*i+positions[0]+1]+=1;break
sources={}
for name in ['MappedWebGraphBackedGraph.kt','MappedCallSiteStringIndexView.kt']:
 f='graphite-webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/'+name
 sources[f]={}
 for rev in [MAIN,CAND]:
  b=subprocess.check_output(['git','show',rev+':'+f],cwd=repo);lines=b.decode().splitlines()
  fragments=['fun distinctStringPropertyDisjunction(','fun selectedProjectionHits(','fun selectedTupleStringIds(','fun selectedTupleAnchor(','fun selectedTupleEncounterOrder(','fun exactMatchingStringIds(','fun exactMatchesCanFillLimit(','fun matchingNodeIds(','fun validatedPostingCursor(','fun postingRange(','fun mappedSparseDistinctCallSiteStringProjection(','fun readRawCallSiteStringIds(','fun parallelRawDistinctCallSiteStringProjection(','fun mappedCallSiteStringIndexView(','fun stringMatches(','class PersistentIndexViewValidator','fun load(']
  refs={fragment:[i for i,line in enumerate(lines,1) if fragment in line] for fragment in fragments if any(fragment in line for line in lines)}
  sources[f][rev]={'sha256':sha(b),'lines':refs}
measurements=[]
for i in range(1,4):
 p={'pair':i}
 for side in ['base','candidate']:
  path=R/f'v3-pair-{i}-{side}'/'fork-001.tsv'
  row=next(x for x in csv.DictReader(path.open(),delimiter='\t') if x['id']==q['id'])
  p[side]={k:row[k] for k in ['latencyNanos','graphWorkUnits','rowCount','digest','hitGraphIds','inputSourceCount','resetMode']}
 assert p['base']['digest']==p['candidate']['digest']
 p['latencyIncreasePercent']=100*(int(p['candidate']['latencyNanos'])/int(p['base']['latencyNanos'])-1)
 measurements.append(p)
out={'schema':'rejected138-cold-source-audit-v1','decision':'rejected-remains-final','base':MAIN,'candidate':CAND,'sourceEvidence':sources,'catalogSha256':sha(catalog.read_bytes()),'query':{k:v for k,v in q.items() if k!='expectedRows'},'oracleFacts':{'selectedCount':200,'selectedProvenanceOnlyGraph':'fixture-android-00','completeQueryHitGraphCount':55,'totalMatches':q['totalMatches'],'totalDistinctMatches':q['totalDistinctMatches'],'firstGraphCallSites':d['perGraphCallSiteCounts'][0],'firstGraphMatches':l['perGraphMatchingCounts'][0],'firstGraphDistinctMatches':l['perGraphDistinctMatchingCounts'][0],'selectedUniqueStrings':len(set(v for row in rows for v in row['values'])),'selectedPropertyValueCounts':[len(set(row['values'][i] for row in rows)) for i in range(4)],'selectedFirstMatchingPredicateOneBasedIfReached':dict(counts),'termExclusiveMatchCounts':l['termExclusiveMatchCounts'],'selectedRowsSha256':sha(json.dumps(rows,ensure_ascii=False,separators=(',',':')).encode())},'originalRejectedTiming':measurements,'facts':['Candidate and main both load/CRC-validate the mapped view before either selected or initial specialization. Validator source logic is unchanged.','The first graph can produce 200 distinct rows; valid-index occurrence upper-bound reaches LIMIT, so candidate initial sparse helper returns null and uses existing raw projection for this query.','The remaining 63 source checks receive the selected 200 complete four-column tuples under the unchanged balanced DISTINCT provenance flow. Full-hit graph count 55 is not selected provenance count.','Main exact matching reuses matching strings by transform/mode/expected (four keys for the sixteen predicates), then converts selected values with local string-ID/property-membership reuse before optional raw scan.','Candidate selected success bypasses exact predicate string discovery and raw scan, but adds per-tuple concrete predicate matching, four posting range lookups for viable tuples, full chosen-range validation if not cached, posting tuple comparison, and final encounter-order sort.','Candidate ID/membership reuse was already present in main raw conversion. It does not cache postingRange results across selected tuples.','For this oracle every remaining-source selected result is empty; sorting selected hit rows cannot process a large nonempty result in this query.','Candidate bills selected column/predicate/posting visits, while main raw conversion does not bill each selected column/findId step. Both use existing explicit work consumers and exception propagation; workUnits are not CPU instructions or bytes.'],'unknowns':['Actual per-graph selected feasibility and early-exit counts, chosen posting lengths, reused validation cache entries/collisions, and StringTable.findId call counts require event/profile evidence or separate verified instrumentation.','Cold shared CRC validation remains, and cache/JIT/GC scheduling may affect time; source and total work counts cannot attribute the 36-40ms regression.','A warm mapped view does not imply all selected posting validation entries are already cached.','The new recording is diagnostic only and cannot reopen, override or replace the rejected original paired timing.'],'noJavaBuildOrMeasurementStarted':True,'recordedCandidateJarSha256':json.loads((R/'build-receipt.json').read_text())['jmhJarSha256']}
(R/'cold-regression-source-audit.json').write_text(json.dumps(out,indent=2)+'\n')
print(json.dumps({'facts':out['oracleFacts'],'originalTimings':measurements,'sourceReferences':sources},indent=2))
