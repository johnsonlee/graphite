"""Correctness checks for the sustained-load evidence, never performance tests."""
import importlib.util
import json
import pathlib
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location(
    "signature_load", pathlib.Path(__file__).with_name("signature-load.py"))
load = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(load)


class SignatureLoadTest(unittest.TestCase):
    def test_identity_retains_values_order_and_provenance(self):
        body = {"columns": ["n"], "rows": [{"n": {"id": 7, "elapsedMs": 99},
                                             "$metadata": {"graphId": "first"}}],
                "rowCount": 1, "graphCount": 64, "total": 1}
        digest, count = load.response_identity(json.dumps(body).encode())
        self.assertEqual(count, 1)
        self.assertEqual(load.response_identity(load.canonical(body))[0], digest)
        body["rows"][0]["$metadata"]["graphId"] = "second"
        self.assertNotEqual(load.response_identity(load.canonical(body))[0], digest)
        body["rows"][0]["$metadata"]["graphId"] = "first"
        body["rows"][0]["n"]["elapsedMs"] = 100
        self.assertNotEqual(load.response_identity(load.canonical(body))[0], digest)

    def test_invalid_json_and_incomplete_row_count_are_rejected(self):
        for raw in (b'{"columns":[],"rows":[],"rowCount":false}',
                    b'{"columns":[],"rows":[],"rowCount":1}',
                    b'{"columns":[],"rows":[],"rowCount":0,"rowCount":0}',
                    b'{"columns":[],"rows":[],"rowCount":0,"total":NaN}'):
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                load.response_identity(raw)

    def test_nearest_rank_and_reference_mismatch_include_every_attempt(self):
        cases = [{"name": "q", "query": "MATCH (n) RETURN n LIMIT 1"}]
        samples = [{"case": "q", "valid": True, "elapsedMs": i,
                    "responseDigest": "digest", "rowCount": 1} for i in range(1, 21)]
        results, failures = load.summarize(cases, samples, [],
                                         {"cases": [{"name": "q", "responseDigests": ["other"]}]})
        self.assertEqual(results[0]["p50Ms"], 10)
        self.assertEqual(results[0]["p95Ms"], 19)
        self.assertEqual(results[0]["latenciesMs"], list(range(1, 21)))
        self.assertEqual(failures[0]["reason"], "reference response mismatch")
        samples.append({"case": "q", "valid": False, "elapsedMs": 100,
                        "bodyFile": "error.body", "validationError": "HTTP 500"})
        results, failures = load.summarize(cases, samples, [])
        self.assertEqual(results[0]["samples"], 21)
        self.assertEqual(results[0]["latenciesMs"][-1], 100)
        self.assertEqual(failures, [{"case": "q", "bodyFile": "error.body", "reason": "HTTP 500"}])

    def test_workers_consume_and_retain_each_full_body_before_validation(self):
        raw = b'{"columns":["v"],"rows":[{"v":7}],"rowCount":1}'

        class Response:
            status = 200

            def __enter__(self):
                return self

            def __exit__(self, *args):
                pass

            def read(self):
                return raw

        class Opener:
            def open(self, request, timeout):
                self.url = request.full_url
                return Response()

        cases = [{"name": "a", "query": "RETURN 7 AS v"},
                 {"name": "b", "query": "RETURN 7 AS v"}]
        with patch.object(load.urllib.request, "build_opener", side_effect=lambda *a: Opener()):
            records, _ = load.run_phase("http://localhost:1", cases, 2, 3, 12, 1)
        self.assertEqual(len(records), 12)
        self.assertEqual({worker: sum(r["worker"] == worker for r, _ in records)
                          for worker in (0, 1)}, {0: 6, 1: 6})
        self.assertTrue(all(body == raw and r["completeBody"] for r, body in records))
        self.assertTrue(all("responseDigest" not in r for r, _ in records))
        with tempfile.TemporaryDirectory() as directory:
            samples = load.retain_phase(pathlib.Path(directory), "samples", records)
            self.assertTrue(all(s["valid"] and s["rowCount"] == 1 for s in samples))
            self.assertTrue(all((pathlib.Path(directory) / s["bodyFile"]).read_bytes() == raw
                                for s in samples))


if __name__ == "__main__":
    unittest.main()
