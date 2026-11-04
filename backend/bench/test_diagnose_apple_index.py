import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("diagnosis", Path(__file__).with_name("diagnose-apple-index.py"))
diagnosis = importlib.util.module_from_spec(spec)
spec.loader.exec_module(diagnosis)


class IndexComparisonTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.path = Path(self.tmp.name)

    def fixture(self, label, *, changed=False):
        out = self.path / label
        root = out / "root"
        root.mkdir(parents=True)
        path = root / "Input.swift"
        empty = root / "Empty.h"
        path.write_text("let value = 1\n")
        empty.touch()
        diagnosis.save(out / "selected-paths.json", [str(path), str(empty)])
        row = {"file": str(path), "path": str(path), "line": 1, "utf8Column": 5,
               "symbol": {"usr": "s:main"}, "roles": 2,
               "relations": [{"symbol": {"usr": "s:owner"}, "roles": 1},
                             {"symbol": {"usr": "s:new" if changed else "s:old"}, "roles": 2}]}
        if label == "workspace":
            row["relations"].reverse()
        with (out / "occurrences.jsonl").open("w") as output:
            for _ in range(2):
                print(json.dumps(row), file=output)
        self.assertEqual(diagnosis.source_summary(out, root), (2, 3))
        diagnosis.save(out / "ir-strings.json", ["value"] * (2 if changed else 1))
        diagnosis.save(out / "observed.json", {"dumpFiles": 2, "dumpUSRs": 3})
        return out

    def test_equal_checkout_facts_preserve_empty_selected_header_and_multiplicity(self):
        left, right = self.fixture("temp"), self.fixture("workspace")
        summary = json.loads((left / "selected-files.json").read_text())
        self.assertEqual(summary["Empty.h"]["occurrences"], 0)
        self.assertEqual(summary["Input.swift"]["occurrences"], 2)
        difference = diagnosis.compare(left, right)
        self.assertEqual(difference["changedFiles"], {})
        self.assertEqual(difference["usrs"], {"onlyTemp": {}, "onlyWorkspace": {}})

    def test_relation_only_usr_changes_and_duplicate_wire_strings_remain_visible(self):
        difference = diagnosis.compare(self.fixture("temp"), self.fixture("workspace", changed=True))
        self.assertEqual(set(difference["changedFiles"]), {"Input.swift"})
        self.assertEqual(difference["usrs"], {"onlyTemp": {"s:old": 1}, "onlyWorkspace": {"s:new": 1}})
        self.assertEqual(difference["irStrings"], {"onlyTemp": {}, "onlyWorkspace": {"value": 1}})

    def test_decode_wire_strings_preserves_indices_and_utf8(self):
        ir = self.path / "sample.graphite-ir"
        ir.write_bytes(b'\x0a\x12\x08\x0a\x01a\x0a\x03' + "값".encode())
        self.assertEqual(list(diagnosis.strings_in_ir(ir)), ["a", "값"])


if __name__ == "__main__":
    unittest.main()
