#!/usr/bin/env python3
"""Pure correctness checks for the compiled-operation evidence validator."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location(
    "artifact_e2e", Path(__file__).with_name("check-typescript-e2e.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class OperationOccurrenceTests(unittest.TestCase):
    def setUp(self):
        # Two distinct emitted calls on the same minified line, each with 'saved'.
        self.operation = {"callee": "success", "value": "saved", "locations": [
            {"file": "chunks/app.js", "line": 1},
            {"file": "chunks/app.js", "line": 1},
        ]}
        self.row = {"callee": "success", "value": "saved", "line": 1,
                    "caller_class": "chunks/app.js#save@1:1"}

    def test_two_same_location_calls_require_two_rows(self):
        self.assertEqual(module.check_operation_occurrences(self.operation, [self.row, self.row]), 2)
        with self.assertRaisesRegex(AssertionError, "missing="):
            module.check_operation_occurrences(self.operation, [self.row])

    def test_unexplained_extra_calls_fail(self):
        with self.assertRaisesRegex(AssertionError, "extra="):
            module.check_operation_occurrences(self.operation, [self.row] * 3)

    def test_other_keys_cannot_satisfy_a_missing_occurrence(self):
        for field, value in [("line", 2), ("callee", "other"), ("value", 1),
                             ("caller_class", "chunks/app.jsx#save")]:
            with self.subTest(field=field), self.assertRaises(AssertionError):
                module.check_operation_occurrences(self.operation, [self.row, {**self.row, field: value}])

    def test_file_and_lexical_owner_spellings_share_the_same_call_location(self):
        for owner in ["chunks/app.js", "chunks/app.js#save@1:1", "chunks/app.js.Widget"]:
            with self.subTest(owner=owner):
                row = {**self.row, "caller_class": owner}
                self.assertEqual(module.check_operation_occurrences(self.operation, [row, row]), 2)

    def test_literal_json_types_are_not_conflated(self):
        operation = {**self.operation, "value": 1}
        with self.assertRaises(AssertionError):
            module.check_operation_occurrences(operation, [{**self.row, "value": True}] * 2)


if __name__ == "__main__":
    unittest.main()
