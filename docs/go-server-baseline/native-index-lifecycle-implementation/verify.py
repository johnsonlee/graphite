"""Compare every public lifecycle value and explicitly supported state field."""
import hashlib
import json
from pathlib import Path

p = Path(__file__).resolve().parent
main_path = p.parent / "native-index-lifecycle/main.json"
main = json.loads(main_path.read_text())
native = json.loads((p / "candidate-final.json").read_text())
assert len(main) == len(native) == 10
unsupported = {"rawMatchCount", "rawProjectionCount"}
state_comparisons = public_queries = prepared = cleared = 0


def compare_state(want, got):
    global state_comparisons
    assert set(got) == {"retained", "mappedView", "trigrams", "loadedFromPersistence", "mappedRangeCount", "indexFile"}
    assert set(want) == set(got) | unsupported
    assert all(want[k] == 0 for k in unsupported)
    assert got == {k: v for k, v in want.items() if k not in unsupported}
    state_comparisons += 1


for want, got in zip(main, native):
    assert set(want) == set(got)
    for key in ("fixture", "startup", "initialIndexFile", "finalIndexFile"):
        assert want[key] == got[key]
    compare_state(want["loaded"], got["loaded"])
    assert len(want["steps"]) == len(got["steps"]) == 7
    previous = got["loaded"]
    for a, b in zip(want["steps"], got["steps"]):
        assert set(a) == set(b)
        compare_state(a["before"], b["before"])
        compare_state(a["after"], b["after"])
        assert b["before"] == previous
        assert {k: v for k, v in a.items() if k not in ("before", "after")} == {
            k: v for k, v in b.items() if k not in ("before", "after")
        }
        public_queries += b["action"] == "query"
        prepared += b["action"] == "prepare"
        cleared += b["action"] == "clear"
        previous = b["after"]
assert (state_comparisons, public_queries, prepared, cleared) == (150, 30, 10, 30)
boundary = json.loads((p / "boundary-main.json").read_text())
assert len(boundary) == 2
assert boundary[0]["fixture"] == "empty" and boundary[1]["fixture"] == "short"
assert all(r["prepared"] is False and r["afterClear"]["indexFile"] is None for r in boundary)
assert boundary[1]["afterPrepare"]["retained"] is True
assert boundary[1]["afterPrepare"]["trigrams"] is False
report = {"passed": True, "scenarios": 10, "lifecycleOperations": 70,
          "publicQueryResponsesEqualMain": public_queries,
          "structuralAndIndexFileStateComparisons": state_comparisons,
          "stateFields": ["retained", "mappedView", "trigrams", "loadedFromPersistence", "mappedRangeCount", "indexFile"],
          "unsupportedNativeStateCounters": sorted(unsupported),
          "unsupportedMainCountersWereAlwaysZero": True,
          "actualMainBoundaryControls": 2,
          "mainSHA256": hashlib.sha256(main_path.read_bytes()).hexdigest(),
          "candidateSHA256": hashlib.sha256((p / "candidate-final.json").read_bytes()).hexdigest(),
          "real64ReplayExecuted": False, "performanceMeasured": False}
(p / "verification.json").write_text(json.dumps(report, indent=2) + "\n")
print("PASS 30 full query responses, 70 operations, 150 structural/file states; 2 raw-cache counters explicitly unavailable")
