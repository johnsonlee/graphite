from pathlib import Path
from collections import Counter,defaultdict
import json,calendar,time,hashlib,math
P=Path(__file__).resolve().parent
summary=json.loads((P/'summary.json').read_text())
def ns(s):
 main,_,fraction=s.rstrip('Z').partition('.')
 return calendar.timegm(time.strptime(main,'%Y-%m-%dT%H:%M:%S'))*10**9+int(fraction.ljust(9,'0'))
def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()
def merged(spans):
 result=[]
 for a,b in sorted(spans):
  if not result or a>result[-1][1]: result.append([a,b])
  else: result[-1][1]=max(b,result[-1][1])
 return result
rows=[];count=0;zero_fractions=[];dense_fractions=[]
for source in summary['inputs']:
 path=Path(source['path']); assert sha(path)==source['sha256']; data=json.loads(path.read_text()); recording=int(path.stem.split('-')[-1])
 for q in data['queries']:
  if not q['id'].startswith('global-wide-wrapped-case-insensitive-distinct-'): continue
  actual=next(r for r in summary['queries'] if r['phase']==recording and r['id']==q['id'])
  for key in ('tsvLatencyNanos','outerDurationNanos','otherNanos','untracedTsvNanos'): assert actual[key]==q[key]
  assert q['tsvLatencyNanos']==q['outerDurationNanos']+q['untracedTsvNanos']
  assert q['initialUnionNanos']+q['provenanceUnionNanos']+q['otherNanos']==q['outerDurationNanos']
  combined={};out={'recording':recording,'query':q['id']}
  for phase in ('initial','provenance'):
   calls=q['calls'][phase]; count+=len(calls); spans=[(ns(c['start']),ns(c['end'])) for c in calls];combined[phase]=merged(spans)
   for c,(a,b) in zip(calls,spans): assert b-a==c['durationNanos'] and b>a
   threads=defaultdict(list)
   for c,span in zip(calls,spans): threads[c['thread']].append(span)
   for ss in threads.values():
    ss.sort(); assert all(left[1]<=right[0] for left,right in zip(ss,ss[1:]))
   union=sum(b-a for a,b in merged(spans)); assert union==q[phase+'UnionNanos']
   # Independently enumerate every endpoint cell and directly count containing spans;
   # no scan-line delta/event accumulator and no import/execution of parent's analyze.py.
   ends=sorted({t for span in spans for t in span}); histogram=Counter()
   for a,b in zip(ends,ends[1:]):
    if b>a: histogram[str(sum(x<=a and b<=y for x,y in spans))]+=b-a
   integrated=sum(int(k)*v for k,v in histogram.items()); total=sum(b-a for a,b in spans)
   assert integrated==total
   assert sum(v for k,v in histogram.items() if int(k)>0)==union
   longest=sorted(spans,key=lambda z:z[1]-z[0],reverse=True)
   overlap=None if len(longest)<2 else max(0,min(longest[0][1],longest[1][1])-max(longest[0][0],longest[1][0]))
   expected={'callCount':len(calls),'unionNanos':union,'sumCallDurationNanos':total,'durationByActiveGraphCallsNanos':dict(histogram),'meanActiveCallsDuringUnion':total/union if union else None,'longestCallNanos':max((b-a for a,b in spans),default=0),'twoLongestCallOverlapNanos':overlap,'threadCallCounts':{t:len(ss) for t,ss in threads.items()}}
   assert expected==actual[phase],(recording,q['id'],phase,expected,actual[phase])
   out[phase]=expected
   if q['id'].endswith('targeted') and phase=='initial':
    assert len(calls)==64 and overlap==0
    zero_fractions.append(histogram['1']/union)
   if q['id'].endswith('dense') and phase=='provenance':
    assert len(calls)==63
    dense_fractions.append(histogram['2']/union)
  assert sum(max(0,min(b,d)-max(a,c)) for a,b in combined['initial'] for c,d in combined['provenance'])==0
  rows.append(out)
assert len(rows)==9 and count==576
result={'result':'pass','offlineOnly':True,'usedParentAnalysisAlgorithm':False,'algorithm':'Absolute ISO timestamps parsed at integer nanosecond precision; sorted interval merge for union; independent O(n^2) endpoint-cell containment counts for occupancy integral; direct intersection of two duration-ranked longest spans.','summarySha256':sha(P/'summary.json'),'readmeSha256':sha(P/'README.md'),'verifiedQueries':len(rows),'verifiedGraphCalls':count,'targetedSingleActiveFraction':zero_fractions,'denseTwoActiveFraction':dense_fractions,'readmeNumericalClaimsVerified':True,'limitations':['Graph-level lifetime overlap is not CPU occupancy, scheduler causality, serial execution time or speedup.','Segment-level workers are outside these graph-level trace spans counts.','Zero-active bins cover only earliest-start through latest-end for each phase, not all outer time.','No graph IDs are available in these traces; long spans cannot identify specific graphs.','Outer duration and untraced gap are checked against existing summary fields; no independent JFR decoding performed.'],'rows':rows}
(P/'independent-audit.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
print('PASS',len(rows),count,zero_fractions,dense_fractions)
