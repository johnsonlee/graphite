from pathlib import Path
import json,hashlib
R=Path(__file__).parent; M=R/'method4-string'
def j(p):return json.loads(p.read_text())
scoremaps={}; signatures={}; files={}
for phase,stem in [('initial','{}-method-compatibility-4-string'),('confirmation','method-confirmation-{}-4-string')]:
 scoremaps[phase]={}; signatures[phase]={}
 for side in ['base','candidate']:
  f=M/(stem.format(side)+'.json');d=j(f)
  assert len(d)==3
  scoremaps[phase][side]={x['params']['scenario']:x for x in d}
  assert set(scoremaps[phase][side])=={'late','prefix','suffix'}
  for x in d:assert x['params']['graphCount']=='4'
  txt=f.with_suffix('.txt'); sig=sorted(set(txt.read_text().splitlines()));assert len(sig)==3
  signatures[phase][side]=sig
  for p in [f,txt]:files[str(p.relative_to(R))]=hashlib.sha256(p.read_bytes()).hexdigest()
 assert signatures[phase]['base']==signatures[phase]['candidate']
assert signatures['initial']['base']==signatures['confirmation']['base']
metrics={'wall':None,'cpu':'processCpuNanos','rss-after':'residentSetAfterBytes','rss-delta':'residentSetDeltaBytes'}
confirmed=[]
for label,metric in metrics.items():
 for ending in ['initial-status','status']:
  p=M/f'method-compatibility-4-string-{label}-{ending}.json';st=j(p);files[str(p.relative_to(R))]=hashlib.sha256(p.read_bytes()).hexdigest()
  for row in st['rows']:
   scenario=row['key'].split('scenario=')[1].split(']')[0]
   for side in ['base','candidate']:
    raw=scoremaps['initial'][side][scenario]
    value=raw['primaryMetric']['score'] if metric is None else raw['secondaryMetrics'][metric]['score']
    assert value==row[side+'Score']
   assert abs((row['candidateScore']/row['baseScore']-1)*100-row['delta'])<1e-9
   if 'confirmation' in row:
    c=row['confirmation']
    for side in ['base','candidate']:
     raw=scoremaps['confirmation'][side][scenario]
     value=raw['primaryMetric']['score'] if metric is None else raw['secondaryMetrics'][metric]['score']
     assert value==c[side+'Score']
    assert abs((c['candidateScore']/c['baseScore']-1)*100-c['delta'])<1e-9
    if row['blocked'] and c['blocked']:
     assert row['delta']>row['threshold'] and c['delta']>row['threshold']
     confirmed.append({'scenario':scenario,'graphCount':4,'metric':metric or 'wallMillis','thresholdPercent':row['threshold'],'initial':{'base':row['baseScore'],'candidate':row['candidateScore'],'increasePercent':row['delta']},'confirmation':{'base':c['baseScore'],'candidate':c['candidateScore'],'increasePercent':c['delta']}})
assert len(confirmed)==1 and confirmed[0]['scenario']=='prefix' and confirmed[0]['metric']=='processCpuNanos'
shard=j(M/'method-compatibility-shard-4-string-status.json');assert not shard['passed'] and not shard['errors']
assert len([x for x in shard['rows'] if x['blocked']])==1
out={'head':'aede4c82f66a925ba9df3fc8588c6e1399c17f61','benchmarkRun':33992947567,'confirmedFailures':confirmed,'originalJmhScoresMatchAllMetricStatusRows':True,'correctness':{'initialBaseCandidateEqual':True,'confirmationBaseCandidateEqual':True,'initialConfirmationEqual':True,'uniqueSignaturesPerSidePhase':3},'inputSha256':files,'scope':'Read-only downloaded Method4 string artifact; no new measurement or runtime cause attribution. Whole-process CPU includes client, reference oracle, engine and background work.'}
(R/'method-confirmed-failures.json').write_text(json.dumps(out,indent=2)+'\n');print(json.dumps(out,indent=2))
