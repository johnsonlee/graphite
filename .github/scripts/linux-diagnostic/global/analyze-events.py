#!/usr/bin/env python3
"""Offline marker/oracle alignment and query-window profile inventory; no causal bottleneck claim."""
from pathlib import Path
from collections import Counter
from decimal import Decimal
import argparse,csv,datetime,hashlib,json,re
p=argparse.ArgumentParser()
for name in ('native-events','observations','run-id','output'):p.add_argument('--'+name,required=True)
p.add_argument('--async-events');p.add_argument('--require-linux-perf',action='store_true');a=p.parse_args()
def events(path):return json.loads(Path(path).read_text())['recording']['events']
def stamp(s):
 m=re.fullmatch(r'(.*T\d\d:\d\d:\d\d)(?:\.(\d+))?(Z|[+-]\d\d:\d\d)',s);assert m,s
 dt=datetime.datetime.fromisoformat(m[1]+m[3].replace('Z','+00:00'))
 return int(dt.timestamp())*1000000000+int((m[2] or '').ljust(9,'0')[:9])
def duration(s):
 if s is None:return 0
 m=re.fullmatch(r'PT(?:(\d+)H)?(?:(\d+)M)?([\d.]+)S',s);assert m,s
 return int((Decimal(m[1] or 0)*3600+Decimal(m[2] or 0)*60+Decimal(m[3]))*1000000000)
def interval(e):v=e['values'];start=stamp(v['startTime']);return start,start+duration(v.get('duration'))
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def thread(v):
 t=v.get('sampledThread') or v.get('eventThread') or {};return f'{t.get("javaThreadId",t.get("osThreadId","unknown"))}:{t.get("javaName",t.get("osName","unknown"))}'
ns=events(a.native_events)
with Path(a.observations).open(newline='') as f:rows=list(csv.DictReader(f,delimiter='\t'))
assert len(rows)==34
markers=[e for e in ns if e['type']=='graphite.GlobalWideQuery'];assert len(markers)==68
byphase={phase:sorted([e for e in markers if e['values']['phase']==phase],key=lambda e:e['values']['ordinal']) for phase in ('execution','observation')}
for phase,es in byphase.items():
 assert len(es)==34 and [e['values']['ordinal'] for e in es]==list(range(1,35))
 for e,r in zip(es,rows):
  v=e['values'];assert v['runId']==a.run_id and v['caseId']==r['id'] and v['shape']==r['shape'] and v['selectivity']==r['selectivity']
  assert v['indexState']=='cold' and v['workloadIdentity']==r['workloadIdentity'] and v['queryText']
  assert v['outcome']=='success' and v['rowCount']==int(r['rowCount'])
  assert v['javaThreadId']==v['eventThread']['javaThreadId'],'Event must commit on the actual owning thread'
  if phase=='observation':
   for prop,col in {'observedLatencyNanos':'latencyNanos','responseBytes':'responseBytes','resultDigest':'digest','graphWorkUnits':'graphWorkUnits','accessedGraphIds':'accessedGraphIds','hitGraphIds':'hitGraphIds','inputSourceCount':'inputSourceCount','parallelScanCount':'parallelScanCount','indexLookupCount':'indexLookupCount','rawScanPeak':'peakActiveWorkers','filteredFastPath':'filteredNodeLimitFastPathExecutions','generalFallback':'generalFallbackExecutions'}.items():
    actual=v[prop];assert actual==(int(r[col]) if isinstance(actual,int) else r[col]),(r['id'],prop,actual,r[col])
  else:assert duration(v['duration'])>0
sources={'native':ns}
if a.async_events:sources['async']=events(a.async_events)
for name,es in sources.items():assert not [e for e in es if e['type']=='jdk.DataLoss'],name
assert a.async_events,'Each diagnostic replay requires two separately written recordings from the same JVM'
jvm_infos={name:[e['values'] for e in es if e['type']=='jdk.JVMInformation'] for name,es in sources.items()}
assert all(jvm_infos.values()),'Missing JVMInformation prevents native/async PID/arguments binding'
ninfo=jvm_infos['native'][0];ainfo=jvm_infos['async'][0]
for key in ('pid','jvmArguments','javaArguments'):assert ninfo[key]==ainfo[key],('JVM identity',key)
start_delta=stamp(ninfo['jvmStartTime'])-stamp(ainfo['jvmStartTime'])
# async field is OS process start; native field is VM initialization. Record, never gate or shift by it.
async_settings=[e['values'] for e in sources['async'] if e['type']=='jdk.ActiveSetting']
assert any(s['name']=='event' and s['value']=='cpu' for s in async_settings),'async recording is not CPU mode'
assert any(s['name']=='interval' and s['value']=='1000000' for s in async_settings),'async recording lacks1ms setting'
engines={s['value'] for s in async_settings if s['name']=='engine'};assert engines,'Missing recorded CPU engine'
if a.require_linux_perf:assert engines=={'perf_events'},('Linux CPU engine must be perf_events; no timer fallback',engines)
assert any(e['type']=='jdk.ActiveSetting' for e in ns) and any(e['type']=='jdk.ActiveRecording' for e in ns)
assert any(e['type'].endswith('ExecutionSample') for e in sources['async']),'No async CPU samples in full34 replay'
request=byphase['execution'][0]['values']['eventThread']
assert all(e['values']['eventThread']['javaThreadId']==request['javaThreadId'] for e in byphase['execution'])
matched_async_samples=[]
execution_windows=[interval(e) for e in byphase['execution']]
for e in sources['async']:
 if not e['type'].endswith('ExecutionSample'):continue
 v=e['values'];t=v.get('sampledThread') or v.get('eventThread')
 if not t or t.get('osThreadId')!=request['osThreadId']:continue
 when=interval(e)[0]
 if not any(lo<=when<=hi for lo,hi in execution_windows):continue
 frames=(v.get('stackTrace') or {}).get('frames') or []
 owners={((f.get('method') or {}).get('type') or {}).get('name','').replace('/','.') for f in frames}
 if any(owner=='io.johnsonlee.graphite.cypher.QueryPipeline' or owner.startswith('io.johnsonlee.graphite.webgraph.LargeBroadQueryPressureBenchmark') for owner in owners):
  matched_async_samples.append({'thread':t,'startTime':v['startTime'],'benchmarkFrameOwners':sorted(owner for owner in owners if owner.startswith('io.johnsonlee.graphite.'))})
