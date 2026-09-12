"""Read exported JFR JSON and same-fork HotSpot XML. No JVM or measurement calls."""
import calendar, collections, csv, datetime, decimal, hashlib, json, pathlib, re
import xml.etree.ElementTree as ET
ROOT=pathlib.Path(__file__).resolve().parent
FIELDS=['id','family','shape','selectivity','operator','boundary','projection','targetGraphId','workloadIdentity','limit','outcome','rowCount','responseBytes','digest']
ORACLE=pathlib.Path('/private/tmp/graphite-attempt140._5jztd0a/old34-pairs/oracle.correctness')
CALLBACK='parallelRawDistinctCallSiteStringProjection$lambda$32$lambda$31$lambda$30'
OWNER='io.johnsonlee.graphite.webgraph.MappedWebGraphBackedGraph'
PIPELINE='io.johnsonlee.graphite.cypher.QueryPipeline'
PHASES={'executeIndexedDistinctStringProjection$projectSource':'initial','executeIndexedDistinctStringProjection$lambda$155$lambda$154':'provenance'}
def instant(s):
 m=re.fullmatch(r'(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)(?:\.(\d{1,9}))?(Z|[+-]\d\d:\d\d)',s);assert m,s
 d=datetime.datetime.fromisoformat(m[1]+m[3].replace('Z','+00:00'))
 return calendar.timegm(d.utctimetuple())*10**9+int((m[2] or '').ljust(9,'0'))
def duration(s):
 m=re.fullmatch(r'PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?',s);assert m,s
 return int((decimal.Decimal(m[1] or 0)*3600+decimal.Decimal(m[2] or 0)*60+decimal.Decimal(m[3] or 0))*10**9)
def method(m):return m['type']['name'].replace('/','.')+' '+m['name']+' '+m['descriptor']
def stack(v):
 s=v.get('stackTrace');return None if s is None else {'truncated':s['truncated'],'frames':[{'method':method(f['method']),'bci':f['bytecodeIndex'],'line':f['lineNumber'],'type':f['type']} for f in s['frames']]}
def sha(p):
 h=hashlib.sha256()
 with p.open('rb') as f:
  for b in iter(lambda:f.read(1024*1024),b''):h.update(b)
 return h.hexdigest()
