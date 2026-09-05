from pathlib import Path
import gzip,json,struct,mmap,hashlib,math
ROOT=Path(__file__).resolve().parent
support=json.loads((ROOT/'census.json').read_text())
terms=list(dict.fromkeys(t for c in support['cases'] for t in c['terms']))
manifest=Path('/private/tmp/pr113-attempt131-ascii.JqgmHw/fixture64/graphs.tsv')
sources=[x.split('\t') for x in manifest.read_text().splitlines() if x and not x.startswith('#')]
sha=lambda b:hashlib.sha256(b).hexdigest()
int32=lambda f:struct.unpack('>i',f.read(4))[0]
def bound(mm,off,n,trigram,upper=False):
 lo=0;hi=n
 while lo<hi:
  mid=(lo+hi)//2; value=struct.unpack_from('>i',mm,off+8*mid)[0]
  if value<trigram or upper and value==trigram:lo=mid+1
  else:hi=mid
 return lo
rows=[]
with gzip.open(ROOT/'strings.utf16.bin.gz','rb') as f:
 assert int32(f)==0x47535431
 for gi,source in enumerate(sources):
  assert int32(f)==gi;S=int32(f);strings=[]
  for _ in range(S):
   n=int32(f);strings.append(f.read(2*n).decode('utf-16-be','surrogatepass'))
  assert len(set(strings))==S
  path=Path(source[1])/'graph.callsite-string-index'
  with path.open('rb') as sf,mmap.mmap(sf.fileno(),0,access=mmap.ACCESS_READ) as mm:
   before=sha(mm); header=bytes(mm[:76]);magic,version,storedS,N=struct.unpack_from('>4i',mm)
   assert storedS==S; U=struct.unpack_from('>4i',mm,48);postingCount=struct.unpack_from('>i',mm,64)[0]
   off=76;propertyIds=[]
   for n in U:
    ids=[x[0] for x in struct.iter_unpack('>i',mm[off:off+4*n])]
    assert len(ids)==len(set(ids)) and ids==sorted(ids) and all(0<=i<S for i in ids)
    propertyIds.append(set(ids));off+=8*n+4*N
   union=set().union(*propertyIds); assert all(strings[i].isascii() for i in union)
   lower={i:strings[i].lower() for i in union};off+=8*S
   assert off+8*postingCount+8==len(mm)
   termRows=[]
   for term in terms:
    assert term.isascii() and len(term)>=3
    hashes=list(dict.fromkeys((ord(term[i])*31+ord(term[i+1]))*31+ord(term[i+2]) for i in range(len(term)-2)))
    spans=[]
    for t in hashes:
     a=bound(mm,off,postingCount,t); b=bound(mm,off,postingCount,t,True)
     spans.append((a,b,t))
    a,b,t=min(spans,key=lambda x:x[1]-x[0])
    anchorIds=[struct.unpack_from('>i',mm,off+8*j+4)[0] for j in range(a,b)]
    assert len(anchorIds)==len(set(anchorIds)) and all(i in union for i in anchorIds)
    matched=[i for i in anchorIds if term in lower[i]]
    expected=sorted(i for i in union if term in lower[i])
    assert sorted(matched)==expected
    m=len(matched); n=max(2,1 << (max(1,math.ceil(m/.75))-1).bit_length())
    assert n<=1<<30
    termRows.append(dict(term=term,anchorTrigram=t,anchorStart=a,anchorEnd=b,anchorCandidates=len(anchorIds),exactCount=m,exactIdsSha256=sha(b''.join(struct.pack('>i',i) for i in sorted(matched))),perPropertyCounts=[sum(i in ids for i in matched) for ids in propertyIds],hashCapacity=n,oneHashKeyArrayPayload=4*(n+1)))
   assert sha(mm)==before
  rows.append(dict(graphIndex=gi,graph=source[0],S=S,N=N,usedStringCount=len(union),uniquePropertyCounts=list(U),sidecarSha256Before=before,sidecarSha256After=before,headerSha256=sha(header),terms=termRows))
  print(f'graph {gi}: {len(union)} used / {S} total; actual anchors match exhaustive used-ID oracle',flush=True)
 assert f.read()==b''
cases=[]
for c in support['cases']:
 perGraph=[]
 for gi,r in enumerate(rows):
  selected=[next(x for x in r['terms'] if x['term']==t) for t in c['terms']]
  # This census verifies property-independent mapped arrays; each keyword is used
  # by four predicates and current raw code constructs four IntOpenHashSets.
  for ti,t in enumerate(c['terms']):
   assert selected[ti]['perPropertyCounts']==c['perGraph'][gi]['matchingIdsPerTermProperty'][ti]
  perGraph.append(dict(graphIndex=gi,hasActualCandidates=any(x['exactCount'] for x in selected),exactEntriesAcrossPredicates=4*sum(x['exactCount'] for x in selected),currentHashKeyArraysPayload=4*sum(x['oneHashKeyArrayPayload'] for x in selected),onePropertyMaskBytePayload=r['S'],perPredicateBytePayload=4*len(selected)*r['S']))
 cases.append(dict(id=c['id'],terms=c['terms'],candidateGraphs=[r['graphIndex'] for r in perGraph if r['hasActualCandidates']],allCandidateGraphTotals={k:sum(r[k] for r in perGraph if r['hasActualCandidates']) for k in ['exactEntriesAcrossPredicates','currentHashKeyArraysPayload','onePropertyMaskBytePayload','perPredicateBytePayload']},perGraph=perGraph))
out=dict(scope='Actual persisted trigram anchor replay plus independent exhaustive used-ID oracle. No query execution, latency, CPU, allocation, peak memory or speedup claim. Payload excludes object headers and unchanged exact IntArrays; runtime selected-tuple pruning may avoid constructing any lookup structure.',stringsExportSha256=sha((ROOT/'strings.utf16.bin.gz').read_bytes()),propertySupportCensusSha256=sha((ROOT/'census.json').read_bytes()),rows=rows,cases=cases)
(ROOT/'mapped-candidates.json').write_text(json.dumps(out,indent=2)+'\n')
print(json.dumps([dict(id=c['id'],candidateGraphs=len(c['candidateGraphs']),totals=c['allCandidateGraphTotals']) for c in cases],indent=2))