assert matched_async_samples,'No actual benchmark CPU sample on matching OS request thread inside an execution marker window'
alignment={'pid':ninfo['pid'],'jvmArguments':ninfo['jvmArguments'],'javaArguments':ninfo['javaArguments'],'jvmStartTimeDeltaNanos':start_delta,'requestThread':request,'matchedAsyncBenchmarkSamples':matched_async_samples,'threadAliasPolicy':'OS tid plus real benchmark/QueryPipeline frame in execution window; Java id/name aliases are retained and not forced equal','startTimeSemantics':'native VM initialization minus async OS process start; no identity threshold or clock correction','clockAdjustmentNanos':0,'asyncActiveSettings':async_settings,'nativeActiveSettings':[e['values'] for e in ns if e['type']=='jdk.ActiveSetting'],'cpuEngines':sorted(engines),'requireLinuxPerfEvents':a.require_linux_perf,'nativeJvmInformation':ninfo,'asyncJvmInformation':ainfo}
results=[]
for ex,ob,r in zip(byphase['execution'],byphase['observation'],rows):
 start,end=interval(ex);obs_start,_=interval(ob);assert end<=obs_start
 result={'ordinal':ex['values']['ordinal'],'id':r['id'],'executionStartNanosUtc':start,'executionEndNanosUtc':end,'executionDurationNanos':end-start,'observedLatencyNanos':int(r['latencyNanos']),'executionThread':thread(ex['values']),'profiles':{}}
 for name,es in sources.items():
  overlaps=[]
  for e in es:
   if e['type']=='graphite.GlobalWideQuery':continue
   lo,hi=interval(e)
   if (start<=lo<=end) if lo==hi else (lo<end and hi>start):overlaps.append(e)
  samples=[e for e in overlaps if e['type'].endswith('ExecutionSample') or e['type']=='jdk.NativeMethodSample']
  frames=Counter();missing=0
  for e in samples:
   stack=e['values'].get('stackTrace') or {};fs=stack.get('frames') or []
   if not fs:missing+=1
   names={f.get('method',{}).get('type',{}).get('name','?')+'.'+f.get('method',{}).get('name','?') for f in fs}
   frames.update(names)
  result['profiles'][name]={'eventCounts':dict(Counter(e['type'] for e in overlaps)),'cpuSamples':len(samples),'samplesWithoutFrames':missing,'truncatedStackSamples':sum(bool((e['values'].get('stackTrace') or {}).get('truncated')) for e in samples),'sampledThreads':dict(Counter(thread(e['values']) for e in samples)),'inclusiveFrameSampleCounts':frames.most_common(60),'threadCpuLoadReadings':[e['values'] for e in overlaps if e['type']=='jdk.ThreadCPULoad'],'overlappingCompilation':[e['values'] for e in overlaps if e['type']=='jdk.Compilation'],'overlappingGc':[e['values'] for e in overlaps if e['type'] in ('jdk.GarbageCollection','jdk.GCPhasePause')]}
 results.append(result)
# Execute markers must remain serial in the unchanged single request-executor workload.
assert all(a['executionEndNanosUtc']<=b['executionStartNanosUtc'] for a,b in zip(results,results[1:]))
out={'passed':True,'queries':34,'executionEvents':34,'observationEvents':34,'runId':a.run_id,'alignment':alignment,'markerObservationFieldsMatch':True,'eventTypeInventory':{name:dict(Counter(e['type'] for e in es)) for name,es in sources.items()},'queryProfiles':results,'inputs':{str(path):sha(path) for path in (a.native_events,a.observations,*([a.async_events] if a.async_events else []))},'limits':['Execution marker duration encloses pressureQueryExecutor creation and execute plus marker result assignment; excludes caller submission/queue wait/canonicalization. Original TSV latency retains original start/end plus diagnostic overhead.','Instant observation is emitted by JMH caller after sample generation; it is not an execution interval.','CPU samples are counts, not CPU time. Inclusive frame counts overlap and cannot be summed. Native and async streams are from the same full34 replay but are different samplers; their counts/weights are not added.','ThreadCPULoad readings average an interval that can straddle a short query; do not multiply their value by query duration to claim precise query CPU.','Events overlapping query envelopes include JVM/compiler/GC and incidental background threads. Thread names/stacks are required to separate application activity.','No scheduler task instrumentation is added: native park/monitor stacks and execution samples can suggest waiting, but cannot prove group eligibility, child lifetime or per-role queue contention.','No DataLoss event was recorded; this does not prove every expected CPU sample was emitted. Absence of samples for a short query is not absence of CPU work.','No latency acceptance or10x claim from instrumented runs.']}
Path(a.output).write_text(json.dumps(out,indent=2)+'\n')
