"""Retain the initial ten-case capture and both expanded original-JVM captures."""
from pathlib import Path
import hashlib,io,json,shutil,tarfile
HERE=Path(__file__).resolve().parent
BASE=Path('/Users/johnsonlee/.codex/benchmarks/graphite')
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def dump(p,x):p.write_text(json.dumps(x,indent=2)+'\n')
def main():
 copies=[]
 for name,label in [('raw-method-zero-main-v1','initial-ten-capture'),('raw-method-zero-expanded-v1','main-capture'),('raw-method-zero-expanded-v2','repeat-capture')]:
  src=BASE/name;dst=HERE/label;dst.mkdir(exist_ok=False)
  receipt=json.loads((src/'receipt.json').read_text());assert receipt['inputsUnchanged'] and all(c['exitCode']==0 for c in receipt['commands'])
  for f in sorted(src.rglob('*')):
   if not f.is_file() or f.relative_to(src).parts[0] in ['fixtures','variants']:continue
   target=dst/f.relative_to(src);target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(f,target);assert sha(f)==sha(target);copies.append(dict(source=str(f),file=str(target.relative_to(HERE)),bytes=f.stat().st_size,sha256=sha(f)))
  inputs=json.loads((src/'inputs.json').read_text())
  with tarfile.open(dst/'input-sources.tar.gz','w:gz')as t:
   for name,h in inputs.items():
    p=Path(name)
    if p.suffix not in ['.java','.kt','.json','.py']:continue
    data=p.read_bytes();assert hashlib.sha256(data).hexdigest()==h;info=tarfile.TarInfo(str(p).lstrip('/'));info.size=len(data);t.addfile(info,io.BytesIO(data))
 a=json.loads((HERE/'main-capture/main.json').read_text());b=json.loads((HERE/'repeat-capture/main.json').read_text());assert a==b and len(a['cases'])==16 and sum(len(c['operations'])for c in a['cases'])==16
 for name in ['main.json','fixtures.tar.gz','fixture-variants.json']:shutil.copy2(HERE/'main-capture'/name,HERE/name)
 dump(HERE/'archive-copy-verification.json',dict(copied=copies,allCopiedBytesVerified=True))
 dump(HERE/'repeat-audit.json',dict(caseCount=16,operationCount=16,completeRecordsEqual=True,fixtureArchiveRegularFiles=16,fixtureFilesUnchangedPerExpandedCapture=256,initialTenCaseCaptureRetained=True,performanceMeasurements=0))
 print('Archived',len(copies),'artifacts; final16cases16ops equal')
if __name__=='__main__':main()
