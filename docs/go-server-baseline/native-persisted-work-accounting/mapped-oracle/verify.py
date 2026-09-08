"""Hash/receipt verification only; no JVM, build, native test, or benchmark."""
from pathlib import Path
import gzip,hashlib,json,tarfile
HERE=Path(__file__).resolve().parent
def sha(p):
 h=hashlib.sha256()
 with p.open('rb')as f:
  for b in iter(lambda:f.read(1048576),b''):h.update(b)
 return h.hexdigest()
def read(p):return json.loads(p.read_text())
def members(p):
 with tarfile.open(p)as t:return[dict(file=m.name,bytes=m.size,sha256=hashlib.sha256(t.extractfile(m).read()).hexdigest())for m in t if m.isfile()]
def checkcopies(h,rows):
 for r in rows:assert sha(h/r['file'])==r['sha256']==sha(Path(r['source'])),r['file']
def verify():
 manifest=read(HERE/'oracle-manifest.json')['files']
 for r in manifest:
  p=HERE/r['file'];assert p.stat().st_size==r['bytes']and sha(p)==r['sha256'],r['file']
 inputs={};groups=[]
 for h,count,ops,files in [(HERE,44,220,386),(HERE/'large-oracle',7,35,67)]:
  a=read(h/'main-capture/main.json');b=read(h/'repeat-capture/main.json');assert a==b==read(h/'main.json')
  specs=read(h/'cases.json');assert [c['spec']for c in a['cases']]==specs and len(specs)==count
  assert sum(len(c['operations'])for c in a['cases'])==ops
  assert all(len(c['sources'])==40 for c in specs)
  variants=read(h/'fixture-variants.json');assert len(variants)==files and members(h/'fixtures.tar.gz')==variants
  checkcopies(h,read(h/'archive-copy-verification.json')['copied']);casefiles=[]
  for label in ['main-capture','repeat-capture']:
   p=h/label;r=read(p/'receipt.json');assert r['inputsUnchanged']and all(c['exitCode']==0 for c in r['commands'])
   cmd=r['commands'][-1]['command'];assert '-Xmx512m'in cmd and not any('OmitStackTrace'in x or 'directStringParallelism'in x for x in cmd)
   assert members(p/'fixtures.tar.gz')==variants==read(p/'fixture-variants.json')
   with gzip.open(p/'fixture-before.json.gz','rt')as f:before=json.load(f)
   with gzip.open(p/'fixture-after.json.gz','rt')as f:after=json.load(f)
   bm={e['file']:e for e in before};am={e['file']:e for e in after}
   actual=dict(originalFiles=len(before),changed=[k for k,v in bm.items()if am.get(k)!=v],added=sorted(am.keys()-bm.keys()),missing=sorted(bm.keys()-am.keys()))
   audit=read(p/'fixture-audit.json');assert audit==actual and not audit['added']and not audit['missing']
   assert len(audit['changed'])==(22 if h==HERE else 0)and all(Path(k).name=='graph.callsite-string-index'for k in audit['changed'])
   casefiles.append(len(before))
   owninputs=read(p/'inputs.json')
   for name,hsh in owninputs.items():
    if name in inputs:assert inputs[name]==hsh
    inputs[name]=hsh
   for s in members(p/'input-sources.tar.gz'):assert owninputs['/'+s['file']]==s['sha256']
  for c in a['cases']:
   cb={e['file']:e for e in c['fixtureBeforeLoad']};ca={e['file']:e for e in c['fixtureAfterClose']};assert cb.keys()==ca.keys()
   assert all(Path(k).name=='graph.callsite-string-index'for k in cb if cb[k]!=ca[k])
   for op in c['operations']:
    for phase in ['before','after']:
     s=op[phase];assert len(s['diagnostics'])==8 and len(s['storage'])==40
     assert all(type(v['mappedViewUnavailable'])is bool and len(v['persisted'])==7 for v in s['storage'])
  groups.append(dict(directory=str(h.relative_to(HERE))or'.',cases=count,operations=ops,fixtureRegularFiles=files,caseFixtureFilesPerCapture=casefiles,fullRawRepeatEqual=True))
 for capture in read(HERE/'runtime-mutations.json')['captures']:
  archive=HERE/capture['capture']/'runtime-changed-files.tar.gz';assert sha(archive)==capture['archiveSHA256']
  assert members(archive)==[{k:r[k]for k in ['file','bytes','sha256']}for r in capture['files']]
  for r in capture['files']:assert sha(Path(r['source']))==r['sha256']
 writer=HERE/'large-writer-capture';wr=read(writer/'receipt.json');assert wr['inputsUnchanged']and all(c['exitCode']==0 for c in wr['commands'])
 assert members(writer/'fixtures.tar.gz')==read(writer/'fixture-variants.json')and wr['fixtureFiles']==17
 assert sha(writer/'fixtures.tar.gz')==wr['fixtureArchiveSHA256']
 checkcopies(HERE,read(writer/'archive-copy-verification.json'))
 wi=read(writer/'inputs.json')
 for s in members(writer/'input-sources.tar.gz'):assert wi['/'+s['file']]==s['sha256']
 inputs.update(wi)
 for name,hsh in inputs.items():assert sha(Path(name))==hsh,name
 bridge=read(HERE/'p06-bridge.json');assert len(bridge['cases'])==2 and all(c['commonOperationEqual']and not c['differences']for c in bridge['cases'])
 assert sha(HERE.parent/'main.json')==bridge['priorCaptureSHA256']
 prior=read(HERE.parent/'oracle-manifest.json')['files']
 for r in prior:assert sha(HERE.parent/r['file'])==r['sha256']
 return dict(status='pass',declaredOwnedFiles=len(manifest),groups=groups,publicCases=51,publicOperations=255,allFullRawRepeatsEqual=True,smallRuntimeRewrittenSidecarsPerCapture=22,largeRuntimeChangedFiles=0,prior52CaseDeclaredFilesUnchanged=len(prior),p06CommonOperationReferencesEqual=2,largeWriterNodes=262145,largeWriterTerminalExitCode=0,distinctFrozenRuntimeInputs=len(inputs),performanceMeasurements=0,scope='Archive/source/public-output verification only; no execution of code under test.')
if __name__=='__main__':print(json.dumps(verify(),indent=2))
