"""`snapshot.py` builds the fixture64 plan, shapes rows like JMH, and fails closed.

Run with `python3 -m unittest discover -s backend/bench -p 'test_*.py'`; no server is
needed. A fake `call` answers every query with deterministic rows and latencies.
"""

import importlib.util
import io
import json
import pathlib
import tempfile
import unittest
from contextlib import redirect_stdout
from unittest.mock import patch

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
    def test_explicit_suites_preserve_legacy_and_include_every_broad_query(self):
        distributions = [{**DISTRIBUTIONS[0], "case": name} for name in
                         ("one-graph", "first-graph", "last-graph", "many-graphs")]
        fast = snapshot.plan(GRAPHS, distributions, "fast34")
        historical = snapshot.plan(GRAPHS, distributions)
        broad = snapshot.plan(GRAPHS, distributions, "broad")
        all_cases = snapshot.plan(GRAPHS, distributions, "all")
        self.assertEqual([len(fast), len(historical), len(broad), len(all_cases)], [34, 37, 39, 73])
        self.assertEqual(historical[:34], fast)
        self.assertEqual(all_cases[:37], historical)
        self.assertEqual(all_cases[37:], broad[3:])
        expected_names = [
            "wide-contains", "label-scan-callsite", "label-scan-method", "label-scan-constant",
            "label-scan-field", "all-nodes", "equality", "inequality", "id-lookup", "in-list",
            "null-check", "regex", "one-hop", "one-hop-typed", "two-hop", "incoming",
            "var-length", "path", "traverse-filtered", "count-all", "count-label", "group-count",
            "distinct", "collect", "count-traversal", "order-by", "order-by-desc", "skip",
            "function-calls", "case-expr", "coalesce", "labels-fn", "with-filter",
            "with-aggregate", "cartesian", "union",
        ]
        self.assertEqual([name for name, _ in snapshot.shapes.SHAPES], expected_names)
        self.assertEqual(broad[3:], [({"selectivity": "broad"}, "shape-" + name, query)
                                     for name, query in snapshot.shapes.SHAPES])
        queries = {name: query for _, name, query in broad}
        self.assertEqual(queries["shape-order-by"],
                         "MATCH (n:CallSite) RETURN n.caller_class ORDER BY n.caller_class LIMIT 200")
        self.assertEqual(queries["shape-count-traversal"], "MATCH (a)-[r]->(b) RETURN count(*)")
        self.assertEqual(queries["shape-var-length"], "MATCH (a)-[*1..2]->(b) RETURN a.id, b.id LIMIT 200")
        self.assertEqual(queries["shape-collect"],
                         "MATCH (n:CallSite) WITH collect(n.callee_name) AS names RETURN names[0..10] AS names")
        with self.assertRaisesRegex(ValueError, "Unknown suite"):
            snapshot.plan(GRAPHS, distributions, "missing")

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
        self.assertIn("cross-case p95", out.getvalue())
        self.assertIn("not request percentiles", out.getvalue())
        self.assertEqual(first["protocol"], "MULTIGRAPH_QUERY_SUITE_V2")
        self.assertEqual(first["requestScope"], "global-cross-graph")
        self.assertEqual(first["statisticScope"], "same-case-sequential-pass-median")
        self.assertFalse(first["sampling"]["requestPercentileEstimate"])
        self.assertEqual(len(first["querySha256"]), 64)
        self.assertEqual(len(first["responseDigest"]), 64)
        self.assertEqual(aggregates[1]["statisticScope"], "cross-case-per-pass")
        self.assertEqual(aggregates[1]["sampling"], first["sampling"])
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

    def test_complete_typed_response_digest_detects_fields_legacy_digest_omits(self):
        body = {"columns": ["n"], "rows": [{"n": {"id": 1, "ordinal": 2},
                                              "$metadata": {"graphIds": ["g1", "g2"]}}],
                "rowCount": 1, "total": 1, "graphCount": 3}
        original, count = snapshot.response_digest(json.dumps(body).encode())
        self.assertEqual(count, 1)
        for changed in [
                {**body, "total": ">1"},
                {**body, "graphCount": 2},
                {**body, "rows": [{"n": {"id": 1, "ordinal": 2}, "$metadata": {"graphIds": ["g2", "g1"]}}]},
                {**body, "rows": [{"n": {"id": 1}, "$metadata": {"graphIds": ["g1", "g2"]}}]},
                {**body, "rows": [{"n": {"id": True, "ordinal": 2}, "$metadata": {"graphIds": ["g1", "g2"]}}]},
                {**body, "columns": ["other"]}]:
            self.assertNotEqual(original, snapshot.response_digest(json.dumps(changed).encode())[0])
        reordered = dict(reversed(list(body.items())))
        self.assertEqual(original, snapshot.response_digest(json.dumps(reordered, indent=4).encode())[0])
        with self.assertRaisesRegex(ValueError, "duplicate JSON key"):
            snapshot.response_digest(b'{"columns":[],"rows":[],"total":1,"total":2}')
        with self.assertRaisesRegex(ValueError, "non-finite"):
            snapshot.response_digest(b'{"columns":[],"rows":[],"total":NaN}')
        for malformed in [b'null', b'{"columns":[],"rows":{}}',
                          b'{"columns":[],"rows":[],"rowCount":true}']:
            with self.assertRaises(ValueError):
                snapshot.response_digest(malformed)

    def test_full_bodies_and_failed_repeat_are_retained(self):
        bodies = [b'{"columns":[],"rows":[],"total":0}',
                  b'{"columns":[],"rows":[],"total":"0"}']
        work = [({}, "count", "MATCH (n) RETURN count(*)")]
        with tempfile.TemporaryDirectory() as directory:
            target = pathlib.Path(directory) / "responses"
            with self.assertRaisesRegex(RuntimeError, "changed between passes"):
                snapshot.measure("http://x", work, 2, 60, suite="broad", responses_dir=target,
                                 call=lambda *_: (1, 200, bodies.pop(0)), log=lambda *_: None)
            attempts = [json.loads(line) for line in (target / "attempts.jsonl").read_text().splitlines()]
            self.assertEqual([r["status"] for r in attempts], ["PASS", "FAIL"])
            self.assertEqual((target / attempts[1]["bodyFile"]).read_bytes(),
                             b'{"columns":[],"rows":[],"total":"0"}')
            manifest = json.loads((target / "manifest.json").read_text())
            self.assertEqual((manifest["status"], manifest["attempted"], manifest["expectedAttempts"]),
                             ("FAIL", 2, 2))
            with self.assertRaises(FileExistsError):
                snapshot.measure("http://x", work, 1, 60, responses_dir=target, call=FakeClient())

    def test_http_and_transport_failures_preserve_attempts(self):
        work = [({}, "failure", "MATCH (n) RETURN n")]
        for transport in (False, True):
            def client(*_):
                if transport:
                    raise TimeoutError("socket timed out")
                return 4, 500, b'{"error":"retained failure"}'
            with tempfile.TemporaryDirectory() as directory:
                target = pathlib.Path(directory) / "responses"
                with self.assertRaises(RuntimeError):
                    snapshot.measure("http://x", work, 1, 60, responses_dir=target, call=client)
                attempt = json.loads((target / "attempts.jsonl").read_text())
                self.assertEqual(attempt["status"], "FAIL")
                if transport:
                    self.assertEqual(attempt["error"], "socket timed out")
                    self.assertEqual(attempt["bodyCompleteness"], "unavailable")
                    self.assertNotIn("bodyFile", attempt)
                else:
                    self.assertEqual(attempt["httpStatus"], 500)
                    self.assertEqual(attempt["bodyCompleteness"], "complete-read")
                    self.assertEqual((target / attempt["bodyFile"]).read_bytes(), b'{"error":"retained failure"}')

    def test_overall_budget_stops_without_omitting_unmeasured_cases(self):
        work = [({}, "first", "RETURN 1"), ({}, "second", "RETURN 2")]
        times = iter([0, 0, 3, 6])
        client = FakeClient()
        with tempfile.TemporaryDirectory() as directory:
            target = pathlib.Path(directory) / "responses"
            with self.assertRaisesRegex(RuntimeError, "remaining cases unmeasured"):
                snapshot.measure("http://x", work, 1, 60, responses_dir=target, overall_timeout=5,
                                 clock=lambda: next(times), call=client)
            self.assertEqual(client.calls, 1)
            manifest = json.loads((target / "manifest.json").read_text())
            self.assertEqual(manifest["status"], "FAIL")
            self.assertEqual(manifest["attempted"], 1)
            self.assertEqual(manifest["expectedAttempts"], 2)
            self.assertEqual(len(manifest["cases"]), 2)
            attempt = json.loads((target / "attempts.jsonl").read_text())
            self.assertEqual(attempt["timeoutSeconds"], 5)

    def test_cli_all_passes_explicit_budget_and_scope_without_network(self):
        with tempfile.TemporaryDirectory() as directory:
            out = str(pathlib.Path(directory) / "out.json")
            argv = ["snapshot.py", "--manifest", "fixture.tsv", "--base", "http://x",
                    "--suite", "all", "--repetitions", "5", "--timeout", "60",
                    "--overall-timeout", "600", "--out", out]
            with patch.object(snapshot.sys, "argv", argv), \
                    patch.object(snapshot.fixture64, "read_manifest", return_value=(GRAPHS, DISTRIBUTIONS)), \
                    patch.object(snapshot, "graph_count", return_value=3), \
                    patch.object(snapshot, "measure", return_value=[]) as measure, \
                    patch.object(snapshot.signal, "signal"), patch.object(snapshot.signal, "setitimer"), \
                    redirect_stdout(io.StringIO()):
                self.assertEqual(snapshot.main(), 0)
            self.assertEqual(measure.call_args.args[2:], (5, 60))
            self.assertEqual(measure.call_args.kwargs["suite"], "all")
            self.assertEqual(measure.call_args.kwargs["overall_timeout"], 600)
            self.assertEqual(measure.call_args.kwargs["input_graph_count"], 3)
            self.assertEqual(measure.call_args.kwargs["responses_dir"], out + ".responses")

    def test_cli_refuses_single_graph_before_any_server_or_query(self):
        with patch.object(snapshot.sys, "argv", ["snapshot.py", "--manifest", "fixture.tsv",
                                                "--base", "http://x", "--out", "unused.json"]), \
                patch.object(snapshot.fixture64, "read_manifest", return_value=(GRAPHS[:1], [])), \
                patch.object(snapshot, "graph_count") as count, \
                patch.object(snapshot, "measure") as measure, \
                patch.object(snapshot.sys, "stderr", io.StringIO()):
            self.assertEqual(snapshot.main(), 2)
        count.assert_not_called()
        measure.assert_not_called()

    def test_case_identity_binds_queries_and_order_and_matches_retained_manifest(self):
        work = [({"selectivity": "broad"}, "shape-count-all", "MATCH (n) RETURN count(*)"),
                ({"selectivity": "broad"}, "shape-count-label", "MATCH (n:CallSite) RETURN count(*)")]
        with tempfile.TemporaryDirectory() as directory:
            target = pathlib.Path(directory) / "responses"
            rows = snapshot.measure("http://x", work, 1, 60, responses_dir=target, suite="broad",
                                    input_graph_count=3, call=FakeClient(), log=lambda *_: None)
            manifest = json.loads((target / "manifest.json").read_text())
            self.assertEqual(manifest["status"], "PASS")
            self.assertEqual(manifest["cases"], snapshot.case_catalog(work))
            self.assertEqual(manifest["attempted"], 2)
            self.assertEqual(manifest["inputGraphCount"], 3)
            for row in rows:
                self.assertEqual(row["caseListSha256"], manifest["caseListSha256"])
                self.assertEqual(row["caseCount"], 2)
                self.assertEqual(row["suite"], "broad")
                self.assertEqual(row["inputGraphCount"], 3)
            reversed_rows = snapshot.measure("http://x", list(reversed(work)), 1, 60,
                                             call=FakeClient(), log=lambda *_: None)
            changed = [(work[0][0], work[0][1], "MATCH (n) RETURN count(n)"), work[1]]
            changed_rows = snapshot.measure("http://x", changed, 1, 60,
                                            call=FakeClient(), log=lambda *_: None)
            self.assertNotEqual(rows[0]["caseListSha256"], reversed_rows[0]["caseListSha256"])
            self.assertNotEqual(rows[0]["caseListSha256"], changed_rows[0]["caseListSha256"])


if __name__ == "__main__":
    unittest.main()
