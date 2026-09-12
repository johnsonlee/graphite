from pathlib import Path
import gzip,json,hashlib,struct,re,collections,math
ROOT=Path(__file__).resolve().parent
EXPORT=Path('/private/tmp/graphite-main-profiling-n50joikp/multi/callsites.tsv.gz')
MANIFEST=Path('/private/tmp/pr113-attempt131-ascii.JqgmHw/fixture64/graphs.tsv')
OLD=Path('/private/tmp/graphite-query-marker-capture/original-catalog.json')
V3=Path('/private/tmp/graphite-main-profiling-n50joikp/oracle-v3/catalog.json')
hashfile=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
v3=json.loads(V3.read_text()); assert hashfile(EXPORT)==v3['exportSha256']; assert hashfile(MANIFEST)==v3['manifestSha256']
sources=[l.split('\t') for l in MANIFEST.read_text().splitlines() if l and not l.startswith('#')]
cases=[]
for c in json.loads(OLD.read_text()):
 if c['id'].endswith(('distinct-targeted','distinct-dense')):
  terms=list(dict.fromkeys(re.findall("CONTAINS '([^']+)'",c['query'])))
  cases.append(dict(id=c['id'],terms=terms))
for c in v3['logicalCases']:
 if c['id'].startswith('or-four'): cases.append(dict(id=c['id'],terms=c['terms'],expectedHitGraphs=c['advertisedHitGraphPositions']))
assert len(cases)==8
terms=list(dict.fromkeys(t for c in cases for t in c['terms']))
headers=[]
for s in sources:
 p=Path(s[1])/'graph.callsite-string-index'
 with p.open('rb') as f:b=f.read(76)
 magic,version,S,N=struct.unpack_from('>4i',b); U=list(struct.unpack_from('>4i',b,48))
 headers.append(dict(graph=s[0],stringCount=S,nodeCount=N,uniquePropertyCounts=U,headerSha256=hashlib.sha256(b).hexdigest(),headerHex=b.hex()))
results={c['id']:[] for c in cases}; processed=[]
# The existing exported raw strings remain case-sensitive keys. Lowercase only for
# matching, following the independently verified catalog's reference derivation.
def finish(gi,sets,n):
 hdr=headers[gi]; assert n==hdr['nodeCount']; assert [len(s) for s in sets]==hdr['uniquePropertyCounts']
 union=set().union(*sets)
 masks={v:sum(1<<i for i,t in enumerate(terms) if t in v.lower()) for v in union}
 for c in cases:
  bits=[1<<terms.index(t) for t in c['terms']]; allbits=sum(bits)
  sizes=[[sum(bool(masks[v]&bit) for v in vals) for vals in sets] for bit in bits]
  unionSizes=[sum(bool(masks[v]&allbits) for v in vals) for vals in sets]
  entries=sum(map(sum,sizes)); S=hdr['stringCount']; P=4*len(bits)
  # Hypothetical array payloads only; no object headers, reservations or peak claim.
  results[c['id']].append(dict(graphIndex=gi,graph=hdr['graph'],matchingIdsPerTermProperty=sizes,unionIdsPerProperty=unionSizes,matchingEntries=entries,hasHit=entries>0,stringCount=S,perPredicateBytePayload=P*S,perPropertyBytePayload=4*S,packedPropertyBytePayload=S,packedPropertyBitPayload=8*((4*S+63)//64)))
 processed.append(dict(graphIndex=gi,rows=n,uniqueCounts=[len(s) for s in sets],unicodePropertyValues=sum(not v.isascii() for v in union)))
 print(f'graph {gi}: {n} nodes verified',flush=True)
gi=-1; sets=None;n=0
with gzip.open(EXPORT,'rt') as f:
 for line in f:
  row=line.rstrip('\n').split('\t');assert len(row)==5
  current=int(row[0])
  if current!=gi:
   if gi>=0:finish(gi,sets,n)
   assert current==gi+1;gi=current;sets=[set() for _ in range(4)];n=0
  for vals,v in zip(sets,row[1:]):vals.add(v)
  n+=1
finish(gi,sets,n);assert len(processed)==64 and sum(x['rows'] for x in processed)==5046935
for c in cases:
 hit=[r['graphIndex'] for r in results[c['id']] if r['hasHit']]
 if 'expectedHitGraphs' in c:assert hit==c['expectedHitGraphs']
 c['observedHitGraphs']=hit
 c['fullHitGraphCandidateEntries']=sum(r['matchingEntries'] for r in results[c['id']] if r['hasHit'])
 c['hypotheticalHitGraphPayloadSums']={k:sum(r[k] for r in results[c['id']] if r['hasHit']) for k in ['perPredicateBytePayload','perPropertyBytePayload','packedPropertyBytePayload','packedPropertyBitPayload']}
 c['perGraph']=results[c['id']]
out=dict(scope='Read-only full-fixture candidate cardinality census, not executed-node counts or performance evidence. Presence of candidate strings is sufficient for pure OR full graph hits. No implication all hit graphs allocate a table at runtime: selected tuple pruning can return first.',inputs=[dict(path=str(p),sha256=hashfile(p)) for p in [EXPORT,MANIFEST,OLD,V3]],headers=headers,processed=processed,cases=cases)
(ROOT/'census.json').write_text(json.dumps(out,indent=2)+'\n')
print(json.dumps([dict(id=c['id'],hitGraphs=len(c['observedHitGraphs']),entries=c['fullHitGraphCandidateEntries'],payloads=c['hypotheticalHitGraphPayloadSums']) for c in cases],indent=2))
