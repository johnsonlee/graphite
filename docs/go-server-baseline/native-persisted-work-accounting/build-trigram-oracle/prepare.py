from pathlib import Path
import hashlib,json,tarfile
HERE=Path(__file__).resolve().parent
SOURCE=HERE.parent/'fixtures.tar.gz'
def prepare(out):
 out.mkdir(parents=True,exist_ok=False)
 with tarfile.open(SOURCE)as t:
  for m in t:
   if m.isfile()and m.name.split('/')[0]in ['bad-magic','empty']:
    p=out/m.name;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(t.extractfile(m).read())
 return dict(sourceArchive=str(SOURCE),sourceArchiveSHA256=hashlib.sha256(SOURCE.read_bytes()).hexdigest(),sourceMainRevision='4e328b0109e13c896b74004823fb049fcb19251a',mutations=[],reusedVariants=['bad-magic','empty'],performanceMeasurements=0)
if __name__=='__main__':
 import argparse
 p=argparse.ArgumentParser();p.add_argument('output',type=Path);a=p.parse_args();r=prepare(a.output);(a.output.parent/'mutations.json').write_text(json.dumps(r,indent=2)+'\n')
