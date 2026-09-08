"""Reuse immutable actual-main writer bytes; mutations are explicit and hashed."""
from pathlib import Path
import hashlib,json,shutil,struct,tarfile,zlib
HERE=Path(__file__).resolve().parent
SOURCE=HERE.parent/'native-leading-work-accounting/prepared-main-capture/fixtures.tar.gz'
def sha(data):return hashlib.sha256(data).hexdigest()
def dump(p,x):p.write_text(json.dumps(x,indent=2)+'\n')
def prepare(out):
 out.mkdir(parents=True,exist_ok=False)
 with tarfile.open(SOURCE)as t:
  for m in t:
   if m.isfile()and m.name.split('/')[0]in ['hit64','hit1024','bad64','empty','prelude-one']:
    p=out/m.name;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(t.extractfile(m).read())
 changes=[]
 def variant(name,actions):
  shutil.copytree(out/'hit64',out/name)
  for file,action,fn in actions:
   p=out/name/file;before=p.read_bytes();after=fn(before)
   if after is None:p.unlink()
   else:p.write_bytes(after)
   changes.append(dict(fixture=name,sourceFixture='hit64',file=file,action=action,beforeBytes=len(before),beforeSHA256=sha(before),afterBytes=None if after is None else len(after),afterSHA256=None if after is None else sha(after)))
 idx='graph.callsite-string-index';identity='graph.callsite-string-content.identity';strings='graph.strings.identity'
 def putint(b,offset,value):return b[:offset]+struct.pack('>i',value)+b[offset+4:]
 mutations=[('bad-magic',lambda b:putint(b,0,0)),('bad-version',lambda b:putint(b,4,3)),('truncated-int',lambda b:b[:2]),('truncated-identity',lambda b:b[:47]),('bad-identity',lambda b:b[:16]+bytes([b[16]^1])+b[17:]),('bad-unique-id',lambda b:putint(b,76,129)),('bad-posting-end',lambda b:putint(b,80,0)),('bad-node-order',lambda b:putint(b,88,struct.unpack_from('>i',b,84)[0])),('bad-checksum',lambda b:b[:-1]+bytes([b[-1]^1])),('trailing-byte',lambda b:b+b'\x7f')]
 for name,fn in mutations:variant(name,[(idx,name,fn)])
 for name,actions in [('no-graph-identity',[(identity,'remove',lambda b:None)]),('no-identities',[(identity,'remove',lambda b:None),(strings,'remove',lambda b:None)]),('short-graph-identity',[(identity,'truncate31',lambda b:b[:31])]),('wrong-graph-identity',[(identity,'flip-first-byte',lambda b:bytes([b[0]^1])+b[1:])])]:variant(name,actions)
 for prop,delta in [('caller_name',9),('callee_name',25)]:
  name='hit64-bad-'+prop
  offsets=(out/'hit64'/'graph.nodeoffsets').read_bytes();nodeOffset=struct.unpack_from('>q',offsets,8+8*74)[0]-1
  byteOffset=nodeOffset+delta
  data=(out/'hit64'/'graph.nodedata').read_bytes();assert struct.unpack_from('>i',data,nodeOffset)[0]==74 and data[nodeOffset+4]==12
  variant(name,[('graph.nodedata','invalid-'+prop+'-SID-at-node74',lambda b,at=byteOffset:putint(b,at,2147483647))])
  changes[-1].update(nodeID=74,recordOffset=nodeOffset,byteOffset=byteOffset,oldSID=struct.unpack_from('>i',data,byteOffset)[0],newSID=2147483647)
 layouts=[]
 for name in ['hit64','hit1024']:
  b=(out/name/idx).read_bytes();S,N=struct.unpack_from('>ii',b,8);U=struct.unpack_from('>4i',b,48);T=struct.unpack_from('>i',b,64)[0]
  canonical=b''.join(b[i:i+4][::-1]for i in range(0,16,4))+b[16:48]+b''.join(b[i:i+4][::-1]for i in range(48,68,4))+b[68:76][::-1]
  csrEnd=76+4*(2*sum(U)+4*N)
  canonical+=b''.join(b[i:i+4][::-1]for i in range(76,csrEnd,4))+b''.join(b[i:i+8][::-1]for i in range(csrEnd,len(b)-8,8))
  assert struct.unpack_from('>q',b,len(b)-8)[0]==zlib.crc32(canonical)
  reader=43+2*sum(U)+4*N+S+T
  layouts.append(dict(fixture=name,bytes=len(b),sha256=sha(b),strings=S,nodes=N,uniqueCounts=U,trigramPostings=T,readerUnits=reader,loaderWithPersistedIdentityAndEOF=reader+2,cold64ProbeAndLoader=reader+66,crcVerified=True))
 return dict(sourceArchive=str(SOURCE),sourceArchiveSHA256=sha(SOURCE.read_bytes()),sourceMainRevision='4e328b0109e13c896b74004823fb049fcb19251a',sourceWriterSHA256=sha((HERE/'LeadingWorkFixture.java').read_bytes()),mutations=changes,layouts=layouts,performanceMeasurements=0)
if __name__=='__main__':
 import argparse
 p=argparse.ArgumentParser();p.add_argument('output',type=Path);a=p.parse_args();dump(a.output.parent/'mutations.json',prepare(a.output))
