"""Verify a complete diagnostic replay against its independent full-hit oracle."""

import argparse
import base64
import csv
import hashlib
import json
import re
from collections import Counter
from pathlib import Path


COLUMNS = ["n.caller_class", "n.caller_name", "n.callee_class", "n.callee_name"]
QUERY_COUNT = 24
GRAPH_COUNT = 64
LIMIT = 200
SCHEMA_V1 = "graphite-wide-query-oracle-v1"
SCHEMA_V2 = "graphite-wide-query-oracle-v2"
SCHEMA_V3 = "graphite-wide-query-oracle-v3"
LEGACY_LOGICAL_IDS = [
    "or-single-early", "and-single-early", "or-single-middle", "and-single-middle",
    "or-single-late", "and-single-late", "or-few-early-late", "or-few-early-middle",
    "or-broad-all", "and-broad-all", "mixed-four-few", "and-zero-disjoint-graphs",
]
V3_OR_POSITIONS = {
    "or-four-single-early": [0],
    "or-four-single-middle": [31],
    "or-four-single-late": [63],
    "or-four-few-early-late": [0, 63],
    "or-four-all": list(range(GRAPH_COUNT)),
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, f"Duplicate JSON key: {key}")
        result[key] = value
    return result


def read_json(text):
    return json.loads(text, object_pairs_hook=unique_object)


def read_tsv(path):
    with Path(path).open(encoding="utf-8", newline="") as stream:
        reader = csv.DictReader(stream, delimiter="\t")
        fields = reader.fieldnames or []
        require(fields and len(set(fields)) == len(fields), "Missing or duplicate TSV columns")
        rows = list(reader)
        require(all(None not in row and None not in row.values() for row in rows), "Invalid TSV row width")
        return rows


def canonical_json(value):
    """Match the Java runner's UTF-8 JSON, including its control-character escapes."""
    if value is None:
        return "null"
    if isinstance(value, str):
        escapes = {'"': '\\"', "\\": "\\\\", "\n": "\\n", "\r": "\\r", "\t": "\\t"}
        return '"' + "".join(escapes.get(char, f"\\u{ord(char):04x}" if ord(char) < 32 else char) for char in value) + '"'
    if isinstance(value, list):
        return "[" + ",".join(canonical_json(item) for item in value) + "]"
    if isinstance(value, dict):
        return "{" + ",".join(canonical_json(key) + ":" + canonical_json(item) for key, item in value.items()) + "}"
    raise ValueError("Unsupported value in canonical result")


def digest_rows(rows):
    normalized = [{"values": row["values"], "graphIds": row["graphIds"]} for row in rows]
    return hashlib.sha256(canonical_json(normalized).encode("utf-8")).hexdigest()


def count(value, label):
    require(type(value) is int and value >= 0, f"{label}: expected nonnegative integer")
    return value


def decimal_count(value, label):
    require(isinstance(value, str) and re.fullmatch(r"0|[1-9][0-9]*", value), f"{label}: invalid decimal count")
    return int(value)


def validate_rows(rows, hit_ids, distinct, label):
    require(isinstance(rows, list), f"{label}: rows must be a list")
    tuples = []
    for row in rows:
        require(isinstance(row, dict) and set(row) == {"values", "graphIds"}, f"{label}: invalid row fields")
        values, graph_ids = row["values"], row["graphIds"]
        require(isinstance(values, list) and len(values) == 4 and all(v is None or isinstance(v, str) for v in values), f"{label}: invalid four-property values")
        require(isinstance(graph_ids, list) and graph_ids and all(isinstance(g, str) for g in graph_ids), f"{label}: missing graph provenance")
        require(graph_ids == sorted(set(graph_ids)) and set(graph_ids) <= set(hit_ids), f"{label}: invalid graph provenance")
        require(distinct or len(graph_ids) == 1, f"{label}: ordinary row must have one source")
        tuples.append(tuple(values))
    require(not distinct or len(set(tuples)) == len(tuples), f"{label}: duplicate DISTINCT tuple")


