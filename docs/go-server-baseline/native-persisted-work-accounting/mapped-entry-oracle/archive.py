from pathlib import Path
import hashlib,io,json,shutil,tarfile
P=Path(__file__).resolve().parent;BASE=Path('/Users/johnsonlee/.codex/benchmarks/graphite');rows=[]
sha=lambda b:hashlib.sha256(b).hexdigest()
for number,label in [(1,'main-capture'),(2,'repeat-capture')]:
 source=BASE/('mapped-entry-work-accounting-v'+str(number));dest=P/label;dest.mkdir(exist_ok=False)
 for f in sorted(source.rglob('*')):
  if not f.is_file()or f.relative_to(source).parts[0]in ['fixtures','variant']:continue
  b=f.read_bytes();out=dest/f.relative_to(source);out.parent.mkdir(parents=True,exist_ok=True);out.write_bytes(b);rows.append(dict(source=str(f),file=str(out.relative_to(P)),bytes=len(b),sha256=sha(b)))
 if number==1:
  with tarfile.open(P/'fixtures.tar.gz','w:gz')as t:
   for f in sorted((source/'variant').rglob('*')):
    if f.is_file():b=f.read_bytes();i=tarfile.TarInfo(str(f.relative_to(source/'variant')));i.size=len(b);t.addfile(i,io.BytesIO(b))
  with tarfile.open(P/'input-sources.tar.gz','w:gz')as t:
   for path,h in json.loads((source/'inputs.json').read_text()).items():
    f=Path(path)
    if f.suffix not in ['.py','.java','.kt','.json']and f.name!='release':continue
    b=f.read_bytes();assert sha(b)==h;i=tarfile.TarInfo(path.lstrip('/'));i.size=len(b);t.addfile(i,io.BytesIO(b))
a=json.loads((P/'main-capture/main.json').read_text());assert a==json.loads((P/'repeat-capture/main.json').read_text());shutil.copy2(P/'main-capture/main.json',P/'main.json');(P/'archive-verification.json').write_text(json.dumps(dict(artifacts=rows,cases=len(a['cases']),probePhases=sum(len(c['probe']['steps'])for c in a['cases']),warmupPhases=sum(len(c.get('warmup',{}).get('steps',[]))for c in a['cases']),fullParsedRepeatEqual=True),indent=2)+'\n');print('archived',len(rows))
