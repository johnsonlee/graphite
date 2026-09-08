"""Fabricated-clock unit tests only: these never establish benchmark performance."""
import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("sampling_summary", Path(__file__).with_name("summarize.py"))
S = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(S)


def inputs(n=200, cases=2):
    workload = {"cases": [{"id": f"case-{i}", "query": f"RETURN {i}"} for i in range(cases)]}
    controller = {"schemaVersion": 1, "workload": {"path": "workload.json", "sha256": "a" * 64},
                  "graphManifestSHA256": "b" * 64, "states": list(S.STATES),
                  "measurementAcceptanceEligible": True, "limitations": [], "trials": []}
    documents = {}
    for state in S.STATES:
        for sample in range(n):
            tid = f"{state}-{sample}"
            controller["trials"].append({"id": tid, "state": state, "recordsPath": tid + ".json",
                                         "recordsSHA256": "c" * 64, "verificationPassed": True,
                                         "complete": True, "serialExecutionVerified": True})
            rows = []
            for i, case in enumerate(workload["cases"]):
                observation = {"outcome": "success", "latencyNanos": sample + 1, "censoredTimeout": False,
                               "validationError": None, "error": None, "message": None,
                               "messageSource": "observed", "digest": "d" * 64,
                               "rowCount": 1, "responseBytes": 8}
                rows.append({"index": i, "id": case["id"], "querySHA256": S.digest(case["query"].encode()),
                             "main": {**observation, "latencyNanos": (sample + 1) * 10}, "go": observation})
            documents[tid] = {"schemaVersion": 1, "trialId": tid, "state": state,
                              "workloadSHA256": "a" * 64, "graphManifestSHA256": "b" * 64, "records": rows}
    return controller, workload, documents


