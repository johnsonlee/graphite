"""Check captured JVM evidence without rerunning or deriving native expectations."""
from pathlib import Path
import collections
import gzip
import hashlib
import json

here = Path(__file__).resolve().parent
cases = json.loads((here / "cases.json").read_text())
records = json.loads((here / "main.json").read_text())
assert len(cases) == 194 and len(records) == 388
assert len({case["name"] for case in cases}) == len(cases)
for index, case in enumerate(cases):
    pair = records[index * 2:index * 2 + 2]
    assert [record["repetition"] for record in pair] == [0, 1]
    for record in pair:
        assert record["name"] == case["name"]
        assert record["spec"] == case
    for field in ["columns", "rows", "error", "qualifiedError", "message", "types"]:
        assert pair[0].get(field) == pair[1].get(field), (case["name"], field)
    if "count(" in case["query"].lower() and "error" not in pair[0]:
        for types in pair[0]["types"]:
            assert all(value == "java.lang.Long" for value in types.values())
by_name = {record["name"]: record for record in records if record["repetition"] == 0}
assert by_name["malformed-unknown-label-1"]["rows"] == []
for index in [0, 2]:
    record = by_name[f"malformed-unknown-label-{index}"]
    assert record["error"] == "IllegalArgumentException" and record["message"] == "Unknown node tag: -1"
for record in records:
    if record["name"].startswith("duplicate-source-ids-"):
        assert record["message"] == "Graph ids must be unique"
for index in [110, 111, 114]:
    assert by_name[f"annotation-positive-{index}"]["rows"] == [{"x": 0, "$metadata": {"graphIds": ["g00", "g01"]}}]
for index, count in [(190, 16), (191, 0), (192, 1), (193, 16)]:
    assert by_name[f"provenance-order-{index}"]["rows"] == [{"x": count, "$metadata": {
        "graphIds": ["a", "android", "hive", "tika", "z", "\U00010000", "\U0001f600", "\ue000"]}}]

scheduling = json.loads((here / "error-scheduling-main.json").read_text())
assert len(scheduling) == 400
allowed = {f"malformed-offset-negative-2-{index}" for index in [0, 1, 2, 7]}
states = collections.defaultdict(collections.Counter)
for record in scheduling:
    assert record["name"] in allowed
    assert record["error"] == "IndexOutOfBoundsException" and record["message"] is None
    assert record["after"][0] == {"id": "g00", "retained": False, "mappedView": False}
    sibling = record["after"][1]
    assert sibling["id"] == "g01" and sibling["mappedView"] is False
    assert isinstance(sibling["retained"], bool)
    if record["before"][1]["retained"]:
        assert sibling["retained"]
    states[record["name"]][str(sibling["retained"]).lower()] += 1
assert set(states) == allowed
assert all(set(counts) == {"true", "false"} for counts in states.values())

primitives = json.loads((here / "posting-main.json").read_text())
assert len(primitives) == 9
for record in primitives[:6]:
    if record["postingCount"] == 11:
        assert record["reads"] == [] and record["count"] == 3
    elif record["failRaw"]:
        assert record["reads"] == [[1, 1]] and record["message"] == "deliberate raw read"
    else:
        assert record["reads"] == [[1, 1], [3, 1], [65, 1]] and record["count"] == 3
for record in primitives[6:]:
    assert record["error"] == "java.lang.ArrayIndexOutOfBoundsException"

before = json.loads(gzip.decompress((here / "fixture-before.json.gz").read_bytes()))
after = json.loads(gzip.decompress((here / "fixture-after.json.gz").read_bytes()))
before_by_file = {entry["file"]: entry for entry in before}
after_by_file = {entry["file"]: entry for entry in after}
assert all(after_by_file.get(name) == entry for name, entry in before_by_file.items())
added = sorted(after_by_file.keys() - before_by_file.keys())
assert len(added) == 280
assert all(Path(name).name in ("graph.nodeoffsets", "graph.typeindex", "graph.callsite-string-index") for name in added)
result = {"publicScenarios": len(cases), "publicResponses": len(records),
          "outcomes": dict(collections.Counter(record.get("error", "success") for record in records)),
          "publicRepeatResponseMismatches": 0, "aggregationPrimitiveObservations": len(primitives),
          "originalFixtureFilesUnchanged": len(before), "generatedSidecars": len(added),
          "errorSchedulingResponses": len(scheduling), "observedSiblingRetainedStates": dict(states),
          "syntheticPerformanceMeasurements": 0}
(here / "oracle-verification.json").write_text(json.dumps(result, indent=2) + "\n")
print(json.dumps(result, indent=2))
