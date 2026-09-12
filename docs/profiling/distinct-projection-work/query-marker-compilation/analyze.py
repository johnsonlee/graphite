import collections
import decimal
import hashlib
import json
import pathlib
import runpy
import xml.etree.ElementTree as ET

ROOT=pathlib.Path(__file__).parent
helpers=runpy.run_path(str(ROOT/'verify-markers.py'))
instant_ns=helpers['instant_ns'];duration_ns=helpers['duration_ns']
OWNER='io.johnsonlee.graphite.webgraph.MappedWebGraphBackedGraph'
NODE=OWNER+'.parallelRawDistinctCallSiteStringProjection$lambda$32$lambda$31$lambda$30'
RAW=OWNER+'.parallelRawDistinctCallSiteStringProjection'
PIPELINE='io.johnsonlee.graphite.cypher.QueryPipeline.'
INITIAL=PIPELINE+'executeIndexedDistinctStringProjection$projectSource'
PROVENANCE=PIPELINE+'executeIndexedDistinctStringProjection$lambda$155$lambda$154'

def method(m): return (m.get('type',{}).get('name','').replace('/','.')+'.'+m.get('name','')) if m else ''
def xml_method(m):
    pieces=m.split(' ',2)
    return pieces[0]+'.'+pieces[1] if len(pieces)==3 else m
def frames(v):
    return [{'method':method(f['method']),'descriptor':f['method'].get('descriptor'),'bci':f.get('bytecodeIndex'),'line':f.get('lineNumber'),'type':f.get('type')} for f in (v.get('stackTrace') or {}).get('frames',[])]
def phase(stack):
    a=any(f['method']==INITIAL for f in stack);b=any(f['method']==PROVENANCE for f in stack)
    assert not(a and b)
    return 'initial-stack' if a else 'provenance-stack' if b else 'unresolved'
def application(thread):
    return thread=='broad-query-pressure-worker' or thread.startswith(('graphite-cypher-scan-','graphite-callsite-scan-','graphite-callsite-segment-'))