def validate_catalog(catalog):
    require(isinstance(catalog, dict), "Catalog must be a JSON object")
    schema = catalog.get("schema", SCHEMA_V1)
    require(schema in (SCHEMA_V1, SCHEMA_V2, SCHEMA_V3), "Unknown catalog schema")
    query_count = {SCHEMA_V1: QUERY_COUNT, SCHEMA_V2: 26, SCHEMA_V3: 36}[schema]
    graphs = catalog["inputGraphs"]
    require(isinstance(graphs, list) and len(graphs) == GRAPH_COUNT and all(isinstance(g, str) and g for g in graphs) and len(set(graphs)) == GRAPH_COUNT, "Catalog must identify 64 unique graphs")
    logical = catalog["logicalCases"]
    require(isinstance(logical, list) and len(logical) == query_count // 2, f"Catalog must contain {query_count // 2} logical cases")
    if schema == SCHEMA_V2:
        require([case["id"] for case in logical[:-1]] == LEGACY_LOGICAL_IDS, "V2 must preserve the original 12 logical IDs/order")
        last = logical[-1]
        require(last["id"] == "or-four-broad", "V2 final logical case must be or-four-broad")
        ast = last["ast"]
        require(ast == ["or", 0, 1, 2, 3] and all(type(index) is int for index in ast[1:]), "V2 requires a pure four-term OR AST")
        terms = last["terms"]
        require(isinstance(terms, list) and len(terms) == 4 and all(isinstance(term, str) and term for term in terms) and len(set(terms)) == 4, "V2 requires four distinct nonempty terms")
    if schema == SCHEMA_V3:
        expected_ids = LEGACY_LOGICAL_IDS + ["or-four-broad"] + list(V3_OR_POSITIONS)
        require([case["id"] for case in logical] == expected_ids, "V3 must preserve all 18 logical IDs/order")
        for case in logical[12:]:
            label, ast, terms = case["id"], case["ast"], case["terms"]
            require(ast == ["or", 0, 1, 2, 3] and all(type(index) is int for index in ast[1:]), f"{label}: V3 requires a pure four-term OR AST")
            require(isinstance(terms, list) and len(terms) == 4 and all(isinstance(term, str) and term.strip() for term in terms) and len(set(terms)) == 4, f"{label}: V3 requires four distinct nonempty terms")
            require(not any(a.lower() in b.lower() for i, a in enumerate(terms) for j, b in enumerate(terms) if i != j), f"{label}: V3 terms must not contain one another")
            exclusive = case.get("termExclusiveMatchCounts")
            require(isinstance(exclusive, list) and len(exclusive) == 4 and all(type(n) is int and n > 0 for n in exclusive), f"{label}: termExclusiveMatchCounts must contain four positive integers")
            require(sum(exclusive) <= count(case["totalMatches"], label), f"{label}: exclusive counts exceed total matches")
    logical_by_id = {}
    for case in logical:
        label = case["id"]
        require(isinstance(label, str) and label not in logical_by_id, "Invalid or duplicate logical case ID")
        matching, distinct_counts = case["perGraphMatchingCounts"], case["perGraphDistinctMatchingCounts"]
        require(len(matching) == len(distinct_counts) == GRAPH_COUNT, f"{label}: incomplete hit census")
        for i, (matches, distinct) in enumerate(zip(matching, distinct_counts)):
            require(count(distinct, label) <= count(matches, label), f"{label}: distinct count exceeds matches at graph {i}")
            require(bool(matches) == bool(distinct), f"{label}: inconsistent distinct hit census")
        hits = [graph for graph, matches in zip(graphs, matching) if matches]
        if schema == SCHEMA_V3 and label in V3_OR_POSITIONS:
            positions = [i for i, matches in enumerate(matching) if matches]
            require(positions == V3_OR_POSITIONS[label], f"{label}: V3 complete hit positions differ")
            advertised = case.get("advertisedHitGraphPositions")
            require(advertised == V3_OR_POSITIONS[label] and all(type(position) is int for position in advertised), f"{label}: V3 advertised hit positions differ")
        require(case["hitGraphIds"] == hits, f"{label}: hit graph IDs differ from complete census")
        if "advertisedHitGraphPositions" in case:
            require(case["advertisedHitGraphPositions"] == [i for i, n in enumerate(matching) if n], f"{label}: advertised hit positions differ")
        total, distinct_total = count(case["totalMatches"], label), count(case["totalDistinctMatches"], label)
        require(total == sum(matching), f"{label}: total matches differ from census")
        require(max(distinct_counts) <= distinct_total <= sum(distinct_counts), f"{label}: invalid global distinct count")
        for is_distinct, field, expected_total in [(False, "expectedRows", total), (True, "expectedDistinctRows", distinct_total)]:
            rows = case[field]
            validate_rows(rows, hits, is_distinct, label)
            require(len(rows) == min(LIMIT, expected_total), f"{label}: expected rows do not match LIMIT/full count")
            returned_counts = Counter(g for row in rows for g in row["graphIds"])
            if not is_distinct:
                remaining = LIMIT
                for graph, matches in zip(graphs, matching):
                    expected = min(remaining, matches)
                    require(returned_counts[graph] == expected, f"{label}: ordinary row sources differ from ordered census")
                    remaining -= expected
                positions = {g: i for i, g in enumerate(graphs)}
                order = [positions[row["graphIds"][0]] for row in rows]
                require(order == sorted(order), f"{label}: ordinary rows violate graph order")
            else:
                for graph, matches in zip(graphs, distinct_counts):
                    require(returned_counts[graph] <= matches, f"{label}: selected provenance exceeds distinct census")
                    if distinct_total <= LIMIT:
                        require(returned_counts[graph] == matches, f"{label}: complete DISTINCT provenance differs from census")
        logical_by_id[label] = case
    queries = catalog["queries"]
    require(isinstance(queries, list) and len(queries) == query_count, f"Catalog must contain all {query_count} queries")
    ids = [query["id"] for query in queries]
    require(all(isinstance(i, str) and re.fullmatch(r"[A-Za-z0-9_-]+", i) for i in ids) and len(set(ids)) == query_count, "Invalid or duplicate query IDs")
    require(ids == [case["id"] + suffix for case in logical for suffix in ("-rows", "-distinct")], "Catalog query IDs/order differ from complete logical cases")
    for query in queries:
        case = logical_by_id[query["logicalId"]]
        distinct = query["distinct"]
        require(type(distinct) is bool, f"{query['id']}: invalid distinct flag")
        require(query["id"] == case["id"] + ("-distinct" if distinct else "-rows"), f"{query['id']}: distinct/ID mismatch")
        require(query["expectedHitGraphIds"] == case["hitGraphIds"] and count(query["totalMatches"], query["id"]) == case["totalMatches"] and count(query["totalDistinctMatches"], query["id"]) == case["totalDistinctMatches"], f"{query['id']}: query counts differ from full census")
        require(query["expectedRows"] == case["expectedDistinctRows" if distinct else "expectedRows"], f"{query['id']}: query rows differ from oracle")
        suffix = " RETURN " + ("DISTINCT " if distinct else "") + ", ".join(COLUMNS) + " LIMIT 200"
        require(isinstance(query["query"], str) and query["query"].startswith("MATCH (n) WHERE ") and query["query"].endswith(suffix), f"{query['id']}: unexpected query projection/scope/LIMIT")
        if (schema == SCHEMA_V2 and case["id"] == "or-four-broad") or (schema == SCHEMA_V3 and case["id"].startswith("or-four-")):
            operands = []
            for term in case["terms"]:
                literal = term.replace("\\", "\\\\").replace("'", "\\'")
                operands.append("(" + " OR ".join(f"toLower(coalesce({column}, '')) CONTAINS '{literal}'" for column in COLUMNS) + ")")
            expected_query = "MATCH (n) WHERE (" + " OR ".join(operands) + ")" + suffix
            require(query["query"] == expected_query, f"{query['id']}: query must execute the catalog's pure four-term OR")
    return queries, ids


def verify_run(catalog_path, workload_path, prefix):
    """Return a fail-closed receipt; never write artifacts or execute a workload."""
    result = {"passed": False, "queryCount": 0, "verifiedFullRowsAndProvenance": False,
              "catalogSha256": None, "queryShape": "unlabeled four-property multi-keyword",
              "distributionCounts": [], "errors": []}
    try:
        catalog_bytes = Path(catalog_path).read_bytes()
        result["catalogSha256"] = hashlib.sha256(catalog_bytes).hexdigest()
        catalog = read_json(catalog_bytes.decode("utf-8"))
        queries, ids = validate_catalog(catalog)
        workloads = read_tsv(workload_path)
        observations = read_tsv(str(prefix) + ".tsv")
        actual = [read_json(line) for line in Path(str(prefix) + "-rows.jsonl").read_text(encoding="utf-8").splitlines()]
        result["queryCount"] = len(observations)
        for label, rows in [("workloads", workloads), ("observations", observations), ("actual rows", actual)]:
            require([row["id"] for row in rows] == ids, f"{label}: all {len(ids)} IDs/order must match catalog")
        for query, workload, observation, output in zip(queries, workloads, observations, actual):
            label = query["id"]
            text = base64.b64decode(workload["queryBase64"], validate=True).decode("utf-8")
            require(text == query["query"], f"{label}: query text differs")
            require(observation["workloadIdentity"] == hashlib.sha256(text.encode("utf-8")).hexdigest(), f"{label}: query hash differs")
            require(workload["distinct"] == str(query["distinct"]).lower(), f"{label}: workload distinct flag differs")
            workload_hits = workload["expectedHitGraphIds"].split(",") if workload["expectedHitGraphIds"] else []
            require(workload_hits == query["expectedHitGraphIds"], f"{label}: workload full hit census differs")
            require(decimal_count(workload["totalMatches"], label) == query["totalMatches"], f"{label}: workload total matches differ")
            require(set(output) == {"id", "columns", "rows"} and output["columns"] == COLUMNS, f"{label}: projected columns differ")
            validate_rows(output["rows"], query["expectedHitGraphIds"], query["distinct"], label)
            require(output["rows"] == query["expectedRows"], f"{label}: full result values/order/provenance differ")
            require(decimal_count(observation["rowCount"], label) == len(output["rows"]), f"{label}: returned row count differs")
            require(observation["digest"] == digest_rows(output["rows"]), f"{label}: result digest differs")
            returned = sorted({g for row in output["rows"] for g in row["graphIds"]})
            require(observation["hitGraphIds"] == ",".join(returned), f"{label}: returned hit graph IDs differ")
            require(observation["outcome"] == "success" and decimal_count(observation["latencyNanos"], label) > 0, f"{label}: unsuccessful or invalid timing")
            require(observation["inputSourceCount"] == "64" and observation["resetMode"] == "per-query-cold", f"{label}: source scope/reset mode differs")
            projection = "distinct-properties" if query["distinct"] else "properties"
            require(observation["family"] == "multi-keyword" and observation["shape"] == label and observation["projection"] == projection, f"{label}: observation query shape differs")
        result.update(passed=True, verifiedFullRowsAndProvenance=True,
                      distributionCounts=sorted({len(query["expectedHitGraphIds"]) for query in queries}))
    except (OSError, ValueError, KeyError, TypeError, UnicodeError, IndexError) as error:
        result["errors"].append(str(error))
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", type=Path, required=True)
    parser.add_argument("--workloads", type=Path, required=True)
    parser.add_argument("--prefix", type=Path, required=True)
    args = parser.parse_args()
    result = verify_run(args.catalog, args.workloads, args.prefix)
    Path(str(args.prefix) + "-reference-check.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2))
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
