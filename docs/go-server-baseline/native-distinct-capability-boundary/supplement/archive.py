"""Archive both declared actual-main correctness captures without normalization."""
from pathlib import Path
import hashlib,io,json,shutil,tarfile
HERE=Path(__file__).resolve().parent
BASE=Path('/Users/johnsonlee/.codex/benchmarks/graphite')
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def dump(p,v):p.write_text(json.dumps(v,indent=2)+'\n')
def main():
 copied=[]
 for version,label in [('v1','main-capture'),('v2','repeat-capture')]:
  src=BASE/('distinct-capability-supplement-'+version);dest=HERE/label;dest.mkdir(exist_ok=False)
  for f in sorted(src.rglob('*')):
   if not f.is_file() or f.relative_to(src).parts[0] in ('fixtures','variants'):continue
   target=dest/f.relative_to(src);target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(f,target)
   assert sha(f)==sha(target);copied.append(dict(source=str(f),file=str(target.relative_to(HERE)),sha256=sha(f)))
  inputs=json.loads((src/'inputs.json').read_text())
  with tarfile.open(dest/'input-sources.tar.gz','w:gz') as t:
   for path,expected in inputs.items():
    p=Path(path)
    if p.suffix not in ('.java','.kt','.py','.json'):continue
    data=p.read_bytes();assert hashlib.sha256(data).hexdigest()==expected,path
    info=tarfile.TarInfo(str(p).lstrip('/'));info.size=len(data);t.addfile(info,io.BytesIO(data))
 a=json.loads((HERE/'main-capture/main.json').read_text());b=json.loads((HERE/'repeat-capture/main.json').read_text())
 diffs=[]
 for x,y in zip(a['cases'],b['cases']):
  keys=[k for k in x.keys()|y.keys() if x.get(k)!=y.get(k)]
  if keys:diffs.append(dict(name=x['name'],fields={k:dict(main=x.get(k),repeat=y.get(k)) for k in keys}))
  assert all(x.get(k)==y.get(k) for k in x.keys()|y.keys() if k!='diagnostics'),x['name']
 a_files=json.loads((HERE/'main-capture/fixture-variants.json').read_text());b_files=json.loads((HERE/'repeat-capture/fixture-variants.json').read_text())
 different=[]
 for x,y in zip(a_files,b_files):
  assert x['file']==y['file']
  if x!=y:
   p=x['file'];left=(BASE/'distinct-capability-supplement-v1/variants'/p).read_bytes();right=(BASE/'distinct-capability-supplement-v2/variants'/p).read_bytes()
   assert Path(p).name=='forward.properties'
   assert b'\n'.join(left.splitlines()[:1]+left.splitlines()[2:])==b'\n'.join(right.splitlines()[:1]+right.splitlines()[2:])
   different.append(dict(file=p,mainWriterComment=left.splitlines()[1].decode(),repeatWriterComment=right.splitlines()[1].decode()))
 dump(HERE/'repeat-audit.json',dict(caseCount=len(a['cases']),publicOutputsExact=True,completeOutputExact=not diffs,fullFieldDifferences=diffs,fixtureFilesPerCapture=len(a_files),byteIdenticalFixtureFiles=len(a_files)-len(different),writerTimestampOnlyDifferences=different,performanceMeasurements=0))
 dump(HERE/'archive-copy-verification.json',dict(copied=copied,allCopiedBytesVerified=True))
 print('archived',len(copied),'files; public outputs exact; full-field differences',len(diffs))
if __name__=='__main__':main()
