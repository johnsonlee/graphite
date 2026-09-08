"""Archive every original capture and classify persisted-index and input-identity differences."""
from pathlib import Path
import hashlib,io,json,shutil,tarfile
HERE=Path(__file__).resolve().parent
BASE=Path('/Users/johnsonlee/.codex/benchmarks/graphite')
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def dump(p,x):p.write_text(json.dumps(x,indent=2)+'\n')
def read(p):return json.loads(p.read_text())
def execution(c):return {k:v for k,v in c.items() if k not in ['fixtureBeforeLoad','fixtureAfterClose']}
def main():
 copied=[];labels=[('main-v1','prepared-main-capture'),('main-v2','prepared-repeat-capture'),('unprepared-v1','main-capture'),('unprepared-v2','repeat-capture')]
 for suffix,label in labels:
  src=BASE/('leading-work-accounting-'+suffix);dest=HERE/label;dest.mkdir(exist_ok=False);receipt=read(src/'receipt.json');assert receipt['inputsUnchanged'] and all(c['exitCode']==0 for c in receipt['commands'])
  for f in sorted(src.rglob('*')):
   if not f.is_file() or f.relative_to(src).parts[0] in ['variants','fixtures']:continue
   target=dest/f.relative_to(src);target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(f,target);assert sha(f)==sha(target);copied.append(dict(source=str(f),file=str(target.relative_to(HERE)),bytes=f.stat().st_size,sha256=sha(f)))
  inputs=read(src/'inputs.json')
  with tarfile.open(dest/'input-sources.tar.gz','w:gz')as t:
   for name,h in inputs.items():
    p=Path(name)
    if p.suffix not in ['.java','.kt','.json','.py']:continue
    data=p.read_bytes();assert hashlib.sha256(data).hexdigest()==h;info=tarfile.TarInfo(str(p).lstrip('/'));info.size=len(data);t.addfile(info,io.BytesIO(data))
 pairs=[]
 for prefix,left,right,count,ops in [('main','prepared-main-capture','prepared-repeat-capture',24,48),('unprepared','main-capture','repeat-capture',26,52)]:
  a=read(HERE/left/'main.json');b=read(HERE/right/'main.json');assert len(a['cases'])==len(b['cases'])==count
  assert sum(len(c['operations'])for c in a['cases'])==ops;assert {k:v for k,v in a.items()if k!='cases'}=={k:v for k,v in b.items()if k!='cases'}
  assert [execution(c)for c in a['cases']]==[execution(c)for c in b['cases']]
  identity=[];fa=read(HERE/left/'fixture-variants.json');fb=read(HERE/right/'fixture-variants.json');assert len(fa)==len(fb)
  for x,y in zip(fa,fb):
   assert x['file']==y['file']
   if x!=y:
    lx=(BASE/('leading-work-accounting-'+prefix+'-v1')/'variants'/x['file']).read_bytes();rx=(BASE/('leading-work-accounting-'+prefix+'-v2')/'variants'/y['file']).read_bytes();assert Path(x['file']).name=='forward.properties';assert b'\n'.join(lx.splitlines()[:1]+lx.splitlines()[2:])==b'\n'.join(rx.splitlines()[:1]+rx.splitlines()[2:]);identity.append(dict(file=x['file'],mainSHA256=x['sha256'],repeatSHA256=y['sha256'],mainComment=lx.splitlines()[1].decode(),repeatComment=rx.splitlines()[1].decode()))
  for data in [a,b]:
   for c in data['cases']:assert c['fixtureBeforeLoad']==c['fixtureAfterClose']
  pairs.append(dict(main=left,repeat=right,cases=count,operations=ops,allExecutionRecordsEqual=True,errorClassMessageStackDifferences=[],fixtureFiles=len(fa),writerTimestampOnlyDifferences=identity,caseFixtureManifestsRetainedWithoutNormalization=True))
 a=read(HERE/'prepared-main-capture/main.json')['cases'];b=read(HERE/'main-capture/main.json')['cases'];byname={c['name']:c for c in b};different=[]
 for old in a:
  new=byname[old['name']]
  if execution(old)==execution(new):continue
  changed=[]
  for i,(o,n)in enumerate(zip(old['operations'],new['operations'])):
   if o==n:continue
   fields=[k for k in sorted(o.keys()|n.keys()) if o.get(k)!=n.get(k) or (k in o)!=(k in n)]
   changed.append(dict(operation=i,fields=fields,prepared={k:o.get(k)for k in ['outcome','error','message','value','after']},unprepared={k:n.get(k)for k in ['outcome','error','message','value','after']},preparedStack=o.get('stack'),unpreparedStack=n.get('stack')))
  different.append(dict(name=old['name'],operations=changed))
 assert {c['name'] for c in different}=={'L04-hit64-budget128','L04-hit64-budget129','L05-hit1024-budget1088','L05-hit1024-budget1089','L12-bad64-budget128','L12-bad64-budget129'}
 dump(HERE/'prepared-comparison.json',dict(comparedCases=24,differentCases=different,scope='Persisted-index-read controls are retained separately; no native parity or complete indexed work accounting is claimed.',performanceMeasurements=0))
 for name in ['main.json','fixtures.tar.gz','fixture-variants.json','mutations.json']:shutil.copy2(HERE/'main-capture'/name,HERE/name)
 # Prove deleted sidecar bytes correspond to the original main-generated prepared variant.
 with tarfile.open(HERE/'prepared-main-capture/fixtures.tar.gz')as t:prepared={m.name:hashlib.sha256(t.extractfile(m).read()).hexdigest() for m in t if m.isfile()}
 deleted=[m for m in read(HERE/'mutations.json')if m.get('action')=='remove-generated-sidecar'];assert len(deleted)==11
 for d in deleted:assert prepared[d['fixture']+'/'+d['file']]==d['sha256']
 dump(HERE/'repeat-audit.json',dict(pairs=pairs,finalCases=26,finalOperations=52,finalFixtureRegularFiles=208,sidecarsExplicitlyRemoved=len(deleted),removedSidecarSHAsMatchActualPreparedArchive=True,defaultJVMConfiguration=True,performanceMeasurements=0))
 dump(HERE/'archive-copy-verification.json',dict(copied=copied,allCopiedBytesVerified=True))
 print('Archived',len(copied),'raw artifacts; final26/52 exact execution repeats; prepared6 route differences preserved')
if __name__=='__main__':main()