summary={'scope':'Frozen production classes, benchmark-only query markers, native JFR and compilation logs. Query ownership uses JFR clock only, not a guessed XML clock conversion.','runs':[]}
for number in range(1,4):
    name=f'profile-{number}'
    events=json.loads((ROOT/(name+'.events.json')).read_text())['recording']['events']
    verified=json.loads((ROOT/(name+'-verification.json')).read_text());assert verified['passed']
    windows=verified['windows']
    def owner(t):
        found=[w for w in windows if w['startEpochNanos']<=t<w['endEpochNanos']]
        assert len(found)<=1
        return found[0] if found else None
    def overlaps(start,end):
        return [{'id':w['id'],'overlapNanos':min(end,w['endEpochNanos'])-max(start,w['startEpochNanos'])} for w in windows if max(start,w['startEpochNanos'])<min(end,w['endEpochNanos'])]
    xml=ET.parse(ROOT/(name+'.compilation.xml')).getroot();tty=list(xml.find('tty'))
    nmethods={e.get('compile_id'):e for e in tty if e.tag=='nmethod'}
    raw_nmethods={key:e for key,e in nmethods.items() if xml_method(e.get('method',''))==NODE}
    raw_ids=set(raw_nmethods)
    raw_events=[];callback_deopts=[];raw_stack_deopts=[];compilations=[];sample_rows=[];gc_pauses=[];compilation_failures=[]
    byquery={w['id']:{'id':w['id'],'markerDurationNanos':w['markerDurationNanos'],'tsvLatencyNanos':w['tsvLatencyNanos'],'allJavaSnapshots':0,'applicationJavaSnapshots':0,'rawStackSnapshots':0,'nodeCallbackSnapshots':0,'nodeCallbackLeafSnapshots':0,'nodeFrameTypes':collections.Counter(),'nodePhaseStacks':collections.Counter(),'applicationLeafMethods':collections.Counter(),'nodeSampleIndices':[],'rawDeoptIndices':[],'rootCallbackDeoptIndices':[]} for w in windows}
    for i,e in enumerate(events):
        kind=e['type'];v=e['values'];start=instant_ns(v['startTime']);q=owner(start);stack=frames(v)
        if kind=='jdk.Compilation':
            if method(v['method'])!=NODE:continue
            cid=str(v['compileId']);assert cid in raw_nmethods
            assert xml_method(raw_nmethods[cid].get('method',''))==method(v['method'])
            assert raw_nmethods[cid].get('compiler')==v['compiler']
            assert int(raw_nmethods[cid].get('level'))==v['compileLevel']
            end=start+duration_ns(v['duration'])
            compilations.append({'eventIndex':i,'compileId':cid,'compiler':v['compiler'],'level':v['compileLevel'],'succeeded':v['succeded'],'isOsr':v['isOsr'],'startEpochNanos':start,'endEpochNanos':end,'durationNanos':duration_ns(v['duration']),'startQuery':q['id'] if q else None,'endQuery':owner(end)['id'] if owner(end) else None,'queryOverlaps':overlaps(start,end),'xmlNmethod':raw_nmethods[cid].attrib,'observedJfrEndMinusXmlStampNanos':end-int(decimal.Decimal(raw_nmethods[cid].get('stamp'))*1_000_000_000)})
        elif kind=='jdk.Deoptimization':
            cid=str(v['compileId']);isroot=cid in raw_ids;inraw=any(f['method'].startswith(RAW) for f in stack)
            if not(isroot or inraw):continue
            record={'eventIndex':i,'query':q['id'] if q else None,'startEpochNanos':start,'nanosIntoQuery':start-q['startEpochNanos'] if q else None,'nanosUntilQueryEnd':q['endEpochNanos']-start if q else None,'compileId':cid,'compiledRootMethod':xml_method(nmethods[cid].get('method','')) if cid in nmethods else None,'rootNmethodIsNodeCallback':isroot,'compiler':v['compiler'],'trapMethod':method(v['method']),'bci':v['bci'],'reason':v['reason'],'action':v['action'],'thread':v['eventThread']['javaName'],'threadId':v['eventThread']['javaThreadId'],'phaseFromStack':phase(stack),'stackTruncated':(v.get('stackTrace') or {}).get('truncated'),'frames':stack}
            raw_stack_deopts.append(record)
            if q:byquery[q['id']]['rawDeoptIndices'].append(i)
            if isroot:
                assert v['action']=='reinterpret'
                callback_deopts.append(record)
                if q:byquery[q['id']]['rootCallbackDeoptIndices'].append(i)
        elif kind=='jdk.ExecutionSample':
            thread=(v.get('sampledThread') or {}).get('javaName','')
            isapp=application(thread);inraw=any(f['method'].startswith(RAW) for f in stack)
            node=next((f for f in stack if f['method']==NODE),None)
            record={'eventIndex':i,'startEpochNanos':start,'query':q['id'] if q else None,'thread':thread,'threadId':(v.get('sampledThread') or {}).get('javaThreadId'),'applicationThread':isapp,'state':v.get('state'),'stackTruncated':(v.get('stackTrace') or {}).get('truncated'),'rawStack':inraw,'nodeFrame':node,'phaseFromStack':phase(stack),'frames':stack}
            sample_rows.append(record)
            if not q:continue
            metrics=byquery[q['id']];metrics['allJavaSnapshots']+=1
            if isapp:
                metrics['applicationJavaSnapshots']+=1
                if stack:metrics['applicationLeafMethods'][stack[0]['method']]+=1
            if inraw:metrics['rawStackSnapshots']+=1
            if node:
                assert isapp
                metrics['nodeCallbackSnapshots']+=1;metrics['nodeFrameTypes'][node['type']]+=1
                metrics['nodeCallbackLeafSnapshots']+=bool(stack and stack[0]['method']==NODE)
                metrics['nodePhaseStacks'][record['phaseFromStack']]+=1;metrics['nodeSampleIndices'].append(i)
        elif kind=='jdk.CompilationFailure':
            compilation_failures.append({'eventIndex':i,'startEpochNanos':start,'query':q['id'] if q else None,**v})
        elif kind=='jdk.GCPhasePause':
            end=start+duration_ns(v['duration']);overlap=overlaps(start,end)
            if overlap:gc_pauses.append({'eventIndex':i,'startEpochNanos':start,'endEpochNanos':end,'durationNanos':end-start,'queryOverlaps':overlap})
    for e in tty:
        if e.get('compile_id') in raw_ids:raw_events.append({'tag':e.tag,**e.attrib,'frames':[j.attrib for j in e.findall('jvms')]})
    jfr_keys=collections.Counter((e['compileId'],e['reason'],e['action'],e['trapMethod'],str(e['bci'])) for e in callback_deopts)
    xml_keys=collections.Counter()
    for e in raw_events:
        if e['tag']=='uncommon_trap':
            f=e['frames'][0];xml_keys[(e['compile_id'],e['reason'],e['action'],xml_method(f['method']),f['bci'])]+=1
    assert jfr_keys==xml_keys,(name,jfr_keys,xml_keys)
    for row in byquery.values():
        row['nodeFrameTypes']=dict(row['nodeFrameTypes']);row['nodePhaseStacks']=dict(row['nodePhaseStacks']);row['applicationLeafMethods']=dict(row['applicationLeafMethods'])
    output={'name':name,'querySummaries':list(byquery.values()),'rootCallbackCompilations':compilations,'rootCallbackDeoptimizations':callback_deopts,'rawStackDeoptimizations':raw_stack_deopts,'rootCallbackXmlRuntimeEvents':raw_events,'jfrAndXmlRootTrapIdentityCountsMatch':True,'allJavaSnapshots':len(sample_rows),'snapshotsWithinQueries':sum(r['query'] is not None for r in sample_rows),'rawNodeSnapshotsOutsideQueries':sum(r['nodeFrame'] is not None and r['query'] is None for r in sample_rows),'nodeSamples':[r for r in sample_rows if r['nodeFrame'] is not None],'gcPauseOverlaps':gc_pauses,'compilationFailures':compilation_failures,'inputEventsSha256':hashlib.sha256((ROOT/(name+'.events.json')).read_bytes()).hexdigest(),'inputCompilationXmlSha256':hashlib.sha256((ROOT/(name+'.compilation.xml')).read_bytes()).hexdigest()}
    assert output['rawNodeSnapshotsOutsideQueries']==0
    (ROOT/(name+'-analysis.json')).write_text(json.dumps(output,indent=2)+'\n')
    summary['runs'].append(output)
