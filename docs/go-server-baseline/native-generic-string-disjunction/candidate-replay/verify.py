#!/usr/bin/env python3
import gzip,hashlib,importlib.util,json,pathlib,tarfile,sys
sys.dont_write_bytecode=True
HERE=pathlib.Path(__file__).resolve().parent
ORACLE=HERE.parent
def sha(b):return hashlib.sha256(b).hexdigest()
def read(p):return json.loads(p.read_text())
sp=importlib.util.spec_from_file_location('runner',HERE/'run.py');runner=importlib.util.module_from_spec(sp);sp.loader.exec_module(runner)
for f in read(HERE/'artifact-manifest.json'):
 p=HERE/f['path'];assert p.stat().st_size==f['size']and sha(p.read_bytes())==f['sha256'],f['path']
refs=[read(ORACLE/'main.json')['cases'],json.loads(gzip.decompress((ORACLE/'repeat-main.json.gz').read_bytes()))['cases']]
baseline=read(ORACLE/'go-baseline/go.json')['cases'];specs=read(ORACLE/'cases.json');result={'performanceMeasurements':0,'versions':[]};actuals=[]
for version in ('v1','v2'):
 folder=HERE/version;compiled={v['path']:v for v in read(folder/'compiled-source.json')};found={}
 with tarfile.open(folder/'source.tar.gz')as t:
  for m in t.getmembers():
   if m.isfile():
    name=m.name.removeprefix('module/');b=t.extractfile(m).read();found[name]={'path':name,'size':len(b),'sha256':sha(b)}
 assert found==compiled=={v['path']:v for v in read(folder/'source-after.json')}
 manifest={v['path']:v for v in read(folder/'artifact-manifest.json')};binary=gzip.decompress((folder/'diagnostic.test.gz').read_bytes());assert len(binary)==manifest['diagnostic.test']['size']and sha(binary)==manifest['diagnostic.test']['sha256']
 before=json.loads(gzip.decompress((folder/'fixtures-before.json.gz').read_bytes()));after=json.loads(gzip.decompress((folder/'fixtures-after.json.gz').read_bytes()));afterby={v['path']:v for v in after};assert len(before)==3036 and all(afterby[v['path']]==v for v in before)
 actual=read(folder/'go.json')['cases'];actuals.append(actual);comparison=read(folder/'comparison.json');assert len(actual)==len(specs)==201
 for i,expected in enumerate(refs):
  differences=[];matches={'public':0,'providerWrapper':0}
  for spec,g,j in zip(specs,actual,expected):
   assert spec['name']==g['name']==j['name'];gj,jj=runner.projected(g),runner.projected(j);kind='providerWrapper'if 'providerType'in spec else'public'
   if gj==jj:matches[kind]+=1
   else:differences.append({'name':g['name'],'scope':kind,'fields':[k for k in sorted(set(gj)|set(jj))if gj.get(k)!=jj.get(k)or(k in gj)!=(k in jj)],'go':gj,'main':jj})
  assert differences==comparison['comparisons'][i]['differences'];assert matches==comparison['comparisons'][i]['matches']=={'public':166,'providerWrapper':19};assert len(differences)==16
 old=read(ORACLE/'go-baseline/comparison.json')['comparisons'][0]['differences'];oldset={v['name']for v in old};newset={v['name']for v in comparison['comparisons'][0]['differences']}
 changed=[g['name']for g,b in zip(actual,baseline)if runner.projected(g)!=runner.projected(b)]
 result['versions'].append({'version':version,'cases':201,'publicMatchesEachReference':166,'providerWrapperMatchesEachReference':19,'differencesEachReference':16,'newMismatchCasesComparedWithBaseline':sorted(newset-oldset),'resolvedMismatchCasesComparedWithBaseline':sorted(oldset-newset),'publicOutputErrorStateChangedCasesComparedWithBaseline':changed,'sourceFiles':len(compiled),'originalFixtureFilesUnchanged':len(before),'addedFixtureFiles':len(after)-len(before)})
 assert not(newset-oldset)
raw_differences=[]
for a,b in zip(*actuals):
 if a!=b:raw_differences.append({'name':a['name'],'fields':sorted(k for k in set(a)|set(b)if a.get(k)!=b.get(k))})
assert all(d['fields']==['goStack']for d in raw_differences)
assert all(runner.projected(a)==runner.projected(b)for a,b in zip(*actuals))
result['v1V2ComparedFieldsEqual']=True;result['v1V2RawDifferencesPreserved']=raw_differences
expected=read(HERE/'verification.json');assert result==expected
print(json.dumps({'versions':len(result['versions']),'casesPerVersion':201,'publicMatchesEachReference':166,'providerWrapperMatchesEachReference':19,'differencesEachReference':16,'resolvedBaselineMismatches':9,'newMismatchCasesInMatrix':0,'v1V2ComparedFieldsEqual':True,'rawStackDifferences':len(raw_differences)}))
