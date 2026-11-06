"""Source-integrity and build-contract tests; no timing claims or real compiler work."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

HERE = Path(__file__).parent
SPEC = importlib.util.spec_from_file_location("prepare_apple_corpus", HERE / "prepare-apple-corpus.py")
prepare = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(prepare)


class Preparation(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="apple corpus ")
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.root = self.directory / "source"
        self.root.mkdir()
        self.git("init", "--quiet")
        (self.root / "Package.swift").write_text("// pinned package\n")
        self.git("add", "Package.swift")
        self.git("-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "--quiet", "-m", "fixture")
        self.manifest = {"schema": "graphite-apple-frontend-corpus-v2", "kind": "real-source", "label": "fixture",
                         "repository": "https://example.invalid/real-source.git", "commit": self.git("rev-parse", "HEAD"), "input": "package"}
        self.lock = self.directory / "pinned.resolved"
        self.lock.write_text('{"pins": []}\n')
        self.manifest["dependencyLock"] = {"path": self.lock.name, "sha256": hashlib.sha256(self.lock.read_bytes()).hexdigest()}
        self.manifest_path = self.directory / "manifest.json"
        self.log = self.directory / "prepare.log"
        self.bin = self.directory / "bin"
        self.bin.mkdir()
        swift = self.bin / "swift"
        swift.write_text('''#!/usr/bin/env python3
import json, pathlib, sys
if sys.argv[1:] == ["--version"]:
    print("Swift test compiler")
else:
    root = pathlib.Path(sys.argv[sys.argv.index("--package-path") + 1])
    (root / "arguments.json").write_text(json.dumps(sys.argv[1:]))
    (root / ".build/debug/index/store").mkdir(parents=True, exist_ok=True)
''')
        swift.chmod(0o755)

    def git(self, *args):
        return subprocess.check_output(["git", "-C", str(self.root), *args], text=True).strip()

    def run_prepare(self, verify_only=False):
        self.manifest_path.write_text(json.dumps(self.manifest))
        with patch.dict(os.environ, {"PATH": str(self.bin) + os.pathsep + os.environ["PATH"]}):
            prepare.prepare(self.manifest_path, self.root, None, self.log, verify_only=verify_only)

    def test_build_uses_pinned_dependencies_and_forces_indexing(self):
        self.run_prepare()
        arguments = json.loads((self.root / "arguments.json").read_text())
        self.assertEqual(arguments, ["build", "--package-path", str(self.root), "--jobs", "4", "-c", "debug", "--enable-index-store", "--force-resolved-versions"])
        self.assertEqual((self.root / "Package.resolved").read_bytes(), self.lock.read_bytes())
        self.assertEqual(json.loads(self.log.with_suffix(".toolchain.json").read_text())["swift"], "Swift test compiler")
        (self.root / "arguments.json").unlink()
        self.run_prepare(verify_only=True)
        self.assertFalse((self.root / "arguments.json").exists(), "a cache verification must not compile again")

    def test_dirty_source_is_rejected_before_compilation(self):
        (self.root / "Package.swift").write_text("// changed source\n")
        with self.assertRaisesRegex(ValueError, "tracked changes"):
            self.run_prepare()
        self.assertFalse((self.root / "arguments.json").exists())

    def test_wrong_checkout_and_wrong_dependency_lock_are_rejected(self):
        self.manifest["commit"] = "a" * 40
        with self.assertRaisesRegex(ValueError, "commit pin"):
            self.run_prepare()
        self.manifest["commit"] = self.git("rev-parse", "HEAD")
        self.lock.write_text("changed\n")
        with self.assertRaisesRegex(ValueError, "manifest digest"):
            self.run_prepare()
        self.assertFalse((self.root / "arguments.json").exists())

    def test_missing_cached_store_and_changed_cached_lock_are_rejected(self):
        self.run_prepare()
        (self.root / ".build/debug/index/store").rmdir()
        with self.assertRaisesRegex(ValueError, "index store is missing"):
            self.run_prepare(verify_only=True)
        (self.root / "Package.resolved").write_text("changed\n")
        with self.assertRaisesRegex(ValueError, "dependency pin"):
            self.run_prepare(verify_only=True)

    def test_generated_corpus_and_escaping_paths_are_rejected(self):
        self.manifest["generator"] = {"script": "generate-apple-corpus.py"}
        with self.assertRaisesRegex(ValueError, "real-source"):
            self.run_prepare()
        del self.manifest["generator"]
        self.manifest["dependencyLock"]["path"] = "../outside"
        with self.assertRaisesRegex(ValueError, "inside its root"):
            self.run_prepare()

    def test_workspace_recipe_preserves_requested_scope_and_build_options(self):
        manifest = {"input": "xcodeproj", "project": "Signal.xcworkspace", "scheme": "Signal"}
        command = prepare.build_command(manifest, self.root, self.directory / "derived data", 4)
        self.assertEqual(command, ["xcodebuild", "-workspace", str(self.root / "Signal.xcworkspace"), "-scheme", "Signal",
                                  "-configuration", "Debug", "-derivedDataPath", str(self.directory / "derived data"),
                                  "-destination", "generic/platform=iOS Simulator", "-jobs", "4", "build",
                                  "COMPILER_INDEX_STORE_ENABLE=YES", "CODE_SIGNING_ALLOWED=NO", "CODE_SIGNING_REQUIRED=NO", "CODE_SIGN_IDENTITY="])
        with self.assertRaisesRegex(ValueError, "--derived-data"):
            prepare.build_command(manifest, self.root, None, 4)


if __name__ == "__main__":
    unittest.main()
