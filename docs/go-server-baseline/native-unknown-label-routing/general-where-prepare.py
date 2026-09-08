"""Eight non-timed actual-main general-WHERE ordering controls, original fixture copies."""
from pathlib import Path
import argparse,hashlib,json,shutil,struct
parser=argparse.ArgumentParser();parser.add_argument('destination',type=Path);args=parser.parse_args()
out=args.destination.resolve();out.mkdir(parents=True,exist_ok=False)
repo=Path(__file__).resolve().parents[3]
source=repo/'graphite-server/internal/query/testdata/node-encounter/mixed-sparse'
def digest(file):return hashlib.sha256(file.read_bytes()).hexdigest()
def manifest(root):return [dict(file=str(f.relative_to(root)),bytes=f.stat().st_size,sha256=digest(f)) for f in sorted(root.rglob('*')) if f.is_file()]
legacy=(source/'graph.nodeindex').read_bytes();magic,count=struct.unpack_from('>II',legacy)
assert magic==0x47524903 and len(legacy)==8+count*13
entries=[(at,*struct.unpack_from('>IBQ',legacy,at)) for at in range(8,len(legacy),13)]
ints=[(node,offset) for at,node,tag,offset in entries if tag==0]
assert ints==[(90,8),(41,75),(22,142)]
typed=(source/'graph.typeindex').read_bytes();magic,count=struct.unpack_from('>II',typed)
assert magic==0x47525403
int_range=next((n,o) for at in range(8,8+count*13,13) for t,n,o in [struct.unpack_from('>BIQ',typed,at)] if t==0)
assert [struct.unpack_from('>I',typed,int_range[1]+i*4)[0] for i in range(int_range[0])]==[90,41,22]
queries=[
 "MATCH (n:IntConstant) WHERE 1/0=0 MATCH (x:Missing) RETURN n",
 "MATCH (n:IntConstant) WHERE false MATCH (x:Missing) RETURN n",
 "UNWIND ['90','22'] AS eid MATCH (n:IntConstant) WHERE elementId(n)=eid AND 1/0=0 MATCH (x:Missing) RETURN n",
 "MATCH (x:IntConstant) RETURN 1 AS k LIMIT 1 MATCH (n:Missing) WHERE elementId(n)='22' RETURN n",
]
cases=[];copied=[];mutations=[]
for index,query in enumerate(queries):
 for bad in [False,True]:
  spec=dict(name=f'general-where-{index}-bad{bad}',query=query,cross=False,scoped=False,sources=[dict(id='g0',mutationNode=22 if bad else None)],fixture='node-encounter/mixed-sparse')
  cases.append(spec);target=out/'fixtures'/spec['name']/'store0';shutil.copytree(source,target)
  for entry in manifest(target):copied.append(dict(entry,file=str((target/entry['file']).relative_to(out))))
  if bad:
   at,node,tag,offset=next(e for e in entries if e[1]==22)
   file=target/'graph.nodedata';data=bytearray(file.read_bytes());before=digest(file)
   assert struct.unpack_from('>i',data,offset)[0]==node and data[offset+4]==tag==0
   stored=struct.unpack_from('>q',(target/'graph.nodeoffsets').read_bytes(),8+node*8)[0]
   assert stored-1==offset
   data[offset+4]=255;file.write_bytes(data)
   mutations.append(dict(file=str(file.relative_to(out)),nodeID=node,indexRecordByteOffset=at,indexTag=tag,nodeDataRecordOffset=offset,tagByteOffset=offset+4,nodeOffsetsValue=stored-1,oldTag=tag,newTag=255,originalSha256=before,mutatedSha256=digest(file)))
for name,value in [('cases',cases),('mutations',mutations),('fixture-copied',copied),('fixture-before',manifest(out/'fixtures'))]:
 if name=='fixture-before':value=[dict(e,file='fixtures/'+e['file']) for e in value]
 (out/(name+'.json')).write_text(json.dumps(value,indent=2)+'\n')
(out/'source-fixture.json').write_text(json.dumps(dict(root=str(source),files=manifest(source),intCandidateOrder=[90,41,22],intRecordOffsets=[8,75,142]),indent=2)+'\n')
