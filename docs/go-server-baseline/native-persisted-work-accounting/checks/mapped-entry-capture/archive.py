from pathlib import Path
import hashlib,json,shutil,tarfile
P=Path(__file__).resolve().parent;B=Path('/Users/johnsonlee/.codex/benchmarks/graphite');rows=[]
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
for number,label in [(1,'failed-v1'),(2,'baseline-v2')]:
 source=B/('mapped-entry-go-baseline-v'+str(number));dest=P/label;dest.mkdir(exist_ok=False)
 for f in sorted(source.rglob('*')):
  if not f.is_file()or f.relative_to(source).parts[0]=='module':continue
  out=dest/f.relative_to(source);out.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(f,out);assert sha(out)==sha(f);rows.append(dict(source=str(f),file=str(out.relative_to(P)),bytes=f.stat().st_size,sha256=sha(f)))
 with tarfile.open(dest/'source-with-adapter.tar.gz')as t:
  expected=json.loads((dest/'module-with-adapter-inputs.json').read_text());members=[m for m in t if m.isfile()];assert len(members)==len(expected)==2571
  for m in members:assert hashlib.sha256(t.extractfile(m).read()).hexdigest()==expected[m.name]
(P/'archive-verification.json').write_text(json.dumps(dict(artifacts=rows,sourceArchiveMembersEach=2571,baselineOriginalFiles=2568,adapterFiles=3,failedAttemptRetained=True,performanceMeasurements=0),indent=2)+'\n');print('archived',len(rows))
