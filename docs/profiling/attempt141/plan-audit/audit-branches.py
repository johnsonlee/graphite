import gzip,hashlib,json,pathlib,struct,math
OUT=pathlib.Path(__file__).resolve().parent
ROOT=pathlib.Path('/private/tmp/graphite-exact-membership-census')
def exact(f,n):
 b=f.read(n);assert len(b)==n;return b
def integer(f):return struct.unpack('>i',exact(f,4))[0]
def sha(b):return hashlib.sha256(b).hexdigest()
# Reproduce Java int -> float conversion and float division at f=0.75 before Math.ceil.
def capacity(m):
 fm=struct.unpack('>f',struct.pack('>f',m))[0]
 quotient=struct.unpack('>f',struct.pack('>f',fm/0.75))[0]
 need=math.ceil(quotient);n=max(2,1<<max(0,(need-1).bit_length()));assert n<=1<<30;return n
mapped=json.loads((ROOT/'mapped-candidates.json').read_text());independent=json.loads((ROOT/'independent-census-audit.json').read_text());support=json.loads((ROOT/'census.json').read_text());terms=[r['term'] for r in mapped['rows'][0]['terms']]
sources=[s.split('\t') for s in pathlib.Path('/private/tmp/pr113-attempt131-ascii.JqgmHw/fixture64/graphs.tsv').read_text().splitlines() if s and not s.startswith('#')]
bygraph=[];checks=0
with gzip.open(ROOT/'strings.utf16.bin.gz','rb') as f:
 assert integer(f)==0x47535431
 for gi,s in enumerate(sources):
  assert integer(f)==gi;S=integer(f);strings=[exact(f,2*integer(f)).decode('utf-16-be','surrogatepass') for _ in range(S)]
  path=pathlib.Path(s[1])/'graph.callsite-string-index'
  with path.open('rb') as sf:
   header=exact(sf,76);magic,version,storedS,N=struct.unpack_from('>4i',header);assert magic==0x47524353 and version==2 and storedS==S;counts=struct.unpack_from('>4i',header,48);used=set()
   for count in counts:
    ids=struct.unpack('>'+str(count)+'i',exact(sf,4*count));used.update(ids);sf.seek(4*count+4*N,1)
  assert len(used)==mapped['rows'][gi]['usedStringCount']==independent['graphs'][gi]['usedUnionCount']
  lower={sid:strings[sid].lower() for sid in used};assert all(strings[sid].isascii() for sid in used)
  sizes={};hashes={};payloads={}
  for t in terms:
   ids=sorted(sid for sid,value in lower.items() if t in value);actual=next(x for x in mapped['rows'][gi]['terms'] if x['term']==t)
   digest=sha(b''.join(struct.pack('>i',i) for i in ids));n=capacity(len(ids));payload=4*(n+1)
   assert actual['exactCount']==len(ids) and actual['exactIdsSha256']==digest
   assert actual['hashCapacity']==n and actual['oneHashKeyArrayPayload']==payload
   sizes[t]=len(ids);hashes[t]=digest;payloads[t]=payload;checks+=1
  bygraph.append({'graphIndex':gi,'graph':s[0],'S':S,'termCounts':sizes,'termIdSha256':hashes,'termHashKeyPayloads':payloads})
 assert f.read(1)==b''
cases=[]
for c in mapped['cases']:
 rows=[]
 for gi,g in enumerate(bygraph):
  old=c['perGraph'][gi];H=4*sum(g['termHashKeyPayloads'][t] for t in c['terms']);has=any(g['termCounts'][t] for t in c['terms']);eligible=g['S']<=H
  assert old['currentHashKeyArraysPayload']==H and old['onePropertyMaskBytePayload']==g['S'] and old['hasActualCandidates']==has
  rows.append({'graphIndex':gi,'graph':g['graph'],'S':g['S'],'hashKeyPayloadH':H,'S_le_H':eligible,'actualCandidates':has,'selectedBranchIfReached':'byte-mask' if eligible else 'original-hash','originalAllExactEmptyReturn':not has,'perPredicateIdsForConstruction':4*sum(g['termCounts'][t] for t in c['terms']),'byteZeroInitializationElements':g['S'] if eligible else 0})
 cases.append({'id':c['id'],'terms':c['terms'],'eligibleGraphIndexes':[r['graphIndex'] for r in rows if r['S_le_H']],'candidateGraphIndexes':[r['graphIndex'] for r in rows if r['actualCandidates']],'ineligibleCandidateGraphIndexes':[r['graphIndex'] for r in rows if r['actualCandidates'] and not r['S_le_H']],'eligiblePayloadTotals':{'S':sum(r['S'] for r in rows if r['S_le_H']),'H':sum(r['hashKeyPayloadH'] for r in rows if r['S_le_H'])},'perGraph':rows})
res={'scope':'Independent offline property-union exact ID hash and Attempt 141 payload branch census; no Java/build/query/measurement','passed':True,'mappedCandidatesSha256':sha((ROOT/'mapped-candidates.json').read_bytes()),'independentPriorCensusSha256':sha((ROOT/'independent-census-audit.json').read_bytes()),'exactTermCountAndIdHashesMatched':checks,'capacityFormula':'Java binary32 expected/0.75, Math.ceil, nextPowerOfTwo, max 2; key int[] payload=4*(n+1). Every predicate position constructs a hash set, including same IntArray referenced by four properties.','perGraphExactEvidence':bygraph,'cases':cases,'limits':['512 logical graph rows do not mean 512 executed raw membership initializations; outer exact-empty, selected-tuple feasibility, LIMIT and source pruning can return before this branch.','Payload excludes original exact IntArrays, array headers/alignment, set/list headers, candidate precheck/mask zeroing costs, reservations and overlap/peak lifetime.','Density criterion is no-increase of selected replacement array payload only, not no-increase of actual heap or runtime.']}
(OUT/'branch-census.json').write_text(json.dumps(res,indent=2)+'\n')
lines=['# 141 independent payload branch census','','| Case | Candidate graphs | Mask-eligible graphs | Candidate graphs staying hash | Eligible S bytes | Replaced H key payload bytes |','|---|---:|---:|---:|---:|---:|']
for c in cases:lines.append(f"| {c['id']} | {len(c['candidateGraphIndexes'])} | {len(c['eligibleGraphIndexes'])} | {len(c['ineligibleCandidateGraphIndexes'])} | {c['eligiblePayloadTotals']['S']} | {c['eligiblePayloadTotals']['H']} |")
lines+=['','All 8×64 per-graph branch values and term count/ID-hash matches are in branch-census.json. Eligible does not prove execution or allocation: existing upstream/selected-tuple early returns still apply.']
(OUT/'branch-census.md').write_text('\n'.join(lines)+'\n')
print(json.dumps({'hashChecks':checks,'cases':[{k:c[k] for k in ['id','eligibleGraphIndexes','candidateGraphIndexes','ineligibleCandidateGraphIndexes','eligiblePayloadTotals']} for c in cases]},indent=2))
