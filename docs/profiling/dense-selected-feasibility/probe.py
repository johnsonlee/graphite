"""Reference-only selected-tuple feasibility census from the authenticated real export.

No graph engine or performance measurements. Per-property sets retain only values
appearing in the 200 selected tuples; this is exactly equivalent to full property
sets for these membership questions, while keeping reference memory bounded.
"""
import collections,csv,gzip,hashlib,json,pathlib,sys
root=pathlib.Path('/private/tmp/graphite-main-profiling-n50joikp')
out=pathlib.Path(__file__).resolve().parent
export=root/'multi/callsites.tsv.gz'
catalog_path=root/'query-catalog.json'
auth_path=root/'oracle-v3/catalog.json'
catalog=json.loads(catalog_path.read_text());auth=json.loads(auth_path.read_text())
manifest=pathlib.Path(catalog['graphManifest']);provenance=manifest.parent/'fixture-provenance.tsv'
census=root/'multi/non-callsite-census.tsv'
# The companion census filename is authenticated by its contents, not inferred if absent.
if not census.exists():
 matches=[p for p in (root/'multi').glob('*.tsv') if hashlib.sha256(p.read_bytes()).hexdigest()==auth['nonCallSiteCensusSha256']]
 assert len(matches)==1; census=matches[0]
def sha(path):
 with path.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
assert __debug__
assert sha(export)==auth['exportSha256']
assert sha(manifest)==auth['manifestSha256']
assert sha(provenance)==auth['provenanceSha256']
assert sha(census)==auth['nonCallSiteCensusSha256']
graphs=[line.split('\t')[0] for line in manifest.read_text().splitlines() if line and not line.startswith('#')]
assert graphs==[s['id'] for s in catalog['sources']]==auth['inputGraphs'] and len(graphs)==64
with provenance.open() as f:proof=list(csv.DictReader(f,delimiter='\t'))
expected_counts=[int(r['callSiteCount']) for r in proof]
assert [r['graphId'] for r in proof]==graphs
census_rows=[s.split('\t') for s in census.read_text().splitlines()[1:]]
assert [r[0] for r in census_rows]==graphs and all(int(r[2])==0 for r in census_rows)
query=next(q for q in catalog['queries'] if q['id']=='global-wide-wrapped-case-insensitive-distinct-dense')
assert query['actualKeywords']==['get'] and query['limit']==200 and query['parameters']=={}
assert sha(catalog_path) # Preserve exact query-bearing catalog identity below.
def rows():
 previous=-1
 with gzip.open(export,'rt',encoding='utf-8',newline='') as f:
  for line in f:
   fields=line.removesuffix('\n').split('\t');assert len(fields)==5
   position=int(fields[0]);assert 0<=position<64 and position>=previous;previous=position
   yield position,tuple(fields[1:])
def matches(values):return any('get' in value.lower() for value in values)
selected={};first_seen=[];position_counts=collections.Counter()
for position,values in rows():
 position_counts[position]+=1
 if matches(values) and values not in selected:
  selected[values]=len(selected)
  first_seen.append({'graphPosition':position,'graphId':graphs[position],'callSiteOrdinalInGraph':position_counts[position]-1})
  if len(selected)==200:break
assert len(selected)==200
wanted=[{values[property_index] for values in selected} for property_index in range(4)]
property_membership=[[set() for _ in range(4)] for _ in graphs]
counts=[0]*64;query_counts=[0]*64;selected_hits=[collections.Counter() for _ in graphs]
for position,values in rows():
 counts[position]+=1
 match=matches(values);query_counts[position]+=int(match)
 ordinal=selected.get(values)
 if ordinal is not None:
  assert match
  selected_hits[position][ordinal]+=1
 for i,value in enumerate(values):
  if value in wanted[i]:property_membership[position][i].add(value)