class StatisticsTests(unittest.TestCase):
    def test_nearest_rank_uses_ceiling_and_does_not_interpolate(self):
        result = S.distribution(list(range(200, 0, -1)))
        self.assertEqual(result, {"sampleCount": 200, "sampleStatus": "sufficient", "minNanos": 1,
                                  "medianNanos": 100.5, "p95Nanos": 190, "maxNanos": 200, "p95Rank": 190})
        self.assertEqual(S.distribution(list(range(1, 202)))["p95Nanos"], 191)
        self.assertEqual(S.distribution([9])["sampleStatus"], "insufficient")
        self.assertIsNone(S.distribution([])["p95Nanos"])

    def test_two_hundred_is_per_case_runtime_and_state(self):
        c, w, d = inputs()
        result = S.summarize(c, w, d)
        self.assertTrue(result["tenfoldP95AcceptanceProven"])
        self.assertEqual(len(result["records"]), 6)
        for row in result["records"]:
            self.assertEqual(row["mainP95OverGoP95"], 10)
            self.assertEqual(row["main"]["distribution"]["p95Nanos"], 1900)
        c["trials"].pop()
        result = S.summarize(c, w, d)
        self.assertFalse(result["tenfoldP95AcceptanceProven"])
        self.assertEqual(result["records"][-1]["sampleStatus"], "insufficient")
        self.assertEqual(result["records"][-1]["go"]["distribution"]["sampleCount"], 199)

    def test_fast_other_cases_cannot_hide_one_slow_case(self):
        c, w, d = inputs()
        for doc in d.values():
            doc["records"][0]["main"]["latencyNanos"] *= 10000
            doc["records"][1]["go"]["latencyNanos"] *= 2
        result = S.summarize(c, w, d)
        self.assertFalse(result["tenfoldP95AcceptanceProven"])
        self.assertEqual([r["thresholdMet"] for r in result["records"]], [True, False] * 3)
        self.assertEqual(result["records"][1]["mainP95OverGoP95"], 5)

    def test_controller_ineligibility_cannot_be_overridden_by_samples(self):
        c, w, d = inputs()
        c["measurementAcceptanceEligible"] = False
        c["limitations"] = ["main metrics differ", "original all-success gate failed"]
        result = S.summarize(c, w, d)
        self.assertTrue(result["integrityPassed"])
        self.assertTrue(all(row["thresholdMet"] for row in result["records"]))
        self.assertFalse(result["measurementAcceptanceEligible"])
        self.assertFalse(result["tenfoldP95AcceptanceProven"])
        self.assertEqual(result["limitations"], c["limitations"])

    def test_censored_timeout_never_becomes_exact_latency(self):
        c, w, d = inputs()
        observation = next(iter(d.values()))["records"][0]["go"]
        observation.update(outcome="timeout", censoredTimeout=True, latencyNanos=60000000000)
        result = S.summarize(c, w, d)
        go = result["records"][0]["go"]
        self.assertEqual(go["sampleCount"], 200)
        self.assertEqual(go["censoredTimeoutCount"], 1)
        self.assertEqual(go["failureCount"], 1)
        self.assertEqual(go["exactSampleCount"], 199)
        self.assertEqual(go["distribution"]["maxNanos"], 200)
        self.assertEqual(go["distribution"]["sampleStatus"], "insufficient")
        self.assertFalse(result["tenfoldP95AcceptanceProven"])

    def test_original_failure_is_retained_as_time_to_failure(self):
        c, w, d = inputs()
        for doc in d.values():
            for runtime in ("main", "go"):
                doc["records"][1][runtime].update(outcome="failed", error="IllegalStateException",
                    message="original failure", messageSource="reference-derived" if runtime == "main" else "observed",
                    digest="java.lang.IllegalStateException", rowCount=0, responseBytes=0)
        result = S.summarize(c, w, d)
        row = result["records"][1]
        self.assertEqual(row["id"], "case-1")
        self.assertEqual(row["main"]["failureCount"], 200)
        self.assertEqual(row["main"]["measurementKind"], "time-to-failure")
        self.assertEqual(row["main"]["timeToFailure"]["p95Nanos"], 1900)
        self.assertEqual(row["main"]["messageSources"], {"reference-derived": 200})
        self.assertFalse(row["successfulCaseP95ThresholdEligible"])
        self.assertFalse(result["tenfoldP95AcceptanceProven"])

    def test_mixed_failure_does_not_mix_failure_and_success_clocks(self):
        c, w, d = inputs()
        next(iter(d.values()))["records"][0]["go"].update(outcome="failed", error="Error", message="x")
        row = S.summarize(c, w, d)["records"][0]
        self.assertEqual(row["go"]["measurementKind"], "mixed-success-and-failure")
        self.assertIsNone(row["go"]["distribution"]["p95Nanos"])
        self.assertEqual(row["go"]["timeToFailure"]["sampleCount"], 1)
        self.assertEqual(row["go"]["successfulLatency"]["sampleCount"], 199)


