"""Verify direct Java17 results, original public outcomes, UTF16 and untouched fixtures."""
from pathlib import Path
import collections,gzip,hashlib,json
HERE=Path(__file__).resolve().parent
def read(name):return json.loads((HERE/name).read_text())
def gz(name):return json.loads(gzip.decompress((HERE/name).read_bytes()))
def units(text):
 raw=text.encode('utf-16-be',errors='surrogatepass');return [int.from_bytes(raw[i:i+2],'big') for i in range(0,len(raw),2)]
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
patterns=read('pattern-main.json');public=read('public-main.json')
assert patterns==gz('repeat-pattern-main.json.gz') and public==gz('repeat-public-main.json.gz')
assert patterns['javaVersion'].startswith('17.') and public['javaVersion']==patterns['javaVersion']
assert patterns['performanceMeasurements']==public['performanceMeasurements']==0
assert public['mainRevision']=='4e328b0109e13c896b74004823fb049fcb19251a'
pattern_specs=read('pattern-cases.json');public_specs=read('public-cases.json')
assert len(pattern_specs)==len(patterns['cases'])==609 and len(public_specs)==len(public['cases'])==68
assert len({c['name'] for c in pattern_specs})==609 and len({c['name'] for c in public_specs})==68
by_name={c['name']:c for c in patterns['cases']};public_by_name={c['name']:c for c in public['cases']}
fast_eligible=0;errors=0
for spec,row in zip(pattern_specs,patterns['cases']):
 assert row['spec']==spec and row['name']==spec['name']
 assert row['patternUTF16']==units(spec['pattern']) and row['textUTF16']==units(spec['text'])
 assert spec['eligibleInput']==all(unit<128 and unit not in [10,13] for unit in row['textUTF16'])
 if row['outcome']=='FAILED':
  errors+=1;assert row['phase']=='compile' and row['errorClass']=='java.util.regex.PatternSyntaxException'
  assert row['errorUTF16']==units(row['message']) and isinstance(row['description'],str) and isinstance(row['index'],int)
 else:
  assert row['phase']=='matches' and row['compileSucceeded'] and type(row['matches'])is bool
 if spec['eligiblePattern']:
  literal=spec['literal'];assert literal and all(ord(c)<128 and c not in '\r\n' for c in literal) and '\\E' not in literal
  assert spec['pattern']=='.*\\Q'+literal+'\\E.*' and row['outcome']=='SUCCESS'
  if spec['eligibleInput']:
   fast_eligible+=1;assert row['matches']==(literal in spec['text'])
assert errors==68
for main in ['main-zero','main-targeted','main-dense']:
 for suffix in ['exact','start','middle','end','repeated','tab-before','vertical-before','formfeed-before','nul-before','del-before','nonascii-before','supplementary-after','high-before','low-after']:
  assert by_name[main+'-'+suffix]['matches'] is True,(main,suffix)
 for suffix in ['empty','miss','cr-before','lf-after','crlf-before','nel-before','ls-after','ps-after','inserted-lf']:
  assert by_name[main+'-'+suffix]['matches'] is False,(main,suffix)
assert by_name['empty-quote-empty']['matches'] is True
assert by_name['dotall-lf']['matches'] is True and by_name['unix-lines-nel']['matches'] is True
assert by_name['case-insensitive-upper']['matches'] is True
assert by_name['quoted-embedded-end-embedded-E']['matches'] is True
assert by_name['literal-cr-cr']['matches'] is True and by_name['literal-lf-lf']['matches'] is True
assert by_name['literal-high-surrogate-surrogate']['matches'] is True
assert any(0xD800 in c['textUTF16'] for c in patterns['cases']) and any(0xDC00 in c['textUTF16'] for c in patterns['cases'])
for spec,row in zip(public_specs,public['cases']):
 assert row['spec']==spec and row['name']==spec['name'] and row['phase']=='execute'
 assert row['before']==row['after']==dict(retained=False,mappedView=False)
 direct=spec['directPatternCase']
 if direct:
  assert row['outcome']=='SUCCESS' and row['columns']==['matched'] and row['rows']==[{'matched':by_name[direct]['matches']}] and row['types']==[{'matched':'java.lang.Boolean'}]
 if row['outcome']=='FAILED':assert row['errorUTF16']==units(row['message'])
for index in [0,1,3,4,5,6]:
 row=public_by_name[f'public-order-{index}'];assert row['rows']==[{'matched':None}] and row['types']==[{'matched':None}]
for index in [2,11,14,15,16,17]:
 row=public_by_name[f'public-order-{index}'];assert row['errorClass']=='io.johnsonlee.graphite.cypher.CypherException' and row['message']=='Division by zero'
for index in [7,8,9,10,13,18]:
 row=public_by_name[f'public-order-{index}'];assert row['errorClass']=='java.util.regex.PatternSyntaxException'
 assert row['message']=='Unclosed character class near index 0\n[\n^'
assert public_by_name['public-order-12']['rows']==[{'matched':True}]
assert public_by_name['public-order-19']['rows']==[]
actual=read('actual-main64.json');assert sha(Path(actual['workload']))==actual['workloadSha256']
for item in actual['cases']:
 row=public_by_name['actual-main64-'+str(item['index'])]
 assert row['spec']['query']==item['case']['query'] and row['spec']['parameters']==item['case']['parameters'] and row['rows']==[]
source=read('source-fixture.json');original={e['file']:e for e in source['files']};assert len(original)==10
for f,e in original.items():assert sha(Path(source['root'])/f)==e['sha256']
for index in range(2):
 receipt=gz(f'run{index}-receipt.json.gz');assert receipt['exitCodes']==dict(compile=0,run=0) and receipt['inputsUnchanged']
 for p,h in receipt['inputs'].items():assert sha(Path(p))==h,p
 before=gz(f'run{index}-fixture-before.json.gz');after=gz(f'run{index}-fixture-after.json.gz');audit=gz(f'run{index}-fixture-audit.json.gz')
 bm={e['file']:e for e in before};am={e['file']:e for e in after};assert len(bm)==680 and len(am)==816
 for f,e in bm.items():assert am[f]==e and original[Path(f).name]['sha256']==e['sha256']
 assert audit['changed']==audit['missing']==[] and set(audit['added'])==am.keys()-bm.keys()
 assert all(Path(f).name in ['graph.nodeoffsets','graph.typeindex'] for f in audit['added'])
summary=dict(javaPatternCases=609,originalPublicCases=68,repeats=2,completeRepeatDifferences=0,strictEligiblePatternAndInputCases=fast_eligible,
 patternOutcomes=dict(collections.Counter(c['outcome'] for c in patterns['cases'])),publicOutcomes=dict(collections.Counter(c['outcome'] for c in public['cases'])),
 main64Indices=[892,893,894],originalCopiedFilesUnchangedPerRun=680,generatedMappedSidecarsPerRun=136,fixtureMutations=0,performanceMeasurements=0)
(HERE/'verification.json').write_text(json.dumps(summary,indent=2)+'\n');print(json.dumps(summary,indent=2))