assert counts==expected_counts and sum(counts)==5046935
all_values=list(selected);selected_rows=[]
for ordinal,values in enumerate(all_values):
 actual_graphs=[graphs[i] for i,hits in enumerate(selected_hits) if ordinal in hits]
 selected_rows.append({'ordinal':ordinal,'values':values,'graphIds':actual_graphs,'firstEncounter':first_seen[ordinal]})
actual_sources=[graphs[i] for i,hits in enumerate(selected_hits) if hits]
assert actual_sources==['fixture-android-00','fixture-tika-00'],actual_sources
results=[]
for i,graph in enumerate(graphs):
 feasible=[j for j,values in enumerate(all_values) if all(value in property_membership[i][k] for k,value in enumerate(values))]
 actual=sorted(selected_hits[i]);assert set(actual)<=set(feasible)
 results.append({'position':i,'graphId':graph,'callSiteCount':counts[i],
  'fullQueryMatchingNodeCount':query_counts[i],'selectedTupleMatchingNodeCount':sum(selected_hits[i].values()),
  'selectedTupleTrueHitCount':len(actual),'selectedTupleTrueHitOrdinals':actual,
  'selectedTupleHitMultiplicity':dict(sorted(selected_hits[i].items())),
  'feasibleTupleCount':len(feasible),'feasibleTupleOrdinals':feasible,
  'propertySelectedValueMembershipCounts':[len(s) for s in property_membership[i]],
  'conservativelyExcluded':not feasible})
assert not (out/'receipt.json').exists(), 'Do not overwrite prior probe evidence'
(out/'selected-tuples.json').write_text(json.dumps(selected_rows,ensure_ascii=False,indent=2)+'\n')
(out/'per-graph.json').write_text(json.dumps(results,indent=2)+'\n')
with (out/'per-graph.tsv').open('w',newline='') as f:
 columns=['position','graphId','callSiteCount','fullQueryMatchingNodeCount','selectedTupleMatchingNodeCount','selectedTupleTrueHitCount','feasibleTupleCount','conservativelyExcluded']
 writer=csv.DictWriter(f,fieldnames=columns,delimiter='\t',extrasaction='ignore');writer.writeheader();writer.writerows(results)
receipt={'passed':True,'referenceOnly':True,'frozenRevision':auth['frozenRevision'],
 'queryId':query['id'],'query':query['query'],'querySha256':hashlib.sha256(query['query'].encode()).hexdigest(),
 'inputs':{name:{'path':str(p),'sha256':sha(p)} for name,p in [('export',export),('manifest',manifest),('provenance',provenance),('nonCallSiteCensus',census),('queryCatalog',catalog_path),('authenticationCatalog',auth_path)]},
 'scriptSha256':sha(pathlib.Path(__file__)),'totalCallSites':sum(counts),'fullQueryMatchingNodes':sum(query_counts),
 'fullQueryHitGraphCount':sum(n>0 for n in query_counts),'selectedTupleCount':len(selected),
 'selectedTrueSourceGraphIds':actual_sources,'excludedGraphCount':sum(r['conservativelyExcluded'] for r in results),
 'excludedGraphIds':[r['graphId'] for r in results if r['conservativelyExcluded']],
 'feasibleButNoTrueSelectedHitGraphIds':[r['graphId'] for r in results if r['feasibleTupleCount'] and not r['selectedTupleTrueHitCount']],
 'selectedTuplesSha256':sha(out/'selected-tuples.json'),'perGraphSha256':sha(out/'per-graph.json'),
 'limits':['No Cypher/Java/benchmark execution and no performance measurement.',
 'Membership uses exact original-case strings and corresponding physical property only.',
 'Four independent property memberships are necessary, not sufficient for same-node tuple occurrence.',
 'Excluded graphs have zero feasible selected tuples; predicate work avoidability is a logical reference count, not latency or CPU evidence.',
 'This studies the order of existing necessary-condition checks, not Attempt133 shortest-posting direct tuple lookup.',
 'No persistent graph formats, indexes, query plans, or source code changed.']}
(out/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n')
print(json.dumps({k:v for k,v in receipt.items() if k not in ['query','inputs','limits']},indent=2))
