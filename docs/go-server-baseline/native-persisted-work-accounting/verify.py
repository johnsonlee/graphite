"""Bounded read-only archive verification; never launches a JVM or native test."""
from pathlib import Path
import gzip,hashlib,json,tarfile
HERE=Path(__file__).resolve().parent
def sha(p):
 h=hashlib.sha256()
 with p.open('rb')as f:
  for b in iter(lambda:f.read(1024*1024),b''):h.update(b)
 return h.hexdigest()
def read(p):return json.loads(p.read_text())
def tar_manifest(p):
 with tarfile.open(p)as t:
  return [dict(file=m.name,bytes=m.size,sha256=hashlib.sha256(t.extractfile(m).read()).hexdigest())for m in t if m.isfile()]
def verify():
 manifest=read(HERE/'oracle-manifest.json')
 for r in manifest['files']:
  p=HERE/r['file'];assert p.stat().st_size==r['bytes']and sha(p)==r['sha256'],r['file']
 copies=read(HERE/'archive-copy-verification.json')['copied']
 for r in copies:
  assert sha(Path(r['source']))==r['sha256']==sha(HERE/r['file']),r['file']
 a=read(HERE/'main-capture/main.json');b=read(HERE/'repeat-capture/main.json');assert a==b==read(HERE/'main.json')
 specs=read(HERE/'cases.json');assert [c['spec']for c in a['cases']]==specs and len(specs)==52
 assert sum(len(c['operations'])for c in a['cases'])==187
 assert all(len(c['sources'])==40 for c in specs)
 variants=read(HERE/'fixture-variants.json');assert len(variants)==352
 assert tar_manifest(HERE/'fixtures.tar.gz')==variants
 inputs={};casefiles=[]
 for label in ['main-capture','repeat-capture']:
  p=HERE/label;receipt=read(p/'receipt.json');assert receipt['inputsUnchanged']and all(c['exitCode']==0 for c in receipt['commands'])
  assert [c['phase']for c in receipt['commands']]==['javap','compile','prepare','run']
  command=receipt['commands'][-1]['command'];assert '-Xmx512m'in command
  assert not any('OmitStackTrace' in x or 'directStringParallelism' in x for x in command)
  assert tar_manifest(p/'fixtures.tar.gz')==variants==read(p/'fixture-variants.json')
  with gzip.open(p/'fixture-before.json.gz','rt')as f:before=json.load(f)
  with gzip.open(p/'fixture-after.json.gz','rt')as f:after=json.load(f)
  assert before==after and len(before)==33335
  assert read(p/'fixture-audit.json')==dict(originalFiles=33335,changed=[],added=[],missing=[])
  for name,h in read(p/'inputs.json').items():
   if name in inputs:assert inputs[name]==h
   inputs[name]=h
  sourceRows=tar_manifest(p/'input-sources.tar.gz')
  for r in sourceRows:assert inputs['/'+r['file']]==r['sha256']
  casefiles.append(len(before))
 for name,h in inputs.items():assert sha(Path(name))==h,name
 for c in a['cases']:
  assert c['fixtureBeforeLoad']==c['fixtureAfterClose']
  for op in c['operations']:
   for phase in ['before','after']:
    s=op[phase];assert len(s['diagnostics'])==8 and len(s['storage'])==40
    assert all(len(r['persisted'])==7 for r in s['storage'])
 bridge=read(HERE/'prepared-bridge.json');assert len(bridge['cases'])==6 and all(c['inputSpecEqual']and c['commonOrderedOperationFieldsEqual']and not c['differences']for c in bridge['cases'])
 assert sha(HERE.parent/'native-leading-work-accounting/prepared-main-capture/main.json')==bridge['priorCaptureSHA256']
 failed=HERE/'failed-attempt-v1';assert read(failed/'failure.json')['publicOracleStarted']is False
 assert tar_manifest(failed/'generated-variants.tar.gz')==variants
 oldinputs=read(failed/'inputs.json')
 for r in tar_manifest(failed/'input-sources.tar.gz'):assert oldinputs['/'+r['file']]==r['sha256']
 return dict(status='pass',ownedManifestFiles=len(manifest['files']),externalCopiedArtifacts=len(copies),distinctFrozenRuntimeInputs=len(inputs),cases=52,operations=187,sourcesPerCase=40,fixtureVariants=21,fixtureRegularFiles=352,caseFixtureFilesPerCapture=casefiles,fullRawRepeatEqual=True,originalPreparedBridgeCommonRecordsEqual=6,failedPreparationPreserved=True,failedGeneratedFixtureBytesEqualSuccessful=True,defaultJVMConfiguration=True,performanceMeasurements=0,scope='Archive/input/public-output verification only. No build, runtime, native test or benchmark was launched by this verifier.')
if __name__=='__main__':print(json.dumps(verify(),indent=2))
