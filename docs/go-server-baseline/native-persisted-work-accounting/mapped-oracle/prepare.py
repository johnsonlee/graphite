"""Immutable prior actual-main fixtures plus explicit mapped-validator controls."""
from pathlib import Path
import hashlib,json,shutil,struct,tarfile,zlib
HERE=Path(__file__).resolve().parent
SOURCE=HERE.parent/'fixtures.tar.gz'
def sha(b):return hashlib.sha256(b).hexdigest()
def crc(b):
 S,N=struct.unpack_from('>ii',b,8);U=struct.unpack_from('>4i',b,48)
 end=76+4*(2*sum(U)+4*N)
 data=b''.join(b[i:i+4][::-1]for i in range(0,16,4))+b[16:48]+b''.join(b[i:i+4][::-1]for i in range(48,68,4))+b[68:76][::-1]
 data+=b''.join(b[i:i+4][::-1]for i in range(76,end,4))+b''.join(b[i:i+8][::-1]for i in range(end,len(b)-8,8))
 return zlib.crc32(data)
def prepare(out):
 out.mkdir(parents=True,exist_ok=False)
 with tarfile.open(SOURCE)as t:
  for m in t:
   if m.isfile():
    p=out/m.name;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(t.extractfile(m).read())
 changes=[]
 for name,base,fn in [('truncated-layout','hit64',lambda b:b[:84]),('bad-node-order-valid-crc','bad-node-order',lambda b:b[:-8]+struct.pack('>q',crc(b)))]:
  shutil.copytree(out/base,out/name);p=out/name/'graph.callsite-string-index';before=p.read_bytes();after=fn(before);p.write_bytes(after)
  changes.append(dict(fixture=name,sourceFixture=base,file=p.name,beforeSHA256=sha(before),afterSHA256=sha(after),beforeBytes=len(before),afterBytes=len(after)))
 return dict(sourceArchive=str(SOURCE),sourceArchiveSHA256=sha(SOURCE.read_bytes()),sourceMainRevision='4e328b0109e13c896b74004823fb049fcb19251a',mutations=changes,performanceMeasurements=0)
if __name__=='__main__':
 import argparse
 p=argparse.ArgumentParser();p.add_argument('output',type=Path);a=p.parse_args();r=prepare(a.output);(a.output.parent/'mutations.json').write_text(json.dumps(r,indent=2)+'\n')
