"""Independent comparison of original records; never rewrites oracle/results."""
import hashlib
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent


def read(name):
    return json.loads((HERE / name).read_text())


def public(record):
    return {k: v for k, v in record.items() if k not in ("before", "after")}


main = read("main.json")
baseline = read("baseline-native.json")
candidate = read("candidate-verified.json")
assert len(main) == len(baseline) == len(candidate) == 160
baseline_public_differences = 0
strict_record_differences = []
permitted_state_leaves = []
for expected, original, actual in zip(main, baseline, candidate):
    for record in (original, actual):
        assert {k: v for k, v in record.items() if k != "repeats"} == {
            k: v for k, v in expected.items() if k != "repeats"
        }
    assert len(expected["repeats"]) == len(actual["repeats"]) == len(original["repeats"]) == 2
    n = expected["count"]
    last = None
    for repeat, (want, old, got) in enumerate(zip(expected["repeats"], original["repeats"], actual["repeats"])):
        baseline_public_differences += public(old) != public(want)
        assert public(got) == public(want), (n, expected["name"], repeat, "public")
        scheduled = n in (40, 64) and expected["badLast"] and (
            expected["name"] == "ordered" or expected["scoped"] and expected["name"] == "and-route"
        )
        if got != want:
            strict_record_differences.append({"count": n, "scoped": expected["scoped"], "name": expected["name"], "repeat": repeat})
        if not scheduled:
            assert got == want
        else:
            assert want["error"] == "IndexOutOfBoundsException"
            assert got["before"] == (want["before"] if last is None else last["after"])
            assert len(got["before"]) == len(got["after"]) == n
            for i, (before, after) in enumerate(zip(got["before"], got["after"])):
                assert set(before) == set(after) == {"id", "retained", "mappedView"}
                assert before["id"] == after["id"] == f"g{i}"
                assert before["mappedView"] is after["mappedView"] is False
                assert type(before["retained"]) is type(after["retained"]) is bool
                assert not before["retained"] or after["retained"]
                assert after["retained"] or n-8 <= i < n-1
                for phase in ("before", "after"):
                    if got[phase][i] != want[phase][i]:
                        assert n-8 <= i < n-1 and (repeat != 0 or phase != "before")
                        permitted_state_leaves.append({"count": n, "scoped": expected["scoped"], "name": expected["name"], "repeat": repeat, "phase": phase, "source": f"g{i}", "main": want[phase][i], "go": got[phase][i]})
        last = got
assert baseline_public_differences == 24

scheduled = read("schedule-matrix-main.json")
assert len(scheduled) == 12
assert {(r["count"], r["shape"], r["active"]) for r in scheduled} == {
    (n, shape, active) for n in (40, 64) for shape in range(3) for active in (False, True)
}
for r in scheduled:
    n, active = r["count"], r["active"]
    assert r["error"] == "IndexOutOfBoundsException"
    assert r["message"] == "Index (2147483647) is greater than or equal to list size (9)"
    assert r["parentInterrupted"] is False
    assert r["after"] == [{"id": f"g{i}", "retained": not(active and n-8 <= i < n-1), "mappedView": False} for i in range(n)]
    events = r["events"]
    if active:
        for prefix in ("entered:", "main-interrupted:"):
            assert sorted(e for e in events if e.startswith(prefix)) == sorted(f"{prefix}{i}" for i in range(n-8, n-1))
        assert f"original-bad-source-called:{n-1}" in events
        assert all(e.startswith(("entered:", "main-interrupted:", "original-bad-source-called:")) for e in events)
    else:
        assert events == []

report = {
    "passed": True,
    "actualMainResponseCount": 320,
    "baselinePublicDifferences": baseline_public_differences,
    "candidatePublicDifferences": 0,
    "strictRecordDifferences": strict_record_differences,
    "permittedCancellationStateLeaves": permitted_state_leaves,
    "actualMainSchedulingControls": 12,
    "schedulingWorkerCount": 8,
    "real64BenchmarkExecuted": False,
    "performanceMeasured": False,
    "inputSHA256": {name: hashlib.sha256((HERE / name).read_bytes()).hexdigest() for name in ("main.json", "baseline-native.json", "candidate-verified.json", "schedule-matrix-main.json")},
}
(HERE / "verification.json").write_text(json.dumps(report, indent=2) + "\n")
print(f"PASS: 320 public responses; 12 main scheduling controls; {len(strict_record_differences)} raw record differences retained; no performance measurement")
