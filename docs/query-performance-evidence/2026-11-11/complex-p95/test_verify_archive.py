"""Archive integrity and extraction safety must survive python -O."""
import hashlib
import io
import json
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
import unittest


class VerifyArchiveTest(unittest.TestCase):
    def fixture(self, root, entries):
        archive = root / "evidence.tar.gz"
        rows = []
        with tarfile.open(archive, "w:gz") as tar:
            for name, kind in entries:
                body = b"retained evidence\n"
                entry = tarfile.TarInfo(name)
                entry.type = kind
                entry.size = len(body) if kind == tarfile.REGTYPE else 0
                entry.linkname = "../outside"
                tar.addfile(entry, io.BytesIO(body) if entry.isfile() else None)
                rows.append({"path": name, "bytes": len(body),
                             "sha256": hashlib.sha256(body).hexdigest()})
        return archive, {"archive": archive.name, "archiveBytes": archive.stat().st_size,
                         "archiveSha256": hashlib.sha256(archive.read_bytes()).hexdigest(),
                         "files": rows}

    def run_verifier(self, root, archive, manifest, optimized):
        manifest_path = root / "manifest.json"
        manifest_path.write_text(json.dumps(manifest))
        return subprocess.run([sys.executable, *(["-O"] if optimized else []),
                               str(Path(__file__).with_name("verify_archive.py")),
                               "--manifest", str(manifest_path), "--archive", str(archive),
                               "--extract", str(root / "extracted")],
                              capture_output=True, text=True)

    def test_valid_archive_extracts_exact_bytes(self):
        for optimized in (False, True):
            with self.subTest(optimized=optimized), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                archive, manifest = self.fixture(root, [("nested/body.json", tarfile.REGTYPE)])
                result = self.run_verifier(root, archive, manifest, optimized)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(json.loads(result.stdout)["verifiedFiles"], 1)
                self.assertEqual((root / "extracted/nested/body.json").read_bytes(), b"retained evidence\n")

    def test_integrity_failures_are_rejected_even_when_optimized(self):
        cases = {
            "archive_hash": "Archive SHA256 mismatch", "archive_size": "Archive size mismatch",
            "member_hash": "Member SHA256 mismatch", "member_size": "Member size mismatch",
            "duplicate_manifest": "Duplicate manifest member", "missing": "Missing archive members",
            "unexpected": "Unexpected archive member", "nonempty": "Extraction requires an empty directory",
        }
        for optimized in (False, True):
            for case, message in cases.items():
                with self.subTest(case=case, optimized=optimized), tempfile.TemporaryDirectory() as tmp:
                    root = Path(tmp)
                    archive, manifest = self.fixture(root, [("body.json", tarfile.REGTYPE)])
                    if case == "archive_hash": manifest["archiveSha256"] = "0" * 64
                    elif case == "archive_size": manifest["archiveBytes"] += 1
                    elif case == "member_hash": manifest["files"][0]["sha256"] = "0" * 64
                    elif case == "member_size": manifest["files"][0]["bytes"] += 1
                    elif case == "duplicate_manifest": manifest["files"].append(dict(manifest["files"][0]))
                    elif case == "missing": manifest["files"].append({**manifest["files"][0], "path": "absent"})
                    elif case == "unexpected": manifest["files"] = []
                    elif case == "nonempty":
                        (root / "extracted").mkdir()
                        (root / "extracted/sentinel").write_text("keep")
                    result = self.run_verifier(root, archive, manifest, optimized)
                    self.assertNotEqual(result.returncode, 0)
                    self.assertIn(message, result.stderr)
                    self.assertNotIn('"verifiedFiles"', result.stdout)
                    if case == "nonempty": self.assertEqual((root / "extracted/sentinel").read_text(), "keep")

    def test_malicious_members_never_escape_destination(self):
        for optimized in (False, True):
            for case in ("traversal", "absolute", "symlink", "hardlink", "duplicate"):
                with self.subTest(case=case, optimized=optimized), tempfile.TemporaryDirectory() as tmp:
                    root = Path(tmp)
                    name = "../outside" if case == "traversal" else str(root / "outside") if case == "absolute" else "body.json"
                    kind = tarfile.SYMTYPE if case == "symlink" else tarfile.LNKTYPE if case == "hardlink" else tarfile.REGTYPE
                    entries = [(name, kind)] * (2 if case == "duplicate" else 1)
                    archive, manifest = self.fixture(root, entries)
                    if case == "duplicate": manifest["files"] = manifest["files"][:1]
                    result = self.run_verifier(root, archive, manifest, optimized)
                    self.assertNotEqual(result.returncode, 0)
                    message = "Unsafe archive member path" if case in {"traversal", "absolute"} else "Duplicate archive member" if case == "duplicate" else "Non-regular archive member"
                    self.assertIn(message, result.stderr)
                    self.assertFalse((root / "outside").exists())
                    self.assertNotIn('"verifiedFiles"', result.stdout)


if __name__ == "__main__":
    unittest.main()
