"""Independent exported-native-JSON/TSV/catalog audit. No Java or parent verifier execution."""
from pathlib import Path
from collections import Counter, defaultdict
from datetime import datetime, timezone
from decimal import Decimal
import csv, gzip, hashlib, json, re, shlex, xml.etree.ElementTree as ET
P=Path(__file__).resolve().parent
D=Path('/private/tmp/graphite-query-marker-diagnostic')
PRIOR=Path('/private/tmp/graphite-attempt140._5jztd0a/old34-pairs')
BASE=Path('/private/tmp/graphite-next-baseline.T2FTs9/graphite-webgraph/build/libs/webgraph-1.0.0-SNAPSHOT-jmh.jar')
BASE_SHA='a5c2db2b0020798488916ec86902459d1044a7dcef606a73e00055883cdf5abe'
OVERLAY_SHA='2728888d2820fc9721149302612c94b1001bfd38ee5a8c69244fed59fc9b6ef9'
MARKER='graphite.diagnostic.QueryExecution'
NAMES=['control','profile-1','profile-2','profile-3']
def load(path):return json.loads(Path(path).read_text())
def sha(path):
 h=hashlib.sha256()
 with Path(path).open('rb') as stream:
  for block in iter(lambda:stream.read(1024*1024),b''):h.update(block)
 return h.hexdigest()
def rows(path):
 with Path(path).open() as f:return list(csv.DictReader(f,delimiter='\t'))
def ns(text):
 m=re.fullmatch(r'(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)(?:\.(\d{1,9}))?(Z|[+-]\d\d:\d\d)',text);assert m,text
 instant=datetime.fromisoformat(m[1]+m[3].replace('Z','+00:00'))
 seconds=int((instant-datetime(1970,1,1,tzinfo=timezone.utc)).total_seconds())
 return seconds*10**9+int((m[2] or '').ljust(9,'0'))
def duration(text):
 m=re.fullmatch(r'PT(\d+(?:\.\d+)?)S',text);assert m,text
 result=Decimal(m[1])*10**9;assert result==int(result);return int(result)
def canonical(value):return json.dumps(value,ensure_ascii=True,sort_keys=True,separators=(',',':'))
assert sha(BASE)==BASE_SHA and sha(D/'diagnostic-jmh.jar')==OVERLAY_SHA
handoff=load(D/'handoff-receipt.json');assert handoff['ready'] and handoff['diagnosticJarSha256']==OVERLAY_SHA
for name,value in handoff['fileSha256'].items():assert sha(D/name)==value,(name,'overlay evidence changed')
cat_receipt=load(P/'catalog-receipt.json');catalog=load(P/'original-catalog.json')
assert sha(P/'original-catalog.json')==cat_receipt['outputSha256']
assert sha(P/'ExportOriginalCatalog.java')==cat_receipt['sourceSha256'] and sha(P/'ExportOriginalCatalog.class')==cat_receipt['classSha256']
assert cat_receipt['jar']==str(BASE) and cat_receipt['jarSha256Before']==cat_receipt['jarSha256After']==BASE_SHA
assert len(catalog)==34 and [x['ordinal'] for x in catalog]==list(range(1,35))
assert all(isinstance(k,str) and isinstance(v,str) for q in catalog for k,v in q['parameters'].items())
assert sum(bool(q['parameters']) for q in catalog)==3
manifest=Path(cat_receipt['manifest']);assert sha(manifest)==cat_receipt['manifestSha256']
manifest_ids=[line.split('\t')[0] for line in manifest.read_text().splitlines() if line and not line.startswith('#')]
assert len(manifest_ids)==64
prior=rows(PRIOR/'base-global-wide-1.tsv')
assert [q['id'] for q in catalog]==[r['id'] for r in prior]
fields=['id','family','shape','selectivity','operator','boundary','projection','targetGraphId','workloadIdentity','limit','outcome','rowCount','responseBytes','digest']
oracle=[line.split('|') for line in (PRIOR/'oracle.correctness').read_text().splitlines() if line and not line.startswith('#')]
assert len(oracle)==34 and all(len(r)==14 for r in oracle)
assert [[r[k] for k in fields] for r in prior]==oracle
plan=load(P/'capture-plan.json');template=load(PRIOR/'base-global-wide-1-command.json')
run_records=load(P/'control-runs.json')+load(P/'profile-runs.json')
assert [r['name'] for r in run_records]==NAMES
assert all(r['exitCode']==0 and r['diagnosticJarSha256Before']==r['diagnosticJarSha256After']==OVERLAY_SHA and r['baseJarSha256After']==BASE_SHA for r in run_records)
assert all(run_records[i]['endedEpoch']<run_records[i+1]['startedEpoch'] for i in range(3))
inventories=[load(P/(mode+'-graph-content-'+when+'.json')) for mode in ['control','profile'] for when in ['before','after']]
assert all(i==inventories[0] for i in inventories)
assert [g['id'] for g in inventories[0]]==manifest_ids
assert sum(len(g['files']) for g in inventories[0])==1088
for graph in inventories[0]:
 assert len({f['path'] for f in graph['files']})==len(graph['files'])
 assert all(f['size']>=0 and re.fullmatch('[0-9a-f]{64}',f['sha256']) for f in graph['files'])
