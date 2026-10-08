"""Reject incomplete joins, incorrect calls and missing two-graph provenance."""

import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location(
    "cross_graph", Path(__file__).with_name("verify-apple-cross-graph.py")
)
CHECK = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECK)


class CrossGraphTest(unittest.TestCase):
    def responses(self):
        return [
            {"graphs": [{"id": "swift"}, {"id": "backend"}]},
            {
                "graphCount": 2,
                "columns": list(CHECK.EXPECTED)[:-1],
                "rowCount": 1,
                "rows": [copy.deepcopy(CHECK.EXPECTED)],
            },
            {"rowCount": 0, "rows": []},
            {"rowCount": 0, "rows": []},
        ]

    def verify(self, responses):
        with tempfile.TemporaryDirectory() as directory:
            evidence = Path(directory) / "evidence.json"
            with patch.object(CHECK, "request", side_effect=responses) as request:
                CHECK.verify("http://localhost:18095", evidence)
            return request.call_args_list, json.loads(evidence.read_text())

    def test_join_and_both_isolated_scopes_are_checked_and_recorded(self):
        calls, evidence = self.verify(self.responses())
        expected = ["/api/graphs", "/api/cypher", "/api/graphs/swift/cypher", "/api/graphs/backend/cypher"]
        self.assertEqual([call.args[1] for call in calls], expected)
        self.assertEqual([record["endpoint"] for record in evidence], expected)
        self.assertEqual(evidence[1]["response"]["rows"][0]["path"], "/v1/charge")
        self.assertTrue(all(call.args[2] == CHECK.JOIN for call in calls[1:]))

    def test_missing_second_graph_is_rejected(self):
        responses = self.responses()
        responses[0]["graphs"].pop()
        with self.assertRaises(AssertionError):
            self.verify(responses)

    def test_empty_join_is_rejected(self):
        responses = self.responses()
        responses[1].update(rowCount=0, rows=[])
        with self.assertRaises(AssertionError):
            self.verify(responses)

    def test_incorrect_path_method_or_provenance_is_rejected(self):
        for key, value in [
            ("path", "/wrong"),
            ("client_method", "unrelated()"),
            ("server_call", "unrelated"),
            ("server_graph", "swift"),
            ("$metadata", {"graphIds": ["swift"]}),
        ]:
            with self.subTest(key=key):
                responses = self.responses()
                responses[1]["rows"][0][key] = value
                with self.assertRaises(AssertionError):
                    self.verify(responses)

    def test_join_succeeding_on_one_graph_is_rejected(self):
        for index in (2, 3):
            with self.subTest(index=index):
                responses = self.responses()
                responses[index] = responses[1]
                with self.assertRaises(AssertionError):
                    self.verify(responses)


if __name__ == "__main__":
    unittest.main()
