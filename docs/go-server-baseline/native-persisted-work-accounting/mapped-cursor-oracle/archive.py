from pathlib import Path
import gzip,hashlib,io,json,shutil,tarfile
P=Path(__file__).resolve().parent;B=Path('/Users/johnsonlee/.codex/benchmarks/graphite');rows=[]
sha=lambda b:hashlib.sha256(b).hexdigest()
for n,label in [(2,'initial-capture'),(3,'main-capture'),(4,'repeat-capture')]:
 source=B/('mapped-cursor-work-accounting-v'+str(n));dest=P/label;dest.mkdir(exist_ok=False)
 for f in sorted(source.rglob('*')):
  if not f.is_file()or f.relative_to(source).parts[0]=='fixtures':continue
  data=f.read_bytes();out=dest/f.relative_to(source);out.parent.mkdir(parents=True,exist_ok=True);out.write_bytes(data);rows.append(dict(source=str(f),file=str(out.relative_to(P)),bytes=len(data),sha256=sha(data)))
 if n==3:
  with tarfile.open(P/'fixtures.tar.gz','w:gz')as t:
   for f in sorted((source/'fixtures').rglob('*')):
    if f.is_file():data=f.read_bytes();i=tarfile.TarInfo(str(f.relative_to(source/'fixtures')));i.size=len(data);t.addfile(i,io.BytesIO(data))
  with tarfile.open(P/'input-sources.tar.gz','w:gz')as t:
   for path,h in json.loads((source/'inputs.json').read_text()).items():
    f=Path(path)
    if f.suffix not in ['.py','.java','.kt']and f.name!='release':continue
    data=f.read_bytes();assert sha(data)==h;i=tarfile.TarInfo(path.lstrip('/'));i.size=len(data);t.addfile(i,io.BytesIO(data))
a=json.loads((P/'main-capture/main.json').read_text());b=json.loads((P/'repeat-capture/main.json').read_text());assert a==b
shutil.copy2(P/'main-capture/main.json',P/'main.json')
(P/'archive-verification.json').write_text(json.dumps(dict(artifacts=rows,cases=len(a['cases']),operations=sum(len(c['operations'])for c in a['cases']),allParsedFieldsRepeatEqual=True,performanceMeasurements=0),indent=2)+'\n')
print('archived',len(rows))
