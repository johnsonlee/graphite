#!/usr/bin/env python3
import gzip,hashlib,importlib.util,json,pathlib,tarfile,sys
sys.dont_write_bytecode=True
HERE=pathlib.Path(__file__).resolve().parent
def sha(b):return hashlib.sha256(b).hexdigest()
spec=importlib.util.spec_from_file_location('capture_runner',HERE/'run.py');runner=importlib.util.module_from_spec(spec);spec.loader.exec_module(runner)
manifest=json.loads((HERE/'artifact-manifest.json').read_text())
for f in manifest:
 p=HERE/f['path'];assert p.stat().st_size==f['size']and sha(p.read_bytes())==f['sha256'],f['path']
capture_manifest={x['path']:x for x in json.loads((HERE/'capture-artifact-manifest.json').read_text())}
binary=gzip.decompress((HERE/'diagnostic.test.gz').read_bytes());assert len(binary)==capture_manifest['diagnostic.test']['size'] and sha(binary)==capture_manifest['diagnostic.test']['sha256']
compiled={x['path']:x for x in json.loads((HERE/'compiled-source.json').read_text())}
with tarfile.open(HERE/'source.tar.gz')as t:
 found={}
 for m in t.getmembers():
  if m.isfile():
   name=m.name.removeprefix('module/');b=t.extractfile(m).read();found[name]={'path':name,'size':len(b),'sha256':sha(b)}
 assert found==compiled
frozen=json.loads((HERE/'frozen-source.json').read_text());original={x['path']:x for x in frozen['files']}
assert all(compiled[k]==v for k,v in original.items())
assert set(compiled)-set(original)=={'internal/query/generic_disjunction_diagnostic_test.go'}
assert compiled['internal/query/generic_disjunction_diagnostic_test.go']['sha256']==sha((HERE/'replay_test.go').read_bytes())
before=json.loads(gzip.decompress((HERE/'fixtures-before.json.gz').read_bytes()));after=json.loads(gzip.decompress((HERE/'fixtures-after.json.gz').read_bytes()));afterby={x['path']:x for x in after}
assert len(before)==3036 and all(afterby[x['path']]==x for x in before)
actual=json.loads((HERE/'go.json').read_text())['cases'];comparison=json.loads((HERE/'comparison.json').read_text());specs=json.loads((HERE.parent/'cases.json').read_text())
assert len(actual)==len(specs)==201
for index,name in enumerate(('main.json','repeat-main.json.gz')):
 p=HERE.parent/name;raw=p.read_bytes();expected=json.loads(gzip.decompress(raw)if name.endswith('.gz')else raw)['cases'];differences=[];matches={'public':0,'providerWrapper':0}
 for spec,g,j in zip(specs,actual,expected):
  assert spec['name']==g['name']==j['name'];gj,jj=runner.projected(g),runner.projected(j);kind='providerWrapper'if 'providerType'in spec else'public'
  if gj==jj:matches[kind]+=1
  else:differences.append({'name':g['name'],'scope':kind,'fields':[k for k in sorted(set(gj)|set(jj))if gj.get(k)!=jj.get(k)or(k in gj)!=(k in jj)],'go':gj,'main':jj})
 assert differences==comparison['comparisons'][index]['differences'];assert matches==comparison['comparisons'][index]['matches']
 assert matches=={'public':157,'providerWrapper':19} and len(differences)==25
print(json.dumps({'artifactFiles':len(manifest),'sourceFiles':len(compiled),'originalFixtureFilesUnchanged':len(before),'recomputedPublicMatches':157,'recomputedProviderWrapperMatches':19,'differencesEachReference':25,'performanceMeasurements':0}))
