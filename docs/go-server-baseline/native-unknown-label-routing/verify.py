"""Verify complete original-JVM responses, label order and explicit fixture mutations."""
from pathlib import Path
import collections
import gzip
import hashlib
import json
import struct
import tarfile

here=Path(__file__).resolve().parent
def compressed(name):return json.loads(gzip.decompress((here/name).read_bytes()))
cases=json.loads((here/"cases.json").read_text());main=json.loads((here/"main.json").read_text());repeat=compressed("repeat-main.json.gz")
assert len(cases)==len(main)==len(repeat)==140
assert len({case["name"] for case in cases})==140
assert cases[:114]==compressed("initial114-cases.json.gz")
with tarfile.open(here/"initial120-oracle.tar.gz") as archive:
    original_cases=json.load(archive.extractfile("cases.json"))
    original_main=json.load(archive.extractfile("main.json"))
    original_manifest=json.load(archive.extractfile("oracle-manifest.json"))
    for item in original_manifest["files"]:
        data=archive.extractfile(item["file"]).read()
        assert len(data)==item["bytes"] and hashlib.sha256(data).hexdigest()==item["sha256"]
assert cases[:120]==original_cases
fields=["name","spec","phase","outcome","columns","rows","types","error","qualifiedError","message","before","after"]
for spec,left,right in zip(cases,main,repeat):
    assert left["spec"]==spec and right["spec"]==spec
    for field in fields:assert left.get(field)==right.get(field),(spec["name"],field)
    assert left["phase"]=="execute"
for left,right in zip(original_main,main):
    for field in fields:assert left.get(field)==right.get(field),(left["name"],field,"original120")
by_name={row["name"]:row for row in main}
for index in [0,4,6]:
    assert by_name[f"typed-erasure-{index}"]["rows"]==[{"labels":["IntConstant","Constant"],"n":{"id":24,"type":"IntConstant","value":5}}]
for index in [1,2,3,5]:assert by_name[f"typed-erasure-{index}"]["rows"]==[]
assert by_name["typed-erasure-7"]["rows"][0]["n"]==dict(id=24,type="IntConstant",value=5,graphId="g0",elementId="g0:24",qualifiedId="g0:24")
assert by_name["typed-erasure-7"]["rows"][1]["n"]["type"]=="CallSiteNode"
for index in range(3):
    assert by_name[f"offset-missing-{index}"]["rows"]==[]
    row=by_name[f"offset-negative2-{index}"]
    assert row["error"]=="EOFException" and row["qualifiedError"]=="java.io.EOFException" and row["message"] is None
    assert by_name[f"offset-out-of-range-{index}"]["rows"]==[]
assert by_name["offset-alias-0"]["rows"]==[{"labels":["IntConstant","Constant"],"n":{"id":0,"type":"IntConstant","value":-17}}]
for index in [1,2]:assert by_name[f"offset-alias-{index}"]["rows"]==[]
def bad_tag(name):
    row=by_name[name];assert row["error"]=="IllegalArgumentException" and row["message"]=="Unknown node tag: -1"
for cross in [False,True]:
    assert by_name[f"label-0-bad0-cross{cross}"]["rows"]==[]
    bad_tag(f"label-1-bad0-cross{cross}")
    assert by_name[f"label-2-bad0-cross{cross}"]["rows"]==[]
    bad_tag(f"label-5-bad24-cross{cross}")
    assert by_name[f"label-6-bad24-cross{cross}"]["rows"]==[]
    for label in [7,8,9]:
        for node in [0,24]:assert by_name[f"label-{label}-bad{node}-cross{cross}"]["rows"]==[]
    for predicate in [0,1]:assert by_name[f"predicate-order-{predicate}-cross{cross}"]["rows"]==[]
    bad_tag(f"predicate-order-2-cross{cross}")
for scoped in [False,True]:
    for selected in ["good","bad"]:
        bad_tag(f"scope-IntConstant-Missing-{selected}-{scoped}")
        assert by_name[f"scope-Missing-IntConstant-{selected}-{scoped}"]["rows"]==[]
assert by_name["clauses-0-bad0"]["rows"]==[{"x":0}]
assert by_name["clauses-2-bad0"]["rows"]==[{"rows":1,"nodes":0}]
for clause in [7,9]:bad_tag(f"clauses-{clause}-bad0")
for clause in [8,10,13]:assert by_name[f"clauses-{clause}-bad0"]["rows"]==[]
assert by_name["clauses-14-bad0"]["message"]=="Requested element count -1 is less than zero."
assert by_name["clauses-15-bad0"]["error"]=="CypherException" and by_name["clauses-15-bad0"]["message"]=="Division by zero"
for relation in [1,2,3]:bad_tag(f"relations-{relation}-bad0")
source=json.loads((here/"source-fixture.json").read_text());source_by_name={entry["file"]:entry for entry in source["files"]}
for name,entry in source_by_name.items():
    file=Path(source["root"])/name
    assert file.stat().st_size==entry["bytes"] and hashlib.sha256(file.read_bytes()).hexdigest()==entry["sha256"]
