import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("check_coverage", Path(__file__).with_name("check-coverage.py"))
coverage = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(coverage)


class CoverageGateTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.paths = [self.write(module) for module in coverage.MODULES]

    def write(self, module, counters='<counter type="LINE" missed="0" covered="100"/>', name=None):
        path = self.root / "frontend/jvm" / module / "build/reports/kover/report.xml"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(f'<report name="{coverage.REPORT_PREFIX}{name or module}">{counters}</report>')
        return path

    def invoke(self):
        return subprocess.run(
            [sys.executable, str(Path(__file__).with_name("check-coverage.py")), "--root", str(self.root)],
            capture_output=True, text=True, check=False,
        )

    def test_exact_boundary_and_unrounded_below_boundary(self):
        rows = coverage.read_reports(self.paths)
        self.assertEqual(list(coverage.MODULES), [row["module"] for row in rows])
        self.assertTrue(all(row["passed"] and row["percent"] == 100 for row in rows))
        self.assertEqual(0, self.invoke().returncode)
        self.write("core", '<counter type="LINE" missed="1" covered="9999"/>')
        row = coverage.read_reports(self.paths)[0]
        self.assertFalse(row["passed"])
        self.assertEqual(99.99, row["percent"])
        self.assertEqual(1, self.invoke().returncode)
        self.write("core", '<counter type="LINE" missed="1" covered="999999"/>')
        row = coverage.read_reports(self.paths)[0]
        self.assertEqual("100.00", f'{row["percent"]:.2f}')
        self.assertFalse(row["passed"], "A percentage displayed as 100.00 still fails with an uncovered line")
        self.assertEqual(1, self.invoke().returncode)
        self.write("core", '<counter type="LINE" missed="0" covered="1"/>')
        self.assertEqual(100, coverage.read_reports(self.paths)[0]["percent"])
        self.assertEqual(0, self.invoke().returncode)

    def test_missing_empty_and_malformed_reports_fail(self):
        for text in ("", "console format changed", "<report>", '<report name="changed"><counter type="LINE" covered="100" missed="0"/></report>'):
            with self.subTest(text=text):
                self.paths[0].write_text(text)
                self.assertEqual(1, self.invoke().returncode)
        self.paths[0].unlink()
        self.assertIn("Missing coverage modules: core", self.invoke().stderr)
        self.assertEqual(1, self.invoke().returncode)
        with self.assertRaisesRegex(coverage.CoverageError, "Missing coverage modules"):
            coverage.read_reports([])

    def test_aggregate_counter_is_required_unique_and_not_replaced_by_class_counter(self):
        for counters in (
            "", '<package><counter type="LINE" missed="0" covered="100"/></package>',
            '<counter type="LINE" missed="0" covered="100"/>' * 2,
        ):
            with self.subTest(counters=counters):
                self.write("core", counters)
                with self.assertRaisesRegex(coverage.CoverageError, "exactly one"):
                    coverage.read_reports(self.paths)
        self.write("core", '<package><counter type="LINE" missed="100" covered="0"/></package><counter type="LINE" missed="0" covered="100"/>')
        self.assertTrue(coverage.read_reports(self.paths)[0]["passed"])

    def test_invalid_counts_and_zero_denominator_fail(self):
        for missed, covered in (("0", "0"), ("-1", "100"), ("1.5", "100"), ("0", "NaN"), ("", "100"), ("0", "1e2"), ("0", "Infinity")):
            with self.subTest(missed=missed, covered=covered):
                self.write("core", f'<counter type="LINE" missed="{missed}" covered="{covered}"/>')
                with self.assertRaises(coverage.CoverageError):
                    coverage.read_reports(self.paths)
        self.write("core", '<counter type="LINE" covered="100"/>')
        self.assertEqual(1, self.invoke().returncode)

    def test_unknown_duplicate_and_misplaced_modules_fail(self):
        self.write("extra")
        self.assertEqual(1, self.invoke().returncode)
        self.assertIn("Unknown coverage module", self.invoke().stderr)
        with self.assertRaisesRegex(coverage.CoverageError, "Duplicate coverage module"):
            coverage.read_reports([self.paths[0], self.paths[0]])
        self.write("core", name="cypher")
        with self.assertRaisesRegex(coverage.CoverageError, "wrong module directory"):
            coverage.read_reports(self.paths)

    def test_invalid_evidence_clears_an_old_summary(self):
        summary = self.root / "coverage.json"
        summary.write_text('{"threshold":98,"modules":[]}')
        self.paths[0].unlink()
        result = subprocess.run(
            [sys.executable, str(Path(__file__).with_name("check-coverage.py")), "--root", str(self.root), "--summary", str(summary)],
            capture_output=True, text=True, check=False,
        )
        self.assertEqual(1, result.returncode)
        self.assertFalse(summary.exists())

    def test_validated_summary_is_written_even_for_insufficient_coverage(self):
        import json
        summary = self.root / "coverage.json"
        self.write("query", '<counter type="LINE" missed="1" covered="0"/>')
        result = subprocess.run(
            [sys.executable, str(Path(__file__).with_name("check-coverage.py")), "--root", str(self.root), "--summary", str(summary)],
            capture_output=True, text=True, check=False,
        )
        self.assertEqual(1, result.returncode)
        data = json.loads(summary.read_text())
        self.assertEqual(100, data["threshold"])
        row = next(row for row in data["modules"] if row["module"] == "query")
        self.assertEqual({"module": "query", "missed": 1, "covered": 0, "total": 1, "percent": 0, "passed": False}, row)


if __name__ == "__main__":
    unittest.main()
