"""`snapshot.py` builds the fixture64 plan, shapes rows like JMH, and fails closed.

Run with `python3 -m unittest discover -s backend/bench -p 'test_*.py'`; no server is
needed. A fake `call` answers every query with deterministic rows and latencies.
"""

import importlib.util
import io
import json
import pathlib
import unittest
from contextlib import redirect_stdout

HERE = pathlib.Path(__file__).resolve().parent


def load(name):
    spec = importlib.util.spec_from_file_location(name, HERE / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


snapshot = load("snapshot")

GRAPHS = [{"id": f"g{i}", "path": f"/x/g{i}", "zero": "zz", "targeted": "tt", "dense": "get",
           "fingerprint": None} for i in range(3)]
DISTRIBUTIONS = [{"case": "one-graph", "requestGraph": "g1", "term": "only", "expectedGraphs": ["g1"]}]


def payload(rows):
    return json.dumps({"columns": ["a"], "rows": [{"a": i} for i in range(rows)]}).encode()


class FakeClient:
    def __init__(self, fail_at=None, drift_at=None):
        self.calls = 0
        self.fail_at = fail_at
        self.drift_at = drift_at

    def __call__(self, base, query, timeout):
        self.calls += 1
        if self.calls == self.fail_at:
            return 5.0, 500, b'{"error": "boom"}'
        rows = 2 if self.calls == self.drift_at else 1
        return float(self.calls), 200, payload(rows)


class SnapshotTest(unittest.TestCase):
    def test_plan_covers_every_shape_at_every_selectivity_then_the_cases(self):
        work = snapshot.plan(GRAPHS, DISTRIBUTIONS)
        shapes = 3 * len(snapshot.fixture64.SHAPES)
        self.assertEqual(len(work), shapes + 1 + len(snapshot.SCHEMA_SHAPES))
        self.assertEqual(work[0][0], {"selectivity": "zero"})
        self.assertIn("'zz'", work[0][2])
        self.assertEqual(work[shapes][0], {"case": "one-graph"})
        self.assertIn("'only'", work[shapes][2])
        # The schema shapes come last, once each, and read no search term.
        for params, name, query in work[shapes + 1:]:
            self.assertEqual(params, {"selectivity": "schema"})
            self.assertTrue(name.startswith("schema-"))
            self.assertNotIn("'", query)

    def test_rows_are_jmh_shaped_with_medians_and_aggregates(self):
        work = snapshot.plan(GRAPHS, DISTRIBUTIONS)
        out = io.StringIO()
        with redirect_stdout(out):
            rows = snapshot.measure("http://x", work, 3, 1, call=FakeClient(), log=print)
        self.assertEqual(len(rows), len(work) + 2)
        first = rows[0]
        self.assertEqual(first["benchmark"], "rust.fixture64.global-wide-four-properties")
        self.assertEqual(first["mode"], "sequential-pass")
        self.assertEqual(first["params"], {"selectivity": "zero"})
        self.assertEqual(first["primaryMetric"]["scoreUnit"], "ms/op")
        # The fake client answers call number k in k ms, so the first query's samples
        # are 1, n + 1 and 2n + 1 over n queries a pass: median n + 1, spread [1, 2n + 1].
        n = len(work)
        self.assertEqual(first["primaryMetric"]["score"], float(n + 1))
        self.assertEqual(first["primaryMetric"]["scoreConfidence"], [1.0, float(2 * n + 1)])
        self.assertEqual(first["rowCount"], 1)
        self.assertEqual(len(first["digest"]), 16)
        aggregates = [r for r in rows if r["benchmark"] == "rust.fixture64.aggregate"]
        self.assertEqual([r["params"]["statistic"] for r in aggregates], ["p50", "p95"])
        p95_of_first_pass = snapshot.fixture64.percentile(list(range(1, n + 1)), 0.95)
        self.assertEqual(aggregates[1]["primaryMetric"]["scoreConfidence"][0], float(p95_of_first_pass))
        self.assertIn("pass 3/3", out.getvalue())
        keys = {(r["benchmark"], json.dumps(r["params"], sort_keys=True)) for r in rows}
        self.assertEqual(len(keys), len(rows))

    def test_a_non_200_answer_fails_the_snapshot(self):
        work = snapshot.plan(GRAPHS, DISTRIBUTIONS)
        with self.assertRaisesRegex(RuntimeError, "HTTP 500"):
            snapshot.measure("http://x", work, 2, 1, call=FakeClient(fail_at=4), log=lambda *_: None)

    def test_an_answer_that_changes_between_passes_fails_the_snapshot(self):
        work = snapshot.plan(GRAPHS, DISTRIBUTIONS)
        with self.assertRaisesRegex(RuntimeError, "changed between passes"):
            snapshot.measure("http://x", work, 2, 1,
                             call=FakeClient(drift_at=len(work) + 2), log=lambda *_: None)


if __name__ == "__main__":
    unittest.main()