summary['limits']=['Native ExecutionSample is a periodic Java-thread stack snapshot, not process CPU samples; samples times period is not CPU time.','JIT compiler native threads are not represented by this Java execution sampler. Compilation duration is elapsed compiler activity, not exclusive CPU.','JFR frame type JIT compiled does not distinguish C1 from C2.','Runtime deoptimization events have no cost duration; multiple threads can report the same invalidated compilation.','Only exact pipeline frames support initial/provenance stack labels; absent caller frames remain unresolved.','Markers and native recording/logging perturb execution. No causal comparison with previous runs or latency gain is claimed.','JFR query ownership uses its own timestamps. XML compile identity matches do not establish a nanosecond clock conversion.']
(ROOT/'analysis.json').write_text(json.dumps(summary,indent=2)+'\n')
for run in summary['runs']:
    print(json.dumps({'name':run['name'],'rootDeopts':[{'query':d['query'],'bci':d['bci'],'remainingMs':d['nanosUntilQueryEnd']/1e6,'phase':d['phaseFromStack']} for d in run['rootCallbackDeoptimizations']],'targetSummaries':[{k:q[k] for k in ['id','markerDurationNanos','applicationJavaSnapshots','nodeCallbackSnapshots','nodeFrameTypes','nodePhaseStacks']} for q in run['querySummaries'] if 'distinct-targeted' in q['id'] or 'distinct-dense' in q['id']]}))