def approximate_xml_ns(attrs,header):return int(header['time_ms'])*10**6+int(decimal.Decimal(attrs['stamp'])*10**9)
def reduce_comp(v):return {**{k:v[k] for k in ['startTime','duration','compileId','compiler','compileLevel','succeded','isOsr','codeSize','inlinedBytes']},'method':method(v['method']),'eventThread':v['eventThread'],'startEpochNanos':instant(v['startTime']),'endEpochNanos':instant(v['startTime'])+duration(v['duration'])}
def audit(name):
 ep=ROOT/(name+'.events.json');es=json.loads(ep.read_text())['recording']['events'];counts=collections.Counter(e['type'] for e in es)
 rows=list(csv.DictReader((ROOT/(name+'.tsv')).open(),delimiter='\t'));cat=json.loads((ROOT/'original-catalog.json').read_text())
 ms=sorted((e['values'] for e in es if e['type']=='graphite.diagnostic.QueryExecution'),key=lambda v:v['ordinal'])
 assert len(ms)==len(cat)==len(rows)==34
 assert [m['ordinal'] for m in ms]==list(range(1,35))
 assert ['|'.join(r[k] for k in FIELDS) for r in rows]==ORACLE.read_text().splitlines()
 ws=[]
 for m,c,r in zip(ms,cat,rows):
  assert m['ordinal']==c['ordinal'] and c['id']==r['id'] and m['query']==c['query']
  assert json.loads(m['parametersJson'])==c['parameters'] and m['parameterEncoding']=='sorted-string-null-json-v1'
  assert m['success'] and m['exceptionClass']=='' and m['markerFailuresBefore']==0
  assert m['javaThreadId']==m['eventThread']['javaThreadId'] and m['javaThreadName']==m['eventThread']['javaName']=='broad-query-pressure-worker'
  start=instant(m['startTime']);end=start+duration(m['duration']);assert start<end
  if ws:assert ws[-1]['endEpochNanos']<=start
  ws.append({'ordinal':c['ordinal'],'id':c['id'],'query':m['query'],'parameters':c['parameters'],'startTime':m['startTime'],'startEpochNanos':start,'endEpochNanos':end,'markerDurationNanos':end-start,'tsvLatencyNanos':int(r['latencyNanos']),'thread':m['eventThread']})
 assert len({w['thread']['javaThreadId'] for w in ws})==1
 def query(t):
  matches=[w for w in ws if w['startEpochNanos']<=t<w['endEpochNanos']];assert len(matches)<=1
  return matches[0] if matches else None
 assert not [e for e in es if e['type']=='jdk.DataLoss' and any(e['values'].get(k,0)>0 for k in ['amount','total'])]
 result={'name':name,'markersBound':34,'oracleSignaturesVerified':34,'eventCounts':dict(counts),'queryWindows':ws,'eventsJsonSha256':sha(ep),'tsvSha256':sha(ROOT/(name+'.tsv'))}
 if name=='control':return result
 xp=ROOT/(name+'.compilation.xml');xr=ET.parse(xp).getroot();tty=xr.find('tty');assert tty is not None
 ji=[e['values'] for e in es if e['type']=='jdk.JVMInformation'];assert len(ji)==1 and int(xr.get('process'))==ji[0]['pid']
 nm=collections.defaultdict(list);traps=collections.defaultdict(list);comp=collections.defaultdict(list)
 for x in tty.findall('nmethod'):nm[int(x.get('compile_id'))].append(dict(x.attrib))
 for i,x in enumerate(tty.findall('uncommon_trap')):
  traps[int(x.get('compile_id'))].append({'runtimeTrapOrdinal':i,'attributes':dict(x.attrib),'jvms':[dict(j.attrib) for j in x.findall('jvms')]})
 for e in es:
  if e['type']=='jdk.Compilation':comp[e['values']['compileId']].append(reduce_comp(e['values']))
 ledger=[]
 for event_index,e in enumerate(es):
  if e['type']!='jdk.Deoptimization':continue
  v=e['values'];t=instant(v['startTime']);w=query(t);cid=v['compileId'];n=nm[cid];s=stack(v);fs=[] if s is None else s['frames']
  roots=[x for x in n if x['compiler']==v['compiler']];assert len(roots)<=1
  root=roots[0] if roots else None
  raw=any(f['method'].startswith(OWNER+' parallelRawDistinctCallSiteStringProjection') for f in fs)
  callback_root=root is not None and root['method'].startswith(OWNER+' '+CALLBACK+' ')
  phases=sorted({PHASES[f['method'].split(' ')[1]] for f in fs if f['method'].startswith(PIPELINE+' ') and f['method'].split(' ')[1] in PHASES})
  phase=phases[0] if len(phases)==1 else 'unknown'
  xml_matches=[]
  for x in traps[cid]:
   a=x['attributes'];j=x['jvms']
   if j and a.get('compiler')==v['compiler'] and int(a['thread'])==v['eventThread']['osThreadId'] and a['reason']==v['reason'] and a['action']==v['action'] and j[0]['method']==method(v['method']) and int(j[0]['bci'])==v['bci']:xml_matches.append(x)
  cs=[c for c in comp[cid] if root is not None and c['method']==root['method'] and c['compiler']==root['compiler'] and c['isOsr']==(root.get('compile_kind')=='osr')]
  entry={'eventIndex':event_index,'startTime':v['startTime'],'epochNanos':t,'queryId':None if w is None else w['id'],'queryOrdinal':None if w is None else w['ordinal'],'offsetFromQueryStartNanos':None if w is None else t-w['startEpochNanos'],'remainingToQueryEndNanos':None if w is None else w['endEpochNanos']-t,'thread':v['eventThread'],'compileId':cid,'compiler':v['compiler'],'trapMethod':method(v['method']),'trapBci':v['bci'],'lineNumber':v['lineNumber'],'instruction':v['instruction'],'reason':v['reason'],'action':v['action'],'stack':s,'hasRawProjectionStack':raw,'rootCallbackCompilation':callback_root,'rootNmethod':root,'phaseFromExactPipelineFrame':phase,'phaseEvidenceFrames':[f for f in fs if f['method'].startswith(PIPELINE+' ') and f['method'].split(' ')[1] in PHASES],'sameIdentityXmlRuntimeMatches':xml_matches,'sameIdentityJfrCompilationMatches':cs}
  if len(xml_matches)==1:
   approx=approximate_xml_ns(xml_matches[0]['attributes'],xr.attrib)
   entry['clockDiagnostic']={'xmlApproxEpochNanos':approx,'jfrMinusXmlApproxNanos':t-approx,'calibrated':False}
  ledger.append(entry)
 # Check publication/end identity independently; do not use these different semantic points as clock anchors.
 publications=[]
 for cid,ns in nm.items():
  for n in ns:
   if not n['method'].startswith(OWNER+' '+CALLBACK+' '):continue
   cs=[c for c in comp[cid] if c['method']==n['method'] and c['compiler']==n['compiler'] and c['isOsr']==(n.get('compile_kind')=='osr')]
   row={'compileId':cid,'nmethod':n,'jfrCompilationMatches':cs,'xmlApproxPublicationEpochNanos':approximate_xml_ns(n,xr.attrib),'xmlMakeNotEntrant':[dict(x.attrib) for x in tty.findall('make_not_entrant') if x.get('compile_id')==str(cid)],'xmlRuntimeTrapCount':len(traps[cid])}
   if len(cs)==1:
    c=cs[0];row['jfrCompilationEndMinusApproxXmlPublicationNanos']=c['endEpochNanos']-row['xmlApproxPublicationEpochNanos']
    row['queryOverlaps']=[{'id':w['id'],'overlapNanos':min(c['endEpochNanos'],w['endEpochNanos'])-max(c['startEpochNanos'],w['startEpochNanos'])} for w in ws if c['startEpochNanos']<w['endEpochNanos'] and c['endEpochNanos']>w['startEpochNanos']]
    row['compilationEndQueryId']=None if query(c['endEpochNanos']) is None else query(c['endEpochNanos'])['id']
   publications.append(row)
 cb=[x for x in ledger if x['rootCallbackCompilation']]
 for d in cb:
  assert d['rootNmethod']['bytes']=='790' and len(d['sameIdentityJfrCompilationMatches'])==1 and len(d['sameIdentityXmlRuntimeMatches'])==1
  assert d['sameIdentityJfrCompilationMatches'][0]['succeded']
  assert any(f['method']==d['rootNmethod']['method'] and f['bci']==322 for f in d['stack']['frames'])
 raw=[x for x in ledger if x['hasRawProjectionStack']]
 result.update({'xmlSha256':sha(xp),'xmlHeader':xr.attrib,'jvmInformation':ji[0],'sameForkPidVerified':True,'deoptimizations':ledger,'deoptimizationConservation':{'total':len(ledger),'insideQueryWindows':sum(x['queryId'] is not None for x in ledger),'outsideQueryWindows':sum(x['queryId'] is None for x in ledger),'rawProjectionStackEvents':len(raw),'rootCallbackEvents':len(cb),'rawStackButDifferentRootEvents':sum(not x['rootCallbackCompilation'] for x in raw)},'rawStackRootMethodCounts':dict(collections.Counter((x['rootNmethod'] or {}).get('method','unmatched') for x in raw)),'callbackPublications':publications,'callbackDeoptimizationEvents':cb,'clockConclusion':'Identity joins only. Approximate XML epoch retained for diagnostics; no calibrated clock or XML query-window assignment asserted.'})
 assert result['deoptimizationConservation']['total']==counts['jdk.Deoptimization']
 return result

