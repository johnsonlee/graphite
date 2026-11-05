"""Correctness of evidence collection only; test fixtures are never performance evidence."""
import copy
import importlib.util
import json
import os
from pathlib import Path
import sys
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("apple_lifecycle", Path(__file__).with_name("apple-lifecycle.py"))
bench = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bench)


def config():
    case = {"name": "counts", "query": "MATCH (n) RETURN count(n)", "scope": ["apple", "jvm"], "row_order": "unordered", "limit": 10000}
    variant = {"revision": "revision", "graphs": [{"id": language, "language": language, "path": "/graphs/" + language, "source": language} for language in ["apple", "jvm"]], "server_command": ["graphite", "serve", "--port", "{port}"]}
    return {"sources": [{"id": name, "kind": "real-source", "repository": "https://example.org/" + name, "commit": "a" * 40} for name in ["apple", "jvm"]],
            "variants": {"baseline": copy.deepcopy(variant), "candidate": copy.deepcopy(variant)},
            "protocol": {"rounds": 2, "requests_per_case": 100, "concurrency": [1, 4], "warmup_requests": 1, "cache_state": "declared warm", "timeout_seconds": 2, "startup_timeout_seconds": 5, "readiness": case, "cases": [copy.deepcopy(case)]}}


def response(rows=None):
    return {"mode": "cross-graph", "graphs": ["apple", "jvm"], "columns": ["value"], "rows": rows if rows is not None else [{"value": 4, "$metadata": {"graphIds": ["apple", "jvm"]}}], "executionTimeMs": 123}


