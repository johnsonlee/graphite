import importlib.util
import pathlib
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("profile_run", pathlib.Path(__file__).with_name("run.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class SummaryTests(unittest.TestCase):
    @staticmethod
    def fork(offset):
        # Numbers only test statistical bookkeeping, not a graph or performance claim.
        return [{"id": f"query-{index}", "latencyNanos": str(offset + index * 1000)}
                for index in range(24)]

    def test_no_percentile_from_a_single_workload_pass(self):
        results = module.summarize([self.fork(1)])
        self.assertTrue(all(row["empiricalP95LatencyNanos"] is None for row in results))

    def test_nearest_rank_stays_within_each_query(self):
        results = module.summarize([self.fork(index) for index in range(20, 0, -1)])
        self.assertEqual(19, results[0]["empiricalP95LatencyNanos"])
        self.assertEqual(23019, results[23]["empiricalP95LatencyNanos"])
        self.assertEqual(list(range(20, 0, -1)), results[0]["latencyNanosInForkOrder"])

    def test_v2_preserves_all_26_queries_and_rejects_missing_additions(self):
        forks = [self.fork(index) + [
            {"id": "or-four-broad-rows", "latencyNanos": str(24000 + index)},
            {"id": "or-four-broad-distinct", "latencyNanos": str(25000 + index)},
        ] for index in range(1, 21)]
        results = module.summarize(forks, expected_count=26)
        self.assertEqual(26, len(results))
        self.assertEqual(25019, results[-1]["empiricalP95LatencyNanos"])
        with self.assertRaises(ValueError):
            module.summarize([fork[:24] for fork in forks], expected_count=26)

    def test_v3_keeps_each_of_36_queries_and_rejects_older_catalog_size(self):
        forks = [[{"id": f"query-{query}", "latencyNanos": str(query * 1000 + run)}
                  for query in range(36)] for run in range(1, 21)]
        results = module.summarize(forks, expected_count=36)
        self.assertEqual(36, len(results))
        self.assertEqual(35019, results[-1]["empiricalP95LatencyNanos"])
        with self.assertRaises(ValueError):
            module.summarize([fork[:26] for fork in forks], expected_count=36)

    def test_reject_incomplete_reordered_or_nonpositive_samples(self):
        for forks in ([], [self.fork(1)[:-1]],
                      [self.fork(1), list(reversed(self.fork(2)))], [self.fork(0)]):
            with self.subTest(forks=forks), self.assertRaises(ValueError):
                module.summarize(forks)

    def test_graph_bytes_are_verified_even_when_manifest_is_unchanged(self):
        # Tiny files exercise identity bookkeeping only, not graph execution.
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            lines = []
            for index in range(64):
                directory = root / str(index)
                directory.mkdir()
                (directory / "content").write_bytes(b"original")
                lines.append(f"graph-{index}\t{directory}\tzero\ttarget\tdense\tidentity-{index}")
            manifest = root / "graphs.tsv"
            manifest.write_text("\n".join(lines))
            before = module.graph_identity(manifest)
            (root / "63" / "content").write_bytes(b"modified")
            after = module.graph_identity(manifest)
            self.assertNotEqual(before, after)
            self.assertEqual(before[:-1], after[:-1])
            (root / "63" / "link").symlink_to(root / "0" / "content")
            with self.assertRaisesRegex(ValueError, "symlink"):
                module.graph_identity(manifest)


if __name__ == "__main__":
    unittest.main()
