from pathlib import Path
import hashlib,io,json,shutil,tarfile
HERE=Path(__file__).resolve().parent
BASE=Path('/Users/johnsonlee/.codex/benchmarks/graphite')
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def read(p):return json.loads(p.read_text())
def dump(p,x):p.write_text(json.dumps(x,indent=2)+'\n')
def diff(a,b,path=''):
 if type(a)!=type(b):return[path]
 if isinstance(a,dict):return[d for k in sorted(a.keys()|b.keys())for d in(diff(a[k],b[k],path+'/'+k)if k in a and k in b else[path+'/'+k])]
 if isinstance(a,list):return[path+'/length']if len(a)!=len(b)else[d for i,(x,y)in enumerate(zip(a,b))for d in diff(x,y,path+'/'+str(i))]
 return[]if a==b else[path]
def archive(here,prefix):
 copied=[]
 for suffix,label in [('v1','main-capture'),('v2','repeat-capture')]:
  src=BASE/(prefix+'-'+suffix);dest=here/label;dest.mkdir(exist_ok=False)
  r=read(src/'receipt.json');assert r['inputsUnchanged']and all(c['exitCode']==0 for c in r['commands'])
  for f in sorted(src.rglob('*')):
   if not f.is_file()or f.relative_to(src).parts[0]in ['variants','fixtures']:continue
   target=dest/f.relative_to(src);target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(f,target);assert sha(f)==sha(target)
   copied.append(dict(source=str(f),file=str(target.relative_to(here)),bytes=f.stat().st_size,sha256=sha(f)))
  with tarfile.open(dest/'input-sources.tar.gz','w:gz')as t:
   for name,h in read(src/'inputs.json').items():
    p=Path(name)
    if p.suffix not in ['.java','.kt','.json','.py']:continue
    data=p.read_bytes();assert hashlib.sha256(data).hexdigest()==h;i=tarfile.TarInfo(str(p).lstrip('/'));i.size=len(data);t.addfile(i,io.BytesIO(data))
 a=read(here/'main-capture/main.json');b=read(here/'repeat-capture/main.json');specs=read(here/'cases.json');assert [c['spec']for c in a['cases']]==specs
 differences=diff(a,b)
 dump(here/'repeat-audit.json',dict(cases=len(specs),operations=sum(len(c['operations'])for c in specs),fullRawRepeatEqual=not differences,differingPaths=differences,defaultPublicJVMConfiguration=True,performanceMeasurements=0))
 for name in ['main.json','fixtures.tar.gz','fixture-variants.json','mutations.json']:shutil.copy2(here/'main-capture'/name,here/name)
 dump(here/'archive-copy-verification.json',dict(copied=copied,allCopiedBytesVerified=True))
 dump(here/'outcomes.json',[dict(name=c['name'],operations=[dict(operation=i,spec=o['spec'],outcome=o['outcome'],error=o.get('error'),message=o.get('message'),workDelta=o['after']['diagnostics']['workUnitsConsumed']-o['before']['diagnostics']['workUnitsConsumed'],diagnostics=o['after']['diagnostics'],source0=o['after']['storage'][0],remaining=o['after']['remaining'])for i,o in enumerate(c['operations'])if o['spec']['op']=='execute'])for c in a['cases']])
 return dict(cases=len(specs),operations=sum(len(c['operations'])for c in specs),repeatDifferences=len(differences),copiedArtifacts=len(copied))
if __name__=='__main__':print(archive(HERE,'build-trigram-work-accounting'))
