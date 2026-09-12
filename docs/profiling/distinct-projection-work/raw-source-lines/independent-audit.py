from pathlib import Path
from collections import Counter
import json,hashlib,calendar,time
P=Path(__file__).resolve().parent
OLD=Path('/private/tmp/graphite-distinct-phase-details')
RAW='io.johnsonlee.graphite.webgraph.MappedWebGraphBackedGraph.parallelRawDistinctCallSiteStringProjection'
NODE=RAW+'$lambda$32$lambda$31$lambda$30('
APP=('broad-query-pressure-worker [','graphite-cypher-scan-','graphite-callsite-scan-','graphite-callsite-segment-')
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def ns(s):
 main,_,tail=s.rstrip('Z').partition('.')
 return calendar.timegm(time.strptime(main,'%Y-%m-%dT%H:%M:%S'))*10**9+int(tail.ljust(9,'0'))
receipt=json.loads((P/'export-receipt.json').read_text()); previous=json.loads((OLD/'receipt.json').read_text()); summary=json.loads((P/'summary.json').read_text())
assert sha(P/'RawFrameDetails.java')==receipt['sourceSha256']
rows=[];recordings=[];total=Counter();types=Counter();bci=Counter();lines=Counter()
for i in range(1,4):
 p=P/f'phase-{i}.json'; d=json.loads(p.read_text()); old=json.loads((OLD/f'phase-{i}.json').read_text())
 r=next(x for x in receipt['inputs'] if x['phase']==i);assert sha(p)==r['outputSha256']
 assert r['jfrSha256']==previous['inputs'][i-1]['jfrSha256']
 assert d['input']==r['command'][-2] and r['command'][-1]==str(p)
 assert d['rawFamilyCpuEvents']==len(d['events'])
 for e in d['events']:
  assert any(f['method'].startswith(RAW) for f in e['framesLeafFirst']) and not e['truncated']
  assert all(isinstance(f['lineNumber'],int) and isinstance(f['bytecodeIndex'],int) for f in e['framesLeafFirst'])
 assigned=Counter()
 for q in old['queries']:
  for phase in ('initial','provenance'):
   spans=[(ns(c['start']),ns(c['end'])) for c in q['calls'][phase]]
   selected=[]
   for idx,e in enumerate(d['events']):
    t=ns(e['timestamp'])
    if any(a<=t<b for a,b in spans):
     assigned[idx]+=1
     if e['thread'].startswith(APP):selected.append(e)
   expected=Counter()
   metric=q['metrics'][phase].get('cpuSamples')
   if metric:
    for thread,stacks in metric['threadStacks'].items():
     if not thread.startswith(APP):continue
     for stack,w in stacks.items():
      if any(f.startswith(RAW) for f in stack.split(';')):expected[(thread,stack)]+=w
   actual=Counter((e['thread'],';'.join(f['method'].replace(';',':').replace('\n',' ') for f in reversed(e['framesLeafFirst']))) for e in selected)
   assert actual==expected,(i,q['id'],phase,actual-expected,expected-actual)
   if not selected:continue
   node=[e for e in selected if e['framesLeafFirst'][0]['method'].startswith(NODE)]
   distribution=Counter((e['framesLeafFirst'][0]['method'],e['framesLeafFirst'][0]['lineNumber'],e['framesLeafFirst'][0]['bytecodeIndex'],e['framesLeafFirst'][0]['frameType']) for e in node)
   stated=next((r for r in summary['rows'] if (r['phase'],r['id'],r['stage'])==(i,q['id'],phase)),None)
   if stated:
    assert stated['rawApplicationCpuSamples']==len(selected) and stated['nodeLeafSamples']==len(node)
    assert stated['nodeLeafKnownLineSamples']==sum(e['framesLeafFirst'][0]['lineNumber']>0 for e in node)
    assert stated['nodeLeafKnownBciSamples']==sum(e['framesLeafFirst'][0]['bytecodeIndex']>=0 for e in node)
    stated_distribution=Counter({(x['method'],x['lineNumber'],x['bytecodeIndex'],x['frameType']):x['samples'] for x in stated['nodeLeafDistribution']})
    assert stated_distribution==distribution
   if q['id'].endswith('distinct-targeted') and phase=='initial':
    total['targetedRaw']+=len(selected); total['targetedNodeLeaf']+=len(node)
    for e in node:
     f=e['framesLeafFirst'][0]; assert f['lineNumber']>0 and f['bytecodeIndex']>=0;types[f['frameType']]+=1;bci[f['bytecodeIndex']]+=1;lines[f['lineNumber']]+=1
   if q['id'].endswith('distinct-dense') and phase=='provenance':total['denseProvenanceRaw']+=len(selected)
   rows.append({'recording':i,'query':q['id'],'phase':phase,'rawApplicationCpuSamples':len(selected),'nodeLeafSamples':len(node),'threadMethodStackHistogramExactlyMatches':True,'lineBciFrameTypeHistogramMatchesSummary':stated is not None})
 assert all(n==1 for n in assigned.values())
 recordings.append({'recording':i,'exportSha256':sha(p),'phaseJsonSha256':sha(OLD/f'phase-{i}.json'),'rawEvents':len(d['events']),'eventsAssignedToExactlyOnePhase':len(assigned),'unassignedEvents':len(d['events'])-len(assigned),'originalJfrReceiptSha256Matches':True})
assert total=={'targetedRaw':209,'targetedNodeLeaf':98,'denseProvenanceRaw':127}
result={'result':'pass','newCapture':False,'newJavaRun':False,'independentJfrRedecode':False,'scope':'Read-only decoder source audit and independent nanosecond timestamp partition from full exported events, with exact original method/thread stack histograms and enriched metadata counts; no execution/import of parent analyze.py.','summarySha256':sha(P/'summary.json'),'decoderSha256':sha(P/'RawFrameDetails.java'),'totals':dict(total),'targetedNodeLeafFrameTypes':dict(types),'targetedNodeLeafBytecodeIndexes':dict(sorted(bci.items())),'targetedNodeLeafLineNumbers':dict(sorted(lines.items())),'recordings':recordings,'rows':rows,'limits':['Original JFR content hash is matched between retained receipts, not recomputed here. Exporter source and output hashes are checked; no second Java export was run.','Positive line and BCI establish available metadata, not true machine PC attribution.','Synthetic Kotlin source lines require exact class LineNumberTable plus SMAP; do not map to same-numbered repository lines.','Interpreted/C1 labels are recorded frame labels, not proof of causes or a steady-state C2 profile.','98 samples are only targeted initial per-node LEAF samples, a subset of 209 raw-family application CPU samples; dense provenance 127 is a separate inclusive scope.']}
(P/'independent-audit.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
print(dict(total));print('frame types',dict(types));print('recordings',recordings)
