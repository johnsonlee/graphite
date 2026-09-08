"""Keep independent raw captures, exact byte receipts and a six-control bridge."""
from pathlib import Path
import hashlib,io,json,shutil,tarfile
HERE=Path(__file__).resolve().parent
BASE=Path('/Users/johnsonlee/.codex/benchmarks/graphite')
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def dump(p,x):p.write_text(json.dumps(x,indent=2)+'\n')
def read(p):return json.loads(p.read_text())
def differences(a,b,path=''):
 if type(a)!=type(b):return [path]
 if isinstance(a,dict):
  out=[]
  for k in sorted(a.keys()|b.keys()):out += differences(a[k],b[k],path+'/'+k)if k in a and k in b else[path+'/'+k]
  return out
 if isinstance(a,list):
  if len(a)!=len(b):return[path+'/length']
  return [d for i,(x,y)in enumerate(zip(a,b))for d in differences(x,y,path+'/'+str(i))]
 return []if a==b else[path]
def common_snapshot(s):
 s=json.loads(json.dumps(s))
 for row in s.get('storage',[]):row.pop('persisted',None)
 return s
def common_operation(op):
 op=json.loads(json.dumps(op));op.pop('stack',None)
 for k in ['before','after']:op[k]=common_snapshot(op[k])
 return op
def main():
 copied=[]
 for suffix,label in [('v2','main-capture'),('v3','repeat-capture')]:
  src=BASE/('persisted-work-accounting-'+suffix);dest=HERE/label;dest.mkdir(exist_ok=False)
  receipt=read(src/'receipt.json');assert receipt['inputsUnchanged']and all(c['exitCode']==0 for c in receipt['commands'])
  for f in sorted(src.rglob('*')):
   if not f.is_file()or f.relative_to(src).parts[0]in ['variants','fixtures']:continue
   target=dest/f.relative_to(src);target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(f,target);assert sha(f)==sha(target)
   copied.append(dict(source=str(f),file=str(target.relative_to(HERE)),bytes=f.stat().st_size,sha256=sha(f)))
  with tarfile.open(dest/'input-sources.tar.gz','w:gz')as t:
   for name,h in read(src/'inputs.json').items():
    p=Path(name)
    if p.suffix not in ['.java','.kt','.json','.py']:continue
    data=p.read_bytes();assert hashlib.sha256(data).hexdigest()==h
    i=tarfile.TarInfo(str(p).lstrip('/'));i.size=len(data);t.addfile(i,io.BytesIO(data))
 a=read(HERE/'main-capture/main.json');b=read(HERE/'repeat-capture/main.json');specs=read(HERE/'cases.json')
 assert len(a['cases'])==len(b['cases'])==len(specs)
 assert [c['spec']for c in a['cases']]==specs
 diffs=differences(a,b)
 dump(HERE/'repeat-audit.json',dict(cases=len(specs),operations=sum(len(c['operations'])for c in specs),allCapturedFieldsEqual=not diffs,differingPaths=diffs,defaultJVMConfiguration=True,performanceMeasurements=0))
 previous=read(HERE.parent/'native-leading-work-accounting/prepared-main-capture/main.json');oldby={c['name']:c for c in previous['cases']};bridge=[]
 for c in a['cases']:
  if c['name']not in oldby:continue
  old=oldby[c['name']];d=differences([common_operation(x)for x in old['operations']],[common_operation(x)for x in c['operations']])
  bridge.append(dict(name=c['name'],inputSpecEqual=c['spec']==old['spec'],commonOrderedOperationFieldsEqual=not d,differences=d,priorOperations=old['operations'],currentOperations=c['operations']))
 assert len(bridge)==6
 dump(HERE/'prepared-bridge.json',dict(priorCapture='../native-leading-work-accounting/prepared-main-capture/main.json',priorCaptureSHA256=sha(HERE.parent/'native-leading-work-accounting/prepared-main-capture/main.json'),cases=bridge,comparisonExcludes=['stack (original frames retained in both records)','new storage.persisted observer object'],performanceMeasurements=0))
 for name in ['main.json','fixtures.tar.gz','fixture-variants.json','mutations.json']:shutil.copy2(HERE/'main-capture'/name,HERE/name)
 dump(HERE/'archive-copy-verification.json',dict(copied=copied,allCopiedBytesVerified=True))
 print('Archived',len(copied),'artifacts;',len(specs),'cases;',len(diffs),'raw repeat differences; bridge diffs:',[(c['name'],c['differences'])for c in bridge if c['differences']])
if __name__=='__main__':main()