# Independently reconstruct both original-JVM sidecars from every legacy index
# entry; this also proves the missing slots and alias target offsets.
legacy=(Path(source["root"])/"graph.nodeindex").read_bytes()
assert struct.unpack_from(">II",legacy)==(0x47524903,16)
entries=[struct.unpack_from(">IBQ",legacy,at) for at in range(8,len(legacy),13)]
expected_offsets=bytearray(struct.pack(">II",0x47524c03,31)+bytes(31*8))
for node,tag,offset in entries:struct.pack_into(">q",expected_offsets,8+node*8,offset+1)
assert bytes(expected_offsets)==(here/"mapped-index-reference/graph.nodeoffsets").read_bytes()
expected_types=bytearray(struct.pack(">II",0x47525403,16));payload=bytearray()
for tag in range(16):
    ids=[n for n,t,o in entries if t==tag]
    expected_types.extend(struct.pack(">BIQ",tag,len(ids),216+len(payload)))
    for node in ids:payload.extend(struct.pack(">I",node))
assert bytes(expected_types+payload)==(here/"mapped-index-reference/graph.typeindex").read_bytes()
reference=json.loads((here/"mapped-index-reference.json").read_text())
for item in reference["files"]:
    data=(here/item["file"]).read_bytes();assert len(data)==item["bytes"] and hashlib.sha256(data).hexdigest()==item["sha256"]
audits=json.loads((here/"fixture-audit.json").read_text())
for index,audit in enumerate(audits):
    copied=compressed(f"run{index}-fixture-copied.json.gz");before=compressed(f"run{index}-fixture-before.json.gz")
    after=compressed(f"run{index}-fixture-after.json.gz");mutations=compressed(f"run{index}-mutations.json.gz")
    cm={entry["file"]:entry for entry in copied};bm={entry["file"]:entry for entry in before};am={entry["file"]:entry for entry in after}
    assert len(cm)==2070 and len(bm)==2088 and len(am)==2484 and len(mutations)==97
    additions=compressed(f"run{index}-additions.json.gz")
    assert len(additions)==18
    base=dict(cm)
    for addition in additions:
        ref=(here/addition["reference"]).read_bytes()
        assert addition["bytes"]==len(ref) and addition["sha256"]==hashlib.sha256(ref).hexdigest()
        assert addition["file"] not in base
        base[addition["file"]]={key:addition[key] for key in ["file","bytes","sha256"]}
    assert base.keys()==bm.keys()
    for file,entry in cm.items():
        reference=source_by_name[Path(file).name]
        assert entry["bytes"]==reference["bytes"] and entry["sha256"]==reference["sha256"]
    changed={file for file in base if base[file]!=bm[file]}
    assert changed=={mutation["file"] for mutation in mutations}
    for mutation in mutations:
        node=mutation["nodeID"]
        if mutation.get("kind")=="nodeOffset":
            assert node==24 and mutation["slotByteOffset"]==200
            assert mutation["oldStoredValue"]==234 and mutation["oldDecodedOffset"]==233
            expected={"missing":0,"negative2":-1,"alias":9}[mutation["mode"]]
            assert mutation["newStoredValue"]==expected and mutation["newDecodedOffset"]==expected-1
            data=bytearray((here/"mapped-index-reference/graph.nodeoffsets").read_bytes())
            struct.pack_into(">q",data,200,expected)
        else:
            offset,tag_at,old_tag={0:(8,12,0),24:(233,237,12)}[node]
            assert (mutation["nodeDataRecordOffset"],mutation["tagByteOffset"],mutation["oldTag"])==(offset,tag_at,old_tag)
            assert mutation["indexTag"]==old_tag and mutation["newTag"] in [0,255]
            data=bytearray((Path(source["root"])/"graph.nodedata").read_bytes())
            assert struct.unpack_from(">i",data,offset)[0]==node and data[tag_at]==old_tag
            data[tag_at]=mutation["newTag"]
        assert mutation["originalSha256"]==base[mutation["file"]]["sha256"]
        assert mutation["mutatedSha256"]==bm[mutation["file"]]["sha256"]==hashlib.sha256(data).hexdigest()
    assert all(am.get(file)==entry for file,entry in bm.items())
    assert audit["changed"]==[] and audit["missing"]==[] and set(audit["added"])==am.keys()-bm.keys()
    assert all(Path(file).name in ["graph.nodeoffsets","graph.typeindex"] for file in audit["added"])
summary=dict(cases=140,actualMainCalls=280,repeatPublicAndStateDifferences=0,
             outcomes=dict(collections.Counter(row.get("error","SUCCESS") for row in main)),
             originalFixtureSourceFiles=len(source_by_name),physicalGraphCopiesPerRun=207,
             copiedFilesPerRun=2070,explicitMutatedFilesPerRun=97,explicitSidecarAdditionsPerRun=18,runtimeChangedFilesPerRun=0,
             generatedSidecarsPerRun=396,performanceMeasurements=0)
(here/"verification.json").write_text(json.dumps(summary,indent=2)+"\n");print(json.dumps(summary,indent=2))
