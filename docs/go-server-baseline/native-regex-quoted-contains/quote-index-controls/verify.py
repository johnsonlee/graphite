from pathlib import Path
import collections,gzip,hashlib,json
HERE=Path(__file__).resolve().parent
def read(name):return json.loads((HERE/name).read_text())
def gz(name):return json.loads(gzip.decompress((HERE/name).read_bytes()))
def units(text):
 b=text.encode('utf-16-be',errors='surrogatepass');return [int.from_bytes(b[i:i+2],'big') for i in range(0,len(b),2)]
a=read('pattern-main.json');assert a==gz('repeat-pattern-main.json.gz');specs=read('cases.json')
assert len(specs)==len(a['cases'])==77 and a['javaVersion'].startswith('17.') and a['performanceMeasurements']==0
for spec,row in zip(specs,a['cases']):
 assert row['spec']==spec and row['name']==spec['name']
 assert row['patternUTF16']==units(spec['pattern']) and row['textUTF16']==units(spec['text'])
 if row['outcome']=='FAILED':
  assert row['phase']=='compile' and row['errorClass']=='java.util.regex.PatternSyntaxException'
  assert row['errorUTF16']==units(row['message']) and type(row['index'])is int
 else:assert row['phase']=='matches' and row['compileSucceeded'] and type(row['matches'])is bool
by_name={r['name']:r for r in a['cases']}
expected={0:7,1:0,2:4,3:2,4:2,5:8,6:4,7:5,8:10,9:9,12:5,13:6,14:16,16:2,17:7,18:3,19:0,20:4}
for n,index in expected.items():assert by_name[f'focused-{n}']['index']==index
for n in [10,11,15]:assert by_name[f'focused-{n}']['outcome']=='SUCCESS'
for i in range(2):
 r=gz(f'run{i}-receipt.json.gz');assert r['exitCodes']=={'compile':0,'run':0} and r['graphLoads']==r['publicQueries']==r['performanceMeasurements']==0
 for p,h in r['inputs'].items():assert hashlib.sha256(Path(p).read_bytes()).hexdigest()==h,p
summary=dict(cases=77,runs=2,completeRepeatDifferences=0,outcomes=dict(collections.Counter(r['outcome'] for r in a['cases'])),scope='Existing Java regex quote-rewrite error-index compatibility; not a new fastpath regression',graphLoads=0,publicQueries=0,performanceMeasurements=0)
(HERE/'verification.json').write_text(json.dumps(summary,indent=2)+'\n');print(json.dumps(summary,indent=2))
