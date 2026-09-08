"""Independently verify original JVM AST-cache observations and fixture integrity."""
from pathlib import Path
import gzip,hashlib,json,tarfile
HERE=Path(__file__).resolve().parent
def read(name):return json.loads((HERE/name).read_text())
def gz(name):return json.loads(gzip.decompress((HERE/name).read_bytes()))
def digest(path):return hashlib.sha256(path.read_bytes()).hexdigest()
main=read('main.json');repeat=gz('repeat-main.json.gz');large=gz('main-8g.json.gz')
assert main==repeat
assert len(main['events'])==len(large['events'])==35
assert main['maxCacheBytes']==4194304 and large['maxCacheBytes']==16777216

def keys(state):return [e['query']['raw'] for e in state['entriesLruToMru']]
def state_invariants(value,cap):
 if isinstance(value,list):
  for v in value:state_invariants(v,cap)
 elif isinstance(value,dict):
  if 'entriesLruToMru' in value:
   assert value['maxBytes']==cap and value['size']<=1024 and value['bytes']<=cap
   entries=value['entriesLruToMru'];assert len(entries)==value['size']
   assert len({x['query']['utf8Sha256'] for x in entries})==len(entries)
   assert sum(x['retainedBytes'] for x in entries)==value['bytes']
   for entry in entries:
    overhead=entry['retainedBytes']-128-2*entry['query']['utf16Length']
    assert overhead>=0 and overhead%2048==0
  for v in value.values():state_invariants(v,cap)

