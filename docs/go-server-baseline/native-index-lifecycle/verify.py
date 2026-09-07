"""Verify actual-main lifecycle observations and fixture mutation boundaries."""
import hashlib
import json
from pathlib import Path

p = Path(__file__).resolve().parent
records = json.loads((p / "main.json").read_text())
assert len(records) == 10
assert {(r["startup"], r["fixture"]) for r in records} == {
    (startup, fixture) for startup in (False, True)
    for fixture in ("clean", "missing", "bad-matched", "bad-missing", "corrupt")
}
actions = ["query", "clear", "query", "prepare", "clear", "query", "clear"]
public_errors = 0
clear_writes = []
for r in records:
    assert "error" not in r
    assert [s["action"] for s in r["steps"]] == actions
    assert r["loaded"]["retained"] is r["startup"]
    assert r["loaded"]["trigrams"] is r["startup"]
    prior = r["loaded"]
    for i, step in enumerate(r["steps"]):
        assert step["before"] == prior
        after = step["after"]
        if step["action"] == "clear":
            assert "error" not in step
            assert all(after[k] is False for k in ("retained", "mappedView", "trigrams", "loadedFromPersistence"))
            assert all(after[k] == 0 for k in ("rawMatchCount", "rawProjectionCount", "mappedRangeCount"))
            if after["indexFile"] != prior["indexFile"]:
                clear_writes.append((r["startup"], r["fixture"], i))
        elif step["action"] == "prepare":
            assert step["prepared"] is True and "error" not in step
            assert after["retained"] is after["trigrams"] is True
        else:
            expect_error = r["fixture"].startswith("bad-") and not (r["startup"] and i == 0)
            if expect_error:
                assert step["error"] == "IndexOutOfBoundsException"
                assert step["message"] == "Index (2147483647) is greater than or equal to list size (9)"
                assert "rows" not in step
                public_errors += 1
            else:
                assert "error" not in step
                assert step["columns"] == ["x"]
                assert step["rows"] == [{"x": "other", "$metadata": {"graphIds": ["g"]}}]
        prior = after
    assert prior["indexFile"] == r["finalIndexFile"]
assert public_errors == 10
assert clear_writes == [(False, name, 4) for name in ("missing", "bad-missing", "corrupt")]

before = {r["path"]: r["sha256"] for r in json.loads((p / "fixture-before.json").read_text())}
after = {str(f.relative_to(p / "fixtures")): hashlib.sha256(f.read_bytes()).hexdigest()
         for f in (p / "fixtures").rglob("*") if f.is_file()}
changes = []
for name in sorted(before.keys() | after.keys()):
    if before.get(name) != after.get(name):
        assert name.endswith("/graph.callsite-string-index")
        assert name.split("/")[0] in {mode + fixture for mode in ("lazy-", "startup-") for fixture in ("missing", "bad-missing", "corrupt")}
        changes.append({"path": name, "before": before.get(name), "after": after.get(name)})
assert len(changes) == 6
report = {"passed": True, "actualMainScenarios": 10, "lifecycleSteps": 70,
          "queryResponses": 30, "successfulResponses": 20, "expectedSourceErrors": 10,
          "clears": 30, "clearsPersistingBuiltIndexes": clear_writes,
          "fixtureChanges": changes, "allOtherFixtureFilesUnchanged": True,
          "nativeParityProven": False, "real64ReplayExecuted": False, "performanceMeasured": False}
(p / "verification.json").write_text(json.dumps(report, indent=2) + "\n")
print("PASS actual-main 10 scenarios/70 operations/30 query responses; native lifecycle remains unimplemented")
