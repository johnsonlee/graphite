import importlib.util
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location("verify_affected", Path(__file__).with_name("verify-affected.py"))
affected = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(affected)


class AffectedPlanTest(unittest.TestCase):
    def test_a_storage_change_reaches_every_crate_that_reads_graphs(self):
        crates, modules, web = affected.plan(["backend/storage/src/graph.rs"])
        self.assertEqual({"graphite-storage", "graphite-cypher", "graphite-explore", "graphite-cli", "graphite-tck"}, crates)
        self.assertEqual(set(), modules)
        self.assertFalse(web)

    def test_a_jvm_module_change_reaches_its_dependents_only(self):
        _, modules, _ = affected.plan(["frontend/jvm/cypher/src/main/kotlin/X.kt"])
        self.assertEqual({"cypher", "webgraph", "query", "explore"}, modules)
        _, modules, _ = affected.plan(["frontend/jvm/ir/src/main/kotlin/X.kt"])
        self.assertEqual({"ir", "query", "explore"}, modules)

    def test_docs_need_nothing_and_build_files_need_everything(self):
        self.assertEqual((set(), set(), False), affected.plan(["docs/webgraph-storage.md", "README.md"]))
        crates, modules, web = affected.plan(["Cargo.lock"])
        self.assertIn("graphite-benchmark", crates)
        self.assertEqual(set(affected.GRADLE_DEPENDENTS), modules)
        self.assertTrue(web)

    def test_all_checks_everything_even_with_no_changed_paths(self):
        crates, modules, web = affected.plan([], everything=True)
        self.assertEqual(crates, set(affected.CARGO_DEPENDENTS) | {c for d in affected.CARGO_DEPENDENTS.values() for c in d})
        self.assertEqual(modules, set(affected.GRADLE_DEPENDENTS))
        self.assertTrue(web)
        self.assertEqual([name for name, _ in affected.commands(crates, modules, web)], ["rust", "jvm", "web"])
        self.assertEqual(affected.plan([]), (set(), set(), False))

    def test_groups_run_rust_jvm_and_web_side_by_side(self):
        groups = affected.commands({"graphite-cli"}, {"query"}, True)
        self.assertEqual(["rust", "jvm", "web"], [name for name, _ in groups])
        self.assertEqual(["cargo", "test", "--locked", "-p", "graphite-cli"], groups[0][1][1])
        self.assertEqual(["./gradlew", ":query:test", "--continue"], groups[1][1][0])


if __name__ == "__main__":
    unittest.main()
