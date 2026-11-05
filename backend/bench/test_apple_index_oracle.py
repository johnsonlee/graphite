import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("verification", Path(__file__).with_name("verify-apple-index-contexts.py"))
verify = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verify)


class OracleTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.out = Path(self.tmp.name)
        self.expected = {"usrs": ["symbol", "relationOnly"], "sharedUSRs": {}, "files": {}, "sharedCalls": {}, "sharedTypes": {}}
        self.model = {"usrs": ["symbol", "relationOnly"]}
        self.shared = {}
        for path, owners in verify.SHARED.items():
            self.expected["sharedUSRs"][path] = self.model["usrs"][:]
            self.expected["files"][path] = {"contexts": {owner: [[3, "digest"]] for owner in owners}}
            self.expected["sharedCalls"][path] = []
            self.expected["sharedTypes"][path] = [[owner, owner + "USR"] for owner in owners]
            self.shared[path] = {"usrs": self.model["usrs"][:], "types": [[owner, owner + "USR"] for owner in owners], "calls": [],
                                 "members": [[owner] for owner in owners]}

    def test_absent_occurrence_block_requires_a_verified_empty_symbol_table(self):
        store = verify.oracle.Store.__new__(verify.oracle.Store)
        store.store = object()
        store.record_reader_create = object()
        store.open = lambda *args: 1
        store.record_reader_dispose = lambda *args: None
        store.record_reader_occurrences_apply_f = lambda *_: False
        store.record_reader_symbols_apply_f = lambda *_: True  # no callback
        self.assertEqual(store.facts("empty"), [])
        store.record_reader_symbols_apply_f = lambda _, _no_cache, context, callback: callback(context, 1)
        with self.assertRaises(verify.oracle.IncompleteTraversal):
            store.facts("nonempty-record-with-unreadable-occurrences")

    def test_each_file_must_have_both_compiler_contexts_and_definitions(self):
        verify.check_model(self.expected, self.model, self.shared, self.out)
        path = next(iter(verify.SHARED))
        self.shared[path]["types"].pop()
        with self.assertRaisesRegex(ValueError, "production model"):
            verify.check_model(self.expected, self.model, self.shared, self.out)
        self.assertIn(path + ":types", json.loads((self.out / "oracle-model-differences.json").read_text()))

    def test_reference_elsewhere_cannot_mask_a_missing_relation_usr(self):
        path = next(iter(verify.SHARED))
        self.shared[path]["usrs"].remove("relationOnly")
        with self.assertRaisesRegex(ValueError, "production model"):
            verify.check_model(self.expected, self.model, self.shared, self.out)
        diff = json.loads((self.out / "oracle-model-differences.json").read_text())
        self.assertEqual(diff[path]["missingFromModel"], ["relationOnly"])

    def test_callee_at_same_location_cannot_hide_the_second_caller(self):
        path = next(iter(verify.SHARED))
        owners = verify.SHARED[path]
        self.expected["sharedCalls"][path] = [[m, m + "caller", "callee", path, "36", "10"] for m in owners]
        self.shared[path]["calls"] = [[owners[0], owners[0] + "caller", "callee", "false", path, "36", "10"]]
        with self.assertRaisesRegex(ValueError, "production model"):
            verify.check_model(self.expected, self.model, self.shared, self.out)
        diff = json.loads((self.out / "oracle-model-differences.json").read_text())
        self.assertEqual(diff[path + ":calls"]["missing"], [self.expected["sharedCalls"][path][1]])


if __name__ == "__main__":
    unittest.main()
