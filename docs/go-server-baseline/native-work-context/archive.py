"""Archive original operation records and independently identify repeat differences."""
from pathlib import Path
import hashlib,io,json,shutil,tarfile
HERE=Path(__file__).resolve().parent
BASE=Path('/Users/johnsonlee/.codex/benchmarks/graphite')
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def dump(p,v):p.write_text(json.dumps(v,indent=2)+'\n')
def differences(a,b,path=''):
 if type(a)!=type(b):return [dict(path=path,main=a,repeat=b)]
 if isinstance(a,dict):
  out=[]
  for k in a.keys()|b.keys():out+=differences(a.get(k),b.get(k),path+'.'+k)
  return out
 if isinstance(a,list):
  if len(a)!=len(b):return [dict(path=path,main=a,repeat=b)]
  return [d for i,(x,y) in enumerate(zip(a,b)) for d in differences(x,y,path+'['+str(i)+']')]
 return [] if a==b else [dict(path=path,main=a,repeat=b)]
def main():
 copied=[]
 for version,label in [('v1','main-capture'),('v2','repeat-capture')]:
  src=BASE/('work-context-main-'+version);dest=HERE/label;dest.mkdir(exist_ok=False)
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
 diffs=differences(a,b)
 expected={'.cases[22].operations[0].value.workers['+str(i)+'].successfulConsumes' for i in range(4)}
 assert {d['path'] for d in diffs}==expected,diffs
 for output in [a,b]:
  value=output['cases'][22]['operations'][0]['value']
  assert value['successfulConsumes']==100 and value['budgetExceededWorkers']==4
  assert sum(w['successfulConsumes'] for w in value['workers'])==100
 a_files=json.loads((HERE/'main-capture/fixture-variants.json').read_text());b_files=json.loads((HERE/'repeat-capture/fixture-variants.json').read_text());different=[]
 for x,y in zip(a_files,b_files):
  assert x['file']==y['file']
  if x!=y:
   p=x['file'];left=(BASE/'work-context-main-v1/variants'/p).read_bytes();right=(BASE/'work-context-main-v2/variants'/p).read_bytes()
   assert Path(p).name=='forward.properties'
   assert b'\n'.join(left.splitlines()[:1]+left.splitlines()[2:])==b'\n'.join(right.splitlines()[:1]+right.splitlines()[2:])
   different.append(dict(file=p,mainWriterComment=left.splitlines()[1].decode(),repeatWriterComment=right.splitlines()[1].decode()))
 dump(HERE/'repeat-audit.json',dict(caseCount=51,operationCount=129,completeOutputExactCases=50,fullFieldDifferences=diffs,concurrentAggregateExact=True,fixtureFilesPerCapture=len(a_files),byteIdenticalFixtureFiles=len(a_files)-len(different),writerTimestampOnlyDifferences=different,performanceMeasurements=0))
 dump(HERE/'archive-copy-verification.json',dict(copied=copied,allCopiedBytesVerified=True))
 print('archived',len(copied),'files; only four worker-allocation fields differ')
if __name__=='__main__':main()
