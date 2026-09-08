from pathlib import Path
import hashlib,json,shutil,struct,tarfile
HERE=Path(__file__).resolve().parent
SOURCES=[HERE.parent/'large-writer-capture/fixtures.tar.gz',HERE.parent.parent/'fixtures.tar.gz']
def sha(b):return hashlib.sha256(b).hexdigest()
def prepare(out):
 out.mkdir(parents=True,exist_ok=False)
 for source in SOURCES:
  with tarfile.open(source)as t:
   for m in t:
    if m.isfile()and m.name.split('/')[0]in ['large','empty']:
     p=out/m.name;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(t.extractfile(m).read())
 changes=[]
 for name,index in [('large-bad-first-chunk',262143),('large-bad-second-chunk',262144)]:
  shutil.copytree(out/'large',out/name);p=out/name/'graph.callsite-string-index';before=p.read_bytes();offset=84+4*index;old=struct.unpack_from('>i',before,offset)[0];after=before[:offset]+struct.pack('>i',2147483647)+before[offset+4:];p.write_bytes(after)
  changes.append(dict(fixture=name,file=p.name,byteOffset=offset,postingIndex=index,before=old,after=2147483647,beforeSHA256=sha(before),afterSHA256=sha(after),invalidChunk=(1 if index==262143 else 2),completePriorPostingChunkUnits=(0 if index==262143 else 262144),priorIdentityAndHeaderAndDirectoryUnits=45))
 b=(out/'large/graph.callsite-string-index').read_bytes();assert struct.unpack_from('>ii',b,8)==(2,262145)and struct.unpack_from('>4i',b,48)==(1,1,1,1)
 return dict(sources=[dict(file=str(p),sha256=sha(p.read_bytes()))for p in SOURCES],mutations=changes,actualWriterNodes=262145,checksumChunkBytes=1048576,integersPerChunk=262144,performanceMeasurements=0)
if __name__=='__main__':
 import argparse
 p=argparse.ArgumentParser();p.add_argument('output',type=Path);a=p.parse_args();r=prepare(a.output);(a.output.parent/'mutations.json').write_text(json.dumps(r,indent=2)+'\n')