class IntegrityTests(unittest.TestCase):
    def test_broken_trial_never_silently_changes_case_inventory(self):
        mutations = {
            "missing": lambda c, d: next(iter(d.values()))["records"].pop(),
            "duplicate": lambda c, d: next(iter(d.values()))["records"].append(copy.deepcopy(next(iter(d.values()))["records"][0])),
            "order": lambda c, d: next(iter(d.values()))["records"].reverse(),
            "same-size-duplicate": lambda c, d: next(iter(d.values()))["records"].__setitem__(1, copy.deepcopy(next(iter(d.values()))["records"][0])),
            "query": lambda c, d: next(iter(d.values()))["records"][0].update(querySHA256="0" * 64),
            "manifest": lambda c, d: next(iter(d.values())).update(graphManifestSHA256="0" * 64),
            "workload": lambda c, d: next(iter(d.values())).update(workloadSHA256="0" * 64),
            "state": lambda c, d: next(iter(d.values())).update(state="coldish"),
            "verification": lambda c, d: c["trials"][0].update(verificationPassed=False),
            "incomplete": lambda c, d: c["trials"][0].update(complete=False),
            "overlap": lambda c, d: c["trials"][0].update(serialExecutionVerified=False),
            "duplicate-trial": lambda c, d: c["trials"].append(copy.deepcopy(c["trials"][0])),
            "missing-state": lambda c, d: c.update(states=["cold"]),
            "validation": lambda c, d: next(iter(d.values()))["records"][0]["go"].update(validationError="bad"),
            "result": lambda c, d: next(iter(d.values()))["records"][0]["go"].update(digest="0" * 64),
            "zero-latency": lambda c, d: next(iter(d.values()))["records"][0]["go"].update(latencyNanos=0),
            "float-latency": lambda c, d: next(iter(d.values()))["records"][0]["go"].update(latencyNanos=1.5),
            "boolean-latency": lambda c, d: next(iter(d.values()))["records"][0]["go"].update(latencyNanos=True),
            "unknown-message-source": lambda c, d: next(iter(d.values()))["records"][0]["main"].update(messageSource="invented"),
            "invalid-trial": lambda c, d: c["trials"].append(None),
        }
        for name, mutate in mutations.items():
            with self.subTest(name=name):
                c, w, d = inputs(1)
                mutate(c, d)
                result = S.summarize(c, w, d)
                self.assertFalse(result["integrityPassed"])
                self.assertFalse(result["tenfoldP95AcceptanceProven"])
                self.assertEqual([(r["state"], r["id"]) for r in result["records"]],
                                 [(s, f"case-{i}") for s in S.STATES for i in range(2)])

    def test_file_hash_validation_and_full_1267_case_retention(self):
        workload_path = Path(__file__).resolve().parents[3] / "graphite-server/internal/benchmarkcase/testdata/main64.json"
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            c, _, d = inputs(1)
            c["workload"] = {"path": str(workload_path), "sha256": S.WORKLOAD_SHA256}
            c["trials"] = c["trials"][:1]
            trial = c["trials"][0]
            raw = json.dumps(d[trial["id"]]).encode()
            (root / trial["recordsPath"]).write_bytes(raw)
            trial["recordsSHA256"] = "0" * 64
            controller = root / "controller.json"
            controller.write_text(json.dumps(c))
            result = S.summarize_file(controller)
            self.assertEqual(len(result["records"]), 1267 * 3)
            self.assertEqual(result["records"][821]["id"], "four-or-graph-id-targeted")
            self.assertFalse(result["tenfoldP95AcceptanceProven"])
            self.assertTrue(any(i["code"] == "trial-file-integrity" for i in result["issues"]))
            # Use every original query definition with fabricated clocks solely
            # to verify full-file loading and inventory, never as timing evidence.
            workload = json.loads(workload_path.read_text())
            doc = d[trial["id"]]
            template = doc["records"][0]
            doc["workloadSHA256"] = S.WORKLOAD_SHA256
            doc["records"] = [{**copy.deepcopy(template), "index": i, "id": case["id"],
                               "querySHA256": S.digest(case["query"].encode())}
                              for i, case in enumerate(workload["cases"])]
            raw = json.dumps(doc).encode()
            (root / trial["recordsPath"]).write_bytes(raw)
            trial["recordsSHA256"] = S.digest(raw)
            controller.write_text(json.dumps(c))
            result = S.summarize_file(controller)
            self.assertTrue(result["integrityPassed"])
            self.assertEqual(result["records"][1266]["go"]["sampleCount"], 1)
            self.assertEqual(result["records"][1267]["go"]["sampleCount"], 0)
            self.assertFalse(result["tenfoldP95AcceptanceProven"])
            # Different path text must not let the same physical sample count twice.
            duplicate = {**trial, "id": "alias", "recordsPath": "./" + trial["recordsPath"]}
            c["trials"].append(duplicate)
            controller.write_text(json.dumps(c))
            result = S.summarize_file(controller)
            self.assertFalse(result["integrityPassed"])
            self.assertTrue(any("reused physical" in i.get("detail", "") for i in result["issues"]))
            c["workload"]["sha256"] = "0" * 64
            controller.write_text(json.dumps(c))
            with self.assertRaisesRegex(ValueError, "workload hash"):
                S.summarize_file(controller)


if __name__ == "__main__":
    unittest.main()
