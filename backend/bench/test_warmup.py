"""The benchmark scripts fail closed on the warmup too, not only on the measured loop.

Run with `python3 -m unittest discover -s backend/bench -p 'test_*.py'`; no server is
needed. A fake client answers the term sampling with a plausible corpus, then
rejects one warmup request, and each script must report that and return 2 before
measuring anything.
"""

import importlib.util
import io
import json
import os
import pathlib
import unittest
from contextlib import redirect_stderr, redirect_stdout

HERE = pathlib.Path(__file__).resolve().parent


def load(name):
    spec = importlib.util.spec_from_file_location(name.replace("-", "_"), HERE / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


WORDS = ["alpha", "bravo", "charlie", "delta", "echo", "foxtrot", "golf", "hotel",
         "india", "juliet", "kilo", "lima", "mike", "november", "oscar", "papa",
         "quebec", "romeo", "sierra", "tango", "uniform", "victor", "whiskey", "xray",
         "yankee", "zulu", "amber", "birch", "cedar", "dune"]
CLASSES = [f"com.acme.{a}.{b}.{a.capitalize()}{b.capitalize()}Handler"
           for a in WORDS[:10] for b in WORDS[10:14]]
NAMES = ["handleRequest", "computeTotal", "registerListener", "validateInput"]


class FakeClient:
    """Answers sampling queries with a corpus, then fails the `reject_at`-th warmup."""

    def __init__(self, reject_at, status=429):
        self.reject_at = reject_at
        self.status = status
        self.warmup_calls = 0
        self.measured = 0
        self.conn = None

    def _answer(self, query):
        if "RETURN DISTINCT n.caller_class AS c LIMIT 400" in query:
            return 200, {"rows": [{"c": c} for c in CLASSES]}
        if "AS v LIMIT 20000" in query:
            values = CLASSES if "class" in query else NAMES * 50
            return 200, {"rows": [{"v": v} for v in values]}
        self.warmup_calls += 1
        if self.warmup_calls == self.reject_at:
            return self.status, {"error": "too many concurrent queries"}
        return 200, {"rows": [{"n.caller_class": CLASSES[0]}]}

    # backtest.Client.post(path, payload)
    def post(self, path_or_query, payload=None):
        query = payload["query"] if payload is not None else path_or_query
        status, body = self._answer(query)
        return 0.1, status, json.dumps(body).encode()


class WarmupFailsClosed(unittest.TestCase):
    def run_quietly(self, fn):
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            code = fn()
        return code, err.getvalue()

    def test_backtest_stops_on_a_rejected_warmup(self):
        backtest = load("backtest")
        client = FakeClient(reject_at=7)
        code, err = self.run_quietly(lambda: backtest.main(
            ["--base", "http://fake:1", "--label", "t", "--mix", "v2"],
            client_factory=lambda base, timeout: client))
        self.assertEqual(code, 2)
        self.assertIn("warmup queries failed", err)
        self.assertIn("'429': 1", err)
        # Warmup is 170 queries; every one is checked, none measured afterwards.
        self.assertEqual(client.warmup_calls, 170)

    def test_backtest_runs_when_every_warmup_succeeds(self):
        backtest = load("backtest")
        client = FakeClient(reject_at=0)
        code, _ = self.run_quietly(lambda: backtest.main(
            ["--base", "http://fake:1", "--label", "t", "--mix", "v2"],
            client_factory=lambda base, timeout: client))
        self.assertEqual(code, 0)
        self.assertEqual(client.warmup_calls, 340)  # 170 warmup + 170 measured

    def test_hot_terms_stops_on_a_failed_warmup(self):
        hot = load("hot-terms")
        client = FakeClient(reject_at=2, status="FAILED:TimeoutError")
        code, err = self.run_quietly(lambda: hot.main(
            ["--base", "http://fake:1", "--label", "t", "--terms", "5"],
            client_factory=lambda base, timeout: client))
        self.assertEqual(code, 2)
        self.assertIn("warmup queries failed", err)
        self.assertIn("FAILED:TimeoutError", err)


if __name__ == "__main__":
    unittest.main()
