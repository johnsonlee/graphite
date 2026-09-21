"""`generate-apple-corpus.py` writes the same corpus for the same manifest, in both layouts.

Run with `python3 -m unittest discover -s backend/bench -p 'test_*.py'`; no Swift toolchain
is needed: the cases check the plan, the text and the project files, not a build.
"""

import importlib.util
import json
import pathlib
import re
import subprocess
import sys
import tempfile
import unittest

HERE = pathlib.Path(__file__).resolve().parent


def load(name):
    spec = importlib.util.spec_from_file_location(name.replace("-", "_"), HERE / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


generator = load("generate-apple-corpus")


class Plan(unittest.TestCase):
    def test_files_are_spread_over_layered_modules(self):
        corpus = generator.Corpus(files=40, modules=4, seed=1, layout="package", name="Demo")
        # 40 files: 4 support files, one entry point, 35 features.
        self.assertEqual(corpus.counts, [9, 9, 9, 8])
        self.assertEqual(corpus.layer, [0, 0, 1, 1])
        self.assertEqual(corpus.dependencies, [[], [], [0, 1], [0, 1]])
        self.assertEqual(corpus.entry_targets(), [(2, 0), (2, 1), (3, 0), (3, 1)])
        # A file sees its own module and the layer before it, never a later one.
        self.assertEqual({d for d, _ in corpus.visible(2)}, {0, 1, 2})
        self.assertEqual({d for d, _ in corpus.visible(0)}, {0})

    def test_impossible_plans_are_refused(self):
        with self.assertRaises(ValueError):
            generator.Corpus(files=3, modules=3, seed=1, layout="package", name="Demo")
        with self.assertRaises(ValueError):
            generator.Corpus(files=9, modules=0, seed=1, layout="package", name="Demo")
        with self.assertRaises(ValueError):
            generator.Corpus(files=9, modules=1, seed=1, layout="workspace", name="Demo")


class Rendering(unittest.TestCase):
    def files(self, layout, files=24, modules=3, seed=7):
        return generator.render(generator.Corpus(files=files, modules=modules, seed=seed, layout=layout, name="Demo"))

    def test_the_corpus_is_a_function_of_its_parameters(self):
        self.assertEqual(self.files("package"), self.files("package"))
        self.assertNotEqual(self.files("package"), self.files("package", seed=8))
        self.assertNotEqual(self.files("package"), self.files("xcodeproj"))

    def test_the_package_layout_holds_every_file_and_a_layered_manifest(self):
        files = self.files("package")
        swift = [path for path in files if path.endswith(".swift") and path != "Package.swift"]
        self.assertEqual(len(swift), 24)
        self.assertIn("Sources/Demo/main.swift", files)
        self.assertIn("Sources/Module02/M02Support.swift", files)
        manifest = files["Package.swift"]
        self.assertIn('.target(name: "Module00", dependencies: []),', manifest)
        self.assertIn('.target(name: "Module02", dependencies: ["Module00", "Module01"]),', manifest)
        self.assertIn('.executableTarget(name: "Demo", dependencies: ["Module02"]),', manifest)
        # A file imports exactly the modules its module depends on, and calls only what it sees.
        feature = files["Sources/Module02/M02Feature000.swift"]
        self.assertEqual(re.findall(r"^import (\w+)$", feature, re.M), ["Foundation", "Module00", "Module01"])
        self.assertEqual(set(re.findall(r"\bM(\d\d)Service\d\d\d\b", feature)) - {"00", "01", "02"}, set())
        self.assertEqual(re.findall(r"^import (\w+)$", files["Sources/Module00/M00Feature000.swift"], re.M), ["Foundation"])
        self.assertNotIn("M01Service", files["Sources/Module00/M00Feature000.swift"])
        # Every service has the shared API the calls rely on.
        for method in ["func load(_ key: String, limit: Int) -> Int", "func store(_ key: String, value: Int) -> Bool",
                       "func describe() -> String", "func validate(_ item: M02Item000) -> Bool",
                       "func compute(_ base: Double, _ factor: Double) -> Double", "func flush()",
                       "func refresh(after delay: Int) -> Int", "private func normalize(_ raw: String) -> String"]:
            self.assertIn(method, feature)
        self.assertIn("@available(iOS 13.0, macOS 10.15, *)", feature)
        self.assertIn("public final class M02Log", files["Sources/Module02/M02Support.swift"])

    def test_the_xcodeproj_layout_references_every_file_from_one_app_target(self):
        files = self.files("xcodeproj")
        swift = [path for path in files if path.endswith(".swift")]
        self.assertEqual(len(swift), 24)
        self.assertIn("Demo/AppDelegate.swift", files)
        self.assertIn("@main", files["Demo/AppDelegate.swift"])
        self.assertIn("UIApplicationDelegate", files["Demo/AppDelegate.swift"])
        # One module, so no module imports.
        self.assertEqual(re.findall(r"^import (\w+)$", files["Demo/Module02/M02Feature000.swift"], re.M), ["Foundation"])
        project = files["Demo.xcodeproj/project.pbxproj"]
        self.assertTrue(project.startswith("// !$*UTF8*$!\n"))
        for path in swift:
            base = path.rsplit("/", 1)[-1]
            self.assertIn(f"/* {base} in Sources */,", project)
            self.assertIn(f"lastKnownFileType = sourcecode.swift; path = {base};", project)
        self.assertEqual(project.count("isa = PBXBuildFile;"), 24)
        # Every basename is unique: Xcode keys a target's per-file outputs by basename.
        basenames = [path.rsplit("/", 1)[-1] for path in swift]
        self.assertEqual(len(basenames), len(set(basenames)))
        self.assertEqual(project.count("isa = PBXFileReference;"), 25)
        for group in ["Module00", "Module01", "Module02"]:
            self.assertIn(f"path = {group};", project)
        self.assertIn('productType = "com.apple.product-type.application";', project)
        self.assertIn("SDKROOT = iphoneos;", project)
        self.assertIn("IPHONEOS_DEPLOYMENT_TARGET = 16.0;", project)
        # Object ids are 24 hex digits, unique.
        ids = re.findall(r"^\t\t([0-9A-F]+) ", project, re.M)
        # 24 build files, 25 file references, 6 groups (root, app, products, 3 modules), target, project, 3 phases, 4 configurations, 2 lists.
        self.assertEqual(len(ids), 24 + 25 + 6 + 1 + 1 + 3 + 4 + 2)
        self.assertEqual(len(ids), len(set(ids)))
        self.assertTrue(all(len(i) == 24 for i in ids), ids[:3])
        scheme = files["Demo.xcodeproj/xcshareddata/xcschemes/Demo.xcscheme"]
        self.assertIn(f'BlueprintIdentifier = "{generator.pbx_id("D", 1)}"', scheme)
        self.assertIn('BuildableName = "Demo.app"', scheme)
        self.assertIn('ReferencedContainer = "container:Demo.xcodeproj"', scheme)


class Manifests(unittest.TestCase):
    def test_the_pinned_manifests_describe_the_corpora_they_pin(self):
        for name in ["apple-frontend-corpus-large.json", "apple-frontend-corpus-xcode.json"]:
            with self.subTest(manifest=name):
                manifest = json.loads((HERE / name).read_text())
                corpus = generator.corpus_from_manifest(manifest)
                self.assertEqual(corpus.files, manifest["files"])
                self.assertEqual(corpus.layout, manifest["generator"]["layout"])
                self.assertEqual(manifest["input"], "package" if corpus.layout == "package" else "xcodeproj")
                files = generator.render(corpus)
                swift = sum(1 for path in files if path.endswith(".swift") and path != "Package.swift")
                self.assertEqual(swift, manifest["files"])
        large = json.loads((HERE / "apple-frontend-corpus-large.json").read_text())
        xcode = json.loads((HERE / "apple-frontend-corpus-xcode.json").read_text())
        # The same sources in both layouts.
        self.assertEqual({k: v for k, v in large["generator"].items() if k != "layout"},
                         {k: v for k, v in xcode["generator"].items() if k != "layout"})

    def test_a_manifest_without_a_matching_generator_is_refused(self):
        with self.assertRaises(ValueError):
            generator.corpus_from_manifest({"label": "x"})
        with self.assertRaises(ValueError):
            generator.corpus_from_manifest({"generator": {"script": "other.py", "files": 9, "modules": 1, "seed": 1, "layout": "package", "name": "D"}})
        with self.assertRaises(ValueError):
            generator.corpus_from_manifest({"files": 10, "generator": {"script": generator.SCRIPT, "files": 9, "modules": 1, "seed": 1, "layout": "package", "name": "D"}})


class CommandLine(unittest.TestCase):
    def run_generator(self, *arguments):
        return subprocess.run([sys.executable, str(HERE / "generate-apple-corpus.py"), *arguments], capture_output=True, text=True)

    def test_writes_the_corpus_and_refuses_a_non_empty_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            out = pathlib.Path(directory) / "corpus"
            manifest = pathlib.Path(directory) / "corpus.json"
            manifest.write_text(json.dumps({"files": 12, "generator": {"script": generator.SCRIPT, "seed": 3, "files": 12, "modules": 2, "layout": "package", "name": "Demo"}}))
            result = self.run_generator("--manifest", str(manifest), "--out", str(out))
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn("Demo (package): 12 Swift files over 2 modules, seed 3", result.stdout)
            self.assertEqual(sum(1 for _ in out.rglob("*.swift")) - 1, 12)
            written = {p.relative_to(out).as_posix(): p.read_text() for p in out.rglob("*") if p.is_file()}
            self.assertEqual(written, generator.render(generator.Corpus(12, 2, 3, "package", "Demo")))
            again = self.run_generator("--manifest", str(manifest), "--out", str(out))
            self.assertEqual(again.returncode, 2)
            self.assertIn("is not empty", again.stderr)
            explicit = self.run_generator("--files", "12", "--modules", "2", "--seed", "3", "--layout", "xcodeproj", "--name", "Demo", "--out", str(out) + "-x")
            self.assertEqual(explicit.returncode, 0, explicit.stderr)
            self.assertTrue((pathlib.Path(str(out) + "-x") / "Demo.xcodeproj" / "project.pbxproj").exists())
            missing = self.run_generator("--files", "12", "--out", str(out) + "-y")
            self.assertEqual(missing.returncode, 2)
            self.assertIn("missing --modules, --seed, --layout, --name", missing.stderr)
            both = self.run_generator("--manifest", str(manifest), "--seed", "1", "--out", str(out) + "-z")
            self.assertEqual(both.returncode, 2)
            bad = pathlib.Path(directory) / "bad.json"
            bad.write_text(json.dumps({"label": "x"}))
            self.assertEqual(self.run_generator("--manifest", str(bad), "--out", str(out) + "-w").returncode, 2)


if __name__ == "__main__":
    unittest.main()