for mode,count in [('control',1),('profile',3)]:
 complete=load(P/(mode+'-completion.json'))
 assert complete['processesTerminal'] and complete['forks']==count and complete['graphFiles']==1088 and complete['graphsUnchanged']
 assert complete['jarSha256']==OVERLAY_SHA and complete['baseSha256']==BASE_SHA and not complete['newProductionCandidate']
 assert sha(P/(mode+'.jfc'))==plan['configs'][mode+'.jfc']
results=[]
for name,run in zip(NAMES,run_records):
 mode='control' if name=='control' else 'profile'
 export=load(P/(name+'-export-receipt.json'))
 assert sha(P/(name+'.jfr'))==export['jfrSha256BeforeAfter']
 assert sha(P/(name+'.events.json'))==export['eventsJsonSha256']
 assert sha(P/(name+'.event-types.json'))==export['eventTypesSha256']
 assert sha(P/'JfrEventTypes.java')==export['metadataSourceSha256'] and sha(P/'JfrEventTypes.class')==export['metadataClassSha256']
 assert export['captureProcessesAlreadyTerminal']
 assert export['commands'][0][1:3]==['print','--json'] and export['commands'][0][-1]==str(P/(name+'.jfr'))
 exported_types=export['commands'][0][export['commands'][0].index('--events')+1].split(',')
 assert export['commands'][1][-2:]==[str(P/(name+'.jfr')),str(P/(name+'.event-types.json'))]
 events=load(P/(name+'.events.json'))['recording']['events']
 counts=Counter(e['type'] for e in events);assert set(counts)<=set(exported_types)
 assert counts[MARKER]==34 and counts['jdk.DataLoss']==0
 markers=[e['values'] for e in events if e['type']==MARKER]
 assert [m['ordinal'] for m in markers]==list(range(1,35))
 types=load(P/(name+'.event-types.json'));type_names={t['id']:t['name'] for t in types}
 assert len(type_names)==len(types)
 settings=defaultdict(lambda:defaultdict(list))
 for event in events:
  if event['type']!='jdk.ActiveSetting':continue
  v=event['values'];assert v['id'] in type_names
  settings[type_names[v['id']]][v['name']].append({'value':v['value'],'startNanos':ns(v['startTime'])})
 expected={MARKER:{'enabled':'true','threshold':'0 ns','stackTrace':'false'},'jdk.DataLoss':{'enabled':'true'},'jdk.JVMInformation':{'enabled':'true'},'jdk.ActiveSetting':{'enabled':'true'},'jdk.ActiveRecording':{'enabled':'true'}}
 for t in ['jdk.ExecutionSample','jdk.Compilation','jdk.CompilationFailure','jdk.Deoptimization','jdk.GarbageCollection','jdk.GCPhasePause']:
  expected[t]={'enabled':'true' if mode=='profile' else 'false'}
 if mode=='profile':
  expected['jdk.ExecutionSample']['period']='2 ms';expected['jdk.Compilation']['threshold']='0 ms';expected['jdk.Deoptimization']['stackTrace']='true'
 jfc=ET.parse(P/(mode+'.jfc')).getroot()
 for e in jfc.findall('event'):
  for s in e.findall('setting'):expected.setdefault(e.get('name'),{})[s.get('name')]=s.text
 for t,options in expected.items():
  for setting,value in options.items():
   actual=settings[t][setting]
   assert actual and all(a['value']==value and a['startNanos']<=ns(markers[0]['startTime']) for a in actual),(name,t,setting,value,actual)
 jvms=[e['values'] for e in events if e['type']=='jdk.JVMInformation'];recordings=[e['values'] for e in events if e['type']=='jdk.ActiveRecording']
 assert len(jvms)==len(recordings)==1
 jvm=jvms[0];recording=recordings[0]
 assert recording['destination']==str(P/(name+'.jfr'))
 assert '17.0.18+0' in jvm['jvmVersion'] and 'bsd-aarch64' in jvm['jvmVersion']
 assert jvm['jvmFlags'] is None and jvm['javaArguments'].startswith('org.openjdk.jmh.runner.ForkedMain 127.0.0.1 ')
 command=load(P/(name+'-command.json'));expected_command=list(template)
 expected_command[2]=str(D/'diagnostic-jmh.jar');expected_command[expected_command.index('-rff')+1]=str(P/(name+'.jmh.json'))
 expected_command[-1]=expected_command[-1].replace(str(PRIOR/'base-global-wide-1.tsv'),str(P/(name+'.tsv')))
 expected_command[-1]+=f' -XX:StartFlightRecording=settings={P/(mode+".jfc")},filename={P/name}.jfr,dumponexit=true -XX:FlightRecorderOptions=stackdepth=256'
 if mode=='profile':expected_command[-1]+=f' -XX:+UnlockDiagnosticVMOptions -XX:+LogCompilation -XX:LogFile={P/name}.compilation.xml'
 assert command==expected_command
 requested=shlex.split(command[command.index('-jvmArgs')+1]);actual_args=shlex.split(jvm['jvmArguments'])
 assert actual_args[:len(requested)]==requested
 extras=actual_args[len(requested):]
 assert extras[:3]==['-XX:+UnlockDiagnosticVMOptions','-XX:+UnlockExperimentalVMOptions','-DcompilerBlackholesEnabled=true']
 assert len(extras)==4 and extras[3].startswith('-XX:CompileCommandFile=')
 assert not any(re.search('agentpath|agentlib|Xcomp|Xbatch|CompileThreshold|TieredStopAtLevel',x) for x in actual_args)
 jmh=load(P/(name+'.jmh.json'));assert len(jmh)==1
 assert jmh[0]['jvmArgs']==requested and jmh[0]['warmupIterations']==0 and jmh[0]['measurementIterations']==1 and jmh[0]['forks']==1
 assert command[command.index('-prof')+1]=='gc'
 xml_identity=None
 if mode=='profile':
  xp=P/(name+'.compilation.xml');b=xp.read_bytes() if xp.exists() else gzip.decompress(Path(str(xp)+'.gz').read_bytes())
  x=ET.fromstring(b)
  assert int(x.get('process'))==jvm['pid'] and shlex.split(x.findtext('vm_arguments/args'))==actual_args
  assert '\njava.class.path='+str(D/'diagnostic-jmh.jar')+'\n' in x.findtext('vm_arguments/properties')
  xml_identity={'process':x.get('process'),'logTimeMillis':x.get('time_ms'),'jvmStartTime':jvm['jvmStartTime'],'xmlUncompressedSha256':hashlib.sha256(b).hexdigest(),'samePidVmArgumentsAndOverlayClasspath':True}
 observed=rows(P/(name+'.tsv'));assert len(observed)==34 and [[r[k] for k in fields] for r in observed]==oracle
 assert set(observed[0])==set(prior[0])
 nonlatency=[{'id':r['id'],'fields':[k for k in r if k!='latencyNanos' and r[k]!=b[k]]} for r,b in zip(observed,prior)]
 assert all(not r['fields'] for r in nonlatency)
 windows=[];thread_keys=set()
 for m,q,r in zip(markers,catalog,observed):
  assert m['query']==q['query'] and m['parametersJson']==canonical(q['parameters']) and json.loads(m['parametersJson'])==q['parameters']
  assert m['parameterEncoding']=='sorted-string-null-json-v1'
  assert m['success'] is True and m['exceptionClass']=='' and m['markerFailuresBefore']==0 and m['stackTrace'] is None
  thread=m['eventThread'];assert m['javaThreadId']==thread['javaThreadId']>0
  assert m['javaThreadName']==thread['javaName']==thread['osName']=='broad-query-pressure-worker'
  assert thread['osThreadId']>0
  thread_keys.add((thread['javaThreadId'],thread['osThreadId'],thread['javaName']))
  start=ns(m['startTime']);length=duration(m['duration']);end=start+length;latency=int(r['latencyNanos'])
  assert length>0 and latency>=length and r['outcome']=='success'
  assert start>=ns(jvm['jvmStartTime']) and start>=ns(recording['recordingStart'])
  assert Decimal(str(run['startedEpoch']))*10**9<=start<end<=Decimal(str(run['endedEpoch']))*10**9
  if windows:assert windows[-1]['endNanos']<=start
  windows.append({'ordinal':m['ordinal'],'id':q['id'],'querySha256':hashlib.sha256(q['query'].encode()).hexdigest(),'parametersJson':m['parametersJson'],'startNanos':start,'endNanos':end,'durationNanos':length,'tsvLatencyNanos':latency,'untracedTsvNanos':latency-length})
 assert len(thread_keys)==1
 parent=load(P/(name+'-verification.json'))
 assert parent['passed'] and parent['markers']==parent['oracleSignatures']==34 and parent['nonLatencyDifferencesVersusPriorBase']==[]
 assert parent['eventTypeCounts']==dict(counts)
 assert len(parent['windows'])==34
 for own,reported,q in zip(windows,parent['windows'],catalog):
  assert own['id']==reported['id'] and own['ordinal']==reported['ordinal']
  assert own['startNanos']==reported['startEpochNanos'] and own['endNanos']==reported['endEpochNanos']
  assert own['durationNanos']==reported['markerDurationNanos'] and own['tsvLatencyNanos']==reported['tsvLatencyNanos']
  assert reported['query']==q['query'] and reported['parameters']==q['parameters'] and reported['javaThreadId']==markers[0]['javaThreadId']
 results.append({'name':name,'passed':True,'markerCount':34,'oracleSignatureCount':34,'nonLatencyDifferences':[],'windows':windows,'workerThreadIdentities':list(thread_keys),'eventCounts':dict(counts),'recordedDataLoss':0,'activeSettingsResolvedByEventTypeId':{t:{s:records for s,records in options.items()} for t,options in settings.items() if t in expected},'jvmInformation':jvm,'activeRecording':recording,'automaticJmhOptions':extras,'profileXmlSameProcessIdentity':xml_identity,'hostIdentityLimits':'JVM version/platform and native/Java thread/process identities present; OSInformation/CPUInformation/hostname not exported. No independent physical-host identity claim.','eventExportSha256':export['eventsJsonSha256'],'jfrSha256':export['jfrSha256BeforeAfter'],'eventTypesSha256':export['eventTypesSha256'],'parentVerifierSummaryMatches':True})