def validate(data):
 assert data['mainRevision']=='4e328b0109e13c896b74004823fb049fcb19251a' and data['performanceMeasurements']==0
 cap=data['maxCacheBytes'];assert cap==min(16*1024*1024,data['maxHeapBytes']//128)
 state_invariants(data['events'],cap)
 events={e['name']:e for e in data['events']};assert len(events)==35
 assert events['raw-identical-object']['sameListIdentity']
 for name,n in [('raw-first',1),('raw-leading-space',2),('raw-trailing-space',3),('raw-trailing-semicolon',4)]:assert events[name]['after']['size']==n
 for name,e in events.items():
  if name.startswith('blank-'):assert e['clauseCount']==0 and e['before']==e['after']
 for name in ['error-first','error-repeat','literal-error-first','literal-error-repeat']:
  e=events[name];assert e['outcome']=='FAILED' and e['before']==e['after'] and e['after']['size']==0
 assert events['error-first']['error']['class']=='io.johnsonlee.graphite.cypher.CypherParseException'
 assert events['error-first']['error']['message']=="Syntax error at position 13: mismatched input 'n' expecting '='"
 assert events['literal-error-first']['error']['class']=='java.lang.NumberFormatException'
 assert events['literal-error-first']['error']['message']=='For input string: "999999999999999999999999"'
 assert keys(events['semicolon-parent']['after'])==['RETURN 1','RETURN 2','RETURN 1; RETURN 2']
 assert events['semicolon-parent-hit']['sameListIdentity'] and events['semicolon-subquery-hit']['sameListIdentity']
 for name in ['semicolon-partial-error','semicolon-partial-error-repeat']:assert keys(events[name]['after'])==['RETURN 1']
 assert events['semicolon-only']['outcome']==events['semicolon-in-string']['outcome']=='FAILED'
 expected={0:['Match','Where','Return','OrderBy','Skip','Limit'],1:['Match','Return','OrderBy','Skip','Limit'],
           3:['Return','Union','Return','Union','Return'],4:['Unwind','With','Return','OrderBy','Limit'],5:['Return'],6:['With','OrderBy','Skip','Limit','Return']}
 for index,clauses in expected.items():
  e=events[f'clauses-{index}'];assert e['clauses']==clauses and e['clauseCount']==len(clauses)
  assert e['after']['bytes']==128+2*e['query']['utf16Length']+2048*len(clauses)
 assert events['clauses-2']['outcome']=='FAILED'
 assert events['clauses-1']['ast'][0]['where'] is not None
 assert events['clauses-4']['ast'][1]['where'] is not None
 assert events['clauses-6']['ast'][0]['where'] is not None
 assert events['clauses-3']['ast'][1]['all'] is False and events['clauses-3']['ast'][3]['all'] is True
 for name in ['deep-parser-immutability','deep-literal-freezer']:
  e=events[name];assert e['mutations']
  assert all(not x['mutationAccepted'] and x['error']['class']=='java.lang.UnsupportedOperationException' for x in e['mutations'])
 assert len(events['deep-parser-immutability']['mutations'])==26 and len(events['deep-literal-freezer']['mutations'])==5
 assert events['deep-parser-immutability']['beforeAst']==events['deep-parser-immutability']['afterAst']
 assert events['deep-parser-immutability']['cacheIdentityUnchanged']
 assert events['deep-literal-freezer']['beforeAst']==events['deep-literal-freezer']['afterExternalAndDirectMutations']
 lru=events['entry-lru-1024'];assert lru['firstIdentityRetained']
 original=[f'RETURN {i} AS x' for i in range(1024)];assert keys(lru['filled'])==original
 touched=[q for q in original if q not in ['RETURN 0 AS x','RETURN 500 AS x']]+['RETURN 0 AS x','RETURN 500 AS x']
 assert keys(lru['afterTouches'])==touched
 assert keys(lru['afterInsertion'])==touched[1:]+['RETURN 1024 AS x']
 assert events['byte-budget-eviction']['state']['size']==15
 oversized=events['oversize-not-cached'];assert not oversized['sameListIdentity'] and oversized['sameValue'] and oversized['before']==oversized['after']
 exact=events['exact-byte-capacity'];assert exact['exactSameIdentity'] and not exact['aboveSameIdentity']
 assert exact['atCapacity']['bytes']==cap and exact['atCapacity']['size']==1 and exact['atCapacity']==exact['afterAboveCapacity']
 executions=events['parameters-and-distinct-graphs']['executions'];assert len(executions)==4
 for index,e in enumerate(executions):
  assert e['graphIndex']==index//2 and e['parameter']==7+index%2
  assert e['columns']==['x'] and e['rows']==[{'x':e['parameter']}] and e['astSameIdentity']
  assert keys(e['state'])==['RETURN $value AS x']
for data in [main,repeat,large]:validate(data)
source=read('source-fixture.json');original={e['file']:e for e in source['files']}
assert len(original)==10
for f,e in original.items():assert digest(Path(source['root'])/f)==e['sha256']
for index in range(3):
 receipt=gz(f'run{index}-receipt.json.gz');assert receipt['exitCodes']=={'compile':0,'run':0} and receipt['inputsUnchanged'] and receipt['performanceMeasurements']==0
 for p,h in receipt['inputs'].items():assert digest(Path(p))==h,p
 before=gz(f'run{index}-fixture-before.json.gz');after=gz(f'run{index}-fixture-after.json.gz');audit=gz(f'run{index}-fixture-audit.json.gz')
 bm={e['file']:e for e in before};am={e['file']:e for e in after};assert len(bm)==20 and len(am)==24
 for f,e in bm.items():assert am[f]==e and original[Path(f).name]['sha256']==e['sha256']
 assert audit['changed']==audit['missing']==[] and set(audit['added'])==am.keys()-bm.keys()
 assert all(Path(f).name in ['graph.nodeoffsets','graph.typeindex'] for f in audit['added'])
summary=dict(eventsPerRun=35,runs=3,repeat512mFullOutputDifferences=0,heapCapBytes512m=main['maxCacheBytes'],heapCapBytes8g=large['maxCacheBytes'],deepMutationRejectionsPerRun=31,publicParameterGraphExecutionControlsPerRun=4,explicitFixtureMutations=0,originalFilesUnchangedPerRun=20,generatedMappedIndexFilesPerRun=4,performanceMeasurements=0)
(HERE/'verification.json').write_text(json.dumps(summary,indent=2)+'\n');print(json.dumps(summary,indent=2))
