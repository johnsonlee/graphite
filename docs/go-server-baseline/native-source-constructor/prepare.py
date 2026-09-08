"""Clone original JVM correctness fixtures independently for each oracle case."""
from pathlib import Path
import argparse
import hashlib
import json
import shutil

parser=argparse.ArgumentParser()
parser.add_argument("destination",type=Path)
args=parser.parse_args()
out=args.destination.resolve();out.mkdir(parents=True,exist_ok=False)
repo=Path(__file__).resolve().parents[3]
data=repo/"graphite-server/internal/query/testdata"
cases=[]
def add(name,ids,stores,query,scoped=False,cancellation="live",fixture="clean"):
    cases.append(dict(name=name,graphIDs=ids,storeIndexes=stores,physicalStores=max(stores,default=-1)+1,
                      query=query,scoped=scoped,cancellation=cancellation,fixture=fixture))

topologies=[("duplicate",["same","same"],[0,1]),("duplicate-same-store",["same","same"],[0,0]),
            ("one-empty",[""],[0]),("two-empty",["",""],[0,1]),
            ("empty-and-valid",["","g"],[0,1]),("no-sources",[],[]),
            ("same-store-unique",["b","a"],[0,0]),("valid-unique",["b","a"],[0,1]),
            ("single-valid",["g"],[0]),("null-graph",["g"],[-1]),
            ("duplicate-null-first",["same","same"],[-1,0]),
            ("duplicate-null-second",["same","same"],[0,-1])]
queries=[("return","RETURN 1 AS x"),
         ("count","MATCH (n) WHERE n.caller_name CONTAINS 'other' RETURN count(*) AS x"),
         ("invalid","NOT A VALID CYPHER QUERY")]
for topology,ids,stores in topologies:
    for scoped in [False,True]:
        for cancellation in ["live","cancelled","timeout"]:
            for kind,query in queries:
                add(f"{topology}-{scoped}-{cancellation}-{kind}",ids,stores,query,scoped,cancellation)

qualification=[
    ("node", "MATCH (n) WHERE n.caller_name = 'other' RETURN graphId(n) AS graph,elementId(n) AS element,n LIMIT 1", "clean"),
    ("method", "MATCH (m:Method) RETURN graphId(m) AS graph,properties(m) AS props,keys(m) AS keys,m LIMIT 1", "all-types"),
    ("node-empty-guard", "MATCH (n) WHERE graphId(n) = '' RETURN count(*) AS x", "clean"),
    ("node-property-empty-guard", "MATCH (n) WHERE n.graphId = '' RETURN count(*) AS x", "clean"),
    ("method-empty-guard", "MATCH (m:Method) WHERE graphId(m) = '' RETURN count(*) AS x", "all-types"),
    ("union", "MATCH (n) WHERE n.caller_name = 'other' RETURN count(*) AS x UNION RETURN 42 AS x", "clean"),
    ("relationship-path", "MATCH p=(a)-[r]->(b) RETURN graphId(r) AS edgeGraph,graphId(p) AS pathGraph,r,p LIMIT 1", "traversal"),
    ("relationship", "MATCH (a)-[r]->(b) RETURN graphId(r) AS graph,elementId(r) AS element,r LIMIT 1", "traversal"),
    ("empty-element-lookup", "MATCH (n) WHERE elementId(n) = ':0' RETURN graphId(n) AS graph,n", "traversal"),
    ("bound-relationship", "MATCH (a)-[r]->(b) WITH r MATCH (c)-[r]->(d) RETURN count(DISTINCT r) AS x", "traversal"),
    ("zero-hop-path", "MATCH p=(a)-[*0..0]->(b) WHERE id(a)=0 RETURN graphId(p) AS graph,p ORDER BY graph", "traversal"),
]
for block in [qualification[:8],qualification[8:]]:
    for scoped in [False,True]:
        for name,query,fixture in block:
            for ids,stores,label in [([""],[0],"empty"),(["g"],[0],"valid"),(["","g"],[0,1],"mixed")]:
                add(f"qualification-{label}-{scoped}-{name}",ids,stores,query,scoped,fixture=fixture)

for topology,ids,stores in [("valid",["g"],[0]),("duplicate",["g","g"],[0,1])]:
    for cancellation in ["live","cancelled"]:
        for name,query in [("literal-overflow","RETURN 9223372036854775808 AS x"),
                           ("unknown-function","RETURN sourceConstructorUnknownFunction() AS x"),
                           ("arithmetic","RETURN 1 / 0 AS x"),
                           ("function-argument","RETURN substring('x', 'bad') AS x")]:
            add(f"precedence-{topology}-{cancellation}-{name}",ids,stores,query,cancellation=cancellation)

for spec in cases:
    source=(data/"traversal" if spec["fixture"]=="traversal" else
            data/"main-string-source/all-types" if spec["fixture"]=="all-types" else data/"candidate-index/clean")
    for index in range(spec["physicalStores"]):shutil.copytree(source,out/"fixtures"/spec["name"]/f"store{index}")
(out/"cases.json").write_text(json.dumps(cases,indent=2)+"\n")
manifest=[dict(file=str(file.relative_to(out)),bytes=file.stat().st_size,sha256=hashlib.sha256(file.read_bytes()).hexdigest())
          for file in sorted((out/"fixtures").rglob("*")) if file.is_file()]
(out/"fixture-before.json").write_text(json.dumps(manifest,indent=2)+"\n")
