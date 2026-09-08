"""Archive one terminal diagnostic invocation after post-run analysis."""
import argparse
import gzip
import hashlib
import json
from pathlib import Path
import shutil
import tarfile

SCRIPT_BASE=Path(__file__).resolve().parent
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--directory',type=Path,required=True)
BASE=parser.parse_args().directory.resolve()
def read(p):return json.loads(p.read_text())
def sha(p):
    h=hashlib.sha256()
    with p.open('rb') as s:
        for b in iter(lambda:s.read(1024*1024),b''):h.update(b)
    return h.hexdigest()
def write(p,v):p.write_text(json.dumps(v,indent=2)+'\n')
process=read(BASE/'process.json');correctness=read(BASE/'correctness.json');build=read(BASE/'build.json')
assert process['status']=='exited' and process['exitCode']==1 and process['inputsUnchanged'] and process['fixtureAuditPassed']
assert correctness['passed'] and correctness['caseCount']==1267
source=Path(build['source']);expected=read(BASE/'build-source-inputs.json')
actual={str(p.relative_to(source)):sha(p) for p in source.rglob('*') if p.is_file()}
assert actual==expected
archive=BASE/'build-source.tar.gz'
assert not archive.exists()
with tarfile.open(archive,'w:gz') as t:t.add(source,arcname='source')
with tarfile.open(archive) as t:
    found={}
    for member in t.getmembers():
        if member.isfile():
            name=member.name.removeprefix('source/');found[name]=hashlib.sha256(t.extractfile(member).read()).hexdigest()
    assert found==expected
binary=Path(build['nativeBinary']);assert sha(binary)==build['binarySHA256']
with (BASE/'graphite-benchmark-profile.gz').open('xb') as dest:
    with gzip.GzipFile(fileobj=dest,mode='wb',mtime=0) as stream:
        with binary.open('rb') as inp:shutil.copyfileobj(inp,stream)
write(BASE/'source-archive.json',dict(file=archive.name,sha256=sha(archive),bytes=archive.stat().st_size,files=len(found),allCompleteModuleFilesVerified=True,binarySHA256=sha(binary)))
# All generated artifacts are new in this evidence directory. Existing scripts stay fixed.
for item in sorted(BASE.iterdir()):
    if item.name in ('source','fixture','graphite-benchmark-profile'):continue
    target=SCRIPT_BASE/item.name
    assert not target.exists(),f'Refuse overwrite: {target}'
    if item.is_dir():shutil.copytree(item,target)
    else:shutil.copy2(item,target)
paths=[p for p in SCRIPT_BASE.rglob('*') if p.is_file() and '__pycache__' not in p.parts]
write(SCRIPT_BASE/'artifact-manifest.json',[dict(path=str(p.relative_to(SCRIPT_BASE)),bytes=p.stat().st_size,sha256=sha(p)) for p in sorted(paths)])
print(json.dumps(dict(archive=str(SCRIPT_BASE),artifactFiles=len(paths),moduleFiles=len(found),performanceMeasurement=False)))
