from pathlib import Path
import json,csv,hashlib,math
R=Path(__file__).parent;D=R/'v3-control'
def j(p):return json.loads(p.read_text())
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def canonical(v):
 if v is None:return 'null'
 if isinstance(v,str):
  esc={'"':'\\"','\\':'\\\\','\n':'\\n','\r':'\\r','\t':'\\t'}
  return '"'+''.join(esc.get(c,('\\u%04x'%ord(c)) if ord(c)<32 else c) for c in v)+'"'
 if isinstance(v,list):return '['+','.join(canonical(x) for x in v)+']'
 if isinstance(v,dict):return '{'+','.join(canonical(k)+':'+canonical(x) for k,x in v.items())+'}'
 raise AssertionError(type(v))
receipt=j(D/'run.json');assert receipt['status']=='complete' and receipt['queryCount']==36 and receipt['completedVerifiedForks']==receipt['requestedForks']==1
catalog=j(D/'catalog.json');assert sha(D/'catalog.json')==receipt['inputs']['catalog']['sha256']
assert (D/'catalog.json').read_bytes()==Path(receipt['inputs']['catalog']['path']).read_bytes()
assert sha(D/'workloads.tsv')==receipt['inputs']['workloads']['sha256']
assert receipt['inputs']['runtimeJar']['sha256']==j(R/'build-receipt.json')['jmhJarSha256']
assert receipt['inputs']['trustedJar']['sha256']==j(R/'build-receipt.json')['baselineJarSha256After']
before=j(D/'graph-content-before.json');after=j(D/'graph-content-after.json');assert before==after and len(before)==64
with (D/'fork-001.tsv').open() as f:observations=list(csv.DictReader(f,delimiter='\t'))
outputs=[json.loads(x) for x in (D/'fork-001-rows.jsonl').read_text().splitlines()];queries=catalog['queries'];assert len(queries)==len(outputs)==len(observations)==36
rows=[]
for q,o,t in zip(queries,outputs,observations):
 assert q['id']==o['id']==t['id']
 assert o['columns']==['n.caller_class','n.caller_name','n.callee_class','n.callee_name']
 assert o['rows']==q['expectedRows']
 assert int(t['rowCount'])==len(o['rows']) and t['outcome']=='success' and t['resetMode']=='per-query-cold' and int(t['inputSourceCount'])==64
 digest=hashlib.sha256(canonical(o['rows']).encode()).hexdigest();assert digest==t['digest']
 actualhits=t['hitGraphIds'].split(',') if t['hitGraphIds'] else [];assert actualhits==sorted({g for row in q['expectedRows'] for g in row['graphIds']})
 assert int(t['latencyNanos'])>0
 if q['distinct']:assert len({tuple(x['values']) for x in o['rows']})==len(o['rows'])
 for x in o['rows']:
  assert x['graphIds']==sorted(set(x['graphIds'])) and set(x['graphIds'])<=set(q['expectedHitGraphIds'])
  assert q['distinct'] or len(x['graphIds'])==1
 rows.append({'id':q['id'],'rows':len(o['rows']),'completeValuesOrderProvenanceMatch':True,'returnedProvenanceGraphCount':len(actualhits),'fullPredicateHitGraphCount':len(q['expectedHitGraphIds']),'latencyNanos':int(t['latencyNanos']),'graphWorkUnits':int(t['graphWorkUnits']),'digest':digest})
# Completion follows run.py's repeated identity checks; no separate after-JAR map is emitted.
assert sha(D/'graph-content-before.json')==receipt['graphContentSha256']==sha(D/'graph-content-after.json')
for key in ['catalog','workloads','adapterSource','runnerSource','verifierSource']:
 assert sha(Path(receipt['inputs'][key]['path']))==receipt['inputs'][key]['sha256']
for rel,h in receipt['compiledClasses'].items():assert sha(D/'classes'/rel)==h
out={'attempt':140,'result':'correctness-control-pass','queriesVerified':36,'fullRowsVerified':sum(x['rows'] for x in rows),'fullValuesOrderProvenanceAndDigestMatch':True,'beforeAfterGraphContentReceiptsEqual':True,'graphFileCount':sum(len(x['files']) for x in before),'recordedCandidateJarSha256':receipt['inputs']['runtimeJar']['sha256'],'rows':rows,'identityEvidence':{'graphContentSha256':receipt['graphContentSha256'],'completionAfterRepeatedInputChecksInBoundRunner':True,'separateAfterJarHashMapExists':False,'recordedInputIdentities':receipt['inputs']},'limits':['Single correctness control is neither paired latency evidence nor per-query P95.','Independent audit compared full decoded outputs with frozen catalog; no Java or query replay occurred.','Large live fixture/JAR files were not rehashed while another timed run might be active; recorded before/after evidence and build binding were checked.'],'inputSha256':{str(p.relative_to(R)):sha(p) for p in [D/'run.json',D/'catalog.json',D/'workloads.tsv',D/'fork-001.tsv',D/'fork-001-rows.jsonl',D/'graph-content-before.json',D/'graph-content-after.json',R/'build-receipt.json']}}
(R/'independent-control-audit.json').write_text(json.dumps(out,indent=2)+'\n');print(json.dumps({k:v for k,v in out.items() if k not in ['rows','inputSha256','identityEvidence']},indent=2))
