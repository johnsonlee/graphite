"""Bounded archive verifier; never executes code under test."""
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
def verify():
 own=read(HERE/'oracle-manifest.json')['files']
 for r in own:assert sha(HERE/r['file'])==r['sha256']and(HERE/r['file']).stat().st_size==r['bytes']
 for r in read(HERE/'archive-copy-verification.json')['copied']:assert sha(HERE/r['file'])==r['sha256']==sha(Path(r['source']))
 a=read(HERE/'main.json');assert a==read(HERE/'main-capture/main.json')==read(HERE/'repeat-capture/main.json')
 specs=read(HERE/'cases.json');assert len(specs)==8 and[a['spec']for a in a['cases']]==specs
 assert sum(len(c['operations'])for c in a['cases'])==40 and all(len(c['sources'])==40 for c in specs)
 assert [c['budget']for c in specs]==list(map(str,[258,259,387,388,516,517,518,519]))
 variants=read(HERE/'fixture-variants.json');assert len(variants)==33 and members(HERE/'fixtures.tar.gz')==variants
 inputs={};counts=[]
 for label in ['main-capture','repeat-capture']:
  p=HERE/label;r=read(p/'receipt.json');assert r['inputsUnchanged']and all(c['exitCode']==0 for c in r['commands'])
  cmd=r['commands'][-1]['command'];assert '-Xmx512m'in cmd and not any('OmitStackTrace'in x or 'directStringParallelism'in x for x in cmd)
  assert members(p/'fixtures.tar.gz')==variants
  with gzip.open(p/'fixture-before.json.gz','rt')as f:before=json.load(f)
  with gzip.open(p/'fixture-after.json.gz','rt')as f:after=json.load(f)
  bm={e['file']:e for e in before};am={e['file']:e for e in after};audit=read(p/'fixture-audit.json')
  assert audit==dict(originalFiles=len(before),changed=[k for k,v in bm.items()if am.get(k)!=v],added=sorted(am.keys()-bm.keys()),missing=sorted(bm.keys()-am.keys()))
  assert len(audit['changed'])==8 and not audit['added']and not audit['missing']and all(Path(k).name=='graph.callsite-string-index'for k in audit['changed'])
  counts.append(len(before));owninputs=read(p/'inputs.json')
  for name,h in owninputs.items():
   if name in inputs:assert inputs[name]==h
   inputs[name]=h
  for s in members(p/'input-sources.tar.gz'):assert owninputs['/'+s['file']]==s['sha256']
 for name,h in inputs.items():assert sha(Path(name))==h
 for c in a['cases']:
  for op in c['operations']:
   for phase in ['before','after']:
    s=op[phase];assert len(s['diagnostics'])==8 and len(s['storage'])==40 and all(set(v['buildTrigram'])=={'metadataReady','postingsReady','metadataArraysPresent'}for v in s['storage'])
 with tarfile.open(HERE.parent/'fixtures.tar.gz')as t:valid=hashlib.sha256(t.extractfile('hit64/graph.callsite-string-index').read()).hexdigest()
 for capture in read(HERE/'runtime-mutations.json')['captures']:
  archive=HERE/capture['capture']/'runtime-changed-files.tar.gz';assert sha(archive)==capture['archiveSHA256']
  assert members(archive)==[{k:r[k]for k in ['file','bytes','sha256']}for r in capture['files']]
  for r in capture['files']:assert sha(Path(r['source']))==r['sha256']==valid
 frozen=[]
 for parent in [HERE.parent,HERE.parent/'mapped-oracle']:
  rows=read(parent/'oracle-manifest.json')['files']
  for r in rows:assert sha(parent/r['file'])==r['sha256']
  frozen.append(dict(directory=str(parent),declaredFilesUnchanged=len(rows)))
 return dict(status='pass',ownedManifestFiles=len(own),cases=8,operations=40,sourcesPerCase=40,fixtureRegularFiles=33,caseFixtureFilesPerCapture=counts,fullRawRepeatEqual=True,allEightDiagnosticsAndThreeBuildObserversRetained=True,rewrittenSidecarsPerCapture=8,rewrittenSidecarsEqualOriginalValidWriterBytes=True,distinctFrozenRuntimeInputs=len(inputs),precedingOracleFiles=frozen,performanceMeasurements=0,scope='Input/archive/public-output verification only; no execution of code under test.')
if __name__=='__main__':print(json.dumps(verify(),indent=2))
