"""Validate actual JVM captures and their fixture audits; no native behavior inferred."""
from pathlib import Path
import collections
import gzip
import json

root=Path(__file__).resolve().parent
cases=json.loads((root/"cases.json").read_text())
main=json.loads((root/"main.json").read_text())
repeat=json.loads(gzip.decompress((root/"repeat-main.json.gz").read_bytes()))
initial_cases=json.loads(gzip.decompress((root/"initial264-cases.json.gz").read_bytes()))
assert len(cases)==298 and len(main)==len(repeat)==298
assert len(initial_cases)==264 and cases[:264]==initial_cases
assert len({spec["name"] for spec in cases})==298
fields=["name","spec","phase","outcome","columns","rows","types","error","qualifiedError","message",
        "cancelAccepted","cancelledBeforeConstructor","sourceObjectsConstructed","constructedSourceCount",
        "executorConstructed","before","after"]
for spec,first,second in zip(cases,main,repeat):
    assert first["spec"]==spec and first["name"]==spec["name"]
    for field in fields:assert first.get(field)==second.get(field),(spec["name"],field)
    ids=spec["graphIDs"]
    if -1 in spec["storeIndexes"]:
        assert first["phase"]=="sources" and first["error"]=="NullPointerException"
        assert first["message"]=="Parameter specified as non-null is null: method io.johnsonlee.graphite.cypher.CypherGraph.<init>, parameter graph"
        assert first["constructedSourceCount"]==spec["storeIndexes"].index(-1)
        assert first["before"]==first["after"]
    elif len(set(ids))!=len(ids):
        assert first["phase"]=="constructor" and first["error"]=="IllegalArgumentException"
        assert first["message"]=="Graph ids must be unique"
        assert first["before"]==first["after"]
    else:
        assert first["phase"]=="execute" and first["executorConstructed"]
        if spec["query"]=="NOT A VALID CYPHER QUERY":
            assert first["error"]=="CypherParseException"
        elif "9223372036854775808" in spec["query"]:
            assert first["error"]=="NumberFormatException"
        elif spec["cancellation"]!="live":
            assert first["error"]==("CypherQueryTimeoutException" if spec["cancellation"]=="timeout" else "CypherQueryCancelledException")
by_name={row["name"]:row for row in main}
assert by_name["one-empty-False-live-count"]["rows"]==[{"x":2,"$metadata":{"graphIds":[""]}}]
assert by_name["no-sources-False-live-count"]["rows"]==[{"x":0,"$metadata":{"graphIds":[]}}]
assert by_name["same-store-unique-False-live-count"]["rows"]==[{"x":4,"$metadata":{"graphIds":["a","b"]}}]
for scoped in ["False","True"]:
    row=by_name[f"qualification-empty-{scoped}-method"]["rows"][0]
    assert row["graph"]=="" and row["props"]["graphId"]=="" and row["m"]["graphId"]==""
    assert "graphId" in row["keys"] and row["$metadata"]=={"graphIds":[""]}
    seek=by_name[f"qualification-mixed-{scoped}-empty-element-lookup"]["rows"]
    assert len(seek)==1 and seek[0]["n"]["elementId"]==":0" and seek[0]["graph"]==""
    assert by_name[f"qualification-mixed-{scoped}-bound-relationship"]["rows"]==[{"x":30,"$metadata":{"graphIds":["","g"]}}]
    paths=by_name[f"qualification-mixed-{scoped}-zero-hop-path"]["rows"]
    assert [row["graph"] for row in paths]==["","g"]
    assert all(row["p"]["length"]==0 and row["p"]["relationships"]==[] for row in paths)
audits=json.loads((root/"fixture-audit.json").read_text())
for index,audit in enumerate(audits):
    before=json.loads(gzip.decompress((root/f"fixture-{index}-before.json.gz").read_bytes()))
    after=json.loads(gzip.decompress((root/f"fixture-{index}-after.json.gz").read_bytes()))
    bm={entry["file"]:entry for entry in before};am={entry["file"]:entry for entry in after}
    assert len(before)==5836 and len(after)==5988
    assert all(am.get(name)==entry for name,entry in bm.items())
    assert audit["changed"]==[] and audit["missing"]==[]
    assert set(audit["added"])==am.keys()-bm.keys()
    assert all(Path(name).name in ["graph.nodeindex","graph.nodeoffsets","graph.typeindex"] for name in audit["added"])
summary={"scenarios":len(cases),"publicExecutionsIncludingFreshRepeat":len(main)+len(repeat),
         "initial264PrefixUnchanged":True,"repeatPublicAndStateDifferences":0,
         "phasesAndOutcomes":dict(collections.Counter(row["phase"]+":"+row.get("error","SUCCESS") for row in main)),
         "unchangedOriginalFixtureFilesPerRun":5836,"generatedSidecarsPerRun":152,"performanceMeasurements":0}
(root/"verification.json").write_text(json.dumps(summary,indent=2)+"\n")
print(json.dumps(summary,indent=2))
