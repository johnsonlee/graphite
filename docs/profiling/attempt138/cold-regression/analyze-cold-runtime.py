import json,pathlib,datetime,re,decimal,collections,hashlib
root=pathlib.Path(__file__).parent/'cold-regression-profile'
def instant(s):
 m=re.fullmatch(r'(.*T\d{2}:\d{2}:\d{2})(?:\.(\d+))?(Z|[+-]\d{2}:\d{2})',s);assert m,s
 t=datetime.datetime.fromisoformat(m[1]+m[3].replace('Z','+00:00'))
 return int(t.timestamp())*10**9+int((m[2] or '').ljust(9,'0'))
def duration(s):
 assert s.startswith('PT') and s.endswith('S'),s
 return int(decimal.Decimal(s[2:-1])*10**9)
def union(intervals):
 total=0;end=-1
 for a,b in sorted(intervals):
  if b>end:total+=b-max(a,end);end=b
 return total
out={'notAcceptanceEvidence':True,'eventsSource':'Existing companion JVM JFR recordings; no new execution','groups':[]}
for side in ['base','candidate']:
 p=root/(side+'-runtime-events.json');events=json.loads(p.read_text())['recording']['events'];qdata=json.loads((root/(side+'-analysis/analysis.json')).read_text())['queries']
 queries=[]
 for q in qdata:
  start,end=instant(q['start']),instant(q['end']);deopts=[];pauses=[];comp=[]
  for e in events:
   v=e['values'];a=instant(v['startTime'])
   if e['type']=='jdk.Deoptimization' and start<=a<end:
    m=v['method'];deopts.append({'method':m['type']['name']+'.'+m['name']+m['descriptor'],'reason':v['reason'],'action':v['action'],'timeNanos':a})
   elif e['type'] in ['jdk.GCPhasePause','jdk.Compilation']:
    b=a+duration(v['duration']);x,y=max(a,start),min(b,end)
    if y>x:
     if e['type']=='jdk.GCPhasePause':pauses.append((x,y))
     else:comp.append({'method':v['method']['type']['name']+'.'+v['method']['name'],'overlapNanos':y-x,'recordedDurationNanos':b-a})
  queries.append({'id':q['id'],'gcPauseOverlapNanos':union(pauses),'gcPauseIntervals':len(pauses),'deoptimizations':deopts,'recordedCompilationOverlaps':comp})
 for kind in ['rows','distinct']:
  qs=[q for q in queries if '-'+kind+'-' in q['id']];assert len(qs)==40
  c=collections.Counter((d['method'],d['reason'],d['action']) for q in qs for d in q['deoptimizations'])
  out['groups'].append({'side':side,'projection':kind,'queryCount':40,'gcPauseOverlapNanos':sum(q['gcPauseOverlapNanos'] for q in qs),'deoptimizationCount':sum(c.values()),'deoptimizationsByMethodReason':[{'method':k[0],'reason':k[1],'action':k[2],'count':v} for k,v in c.items()],'perQuery':qs})
 out[side+'InputSha256']=hashlib.sha256(p.read_bytes()).hexdigest()
out['limitations']=['GCPhasePause intersections use each full query trace interval and union overlapping pauses; they are not summed with GarbageCollection events.', 'Compilation recording has a 100ms duration threshold; retained event counts are not total compilations or total compilation work.', 'Compilation intervals run on background compiler threads; overlap does not prove equivalent query blocking time.', 'Deoptimization events, GC overlap and identical validator bytecode cannot identify the unprofiled regression cause by themselves.']
(root/'runtime-summary.json').write_text(json.dumps(out,indent=2)+'\n')
for g in out['groups']:print(g['side'],g['projection'],'GC overlap ms',g['gcPauseOverlapNanos']/1e6,'deopts',g['deoptimizationCount'])
