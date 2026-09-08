"""Freeze all actual captures, including nullable fast-throw errors, without rewriting them."""
from pathlib import Path
import hashlib,io,json,shutil,tarfile
HERE=Path(__file__).resolve().parent
BASE=Path('/Users/johnsonlee/.codex/benchmarks/graphite')
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def dump(p,v):p.write_text(json.dumps(v,indent=2)+'\n')
def differences(a,b,path=''):
 if type(a)!=type(b):return [dict(path=path,main=a,repeat=b)]
 if isinstance(a,dict):return [d for k in sorted(a.keys()|b.keys()) for d in differences(a.get(k),b.get(k),path+'.'+k)]
 if isinstance(a,list):
  if len(a)!=len(b):return [dict(path=path,main=a,repeat=b)]
  return [d for i,(x,y) in enumerate(zip(a,b)) for d in differences(x,y,path+'['+str(i)+']')]
 return [] if a==b else [dict(path=path,main=a,repeat=b)]
def main():
 copied=[]
 for suffix,label in [('main-v1','main-capture'),('main-v2','repeat-capture'),('diagnostic-v1','diagnostic-main-capture'),('diagnostic-v2','diagnostic-repeat-capture')]:
  src=BASE/('raw-work-batches-'+suffix);dest=HERE/label;dest.mkdir(exist_ok=False)
  receipt=json.loads((src/'receipt.json').read_text());assert receipt['inputsUnchanged'] and all(c['exitCode']==0 for c in receipt['commands'])
  for f in sorted(src.rglob('*')):
   if not f.is_file() or f.relative_to(src).parts[0] in ('fixtures','variants'):continue
   target=dest/f.relative_to(src);target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(f,target);assert sha(f)==sha(target)
   copied.append(dict(source=str(f),file=str(target.relative_to(HERE)),bytes=f.stat().st_size,sha256=sha(f)))
  inputs=json.loads((src/'inputs.json').read_text())
  with tarfile.open(dest/'input-sources.tar.gz','w:gz') as t:
   for path,expected in inputs.items():
    p=Path(path)
    if p.suffix not in ('.java','.kt','.py','.json'):continue
    data=p.read_bytes();assert hashlib.sha256(data).hexdigest()==expected,path
    info=tarfile.TarInfo(str(p).lstrip('/'));info.size=len(data);t.addfile(info,io.BytesIO(data))
 pairs=[]
 for left,right,expected in [('main-capture','repeat-capture',4),('diagnostic-main-capture','diagnostic-repeat-capture',0)]:
  a=json.loads((HERE/left/'main.json').read_text());b=json.loads((HERE/right/'main.json').read_text());diff=differences(a,b)
  assert len(a['cases'])==len(b['cases'])==43 and sum(len(c['operations'])for c in a['cases'])==57
  assert len(diff)==expected,diff
  if expected:assert {d['path']for d in diff}=={f'.cases[{i}].operations[0].{field}'for i in [32,34] for field in ['message','stack']}
  pa=json.loads((HERE/left/'buffered-main.json').read_text());pb=json.loads((HERE/right/'buffered-main.json').read_text());assert pa==pb and len(pa['cases'])==12 and sum(len(c['operations'])for c in pa['cases'])==39
  pairs.append(dict(main=left,repeat=right,publicCaseCount=43,publicOperationCount=57,primitiveCaseCount=12,primitiveOperationCount=39,primitiveCompleteRecordsEqual=True,fullFieldDifferences=diff))
 for name in ['main.json','buffered-main.json','fixtures.tar.gz','fixture-variants.json','mutations.json']:shutil.copy2(HERE/'main-capture'/name,HERE/name)
 variants=json.loads((HERE/'fixture-variants.json').read_text());assert len(variants)==329
 with tarfile.open(HERE/'fixtures.tar.gz')as t:
  members=t.getmembers();assert len(members)==329 and all(m.isfile()for m in members)
  records={v['file']:v for v in variants}
  for m in members:assert hashlib.sha256(t.extractfile(m).read()).hexdigest()==records[m.name]['sha256']
 dump(HERE/'repeat-audit.json',dict(pairs=pairs,originalConfigurationPreserved=True,diagnosticFlag='-XX:-OmitStackTraceInFastThrow',diagnosticPurpose='Investigate nullable messages and omitted stacks; does not replace original JVM configuration acceptance.',fixtureArchiveRegularFiles=329,performanceMeasurements=0))
 dump(HERE/'archive-copy-verification.json',dict(copied=copied,allCopiedBytesVerified=True))
 print('Archived',len(copied),'original artifacts; public43/57 and buffered12/39 in each of four terminal captures')
if __name__=='__main__':main()
