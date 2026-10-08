"""Optional boundary observations; no native server, graph or network required."""
import json
import pathlib
import tempfile
import unittest
from unittest.mock import Mock, patch

from test_snapshot import snapshot, GRAPHS, DISTRIBUTIONS, payload
import metrics_capture


class SnapshotDiagnosticsTest(unittest.TestCase):
    work = [({}, "shape-union", "RETURN 1")]

    @staticmethod
    def attempts(directory):
        return [json.loads(s) for s in (directory / "attempts.jsonl").read_text().splitlines()]

    @staticmethod
    def capture(_url, directory, boundary, timeout):
        assert 0 < timeout <= 60
        (directory / ("metrics-" + boundary + ".prom")).write_bytes(b"retained raw metrics")
        return {"boundary": boundary}

    def run_measure(self, directory, **kwargs):
        return snapshot.measure("http://x", self.work, 1, 60, log=lambda *_: None,
                                responses_dir=directory, diagnostic_metrics_case="shape-union",
                                **kwargs)

    def test_disabled_does_not_read_diagnostic_clocks_metrics_or_change_rows(self):
        call = lambda *_: (1.0, 200, payload(1))
        expected = snapshot.measure("http://x", self.work, 1, 60, call=call, log=lambda *_: None)
        with tempfile.TemporaryDirectory() as temp, \
                patch.object(snapshot.time, "monotonic_ns", side_effect=AssertionError("extra clock")), \
                patch.object(snapshot.time, "thread_time_ns", side_effect=AssertionError("extra CPU")), \
                patch.object(metrics_capture, "capture", side_effect=AssertionError("extra scrape")):
            directory = pathlib.Path(temp) / "responses"
            actual = snapshot.measure("http://x", self.work, 1, 60, call=call,
                                      responses_dir=directory, log=lambda *_: None)
            self.assertEqual(actual, expected)
            self.assertFalse(any("Monotonic" in k or "ThreadCpu" in k or "metrics" in k.lower()
                                 for k in self.attempts(directory)[0]))

    def test_all73_five_passes_keep_every_query_and_trace_only_target_five(self):
        distributions = [{**DISTRIBUTIONS[0], "case": name} for name in
                         ("one-graph", "first-graph", "last-graph", "many-graphs")]
        work = snapshot.plan(GRAPHS, distributions, "all")
        called = []
        def call(_base, query, _timeout):
            called.append(query)
            return 2.5, 200, payload(1)
        window = {"histograms": {name: [{"countDelta": 1, "sumSecondsDelta": "0.001"}]
                                 for name in (metrics_capture.HTTP, metrics_capture.GUARD)}}
        with tempfile.TemporaryDirectory() as temp, \
                patch.object(metrics_capture, "capture", side_effect=self.capture) as capture, \
                patch.object(metrics_capture, "validate_window", return_value=window) as validate:
            directory = pathlib.Path(temp) / "responses"
            rows = snapshot.measure("http://x", work, 5, 60, call=call, log=lambda *_: None,
                                    suite="all", responses_dir=directory,
                                    diagnostic_metrics_case="shape-union")
            records = self.attempts(directory)
            self.assertEqual(called, [query for _ in range(5) for _, _, query in work])
            self.assertEqual(len(records), 365)
            self.assertEqual(len(rows), 75)
            self.assertEqual(capture.call_count, 10)
            self.assertEqual(validate.call_count, 5)
            for record in records:
                self.assertEqual(record["status"], "PASS")
                self.assertEqual(record["elapsedMs"], 2.5)
                self.assertIn("callStartMonotonicNs", record)
                self.assertIn("callEndThreadCpuNs", record)
                self.assertEqual("serverMetricsWindow" in record, record["caseIndex"] == 72)
                if record["caseIndex"] == 72:
                    self.assertEqual(record["serverMetricsWindow"], window)
                self.assertEqual((directory / record["bodyFile"]).read_bytes(), payload(1))
            self.assertTrue(all(c.kwargs == {"expected_count": 1} for c in validate.call_args_list))

    def test_missing_case_and_missing_response_directory_rejected_before_query(self):
        call = Mock(side_effect=AssertionError("query must not run"))
        with self.assertRaisesRegex(ValueError, "absent"):
            snapshot.measure("http://x", self.work, 1, 60, call=call,
                             diagnostic_metrics_case="absent")
        with self.assertRaisesRegex(ValueError, "requires retained response directory"):
            snapshot.measure("http://x", self.work, 1, 60, call=call,
                             diagnostic_metrics_case="shape-union")
        call.assert_not_called()

    def test_metrics_failure_retains_query_body_when_already_received(self):
        for fail_after in (False, True):
            def capture(url, directory, boundary, timeout):
                if boundary.endswith("-after") == fail_after:
                    raise ValueError("metrics unavailable")
                return self.capture(url, directory, boundary, timeout)
            with self.subTest(fail_after=fail_after), tempfile.TemporaryDirectory() as temp, \
                    patch.object(metrics_capture, "capture", side_effect=capture):
                directory = pathlib.Path(temp) / "responses"
                call = Mock(return_value=(3, 200, payload(1)))
                with self.assertRaisesRegex(RuntimeError, "metrics unavailable"):
                    self.run_measure(directory, call=call)
                record, = self.attempts(directory)
                self.assertEqual(record["status"], "FAIL")
                self.assertEqual(call.call_count, int(fail_after))
                if fail_after:
                    self.assertEqual((directory / record["bodyFile"]).read_bytes(), payload(1))
                    self.assertEqual(record["httpStatus"], 200)
                else:
                    self.assertEqual(record["bodyCompleteness"], "unavailable")

    def test_budget_expiring_during_after_metrics_retains_complete_query(self):
        now = [0]
        def capture(url, directory, boundary, timeout):
            out = self.capture(url, directory, boundary, timeout)
            if boundary.endswith("-after"):
                now[0] = 11
            return out
        with tempfile.TemporaryDirectory() as temp, \
                patch.object(metrics_capture, "capture", side_effect=capture), \
                patch.object(metrics_capture, "validate_window") as validate:
            directory = pathlib.Path(temp) / "responses"
            with self.assertRaisesRegex(RuntimeError, "budget exhausted after metrics"):
                self.run_measure(directory, call=lambda *_: (1, 200, payload(1)),
                                 overall_timeout=10, clock=lambda: now[0])
            record, = self.attempts(directory)
            self.assertEqual(record["status"], "FAIL")
            self.assertEqual((directory / record["bodyFile"]).read_bytes(), payload(1))
            self.assertEqual(record["httpStatus"], 200)
            self.assertTrue((directory / "metrics-pass-000-case-000-after.prom").exists())
            validate.assert_not_called()

    def test_records_actual_query_timeout_after_before_metrics(self):
        for transport_error in (False, True):
            now = [0]
            def capture(url, directory, boundary, timeout):
                out = self.capture(url, directory, boundary, timeout)
                if boundary.endswith("-before"):
                    now[0] = 3
                return out
            call = Mock(side_effect=TimeoutError("query timeout")) if transport_error else \
                Mock(return_value=(1, 200, payload(1)))
            with self.subTest(transport_error=transport_error), tempfile.TemporaryDirectory() as temp, \
                    patch.object(metrics_capture, "capture", side_effect=capture), \
                    patch.object(metrics_capture, "validate_window", return_value={}):
                directory = pathlib.Path(temp) / "responses"
                if transport_error:
                    with self.assertRaisesRegex(RuntimeError, "query timeout"):
                        self.run_measure(directory, call=call, overall_timeout=10, clock=lambda: now[0])
                else:
                    self.run_measure(directory, call=call, overall_timeout=10, clock=lambda: now[0])
                call.assert_called_once_with("http://x", "RETURN 1", 7)
                record, = self.attempts(directory)
                self.assertEqual(record["timeoutSeconds"], 7)
                self.assertEqual(record["status"], "FAIL" if transport_error else "PASS")

    def test_budget_expiring_during_before_metrics_does_not_issue_query(self):
        now = [0]
        def capture(url, directory, boundary, timeout):
            out = self.capture(url, directory, boundary, timeout)
            now[0] = 11
            return out
        with tempfile.TemporaryDirectory() as temp, \
                patch.object(metrics_capture, "capture", side_effect=capture):
            directory = pathlib.Path(temp) / "responses"
            call = Mock(side_effect=AssertionError("query must not run"))
            with self.assertRaisesRegex(RuntimeError, "budget exhausted after metrics"):
                self.run_measure(directory, call=call, overall_timeout=10, clock=lambda: now[0])
            call.assert_not_called()
            record, = self.attempts(directory)
            self.assertEqual(record["status"], "FAIL")
            self.assertEqual(record["bodyCompleteness"], "unavailable")
            self.assertNotIn("callStartMonotonicNs", record)

    def test_original_http_and_transport_errors_keep_the_original_failure(self):
        for transport in (False, True):
            def call(*_):
                if transport:
                    raise TimeoutError("original socket timeout")
                return 3, 500, b'{"error":"original HTTP failure"}'
            with self.subTest(transport=transport), tempfile.TemporaryDirectory() as temp, \
                    patch.object(metrics_capture, "capture", side_effect=self.capture) as capture:
                directory = pathlib.Path(temp) / "responses"
                expected = "original socket timeout" if transport else "HTTP 500"
                with self.assertRaisesRegex(RuntimeError, expected):
                    self.run_measure(directory, call=call)
                record, = self.attempts(directory)
                self.assertEqual(record["error"], expected)
                self.assertEqual(capture.call_count, 1)  # do not mask query error with after metrics
                self.assertIn("callEndMonotonicNs", record)
                if transport:
                    self.assertEqual(record["bodyCompleteness"], "unavailable")
                else:
                    self.assertEqual((directory / record["bodyFile"]).read_bytes(),
                                     b'{"error":"original HTTP failure"}')

    def test_metrics_flag_changes_only_requested_serve_argv(self):
        with tempfile.TemporaryDirectory() as temp, patch.object(snapshot.subprocess, "Popen") as popen:
            snapshot.serve("graphite", GRAPHS, 1234, str(pathlib.Path(temp)/"plain.log"), 60000)
            plain = popen.call_args.args[0]
            popen.call_args.kwargs["stdout"].close()
            snapshot.serve("graphite", GRAPHS, 1234, str(pathlib.Path(temp)/"metrics.log"), 60000,
                           metrics=True)
            enabled = popen.call_args.args[0]
            popen.call_args.kwargs["stdout"].close()
            self.assertNotIn("--metrics", plain)
            self.assertEqual(enabled.count("--metrics"), 1)
            self.assertEqual([x for x in enabled if x != "--metrics"], plain)

    def test_metric_counter_schema_error_preserves_response_and_both_raw_scrapes(self):
        with tempfile.TemporaryDirectory() as temp, \
                patch.object(metrics_capture, "capture", side_effect=self.capture), \
                patch.object(metrics_capture, "validate_window", side_effect=ValueError("Guard limit changed")):
            directory = pathlib.Path(temp) / "responses"
            with self.assertRaisesRegex(RuntimeError, "Guard limit changed"):
                self.run_measure(directory, call=lambda *_: (1, 200, payload(1)))
            record, = self.attempts(directory)
            self.assertEqual(record["status"], "FAIL")
            self.assertEqual((directory / record["bodyFile"]).read_bytes(), payload(1))
            self.assertEqual(len(list(directory.glob('metrics-*.prom'))), 2)

    def test_selects_only_largest_absolute_confirmed_broad_shape_failure(self):
        def row(name, delta, **extra):
            return {"key": f"rust.fixture64.{name}[selectivity=broad]", "blocked": True,
                    "confirmation": {"blocked": True}, "absoluteDeltaMs": delta, **extra}
        status = {"passed": False, "errors": [], "thresholdOnly": True,
                  "rows": [row("shape-union", 26), row("shape-cartesian", 40),
                           row("shape-collect", 100, confirmation={"blocked": False}),
                           row("shape-with-filter", 200, blocked=False),
                           row("aggregate", 900), row("unknown", 999)]}
        self.assertEqual(snapshot.confirmed_diagnostic_shape(status), "shape-cartesian")
        for changed in ({"passed": True}, {"errors": ["digest changed"]},
                        {"thresholdOnly": False}, {"rows": [row("shape-union", 26, confirmation=None)]}):
            self.assertIsNone(snapshot.confirmed_diagnostic_shape({**status, **changed}))
        for invalid in (float("nan"), float("inf"), -1, True, "27"):
            self.assertIsNone(snapshot.confirmed_diagnostic_shape(
                {**status, "rows": [row("shape-union", invalid)]}))
        self.assertIsNone(snapshot.confirmed_diagnostic_shape({}))


