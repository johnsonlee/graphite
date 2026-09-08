"""Bounded original-fixture matrix, explicit node-index-derived tag mutations."""
from pathlib import Path
import argparse
import hashlib
import json
import shutil
import struct

parser=argparse.ArgumentParser();parser.add_argument("destination",type=Path);args=parser.parse_args()
out=args.destination.resolve();out.mkdir(parents=True,exist_ok=False)
repo=Path(__file__).resolve().parents[3]
source=repo/"graphite-server/internal/query/testdata/main-string-source/all-types"
cases=[]
def add(name,query,mutation=None,cross=False,scoped=False,sources=None):
    if sources is None:
        sources=[dict(id="g0",mutationNode=mutation)]
        if cross:sources.append(dict(id="g1",mutationNode=None))
    cases.append(dict(name=name,query=query,cross=cross,scoped=scoped,sources=sources,fixture="all-types"))

labels=["Missing","IntConstant:Missing","Missing:IntConstant","Node:Missing","Missing:Node",
        "CallSite:Missing","Missing:CallSite","Method:Missing","Missing:Method","Method:IntConstant:Missing"]
for label_index,label in enumerate(labels):
    for mutation in [None,0,24]:
        for cross in [False,True]:
            add(f"label-{label_index}-bad{mutation}-cross{cross}",f"MATCH (n:{label}) RETURN n LIMIT 1",mutation,cross)

clauses=[
    "MATCH (n:Missing) RETURN count(*) AS x",
    "OPTIONAL MATCH (n:Missing) RETURN n",
    "OPTIONAL MATCH (n:Missing) RETURN count(*) AS rows,count(n) AS nodes",
    "UNWIND [1,2] AS x OPTIONAL MATCH (n:Missing) RETURN x,n",
    "UNWIND [1,2] AS x MATCH (n:Missing) RETURN x",
    "MATCH (n:Missing) UNWIND [1/0] AS x RETURN x",
    "WITH {id:0} AS n MATCH (n:Missing) RETURN n",
    "MATCH (n:IntConstant) WITH n MATCH (n:Missing) RETURN n",
    "MATCH (n:Missing) MATCH (m:IntConstant) RETURN m",
    "MATCH (n:IntConstant),(m:Missing) RETURN n",
    "MATCH (n:Missing),(m:IntConstant) RETURN m",
    "MATCH (n:Missing) RETURN count(*) AS x UNION RETURN 1 AS x",
    "MATCH (n:Missing) RETURN n UNION ALL RETURN 1 AS n",
    "MATCH (n:Missing) RETURN n LIMIT 0",
    "MATCH (n:Missing) RETURN n LIMIT -1",
    "MATCH (n:Missing) RETURN n LIMIT 1/0",
]
for index,query in enumerate(clauses):
    for mutation in [None,0]:add(f"clauses-{index}-bad{mutation}",query,mutation,cross=index%2==1)
for label in ["Method","CallSite","IntConstant"]:
    for mutation in [None,0]:add(f"positive-{label}-bad{mutation}",f"MATCH (n:{label}) RETURN n LIMIT 1",mutation)
for label in ["IntConstant:Missing","Missing:IntConstant"]:
    for selected in ["good","bad"]:
        for scoped in [False,True]:
            add(f"scope-{label.replace(':','-')}-{selected}-{scoped}",
                f"MATCH (n:{label}) WHERE graphId(n)='{selected}' RETURN n",cross=True,scoped=scoped,
                sources=[dict(id="good",mutationNode=None),dict(id="bad",mutationNode=0)])
relations=[
    "MATCH (a:Missing)-[r]->(b) RETURN b LIMIT 1",
    "MATCH (a:CallSite)-[r]->(b:Missing) RETURN b LIMIT 1",
    "MATCH (a:IntConstant:Missing)-[r]->(b) RETURN b LIMIT 1",
    "MATCH (n:Missing) WHERE elementId(n)='g0:0' RETURN n",
]
for index,query in enumerate(relations):
    for mutation in [None,0]:add(f"relations-{index}-bad{mutation}",query,mutation,cross=True)
for index,query in enumerate([
    "MATCH (n:Missing) WHERE false RETURN n",
    "MATCH (n:Missing) WHERE 1/0=0 RETURN n",
    "MATCH (n:IntConstant:Missing) WHERE false RETURN n",
]):
    for cross in [False,True]:add(f"predicate-order-{index}-cross{cross}",query,0,cross)
assert len(cases)==120
# Keep the original public-routing prefix unchanged. These controls force the
# general pipeline through an unrelated unknown-label OPTIONAL pattern.
typed_queries=[
    "MATCH (n:CallSite) OPTIONAL MATCH (x:Missing) RETURN labels(n) AS labels,n",
    "MATCH (n:CallSite:CallSite) OPTIONAL MATCH (x:Missing) RETURN labels(n) AS labels,n",
    "MATCH (n:CallSite:IntConstant) OPTIONAL MATCH (x:Missing) RETURN labels(n) AS labels,n",
    "MATCH (n:CallSite) WITH n MATCH (n:CallSite) OPTIONAL MATCH (x:Missing) RETURN labels(n) AS labels,n",
    "MATCH (n:CallSite) WITH n MATCH (n:IntConstant) OPTIONAL MATCH (x:Missing) RETURN labels(n) AS labels,n",
    "MATCH (n:CallSite) WHERE elementId(n)='24' OPTIONAL MATCH (x:Missing) RETURN labels(n) AS labels,n",
    "MATCH (n:IntConstant) WHERE elementId(n)='24' OPTIONAL MATCH (x:Missing) RETURN labels(n) AS labels,n",
    "MATCH (n:CallSite) OPTIONAL MATCH (x:Missing) RETURN labels(n) AS labels,n",
]
for index,query in enumerate(typed_queries):
    sources=[dict(id="g0",mutationNode=24,mutationTag=0)]
    if index==7:sources.append(dict(id="g1",mutationNode=None))
    add(f"typed-erasure-{index}",query,cross=index==7,sources=sources)