class LifecycleTests(unittest.TestCase):
    def test_refuses_single_graph_synthetic_unpinned_or_mismatched_work(self):
        bench.validate(config())
        edits = [lambda c: c["sources"][0].update(kind="synthetic"),
                 lambda c: c["sources"][0].update(commit="main"),
                 lambda c: c["variants"]["candidate"]["graphs"].pop(),
                 lambda c: c["variants"]["candidate"]["graphs"][1].update(language="apple"),
                 lambda c: c["variants"]["candidate"]["graphs"][1].update(path="/graphs/apple"),
                 lambda c: c["protocol"]["cases"][0].update(scope=["apple"]),
                 lambda c: c["protocol"].update(requests_per_case=99),
                 lambda c: c["protocol"].update(concurrency=[1]),
                 lambda c: c["variants"]["candidate"]["server_command"].extend(["--graph", "other:/tmp/other"])]
        for edit in edits:
            current = config()
            edit(current)
            with self.assertRaises(bench.Invalid):
                bench.validate(current)

    def test_matched_settings_construction_catalog_and_case_names_are_required(self):
        mismatched = config()
        mismatched["variants"]["candidate"]["server_command"].append("--different-feature")
        with self.assertRaisesRegex(bench.Invalid, "server settings"):
            bench.validate(mismatched)
        baseline_work = {"name": "swiftpm", "source": "apple", "output": "{work}/graph",
                         "commands": [["frontend", "build"], ["java", "-Xmx8g", "-jar", "base.jar"]],
                         "verify_command": ["graphite", "verify", "{work}/graph"]}
        for change in [{"name": "signal"}, {"source": "jvm"}, {"commands": [["frontend", "build"], ["java", "-Xmx4g", "-jar", "candidate.jar"]]}]:
            mismatch = config()
            mismatch["variants"]["baseline"]["construction"] = [copy.deepcopy(baseline_work)]
            mismatch["variants"]["candidate"]["construction"] = [{**copy.deepcopy(baseline_work), **change}]
            with self.subTest(change=change), self.assertRaisesRegex(bench.Invalid, "construction workloads"):
                bench.validate(mismatch)
        collision = config()
        collision["protocol"]["readiness"]["query"] = "RETURN 1"
        with self.assertRaisesRegex(bench.Invalid, "same request"):
            bench.validate(collision)
        valid = config()
        valid["variants"]["baseline"]["server_command"][0] = "/baseline/graphite"
        valid["variants"]["candidate"]["server_command"][0] = "/candidate/graphite"
        bench.validate(valid)

    def test_heap_limit_checks_direct_java_and_environment(self):
        with patch.dict("os.environ", {}, clear=True):
            bench.command_check(["java", "-Xmx8g", "-jar", "graphite.jar"])
            for command in [["java", "-jar", "x.jar"], ["java", "-Xmx8193m"], ["bash", "-c", "anything"]]:
                with self.assertRaises(bench.Invalid):
                    bench.command_check(command)
            with patch.dict("os.environ", {"JAVA_TOOL_OPTIONS": "-Xmx9g"}):
                with self.assertRaises(bench.Invalid):
                    bench.command_check(["java", "-Xmx8g"])
            with patch.dict("os.environ", {"JAVA_OPTS": "-Xmx9g"}):
                with self.assertRaises(bench.Invalid):
                    bench.command_check(["graphite", "import", "input.ir"])

    def test_canonical_digest_preserves_duplicate_rows_and_graph_provenance(self):
        case = config()["protocol"]["cases"][0]
        first = response([{"value": 1, "$metadata": {"graphIds": ["apple"]}}, {"value": 2, "$metadata": {"graphIds": ["jvm"]}}])
        second = copy.deepcopy(first)
        second["rows"].reverse()
        second["executionTimeMs"] = 999
        digest = lambda payload: bench.digest_result(json.dumps(payload).encode(), case)
        self.assertEqual(digest(first), digest(second))
        second["rows"][0]["$metadata"]["graphIds"] = ["apple"]
        self.assertNotEqual(digest(first), digest(second))
        second = copy.deepcopy(first)
        second["rows"].append(second["rows"][0])
        self.assertNotEqual(digest(first), digest(second))
        case["row_order"] = "ordered"
        second = copy.deepcopy(first)
        second["rows"].reverse()
        self.assertNotEqual(digest(first), digest(second))

    def test_digest_refuses_wrong_scope_truncation_and_result_cap(self):
        case = config()["protocol"]["cases"][0]
        for changes in [{"graphs": ["apple"]}, {"mode": "fanout"}, {"truncated": True}, {"error": "cancelled"}]:
            with self.assertRaises(bench.Invalid):
                bench.digest_result(json.dumps({**response(), **changes}).encode(), case)
        with self.assertRaises(bench.Invalid):
            bench.digest_result(json.dumps(response()).encode(), {**case, "limit": 1})

    def test_nearest_rank_and_parity_do_not_pool_different_cases(self):
        rows = []
        for variant in ["baseline", "candidate"]:
            for round_number in [0, 1]:
                for value in range(1, 101):
                    rows.append({"kind": "request", "ok": True, "case": "counts", "variant": variant, "round": round_number, "phase": "measured", "concurrency": 4, "wall_ms": value, "digest": "same", "row_count": 2})
        summary = bench.summarize(rows)
        self.assertEqual(summary["queries"][0]["p50_ms"], 50)
        self.assertEqual(summary["queries"][0]["p95_ms"], 95)
        self.assertEqual(summary["queries"][0]["round_p95_range_ms"], [95, 95])
        self.assertTrue(summary["parity"][0]["ok"])
        rows[-1]["digest"] = "different"
        self.assertFalse(bench.summarize(rows)["parity"][0]["ok"])

    def test_wait4_retains_exit_code_cpu_rss_and_failed_command(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            journal = bench.Journal(root / "samples.jsonl")
            try:
                good = bench.run_command([sys.executable, "-c", "print('verified')"], root / "good.log", 5, journal)
                self.assertTrue(good["ok"])
                self.assertGreater(good["peak_process_rss_bytes"], 0)
                self.assertEqual(good["cpu_seconds"], good["user_seconds"] + good["system_seconds"])
                with self.assertRaises(bench.Invalid):
                    bench.run_command([sys.executable, "-c", "raise SystemExit(7)"], root / "bad.log", 5, journal)
                self.assertEqual(journal.rows[-1]["exit_code"], 7)
                self.assertFalse(journal.rows[-1]["ok"])
                timed = bench.Child([sys.executable, "-c", "import time; time.sleep(20)"], root / "timeout.log").wait(.03)
                self.assertTrue(timed["timeout"])
                self.assertLess(timed["exit_code"], 0)
            finally:
                journal.file.close()

    def test_live_cpu_counter_and_batch_boundary(self):
        before = bench.process_usage(os.getpid())
        sampler = bench.BatchUsage(os.getpid())
        total = sum(i * i for i in range(500000))
        measured = sampler.finish()
        self.assertGreater(total, 0)
        self.assertEqual(measured["cpu_before"]["identity"], before["identity"])
        self.assertGreater(measured["cpu_seconds"], 0)
        self.assertGreater(measured["sampled_rss_lower_bound_bytes"], 0)
        self.assertEqual(measured["cpu_seconds"], measured["user_seconds"] + measured["system_seconds"])
        self.assertEqual(measured["rss_sample_errors"], [])

    def test_mac_cpu_ticks_are_converted_with_the_host_timebase(self):
        usage = bench.MacUsageV2()
        usage.ri_user_time, usage.ri_system_time = 24000000, 12000000
        usage.ri_resident_size, usage.ri_proc_start_abstime = 4096, 123456
        measured = bench.mac_usage(usage, (125, 3))
        self.assertEqual(measured["user_seconds"], 1.0)
        self.assertEqual(measured["system_seconds"], 0.5)
        self.assertEqual(measured["raw_cpu_ticks"], {"user": 24000000, "system": 12000000})
        self.assertEqual(measured["mach_timebase"], {"numer": 125, "denom": 3})
        self.assertEqual(measured["resident_bytes"], 4096)
        self.assertEqual(measured["identity"], "123456")
        self.assertEqual(measured["cpu_counter"], "proc_pid_rusage-v2-mach-ticks-converted-to-seconds")
        self.assertEqual(bench.mac_usage(usage, (1, 1))["user_seconds"], .024)
        with self.assertRaisesRegex(bench.Invalid, "timebase"):
            bench.mac_usage(usage, (125, 0))

    def test_linux_cpu_fields_handle_spaces_and_parentheses_in_process_name(self):
        fields = ["S"] + ["0"] * 21
        fields[11], fields[12], fields[19], fields[21] = "250", "75", "12345", "99"
        measured = bench.linux_usage("42 (worker (with spaces)) " + " ".join(fields), 100, 4096)
        self.assertEqual(measured["user_seconds"], 2.5)
        self.assertEqual(measured["system_seconds"], .75)
        self.assertEqual(measured["identity"], "12345")
        self.assertEqual(measured["resident_bytes"], 99 * 4096)

    def test_query_batches_keep_all_one_hundred_outcomes_on_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            journal = bench.Journal(root / "samples.jsonl")
            class FakeChild:
                process = type("Process", (), {"pid": os.getpid()})()
                def poll(self):
                    return None
                def stop(self):
                    return {"exit_code": -15}
            def fake_request(base, case, timeout, journal, **context):
                return journal.write(kind="request", case=case["name"], scope=case["scope"],
                                     **context, ok=not (context.get("phase") == "measured" and context.get("sample") == 3), wall_ms=1)
            try:
                with patch.object(bench, "start_server", return_value=(FakeChild(), "unused")), patch.object(bench, "request", side_effect=fake_request):
                    with self.assertRaises(bench.Invalid):
                        bench.server_run(config()["variants"]["baseline"], config()["protocol"], root, journal,
                                         "query", variant="baseline", round=0)
                measured = [row for row in journal.rows if row.get("phase") == "measured"]
                self.assertEqual(len(measured), 100)
                self.assertEqual(sum(not row["ok"] for row in measured), 1)
                batch = next(row for row in journal.rows if row["kind"] == "query_batch")
                self.assertEqual(batch["samples"], 100)
                self.assertEqual(batch["failures"], 1)
                self.assertFalse(batch["ok"])
                self.assertFalse(journal.rows[-1]["ok"])
            finally:
                journal.file.close()

    def test_main_pairs_rounds_ab_ba_and_retains_failed_pair_member(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / "config.json"
            path.write_text(json.dumps(config()))
            order = []
            def run(variant_config, protocol, folder, journal, operation, **context):
                order.append((context["round"], context["variant"]))
                if context["round"] == 0 and context["variant"] == "baseline":
                    raise bench.Invalid("deliberate correctness-test failure")
                journal.write(kind="load", operation=operation, **context, ok=True, wall_ms=1)
            with patch.object(bench, "fingerprint", return_value=[{"path": "fixture", "sha256": "test-only"}]), patch.object(bench, "server_run", side_effect=run):
                self.assertEqual(bench.main(["load", str(path), "--out", str(root / "evidence")]), 2)
            self.assertEqual(order, [(0, "baseline"), (0, "candidate"), (1, "candidate"), (1, "baseline")])
            rows = [json.loads(line) for line in (root / "evidence/samples.jsonl").read_text().splitlines()]
            failure = next(row for row in rows if row["kind"] == "failure")
            self.assertEqual(failure["variant"], "baseline")
            self.assertIn("deliberate", failure["error"])
            self.assertFalse(json.loads((root / "evidence/summary.json").read_text())["ok"])

    def test_construction_measures_stages_and_independently_verifies_saved_artifact(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            journal = bench.Journal(root / "samples.jsonl")
            variant = {"construction": [{"name": "source", "source": "apple", "output": "{work}/graph",
                "commands": [[sys.executable, "-c", "from pathlib import Path; Path(r'{work}/ir').write_text('ir')"],
                             [sys.executable, "-c", "from pathlib import Path; assert Path(r'{work}/ir').read_text() == 'ir'; Path(r'{work}/graph').write_text('saved')"]],
                "verify_command": [sys.executable, "-c", "from pathlib import Path; assert Path(r'{work}/graph').read_text() == 'saved'"]}]}
            try:
                bench.construct(variant, config()["protocol"], root, journal, variant="baseline", round=0)
                construction = journal.rows[-1]
                stages = [row for row in journal.rows if isinstance(row.get("stage"), int)]
                self.assertEqual(len(stages), 2)
                self.assertTrue(construction["ok"])
                self.assertEqual(construction["cpu_seconds"], sum(row["cpu_seconds"] for row in stages))
                self.assertEqual(construction["max_stage_peak_process_rss_bytes"], max(row["peak_process_rss_bytes"] for row in stages))
                self.assertEqual(construction["artifacts"][0]["bytes"], 5)
                self.assertEqual(journal.rows[-2]["stage"], "verification-excluded")
            finally:
                journal.file.close()

    def test_request_consumes_delayed_body_and_retains_http_failure(self):
        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                received = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                self.server.received = received
                if received["query"] == "fail":
                    self.send_response(500)
                    self.end_headers()
                    self.wfile.write(b'{"error":"failed"}')
                    return
                body = json.dumps(response()).encode()
                self.send_response(200)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body[:10])
                self.wfile.flush()
                time.sleep(.04)
                self.wfile.write(body[10:])
            def log_message(self, *args):
                pass
        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            with tempfile.TemporaryDirectory() as directory:
                journal = bench.Journal(Path(directory) / "samples.jsonl")
                try:
                    case = config()["protocol"]["cases"][0]
                    base = f"http://127.0.0.1:{server.server_port}"
                    row = bench.request(base, case, 2, journal)
                    self.assertTrue(row["ok"])
                    self.assertGreaterEqual(row["wall_ms"], 35)
                    self.assertEqual(row["row_count"], 1)
                    self.assertEqual(server.received["graphs"], ["apple", "jvm"])
                    bad = bench.request(base, {**case, "query": "fail"}, 2, journal)
                    self.assertEqual(bad["status"], 500)
                    self.assertFalse(bad["ok"])
                    self.assertEqual(json.loads(bad["response_error_body"]), {"error": "failed"})
                    self.assertEqual(len(journal.rows), 2)
                finally:
                    journal.file.close()
        finally:
            server.shutdown()
            server.server_close()
            thread.join()


if __name__ == "__main__":
    unittest.main()
