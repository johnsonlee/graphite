import collections,csv,gzip,hashlib,json,pathlib
root=pathlib.Path(__file__).parent
profile=pathlib.Path('/private/tmp/graphite-main-profiling-n50joikp')
export=profile/'multi/callsites.tsv.gz';auth=json.loads((profile/'oracle-v3/catalog.json').read_text());catalog=json.loads((profile/'query-catalog.json').read_text())
def sha(p):
 with p.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
assert sha(export)==auth['exportSha256']
manifest=pathlib.Path(catalog['graphManifest']);assert sha(manifest)==auth['manifestSha256']
proof=manifest.parent/'fixture-provenance.tsv';assert sha(proof)==auth['provenanceSha256']
query=next(q for q in catalog['queries'] if q['id']=='global-wide-wrapped-case-insensitive-distinct-targeted')
assert len(query['actualKeywords'])==1;term=query['actualKeywords'][0]
graphs=auth['inputGraphs'];counts=[0]*64;matches=[0]*64;per_property=[[0]*4 for _ in graphs];tuples=[collections.Counter() for _ in graphs];positions=[[] for _ in graphs];previous=-1
with gzip.open(export,'rt',encoding='utf8') as f:
 for line in f:
  cells=line.rstrip('\n').split('\t');assert len(cells)==5;g=int(cells[0]);assert 0<=g<64 and g>=previous;previous=g
  ordinal=counts[g];counts[g]+=1;values=tuple(cells[1:]);flags=[term in v.lower() for v in values]
  for k,b in enumerate(flags):per_property[g][k]+=int(b)
  if any(flags):matches[g]+=1;tuples[g][values]+=1;positions[g].append(ordinal)
expected=list(csv.DictReader(proof.open(),delimiter='\t'));assert [r['graphId'] for r in expected]==graphs and counts==[int(r['callSiteCount']) for r in expected]
assert sum(counts)==5046935
results=[{'graphId':graphs[i],'callSiteCount':counts[i],'matchingNodeCount':matches[i],'matchingDistinctTupleCount':len(tuples[i]),'matchingPerPropertyCounts':per_property[i],'matchingPhysicalOrdinals':positions[i]} for i in range(64)]
all_tuples={t for group in tuples for t in group};assert len(all_tuples)==12
output={'referenceOnly':True,'queryId':query['id'],'query':query['query'],'querySha256':query['querySha256'],'exportSha256':sha(export),'manifestSha256':sha(manifest),'provenanceSha256':sha(proof),'totalCallSites':sum(counts),'matchingNodeCount':sum(matches),'distinctTupleCount':len(all_tuples),'hitGraphIds':[graphs[i] for i in range(64) if matches[i]],'callSitesInHitGraphs':sum(counts[i] for i in range(64) if matches[i]),'graphs':results,'limits':['Independent existing export only; no graph engine execution or timing.','Matching property posting union may identify these nodes only after the existing index integrity and physical-order validation requirements.','Logical node counts do not measure lookup work, latency, or select an accepted optimization.']}
(root/'targeted-reference-census.json').write_text(json.dumps(output,indent=2)+'\n')
print({k:v for k,v in output.items() if k not in ('query','graphs','limits')})
