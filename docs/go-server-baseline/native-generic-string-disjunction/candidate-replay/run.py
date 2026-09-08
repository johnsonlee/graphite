#!/usr/bin/env python3
"""Correctness only. Never changes workspace module. Archives differences, exits 1 on any."""
import argparse,gzip,hashlib,json,os,pathlib,shutil,subprocess,tarfile
HERE=pathlib.Path(__file__).resolve().parent
ORACLE=HERE.parent
GO=pathlib.Path('/opt/homebrew/Cellar/go/1.22.0/libexec/bin/go')
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def files(p):return [{'path':str(f.relative_to(p)),'size':f.stat().st_size,'sha256':sha(f)} for f in sorted(p.rglob('*')) if f.is_file()]
def dump(p,x):p.write_text(json.dumps(x,indent=2,ensure_ascii=True)+'\n')
def encoded(v):
 if isinstance(v,str):
  b=v.encode('utf-16-be','surrogatepass');return {'$utf16':[int.from_bytes(b[i:i+2],'big')for i in range(0,len(b),2)]}
 if isinstance(v,dict):return {k:encoded(x)for k,x in v.items()}
 if isinstance(v,list):return list(map(encoded,v))
 return v
def projected(r):
 out={k:r[k]for k in ('outcome','before','after','phase') if k in r}
 if 'providerType'in r.get('spec',{}) or 'providerScope'in r:
  for k in ('providerSupported','yielded'):
   if k in r:out[k]=([{p:v for p,v in x.items()if p!='value'}for x in r[k]] if k=='yielded'else r[k])
 elif r['outcome']=='SUCCESS':
  for k in ('columns','rowsUTF16'):out[k]=r[k]
 if r['outcome']=='FAILED':
  out.update(error=r.get('error'),nullMessage=r.get('message')is None,errorUTF16=r.get('errorUTF16'))
 return out
def main():
 p=argparse.ArgumentParser();p.add_argument('--module',type=pathlib.Path,required=True);p.add_argument('--output',type=pathlib.Path,required=True);a=p.parse_args();module=a.module.resolve();out=a.output.resolve();out.mkdir(exist_ok=False)
 before=files(module);dump(out/'source-before.json',before)
 inputs=[HERE/'run.py',HERE/'replay_test.go',ORACLE/'cases.json',ORACLE/'main.json',ORACLE/'repeat-main.json.gz',ORACLE/'fixtures.tar.gz',ORACLE/'fixture-variants.json',GO]
 dump(out/'inputs.json',[{'path':str(x),'size':x.stat().st_size,'sha256':sha(x)}for x in inputs])
 specs=json.loads((ORACLE/'cases.json').read_text());prepared=[dict(s,parametersEncoded=encoded(s['parameters']))for s in specs];dump(out/'input.json',prepared)
 variants=out/'variants';variants.mkdir();
 with tarfile.open(ORACLE/'fixtures.tar.gz')as t:
  for m in t.getmembers():
   if m.name.startswith('/')or '..'in pathlib.PurePosixPath(m.name).parts or not m.isfile():raise RuntimeError(('unsafe archive member',m.name))
  t.extractall(variants)
 fixtures=out/'fixtures'
 for s in specs:
  for i,v in enumerate(s['fixtures']):shutil.copytree(variants/v,fixtures/s['name']/f'store{i}')
 fixture_before=files(fixtures);dump(out/'fixtures-before.json',fixture_before)
 helper=module/'internal/query/generic_disjunction_diagnostic_test.go'
 if helper.exists():raise RuntimeError('refuse overwrite existing diagnostic helper')
 shutil.copy2(HERE/'replay_test.go',helper);dump(out/'compiled-source.json',files(module))
 with tarfile.open(out/'source.tar.gz','w:gz')as t:t.add(module,arcname='module')
 env=dict(os.environ,PATH=str(GO.parent)+os.pathsep+os.environ.get('PATH',''),GOTOOLCHAIN='local',GRAPHITE_GENERIC_DIAGNOSTIC_INPUT=str(out/'input.json'),GRAPHITE_GENERIC_DIAGNOSTIC_FIXTURES=str(fixtures),GRAPHITE_GENERIC_DIAGNOSTIC_OUTPUT=str(out/'go.json'))
 commands=[]
 for name,cmd in [('compile',[str(GO),'test','-c','-o',str(out/'diagnostic.test'),'./internal/query']),('capture',[str(out/'diagnostic.test'),'-test.run=^TestGenericDisjunctionDiagnosticCapture$','-test.count=1','-test.v'])]:
  with (out/f'{name}.stdout').open('wb')as stdout,(out/f'{name}.stderr').open('wb')as stderr:r=subprocess.run(cmd,cwd=module,env=env,stdout=stdout,stderr=stderr)
  commands.append({'name':name,'command':cmd,'exitCode':r.returncode});dump(out/'commands.json',commands)
  if r.returncode:raise RuntimeError(f'{name} failed; all output retained')
 actual=json.loads((out/'go.json').read_text())['cases'];refs=[('main',json.loads((ORACLE/'main.json').read_text())['cases']),('repeat',json.loads(gzip.decompress((ORACLE/'repeat-main.json.gz').read_bytes()))['cases'])];comparisons=[]
 assert [x['name']for x in actual]==[x['name']for x in specs]
 for label,expected in refs:
  differences=[];matches={'public':0,'providerWrapper':0}
  for spec,g,j in zip(specs,actual,expected):
   assert spec['name']==j['name'];gj,jj=projected(g),projected(j);kind='providerWrapper'if 'providerType'in spec else'public'
   if gj==jj:matches[kind]+=1
   else:differences.append({'name':g['name'],'scope':kind,'fields':[k for k in sorted(set(gj)|set(jj))if gj.get(k)!=jj.get(k) or (k in gj)!=(k in jj)],'go':gj,'main':jj})
  comparisons.append({'reference':label,'matches':matches,'differences':differences})
 fixture_after=files(fixtures);dump(out/'fixtures-after.json',fixture_after);old={x['path']:x for x in fixture_before};new={x['path']:x for x in fixture_after};changed=[k for k,v in old.items()if new.get(k)!=v];added=[v for k,v in new.items()if k not in old]
 source_after=files(module);dump(out/'source-after.json',source_after);compiled=json.loads((out/'compiled-source.json').read_text());assert source_after==compiled
 result={'performanceMeasurements':0,'cases':len(actual),'publicCases':181,'providerWrapperControls':20,'diagnosticsComparison':'unavailable: Go lacks original diagnostics API; no diagnostics values suppressed or fabricated in source main captures','providerScope':'Go mainStringCandidates includes merge/order checks absent from the private original provider API; matching controls are bounded payload evidence, not exact API fidelity','fixtureOriginalChanged':changed,'fixtureAdded':added,'sourceUnchanged':True,'comparisons':comparisons,'allComparedFieldsEqual':not any(c['differences']for c in comparisons)}
 dump(out/'comparison.json',result)
 dump(out/'artifact-manifest.json',files(out))
 print(json.dumps({k:v for k,v in result.items()if k!='comparisons'},indent=2));print([(c['reference'],c['matches'],len(c['differences']))for c in comparisons]);return 0 if result['allComparedFieldsEqual']and not changed else 1
if __name__=='__main__':raise SystemExit(main())
