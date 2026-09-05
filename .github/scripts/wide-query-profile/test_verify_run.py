"""Small JSON correctness fixtures only; no graph or performance measurements."""

import base64
import copy
import csv
import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from verify_run import canonical_json, verify_run


COLUMNS = ["n.caller_class", "n.caller_name", "n.callee_class", "n.callee_name"]


def sha(text):
    return hashlib.sha256(text.encode()).hexdigest()


def fixture():
    graphs = [f"g-{i:02}" for i in range(64)]
    logical, queries, workloads, observations, outputs = [], [], [], [], []
    for index in range(12):
        case_id = f"case-{index}"
        values = ["Caller", f"method-{index}", "Callee", "target"]
        row = lambda value, ids: {"values": value, "graphIds": ids}
        counts, distinct_counts = [0] * 64, [0] * 64
        if index == 2:
            rows, distinct_rows, distinct_total = [], [], 0
        elif index == 1:
            counts[0] = counts[63] = distinct_counts[0] = distinct_counts[63] = 1
            rows = [row(values, [graphs[0]]), row(values, [graphs[63]])]
            distinct_rows, distinct_total = [row(values, [graphs[0], graphs[63]])], 1
        elif index == 3:
            counts[0] = distinct_counts[0] = 201
            counts[63] = distinct_counts[63] = 1
            rows = [row(["Caller", str(i), "Callee", "target"], [graphs[0]]) for i in range(200)]
            distinct_rows, distinct_total = copy.deepcopy(rows), 202
        else:
            counts[0], distinct_counts[0] = 2, 1
            rows = [row(values, [graphs[0]]), row(values, [graphs[0]])]
            distinct_rows, distinct_total = [row(values, [graphs[0]])], 1
        hits = [g for g, count in zip(graphs, counts) if count]
        case = {"id": case_id, "perGraphMatchingCounts": counts, "perGraphDistinctMatchingCounts": distinct_counts,
                "hitGraphIds": hits, "totalMatches": sum(counts), "totalDistinctMatches": distinct_total,
                "expectedRows": rows, "expectedDistinctRows": distinct_rows}
        logical.append(case)
        for distinct in [False, True]:
            query_id = case_id + ("-distinct" if distinct else "-rows")
            query = f"MATCH (n) WHERE n.caller_name CONTAINS '{case_id}' RETURN " + ("DISTINCT " if distinct else "") + ", ".join(COLUMNS) + " LIMIT 200"
            selected = copy.deepcopy(distinct_rows if distinct else rows)
            queries.append({"id": query_id, "logicalId": case_id, "query": query, "distinct": distinct,
                            "expectedHitGraphIds": hits, "totalMatches": sum(counts),
                            "totalDistinctMatches": distinct_total, "expectedRows": copy.deepcopy(selected)})
            workloads.append({"id": query_id, "queryBase64": base64.b64encode(query.encode()).decode(),
                              "distinct": str(distinct).lower(), "expectedHitGraphIds": ",".join(hits), "totalMatches": str(sum(counts))})
            observations.append({"id": query_id, "family": "multi-keyword", "shape": query_id,
                                 "projection": "distinct-properties" if distinct else "properties", "workloadIdentity": sha(query),
                                 "outcome": "success", "rowCount": str(len(selected)),
                                 "digest": sha(json.dumps(selected, separators=(",", ":"), ensure_ascii=False)),
                                 "latencyNanos": "1", "hitGraphIds": ",".join(sorted({g for r in selected for g in r["graphIds"]})),
                                 "inputSourceCount": "64", "resetMode": "per-query-cold"})
            outputs.append({"id": query_id, "columns": COLUMNS.copy(), "rows": selected})
    return {"inputGraphs": graphs, "logicalCases": logical, "queries": queries}, workloads, observations, outputs