assert len({r['jvmInformation']['pid'] for r in results})==4
assert len({r['jvmInformation']['jvmVersion'] for r in results})==1
input_files=[x for x in P.iterdir() if x.is_file() and x.name in ['original-catalog.json','catalog-receipt.json','capture-plan.json','capture.py','root-overlay-audit.json','control.jfc','profile.jfc','control-runs.json','profile-runs.json','control-completion.json','profile-completion.json','ExportOriginalCatalog.java','JfrEventTypes.java'] or x.is_file() and any(x.name.endswith(s) for s in ['-command.json','-export-receipt.json','-graph-content-before.json','-graph-content-after.json','.tsv','.jmh.json','.log'])]
output={'passed':True,'ranParentVerifier':False,'startedJavaBuildOrRecording':False,'independentMethod':'Read native jfr-print JSON, exact integer-nanosecond marker checks, original catalog and TSV/oracle comparisons; resolve actual ActiveSetting IDs through per-recording event metadata; cross-check commands/JVMInformation/profile XML PID/classpath, output and input hashes.','totalMarkers':136,'totalOracleSignatures':136,'allNonLatencyFieldsEqualPriorBase':True,'baseJarCurrentSha256':sha(BASE),'overlayJarCurrentSha256':sha(D/'diagnostic-jmh.jar'),'overlayHandoffEvidenceHashesUnchanged':True,'catalogSha256':sha(P/'original-catalog.json'),'manifestSha256':sha(manifest),'graphs':64,'graphFiles':1088,'allFourCapturedGraphInventoriesEqual':True,'independentlyRehashedLargeGraphCorpus':False,'allFourProcessesExitedZeroAndSequential':True,'results':results,'inputSha256':{x.name:sha(x) for x in input_files},'limits':['No query/graph execution, Java build, new JFR capture or parent verifier invocation during this audit.','Native JSON was produced by the recorded jfr-print commands; this audit checks raw JFR/export hashes and JSON contents, not a second binary JFR decoding. Only explicitly exported event types are covered.','DataLoss is enabled and no DataLoss events are recorded; this does not prove absence of every possible sampling blind spot.','Graph integrity uses complete captured before/after path/size/SHA inventories and reviewed hashing implementation; the large corpus was not rehashed again.','Host OS/CPU/hostname events are absent from these exports; JVM platform/build, process and thread identities are available, with profile XML PID/arguments/classpath cross-check.','Marker preparation/commit, queuing and other work outside execute cause positive TSV gaps. No positive-gap upper bound is imposed; all trace durations are strictly within TSV latency.','Marker/JFR/LogCompilation perturb execution. No performance gain, regression cause, exclusive CPU cost, or causal query ownership claim is made.','CompilationFailure events (three per full recording) are preserved in totals and are not query failures. Their cause and event-to-query attribution are outside this bounded audit.']}
(P/'independent-marker-audit.json').write_text(json.dumps(output,indent=2,ensure_ascii=False)+'\n')
print(json.dumps({'passed':True,'markers':136,'oracle':136,'recordings':[{'name':r['name'],'events':r['eventCounts'],'maxUntracedTsvNanos':max(w['untracedTsvNanos'] for w in r['windows'])} for r in results]},ensure_ascii=False))
