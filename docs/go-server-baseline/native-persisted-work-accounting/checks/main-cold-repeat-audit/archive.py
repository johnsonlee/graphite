from pathlib import Path
import gzip,hashlib,io,json,shutil,tarfile
HERE=Path(__file__).resolve().parent
BASE=Path('/Users/johnsonlee/.codex/benchmarks/graphite')
SRC=BASE/'persisted-work-f0838dda-main-cold-repeat-v1'
def sha(p):
 h=hashlib.sha256()
 with p.open('rb')as f:
  for b in iter(lambda:f.read(1048576),b''):h.update(b)
 return h.hexdigest()
rows=[]
for p in sorted(SRC.rglob('*')):
 if not p.is_file()or p.relative_to(SRC).parts[0]=='fixture':continue
 rel=p.relative_to(SRC);d=HERE/'evidence'/rel;d.parent.mkdir(parents=True,exist_ok=True)
 if p.suffix in ['.jsonl','.log']:
  d=Path(str(d)+'.gz')
  with p.open('rb')as a,gzip.open(d,'wb')as b:shutil.copyfileobj(a,b)
  with gzip.open(d,'rb')as f:h=hashlib.file_digest(f,'sha256').hexdigest()
  assert h==sha(p)
 else:shutil.copy2(p,d);assert sha(d)==sha(p)
 rows.append(dict(source=str(p),file=str(d.relative_to(HERE)),rawBytes=p.stat().st_size,rawSHA256=sha(p),archiveBytes=d.stat().st_size,archiveSHA256=sha(d),gzipEncoded=p.suffix in ['.jsonl','.log']))
for name in ['graphs.tsv','graphs-relocated.tsv']:
 p=SRC/'fixture'/name;d=HERE/'evidence'/name;shutil.copy2(p,d);rows.append(dict(source=str(p),file=str(d.relative_to(HERE)),rawBytes=p.stat().st_size,rawSHA256=sha(p),archiveBytes=d.stat().st_size,archiveSHA256=sha(d),gzipEncoded=False))
with tarfile.open(HERE/'evidence/input-sources.tar.gz','w:gz')as t:
 for name,h in json.loads((SRC/'input-identities.json').read_text()).items():
  p=Path(name)
  if p.suffix not in ['.py','.json','.java','.txt']and p.name!='release':continue
  data=p.read_bytes();assert hashlib.sha256(data).hexdigest()==h;i=tarfile.TarInfo(str(p).lstrip('/'));i.size=len(data);t.addfile(i,io.BytesIO(data))
natives=[]
for index,label in enumerate(['persisted-work-f0838dda-real64-v1','persisted-work-f0838dda-real64-cold-repeat-v1'],1):
 root=BASE/label;records=root/'native-cold/responses.jsonl';natives.append(dict(directory=str(root),responsesSHA256=sha(records),responsesBytes=records.stat().st_size))
 for name in ['native-cold-process.json','native-cold-inputs.json','module-source.json','cold-comparison.json']:
  p=root/name;d=HERE/'evidence'/('native-'+str(index)+'-'+name);shutil.copy2(p,d);assert sha(p)==sha(d);rows.append(dict(source=str(p),file=str(d.relative_to(HERE)),rawBytes=p.stat().st_size,rawSHA256=sha(p),archiveBytes=d.stat().st_size,archiveSHA256=sha(d),gzipEncoded=False))
(HERE/'archive-verification.json').write_text(json.dumps(dict(copied=rows,nativeExternalResponses=natives,scope='Byte-preserved independent repeat and original rejected comparisons; no reference replacement.',performanceMeasurement=False),indent=2)+'\n')
print('Archived',len(rows),'raw artifacts')
