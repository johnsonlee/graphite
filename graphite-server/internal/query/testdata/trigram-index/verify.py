#!/usr/bin/env python3
"""Independent UTF-16 model vs main-produced strings/index/full query outputs."""
import json,pathlib
root=pathlib.Path(__file__).resolve().parent
read=lambda p:json.loads((root/p).read_text())
inputs,strings,index,nodes,queries=[read(p) for p in ['inputs.json','strings.json','index.json','nodes.json','queries.json']]
table={s['id']:s for s in strings};postings={int(k):set(v) for k,v in index['trigrams'].items()}
def units(s):
 b=s.encode('utf-16-be','surrogatepass');return [int.from_bytes(b[i:i+2],'big') for i in range(0,len(b),2)]
def grams(u):return set((a*31+b)*31+c for a,b,c in zip(u,u[1:],u[2:]))
def contains(a,b):return any(a[i:i+len(b)]==b for i in range(len(a)-len(b)+1))
used={sid for n in nodes for sid in n['stringIds']};required=0
for sid in used:
 for h in grams(table[sid]['lowerUnits']):
  assert sid in postings.get(h,set()),(sid,h);required+=1
properties=[{n['stringIds'][p] for n in nodes} for p in range(4)]
verified=0;eligible=0;fallback=0
for case in inputs['cases']:
 if not case['query'].startswith('MATCH'):continue
 rhs=units(case['params']['term']);wrapped=case['name'].endswith('wrapped');typed='-typed-' in case['name']
 can=len(rhs)>=3 and (wrapped or all(u<128 for u in rhs));eligible+=can;fallback+=not can
 anchorRhs=rhs if wrapped else [u+32 if 65<=u<=90 else u for u in rhs]
 candidate=used
 if can:
  ranges=[postings.get(h,set()) for h in grams(anchorRhs)];candidate=min(ranges,key=len)
 matches=set()
 for p in range(4):
  matches.update(s for s in candidate&properties[p] if contains(table[s]['lowerUnits' if wrapped else 'units'],rhs))
 ids=sorted(n['id'] for n in nodes if any(s in matches for s in n['stringIds']))
 # Wrapped empty is not safe for A6 untyped inference: full scan includes Int.
 if wrapped and not rhs and not typed:ids.append(999)
 for cross in [False,True]:
  got=next(q for q in queries if q['name']==case['name'] and q['cross']==cross)
  rows=[dict(id=i,**{'$metadata':{'graphIds':[g]}}) for i in ids for g in ['a','b']] if cross else [{'id':i} for i in ids]
  expected=dict(name=case['name'],cross=cross,columns=['id'],rows=rows)
  assert got==expected,(case['name'],cross,got,expected);verified+=1
assert grams(units('aaz'))==grams(units('ab[')) and 'aaz'!='ab['
primitive={tuple(p['units']):p for p in read('primitives.json')}
assert primitive[tuple(units('aaa'))]['signatureHelper']==primitive[tuple(units('acc'))]['signatureHelper']
assert grams(units('aaa'))!=grams(units('acc'))
# Concrete unsound raw non-ASCII lower-RHS examples.
examples=[]
for actual,rhs in [('XΟΣΑ','XΟΣ'),('AΣ12','Σ12')]:
 lowerActual=primitive[tuple(units(actual))]['lowerUnits'];lowerRhs=primitive[tuple(units(rhs))]['lowerUnits']
 assert contains(units(actual),units(rhs)) and not grams(lowerRhs).issubset(grams(lowerActual))
 examples.append({'actual':actual,'rhs':rhs,'rawContains':True,'lowerActualUnits':lowerActual,'lowerRhsUnits':lowerRhs,'missingHashes':sorted(grams(lowerRhs)-grams(lowerActual))})
summary={'mainQueryObservations':len(queries),'fullFourFieldRowsCompared':verified,'eligibleCases':eligible,'fallbackCases':fallback,'usedStringIDs':len(used),'requiredTrigramPairsVerified':required,'fullHashCollision':['aaz','ab['],'signatureCollision':['aaa','acc'],'rawNonASCIIUnsoundLowering':examples,'productionChanges':False,'performanceRun':False}
(root/'verification.json').write_text(json.dumps(summary,ensure_ascii=True,indent=2)+'\n');print(json.dumps(summary,ensure_ascii=True))