for mode in ["missing","negative2","alias"]:
    for index,query in enumerate([typed_queries[0],typed_queries[5],typed_queries[6]]):
        add(f"offset-{mode}-{index}",query,sources=[dict(id="g0",mutationNode=None,
            nodeOffset=dict(nodeID=24,mode=mode,aliasNode=0 if mode=="alias" else None))])
for index,node_id in enumerate([-1,31,2147483647]):
    element_id=f"g0:{node_id}" if index==2 else str(node_id)
    add(f"offset-out-of-range-{index}",
        f"MATCH (n:CallSite) WHERE elementId(n)='{element_id}' OPTIONAL MATCH (x:Missing) RETURN n",
        mutation=0,cross=index==2)
assert len(cases)==140

def digest(file):return hashlib.sha256(file.read_bytes()).hexdigest()
copied=[];mutations=[];additions=[]
for spec in cases:
    for index,spec_source in enumerate(spec["sources"]):
        target=out/"fixtures"/spec["name"]/f"store{index}";shutil.copytree(source,target)
        for file in sorted(target.iterdir()):
            if file.is_file():copied.append(dict(file=str(file.relative_to(out)),bytes=file.stat().st_size,sha256=digest(file)))
        node=spec_source["mutationNode"]
        if node is None and "nodeOffset" not in spec_source:continue
        index_bytes=(target/"graph.nodeindex").read_bytes()
        magic,count=struct.unpack_from(">II",index_bytes)
        assert magic==0x47524903 and len(index_bytes)==8+count*13
        locations=[(at,*struct.unpack_from(">IBQ",index_bytes,at)) for at in range(8,len(index_bytes),13)]
        if "nodeOffset" in spec_source:
            change=spec_source["nodeOffset"];node=change["nodeID"]
            for sidecar in ["graph.nodeoffsets","graph.typeindex"]:
                reference=Path(__file__).resolve().parent/"mapped-index-reference"/sidecar
                shutil.copyfile(reference,target/sidecar)
                additions.append(dict(file=str((target/sidecar).relative_to(out)),bytes=reference.stat().st_size,
                                      sha256=digest(reference),reference="mapped-index-reference/"+sidecar))
            file=target/"graph.nodeoffsets";before=digest(file);data=bytearray(file.read_bytes())
            record=next(record for record in locations if record[1]==node)
            slot=8+node*8;original=struct.unpack_from(">q",data,slot)[0]
            assert original==record[3]+1
            alias=next((record for record in locations if record[1]==change["aliasNode"]),None)
            stored={"missing":0,"negative2":-1,"alias":alias[3]+1 if alias else 0}[change["mode"]]
            struct.pack_into(">q",data,slot,stored);file.write_bytes(data)
            mutations.append(dict(kind="nodeOffset",file=str(file.relative_to(out)),nodeID=node,
                slotByteOffset=slot,oldStoredValue=original,newStoredValue=stored,
                oldDecodedOffset=original-1,newDecodedOffset=stored-1,mode=change["mode"],
                aliasNode=change["aliasNode"],originalSha256=before,mutatedSha256=digest(file)))
            continue
        record=[record for record in locations if record[1]==node]
        assert len(record)==1
        record_at,node_id,index_tag,offset=record[0]
        file=target/"graph.nodedata";before=digest(file);data=bytearray(file.read_bytes())
        actual_id=struct.unpack_from(">i",data,offset)[0];tag_at=offset+4
        assert actual_id==node and data[tag_at]==index_tag==({0:0,24:12}[node])
        offsets=target/"graph.nodeoffsets";offset_value=None
        if offsets.exists():
            offset_value=struct.unpack_from(">q",offsets.read_bytes(),8+node*8)[0]-1
            assert offset_value==offset
        old_tag=data[tag_at];new_tag=spec_source.get("mutationTag",255);data[tag_at]=new_tag;file.write_bytes(data)
        mutations.append(dict(file=str(file.relative_to(out)),nodeID=node,indexRecordByteOffset=record_at,
                              indexTag=index_tag,nodeDataRecordOffset=offset,tagByteOffset=tag_at,
                              nodeOffsetsValue=offset_value,oldTag=old_tag,newTag=new_tag,
                              originalSha256=before,mutatedSha256=digest(file)))

(out/"cases.json").write_text(json.dumps(cases,indent=2)+"\n")
(out/"additions.json").write_text(json.dumps(additions,indent=2)+"\n")
(out/"mutations.json").write_text(json.dumps(mutations,indent=2)+"\n")
(out/"fixture-copied.json").write_text(json.dumps(copied,indent=2)+"\n")
manifest=[dict(file=str(file.relative_to(out)),bytes=file.stat().st_size,sha256=digest(file))
          for file in sorted((out/"fixtures").rglob("*")) if file.is_file()]
(out/"fixture-before.json").write_text(json.dumps(manifest,indent=2)+"\n")
source_manifest=[dict(file=str(file.relative_to(source)),bytes=file.stat().st_size,sha256=digest(file))
                 for file in sorted(source.iterdir()) if file.is_file()]
(out/"source-fixture.json").write_text(json.dumps(dict(root=str(source),files=source_manifest),indent=2)+"\n")