def v2_fixture():
    catalog, workloads, observations, outputs = fixture()
    names = ["or-single-early", "and-single-early", "or-single-middle", "and-single-middle",
             "or-single-late", "and-single-late", "or-few-early-late", "or-few-early-middle",
             "or-broad-all", "and-broad-all", "mixed-four-few", "and-zero-disjoint-graphs"]
    for index, name in enumerate(names):
        catalog["logicalCases"][index]["id"] = name
        for offset, suffix in enumerate(["-rows", "-distinct"]):
            position, query_id = index * 2 + offset, name + suffix
            catalog["queries"][position].update(id=query_id, logicalId=name)
            workloads[position]["id"] = query_id
            observations[position].update(id=query_id, shape=query_id)
            outputs[position]["id"] = query_id
    catalog["schema"] = "graphite-wide-query-oracle-v2"
    last = copy.deepcopy(catalog["logicalCases"][0])
    last.update(id="or-four-broad", ast=["or", 0, 1, 2, 3], terms=["caller", "method-0", "callee", "target"])
    catalog["logicalCases"].append(last)
    operands = ["(" + " OR ".join(f"toLower(coalesce({column}, '')) CONTAINS '{term}'" for column in COLUMNS) + ")" for term in last["terms"]]
    for index, suffix in enumerate(["-rows", "-distinct"]):
        query_id = "or-four-broad" + suffix
        query = "MATCH (n) WHERE (" + " OR ".join(operands) + ") RETURN " + ("DISTINCT " if index else "") + ", ".join(COLUMNS) + " LIMIT 200"
        copied = copy.deepcopy(catalog["queries"][index])
        copied.update(id=query_id, logicalId="or-four-broad", query=query)
        catalog["queries"].append(copied)
        workload = copy.deepcopy(workloads[index])
        workload.update(id=query_id, queryBase64=base64.b64encode(query.encode()).decode())
        workloads.append(workload)
        observation = copy.deepcopy(observations[index])
        observation.update(id=query_id, shape=query_id, workloadIdentity=sha(query))
        observations.append(observation)
        output = copy.deepcopy(outputs[index])
        output["id"] = query_id
        outputs.append(output)
    return catalog, workloads, observations, outputs


def v3_fixture():
    catalog, workloads, observations, outputs = v2_fixture()
    catalog["schema"] = "graphite-wide-query-oracle-v3"
    catalog["logicalCases"] = catalog["logicalCases"][:12]
    catalog["queries"] = catalog["queries"][:24]
    workloads, observations, outputs = workloads[:24], observations[:24], outputs[:24]
    positions_by_case = {
        "or-four-broad": [0, 31, 63],
        "or-four-single-early": [0], "or-four-single-middle": [31],
        "or-four-single-late": [63], "or-four-few-early-late": [0, 63],
        "or-four-all": list(range(64)),
    }
    for label, positions in positions_by_case.items():
        terms = ["keyword-alpha", "keyword-bravo", "keyword-charlie", "keyword-delta"]
        hits = [catalog["inputGraphs"][i] for i in positions]
        values = [[term, "method", "Callee", "target"] for term in terms]
        all_rows = [{"values": value, "graphIds": [graph]} for graph in hits for value in values]
        distinct_rows = [{"values": value, "graphIds": hits} for value in values]
        counts = [4 if i in positions else 0 for i in range(64)]
        case = {"id": label, "ast": ["or", 0, 1, 2, 3], "terms": terms,
                "advertisedHitGraphPositions": positions, "perGraphMatchingCounts": counts,
                "perGraphDistinctMatchingCounts": counts.copy(), "hitGraphIds": hits,
                "totalMatches": len(all_rows), "totalDistinctMatches": 4,
                "termExclusiveMatchCounts": [len(positions)] * 4,
                "expectedRows": all_rows[:200], "expectedDistinctRows": distinct_rows}
        catalog["logicalCases"].append(case)
        operands = ["(" + " OR ".join(f"toLower(coalesce({column}, '')) CONTAINS '{term}'" for column in COLUMNS) + ")" for term in terms]
        for distinct in [False, True]:
            query_id = label + ("-distinct" if distinct else "-rows")
            query = "MATCH (n) WHERE (" + " OR ".join(operands) + ") RETURN " + ("DISTINCT " if distinct else "") + ", ".join(COLUMNS) + " LIMIT 200"
            selected = copy.deepcopy(distinct_rows if distinct else all_rows[:200])
            catalog["queries"].append({"id": query_id, "logicalId": label, "query": query,
                "distinct": distinct, "expectedHitGraphIds": hits, "totalMatches": len(all_rows),
                "totalDistinctMatches": 4, "expectedRows": copy.deepcopy(selected)})
            workloads.append({"id": query_id, "queryBase64": base64.b64encode(query.encode()).decode(),
                "distinct": str(distinct).lower(), "expectedHitGraphIds": ",".join(hits), "totalMatches": str(len(all_rows))})
            observations.append({"id": query_id, "family": "multi-keyword", "shape": query_id,
                "projection": "distinct-properties" if distinct else "properties", "workloadIdentity": sha(query),
                "outcome": "success", "rowCount": str(len(selected)), "digest": sha(canonical_json(selected)),
                "latencyNanos": "1", "hitGraphIds": ",".join(sorted({g for row in selected for g in row["graphIds"]})),
                "inputSourceCount": "64", "resetMode": "per-query-cold"})
            outputs.append({"id": query_id, "columns": COLUMNS.copy(), "rows": selected})
    return catalog, workloads, observations, outputs


class VerifyRunTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.catalog_path = self.directory / "catalog.json"
        self.workload_path = self.directory / "workloads.tsv"
        self.prefix = self.directory / "capture"
        self.catalog, self.workloads, self.observations, self.outputs = fixture()

    def write(self):
        self.catalog_path.write_text(json.dumps(self.catalog), encoding="utf-8")
        for path, rows in [(self.workload_path, self.workloads), (Path(str(self.prefix) + ".tsv"), self.observations)]:
            with path.open("w", encoding="utf-8", newline="") as stream:
                writer = csv.DictWriter(stream, fieldnames=rows[0].keys(), delimiter="\t")
                writer.writeheader()
                writer.writerows(rows)
        Path(str(self.prefix) + "-rows.jsonl").write_text("\n".join(json.dumps(row) for row in self.outputs) + "\n", encoding="utf-8")

    def verify(self):
        self.write()
        return verify_run(self.catalog_path, self.workload_path, self.prefix)

    def rejected(self, message):
        result = self.verify()
        self.assertFalse(result["passed"])
        self.assertFalse(result["verifiedFullRowsAndProvenance"])
        self.assertIn(message, " ".join(result["errors"]))

    def test_complete_results_allow_capped_returned_sources_to_be_subset_of_full_census(self):
        result = self.verify()
        self.assertTrue(result["passed"], result)
        self.assertEqual(result["queryCount"], 24)
        self.assertEqual(result["distributionCounts"], [0, 1, 2])
        self.assertEqual(self.workloads[6]["expectedHitGraphIds"], "g-00,g-63")
        self.assertEqual(self.observations[6]["hitGraphIds"], "g-00")
        self.assertFalse(Path(str(self.prefix) + "-reference-check.json").exists())

    def test_corrupt_value_rejected_even_with_recomputed_digest(self):
        self.outputs[0]["rows"][0]["values"][0] = "wrong"
        self.observations[0]["digest"] = sha(json.dumps(self.outputs[0]["rows"], separators=(",", ":")))
        self.rejected("values/order/provenance differ")

    def test_missing_distinct_contributing_source_rejected(self):
        self.outputs[3]["rows"][0]["graphIds"] = ["g-00"]
        self.rejected("values/order/provenance differ")

    def test_result_row_order_rejected(self):
        self.outputs[6]["rows"][0], self.outputs[6]["rows"][1] = self.outputs[6]["rows"][1], self.outputs[6]["rows"][0]
        self.rejected("values/order/provenance differ")

    def test_projection_column_order_rejected(self):
        self.outputs[0]["columns"].reverse()
        self.rejected("projected columns differ")

    def test_query_hash_rejected(self):
        self.observations[0]["workloadIdentity"] = "0" * 64
        self.rejected("query hash differs")

    def test_query_content_rejected_even_if_observed_hash_matches_mutation(self):
        query = self.catalog["queries"][0]["query"].replace("case-0", "other")
        self.workloads[0]["queryBase64"] = base64.b64encode(query.encode()).decode()
        self.observations[0]["workloadIdentity"] = sha(query)
        self.rejected("query text differs")

    def test_digest_rejected(self):
        self.observations[0]["digest"] = "0" * 64
        self.rejected("result digest differs")

    def test_reported_row_count_must_match_rows(self):
        self.observations[0]["rowCount"] = "200"
        self.rejected("returned row count differs")

    def test_reported_sources_must_be_returned_sources_not_full_census(self):
        self.observations[6]["hitGraphIds"] = self.workloads[6]["expectedHitGraphIds"]
        self.rejected("returned hit graph IDs differ")

    def test_duplicate_provenance_is_rejected(self):
        self.outputs[0]["rows"][0]["graphIds"] = ["g-00", "g-00"]
        self.rejected("invalid graph provenance")

    def test_distinct_cannot_return_duplicate_tuples(self):
        self.outputs[1]["rows"].append(copy.deepcopy(self.outputs[1]["rows"][0]))
        self.rejected("duplicate DISTINCT tuple")

    def test_projection_flag_must_match_catalog(self):
        self.workloads[0]["distinct"] = "true"
        self.rejected("workload distinct flag differs")

    def test_query_projection_cannot_be_changed_even_in_catalog(self):
        self.catalog["queries"][0]["query"] = self.catalog["queries"][0]["query"].replace("LIMIT 200", "LIMIT 20")
        self.rejected("unexpected query projection/scope/LIMIT")

    def test_missing_cases_in_each_stream_rejected(self):
        for field in ["workloads", "observations", "outputs"]:
            with self.subTest(field=field):
                original = getattr(self, field)
                setattr(self, field, original[:-1])
                self.rejected("all 24 IDs/order")
                setattr(self, field, original)

    def test_reordered_query_stream_rejected(self):
        self.outputs.reverse()
        self.rejected("all 24 IDs/order")

    def test_truncated_catalog_does_not_define_smaller_successful_run(self):
        self.catalog["queries"].pop()
        self.rejected("all 24 queries")

    def test_timing_outcome_scope_and_reset_fail_closed(self):
        for field, values in {"latencyNanos": ["0", "-1", "nan", "1.5"], "outcome": ["failed"], "inputSourceCount": ["1"], "resetMode": ["warm"]}.items():
            for value in values:
                with self.subTest(field=field, value=value):
                    original = self.observations[0][field]
                    self.observations[0][field] = value
                    self.assertFalse(self.verify()["passed"])
                    self.observations[0][field] = original

    def test_hit_graph_census_is_not_returned_subset(self):
        self.workloads[6]["expectedHitGraphIds"] = self.observations[6]["hitGraphIds"]
        self.rejected("workload full hit census differs")

    def test_total_count_must_equal_full_per_graph_census(self):
        self.catalog["logicalCases"][0]["totalMatches"] += 1
        self.rejected("total matches differ from census")

    def test_hit_graph_ids_must_match_nonzero_census_entries(self):
        self.catalog["logicalCases"][0]["hitGraphIds"] = ["g-63"]
        self.rejected("hit graph IDs differ from complete census")

    def test_complete_distinct_oracle_requires_every_contributing_graph(self):
        self.catalog["logicalCases"][1]["expectedDistinctRows"][0]["graphIds"] = ["g-00"]
        self.rejected("complete DISTINCT provenance differs from census")

    def test_full_distinct_count_does_not_sum_duplicates_across_graphs(self):
        self.assertEqual(self.catalog["logicalCases"][1]["totalDistinctMatches"], 1)
        self.assertEqual(sum(self.catalog["logicalCases"][1]["perGraphDistinctMatchingCounts"]), 2)
        self.assertTrue(self.verify()["passed"])

    def test_missing_or_malformed_input_returns_failure(self):
        self.assertFalse(verify_run(self.catalog_path, self.workload_path, self.prefix)["passed"])
        self.write()
        self.catalog_path.write_text('{"inputGraphs": [], "inputGraphs": []}')
        result = verify_run(self.catalog_path, self.workload_path, self.prefix)
        self.assertIn("Duplicate JSON key", result["errors"][0])

    def test_java_canonical_control_characters_and_unicode(self):
        self.assertEqual(canonical_json(["漢字", "\b\f\n\t\r", "\\b"]), '["漢字","\\u0008\\u000c\\n\\t\\r","\\\\b"]')

    def test_cli_writes_receipt_and_returns_failure_for_corruption(self):
        self.observations[0]["digest"] = "0" * 64
        self.write()
        command = [sys.executable, str(Path(__file__).with_name("verify_run.py")), "--catalog", str(self.catalog_path), "--workloads", str(self.workload_path), "--prefix", str(self.prefix)]
        process = subprocess.run(command, capture_output=True, text=True, check=False)
        self.assertEqual(process.returncode, 1)
        receipt = json.loads(Path(str(self.prefix) + "-reference-check.json").read_text())
        self.assertFalse(receipt["passed"])
        self.assertIn("result digest differs", receipt["errors"][0])

    def use_v2(self):
        self.catalog, self.workloads, self.observations, self.outputs = v2_fixture()

    def test_explicit_v1_retains_exactly_24_cases(self):
        self.catalog["schema"] = "graphite-wide-query-oracle-v1"
        result = self.verify()
        self.assertTrue(result["passed"], result)
        self.assertEqual(result["queryCount"], 24)

    def test_v2_accepts_both_pure_four_or_projections(self):
        self.use_v2()
        result = self.verify()
        self.assertTrue(result["passed"], result)
        self.assertEqual(result["queryCount"], 26)
        self.assertEqual([q["id"] for q in self.catalog["queries"][-2:]], ["or-four-broad-rows", "or-four-broad-distinct"])

    def test_unknown_or_null_schema_rejected(self):
        for schema in ["graphite-wide-query-oracle-v4", "", None]:
            with self.subTest(schema=schema):
                self.catalog["schema"] = schema
                self.rejected("Unknown catalog schema")

    def test_v2_cannot_fall_back_to_legacy_count_when_truncated(self):
        self.use_v2()
        self.catalog["logicalCases"].pop()
        self.catalog["queries"] = self.catalog["queries"][:24]
        self.rejected("13 logical cases")

    def test_legacy_schema_does_not_implicitly_accept_26_queries(self):
        self.use_v2()
        del self.catalog["schema"]
        self.rejected("12 logical cases")

    def test_v2_missing_projection_rejected(self):
        self.use_v2()
        self.catalog["queries"].pop()
        self.rejected("all 26 queries")

    def test_v2_rejects_truncated_actual_streams(self):
        self.use_v2()
        for field in ["workloads", "observations", "outputs"]:
            with self.subTest(field=field):
                original = getattr(self, field)
                setattr(self, field, original[:24])
                self.rejected("all 26 IDs/order")
                setattr(self, field, original)

    def test_v2_mixed_or_wrong_arity_ast_rejected(self):
        self.use_v2()
        for ast in [["and", 0, 1, 2, 3], ["or", ["and", 0, 1], 2, 3], ["or", 0, 1, 2], ["or", 0, True, 2, 3]]:
            with self.subTest(ast=ast):
                self.catalog["logicalCases"][-1]["ast"] = ast
                self.rejected("pure four-term OR AST")

    def test_v2_requires_four_distinct_nonempty_terms(self):
        self.use_v2()
        for terms in [["a", "b", "c"], ["a", "b", "c", "a"], ["a", "b", "c", ""], ["a", "b", "c", 4]]:
            with self.subTest(terms=terms):
                self.catalog["logicalCases"][-1]["terms"] = terms
                self.rejected("four distinct nonempty terms")

    def test_v2_metadata_cannot_hide_mixed_query_text(self):
        self.use_v2()
        self.catalog["queries"][-1]["query"] = self.catalog["queries"][-1]["query"].replace(") OR (", ") AND (", 1)
        self.rejected("query must execute the catalog's pure four-term OR")

    def test_v2_requires_last_case_and_original_prefix_order(self):
        self.use_v2()
        self.catalog["logicalCases"][-1]["id"] = "mixed-four-few"
        self.rejected("final logical case must be or-four-broad")
        self.use_v2()
        self.catalog["logicalCases"][0], self.catalog["logicalCases"][1] = self.catalog["logicalCases"][1], self.catalog["logicalCases"][0]
        self.rejected("preserve the original 12 logical IDs/order")

    def use_v3(self):
        self.catalog, self.workloads, self.observations, self.outputs = v3_fixture()

    def test_v3_accepts_all_six_pure_or_cases_and_fixed_hit_distributions(self):
        self.use_v3()
        result = self.verify()
        self.assertTrue(result["passed"], result)
        self.assertEqual(result["queryCount"], 36)
        self.assertEqual(result["distributionCounts"], [0, 1, 2, 3, 64])
        self.assertEqual([case["advertisedHitGraphPositions"] for case in self.catalog["logicalCases"][-5:]],
                         [[0], [31], [63], [0, 63], list(range(64))])
        self.assertEqual(len(self.outputs[-2]["rows"]), 200)
        self.assertEqual(len(self.outputs[-1]["rows"][0]["graphIds"]), 64)

    def test_v3_truncation_cannot_redefine_success(self):
        for field in ["logicalCases", "queries"]:
            with self.subTest(field=field):
                self.use_v3()
                self.catalog[field].pop()
                self.rejected("18 logical cases" if field == "logicalCases" else "all 36 queries")
        for field in ["workloads", "observations", "outputs"]:
            with self.subTest(field=field):
                self.use_v3()
                getattr(self, field).pop()
                self.rejected("all 36 IDs/order")

    def test_v3_enforces_entire_prefix_and_appended_case_order(self):
        for first, second in [(0, 1), (11, 12), (12, 13), (16, 17)]:
            with self.subTest(first=first, second=second):
                self.use_v3()
                cases = self.catalog["logicalCases"]
                cases[first], cases[second] = cases[second], cases[first]
                self.rejected("all 18 logical IDs/order")

    def test_v3_requires_pure_or_ast_for_every_four_keyword_case(self):
        for index in range(12, 18):
            for ast in [["and", 0, 1, 2, 3], ["or", 0, ["and", 1, 2], 3], ["or", 0, 1, 2], ["or", False, 1, 2, 3]]:
                with self.subTest(index=index, ast=ast):
                    self.use_v3()
                    self.catalog["logicalCases"][index]["ast"] = ast
                    self.rejected("pure four-term OR AST")

    def test_v3_rejects_empty_duplicate_and_contained_terms(self):
        for terms, message in [
            (["alpha", "bravo", "charlie"], "four distinct nonempty terms"),
            (["alpha", "bravo", "charlie", ""], "four distinct nonempty terms"),
            (["alpha", "bravo", "charlie", "  "], "four distinct nonempty terms"),
            (["alpha", "bravo", "charlie", "alpha"], "four distinct nonempty terms"),
            (["alpha", "bravo", "charlie", None], "four distinct nonempty terms"),
            (["alpha", "bravo", "charlie", "prefix-alpha"], "must not contain one another"),
            (["alpha", "bravo", "charlie", "ALPHA"], "must not contain one another"),
        ]:
            for index in range(12, 18):
                with self.subTest(index=index, terms=terms):
                    self.use_v3()
                    self.catalog["logicalCases"][index]["terms"] = terms
                    self.rejected(message)

    def test_v3_exclusive_counts_require_every_term_to_contribute(self):
        for index in range(12, 18):
            for counts in [None, [], [1, 1, 1], [1, 1, 1, 1, 1], [1, 1, 0, 1], [1, 1, -1, 1], [1, True, 1, 1], [1, 1.0, 1, 1], [1, "1", 1, 1]]:
                with self.subTest(index=index, counts=counts):
                    self.use_v3()
                    case = self.catalog["logicalCases"][index]
                    if counts is None:
                        del case["termExclusiveMatchCounts"]
                    else:
                        case["termExclusiveMatchCounts"] = counts
                    self.rejected("termExclusiveMatchCounts must contain four positive integers")

    def test_v3_disjoint_exclusive_counts_cannot_exceed_all_matching_nodes(self):
        self.use_v3()
        self.catalog["logicalCases"][13]["termExclusiveMatchCounts"] = [2, 1, 1, 1]
        self.rejected("exclusive counts exceed total matches")

    def test_v3_requires_actual_complete_census_at_fixed_positions(self):
        for index in range(13, 18):
            with self.subTest(index=index):
                self.use_v3()
                case = self.catalog["logicalCases"][index]
                position = case["advertisedHitGraphPositions"][0]
                case["perGraphMatchingCounts"][position] = 0
                case["perGraphDistinctMatchingCounts"][position] = 0
                self.rejected("V3 complete hit positions differ")

    def test_v3_census_cannot_add_an_unadvertised_matching_graph(self):
        self.use_v3()
        case = self.catalog["logicalCases"][13]
        case["perGraphMatchingCounts"][1] = case["perGraphDistinctMatchingCounts"][1] = 1
        self.rejected("V3 complete hit positions differ")

    def test_v3_requires_advertised_fixed_positions_as_well_as_census(self):
        for index in range(13, 18):
            with self.subTest(index=index):
                self.use_v3()
                del self.catalog["logicalCases"][index]["advertisedHitGraphPositions"]
                self.rejected("V3 advertised hit positions differ")

    def test_v3_all_twelve_projection_texts_must_execute_exact_pure_or(self):
        for index in range(24, 36):
            with self.subTest(index=index):
                self.use_v3()
                self.catalog["queries"][index]["query"] = self.catalog["queries"][index]["query"].replace(") OR (", ") AND (", 1)
                self.rejected("query must execute the catalog's pure four-term OR")

    def test_v3_fixed_positions_reject_boolean_and_float_aliases(self):
        for position in [False, 0.0]:
            with self.subTest(position=position):
                self.use_v3()
                self.catalog["logicalCases"][13]["advertisedHitGraphPositions"] = [position]
                self.rejected("V3 advertised hit positions differ")

    def test_v3_new_rows_provenance_and_query_hash_remain_fail_closed(self):
        self.use_v3()
        self.outputs[-1]["rows"][0]["graphIds"].pop()
        self.rejected("values/order/provenance differ")
        self.use_v3()
        self.outputs[-2]["rows"][0]["values"][0] = "wrong"
        self.rejected("values/order/provenance differ")
        self.use_v3()
        self.observations[-1]["workloadIdentity"] = "0" * 64
        self.rejected("query hash differs")

    def test_v2_does_not_implicitly_accept_v3_size(self):
        self.use_v3()
        self.catalog["schema"] = "graphite-wide-query-oracle-v2"
        self.rejected("13 logical cases")


if __name__ == "__main__":
    unittest.main()