class StrictMetricsWindowTest(unittest.TestCase):
    @staticmethod
    def raw(count, *, guard_limit=4, rejected=0):
        lines = ["graphite_cypher_queries_active 0", f"graphite_cypher_queries_limit {guard_limit}",
                 f"graphite_cypher_queries_rejected_total {rejected}"]
        for prefix, variants in [
            (metrics_capture.HTTP, [('method="POST",outcome="SUCCESS",status="200",uri="/api/cypher"', True)]),
            (metrics_capture.GUARD, [(f'outcome="{o}"', o == 'success') for o in metrics_capture.OUTCOMES])
        ]:
            for labels, success in variants:
                n = count if success else 0
                for bound in ('0.01', '0.05', '0.1', '0.5', '1', '5', '30', '120', '+Inf'):
                    lines.append(f'{prefix}_bucket{{{labels},le="{bound}"}} {n}')
                lines += [f'{prefix}_count{{{labels}}} {n}', f'{prefix}_sum{{{labels}}} {n * .001}',
                          f'{prefix}_max{{{labels}}} {0.001 if n else 0}']
        return ('\n'.join(lines) + '\n').encode()

    def test_real_parser_accepts_exact_success_counters_and_sum(self):
        result = metrics_capture.validate_window(self.raw(0), self.raw(1), expected_count=1)
        self.assertEqual(result['rejectedDelta'], 0)
        for name in (metrics_capture.HTTP, metrics_capture.GUARD):
            groups = result['histograms'][name]
            self.assertEqual(sum(g['countDelta'] for g in groups), 1)
            self.assertAlmostEqual(sum(float(g['sumSecondsDelta']) for g in groups), .001)
            self.assertEqual(sum(g['cumulativeBucketDeltas'][-1] for g in groups), 1)

    def test_real_parser_rejects_bad_limits_counts_buckets_and_duplicate_samples(self):
        before, after = self.raw(0), self.raw(1)
        invalid = [self.raw(1, guard_limit=8), self.raw(1, rejected=1), self.raw(2),
                   after.replace(b'le="0.01"} 1', b'le="0.01"} 2', 1),
                   after + b'graphite_cypher_queries_active 0\n',
                   after.replace(b'graphite_cypher_queries_active 0', b'graphite_cypher_queries_active NaN')]
        for raw in invalid:
            with self.subTest(raw=raw[:80]), self.assertRaises(ValueError):
                metrics_capture.validate_window(before, raw, expected_count=1)