def main():
 results=[audit(n) for n in ['control','profile-1','profile-2','profile-3']]
 output={'scope':'Independent Python-only audit of original exported JFR JSON, marker/catalog/TSV/oracle and same-fork XML tty events. Does not consume root analysis summaries. No Java, build or measurement.','oracleFields':FIELDS,'oracleSha256':sha(ORACLE),'catalogSha256':sha(ROOT/'original-catalog.json'),'phaseMappingSource':'docs/profiling/distinct-projection-work/phase-application/DistinctPhaseDetails.java and docs/profiling/distinct-phase-boundaries/README.md frozen-JAR method mapping','callbackRootSelector':OWNER+' '+CALLBACK+' ','results':results,'limits':['Native ExecutionSample counts are not CPU time.','Deoptimization has no measured duration; this audit does not assign a cost.','Late traps cannot explain elapsed time before the trap.','Raw-projection stack presence does not mean root callback compilation deoptimized.','Segment workers lacking exact QueryPipeline phase frames remain phase unknown.','No calibration from XML nmethod publication to JFR Compilation end: different semantic moments and coarser XML clock.']}
 (ROOT/'independent-deopt-audit.json').write_text(json.dumps(output,indent=2)+'\n')
 lines=['# Independent query-marker / deoptimization audit','','Python-only recomputation from original `*.events.json`, original catalog, TSV/oracle and same-fork `tty` XML. Root analysis summaries were not inputs. No Java, build or measurement was executed.','','All four recordings independently bind exactly 34 successful, continuous, non-overlapping markers to exact catalog query text/parameters, TSV order and all 14 oracle fields (136 signatures). No recorded positive DataLoss event was found. Query assignment below uses JFR timestamps only, with half-open windows.','','## Root callback traps','','Root means the same-fork XML `nmethod` selected by compileId/compiler, confirmed against JFR Compilation method/descriptor and OSR flag; it does not mean any event with raw projection somewhere in its stack.','','| Fork | Query | Compile ID | Thread | Trap BCI | Offset from start ms | Until query end ms | Exact pipeline phase frame | XML runtime matches |','|---|---|---:|---|---:|---:|---:|---|---:|']
 for r in results[1:]:
  for d in r['callbackDeoptimizationEvents']:
   lines.append(f"| {r['name']} | {d['queryId']} | {d['compileId']} | {d['thread']['javaName']} | {d['trapBci']} | {d['offsetFromQueryStartNanos']/1e6:.6f} | {d['remainingToQueryEndNanos']/1e6:.6f} | {d['phaseFromExactPipelineFrame']} | {len(d['sameIdentityXmlRuntimeMatches'])} |")
 lines+=['','Every row above is a separate original JFR event. Multiple worker events on one compile ID are retained, not collapsed into one event or misreported as multiple compiled methods. Trap method is `IntOpenHashSet.contains (I)Z`; the compiled root is the 790-byte mapped raw node callback.','','## All deoptimizations: conservation and root distinction','','| Fork | All | Within query | Outside query | Raw stack | Root callback | Raw stack, other root |','|---|---:|---:|---:|---:|---:|---:|']
 for r in results[1:]:
  c=r['deoptimizationConservation'];lines.append('| '+r['name']+' | '+' | '.join(str(c[k]) for k in ['total','insideQueryWindows','outsideQueryWindows','rawProjectionStackEvents','rootCallbackEvents','rawStackButDifferentRootEvents'])+' |')
 for r in results[1:]:
  for d in r['deoptimizations']:
   if d['hasRawProjectionStack'] and not d['rootCallbackCompilation'] and d['trapMethod']=='it.unimi.dsi.fastutil.ints.IntOpenHashSet contains (I)Z':
    lines.append(f"\nConcrete distinction: {r['name']} compile {d['compileId']}, {d['queryId']} +{d['offsetFromQueryStartNanos']/1e6:.6f} ms, contains BCI {d['trapBci']}, has a raw callback frame but its root nmethod is `{d['rootNmethod']['method']}`. It is not an additional root-callback deoptimization.")
 lines+=['','All raw-stack root-method distributions and all individual deoptimizations (including events outside queries) are in the JSON. `phaseFromExactPipelineFrame` only uses the frozen-JAR initial/projectSource and provenance/lambda155-lambda154 mappings. A segment worker without either parent frame stays unknown, even when another thread has a provenance frame at nearly the same timestamp.','','## Compilation identity and clock boundary','','| Fork | Root compile ID | Compiler | XML nmethod stamp s | JFR compile duration ms | JFR end minus approximate XML publication ms | JFR compile end query | XML runtime traps |','|---|---:|---|---:|---:|---:|---|---:|']
 for r in results[1:]:
  for p in r['callbackPublications']:
   cs=p['jfrCompilationMatches'];c=cs[0] if len(cs)==1 else None
   lines.append(f"| {r['name']} | {p['compileId']} | {p['nmethod']['compiler']} | {p['nmethod']['stamp']} | {duration(c['duration'])/1e6 if c else 'unmatched'} | {p.get('jfrCompilationEndMinusApproxXmlPublicationNanos',0)/1e6 if c else 'unmatched'} | {p.get('compilationEndQueryId')} | {p['xmlRuntimeTrapCount']} |")
 lines+=['','This capture must be read on its own: profile-1 also has a later successful callback C2 publication (4308); the earlier no-tracing captures lacked that later publication, so their terminal compiler state must not be copied into this report.\n\nSame-fork PID is checked. XML runtime traps are selected only from `tty/uncommon_trap`, not compile-task parse traps. Runtime identity matching uses compileId/compiler, trap method/BCI, reason/action and **OS thread ID**. JFR Compilation root-method matching is separate; an inlined trap method is expected to differ from the root method. All matching rows and make_not_entrant records are preserved in JSON.','','The table compares different semantic moments: XML nmethod publication versus JFR Compilation end. Approximate XML epoch is `time_ms + stamp`; stamp is rounded to 3 decimal seconds. These differences are diagnostic residuals, not a proven shared nanosecond clock. XML timelines remain separate; no XML query ownership is inferred from this approximate conversion. Multiple runtime-trap matches, if present, remain a list rather than choosing the nearest event.','','## Interpretation limits','','- These are three diagnostic captures, not stable performance estimates or optimization acceptance.','- ExecutionSample counts are not CPU time; no count-to-ms conversion was made.','- Deoptimization has no measured duration here; neither its cost nor its contribution to query latency is established.','- A trap occurring approximately 37 ms into a query cannot explain the preceding 37 ms merely because it occurred in that query.','- Full per-event fields and input hashes are preserved in `independent-deopt-audit.json`; `independent-deopt-audit.py` is the standalone reproducer.']
 (ROOT/'independent-deopt-audit.md').write_text('\n'.join(lines)+'\n')
 (ROOT/'independent-deopt-audit-receipt.json').write_text(json.dumps({'files':[{'path':n,'sha256':sha(ROOT/n),'bytes':(ROOT/n).stat().st_size} for n in ['independent-deopt-audit.py','independent-deopt-audit.json','independent-deopt-audit.md']],'inputHashesInAuditJson':True,'productionChanges':False,'javaBuildMeasurementExecuted':False},indent=2)+'\n')
 print(json.dumps([{'name':r['name'],'markers':r['markersBound'],'counts':r.get('deoptimizationConservation'),'callbackEvents':[{k:d[k] for k in ['queryId','compileId','trapBci','offsetFromQueryStartNanos','remainingToQueryEndNanos','phaseFromExactPipelineFrame']} for d in r.get('callbackDeoptimizationEvents',[])]} for r in results],indent=2))
if __name__=='__main__':main()
