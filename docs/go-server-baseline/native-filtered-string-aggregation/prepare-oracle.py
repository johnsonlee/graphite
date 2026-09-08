"""Prepare independent per-scenario JVM fixtures; no engines/builds or timing."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import struct

parser = argparse.ArgumentParser()
parser.add_argument("destination", type=Path)
args = parser.parse_args()
repo = Path(__file__).resolve().parents[3]
data = repo / "graphite-server/internal/query/testdata"
out = args.destination.resolve()
out.mkdir(parents=True, exist_ok=False)
specs = []

def add(name, query, sources=2, fixture="all-types", **extra):
    specs.append(dict(name=name, sources=sources, fixture=fixture, query=query, **extra))

for label, prop, term in [("EnumConstant", "name", "RED"), ("LocalVariable", "name", "x"),
                          ("Field", "name", "field"), ("CallSite", "caller_name", "run"),
                          ("Annotation", "name", "Audit"), ("", "name", "")]:
    match = f"MATCH (n{':' + label if label else ''}) WHERE n.{prop} CONTAINS '{term}'"
    for counted in ["*", f"n.{prop}", f"DISTINCT n.{prop}", "n.missing", "DISTINCT n.missing",
                    "n.class", "DISTINCT n.class", "n", "DISTINCT n", "1", "null"]:
        ordinal = len(specs)
        add(f"types-{ordinal:03d}", f"{match} RETURN count({counted}) AS x")

prefix = "MATCH (n) WHERE n.caller_name CONTAINS 'other'"
queries = [
    prefix + " RETURN count(*) AS x",
    prefix + " RETURN count(n.caller_name) AS x",
    prefix + " RETURN count(DISTINCT n.caller_name) AS x",
    prefix + " RETURN count(n.line) AS x",
    prefix + " RETURN count(DISTINCT n.line) AS x",
    prefix + " RETURN count(n.class) AS x",
    prefix + " RETURN count(DISTINCT n.class) AS x",
    prefix + " AND n.line > 5 RETURN count(n.caller_name) AS x",
    prefix + " AND n.line < 0 RETURN count(*) AS x",
    prefix + " RETURN count(DISTINCT toLower(n.caller_name)) AS x",
    prefix + " AND n.graphId = 'g00' RETURN count(*) AS x",
    prefix + " AND n.graphId = 'g00' AND n.graphId = 'g01' RETURN count(*) AS x",
    "MATCH (n) WHERE n.caller_name IN ['other','other','missing'] RETURN count(*) AS x",
    "MATCH (n) WHERE n.caller_name CONTAINS 'other' AND exists(n.caller_name) RETURN count(*) AS x",
    "MATCH (n) WHERE n.caller_name =~ '.*\\\\Qother\\\\E.*' RETURN count(*) AS x",
    "MATCH (n) WHERE toLower(n.caller_name) CONTAINS 'other' RETURN count(*) AS x",
    "MATCH (n) WHERE toLower(coalesce(n.caller_name,'')) CONTAINS '' RETURN count(*) AS x",
    "MATCH (n) WHERE n.caller_name = 'other' RETURN count(*) AS x",
    "MATCH (n) WHERE n.caller_name STARTS WITH 'oth' RETURN count(*) AS x",
    "MATCH (n) WHERE n.caller_name ENDS WITH 'her' RETURN count(*) AS x",
    "MATCH (n:NoSuchType) WHERE n.caller_name CONTAINS 'other' RETURN substring(n.name,'bad') AS x",
    prefix + " RETURN count(*) AS x LIMIT 1",
    prefix + " RETURN count(*) AS x, count(n.caller_name) AS y",
    prefix + " RETURN DISTINCT count(*) AS x",
]
for i, query in enumerate(queries):
    add(f"expression-{i:03d}", query, fixture="clean")
for count in [1, 64]:
    for i, query in enumerate(queries[:7]):
        add(f"sources-{count}-{i}", query, sources=count, fixture="clean")
for value in ["other", 42, None]:
    add(f"parameter-{len(specs)}", "MATCH (n) WHERE n.caller_name CONTAINS $term RETURN count(*) AS x",
        fixture="clean", parameters={"term": value})

for counted in ["*", "n.name", "DISTINCT n.name", "n.caller_name", "DISTINCT n.caller_name",
                "n.callee_name", "DISTINCT n.callee_name", "n.missing", "n.class", "DISTINCT n.class"]:
    add(f"annotation-positive-{len(specs)}", "MATCH (n:Annotation) WHERE n.name = 'Audit' RETURN count(" + counted + ") AS x", fixture="annotation")
for counted in ["*", "n.class", "DISTINCT n.caller_name"]:
    add(f"duplicate-source-ids-{len(specs)}", prefix + " RETURN count(" + counted + ") AS x",
        sources=3, fixture="clean", graphIDs=["same", "same", "last"])
for i, query in enumerate(["MATCH (n) WHERE false RETURN n", "MATCH (n:NoSuchLabel) WHERE true RETURN n", "MATCH (n) WHERE 1/0=0 RETURN n"]):
    add(f"malformed-unknown-label-{i}", query, sources=1, fixture="traversal", mutation="traversal-bad-tag")

# The clean fixture's two matching IDs are 2 and 41. Mutate only source g00.
# Other sources stay clean to expose source-local provenance/error behavior.
for mutation in ["bad-return-type", "caller-name-max", "caller-name-negative", "offset-negative-2", "missing-index"]:
    for i, query in enumerate(queries[:7] + [prefix.replace("'other'", "'NeverPresentCountOracleTerm'") + " RETURN count(*) AS x"]):
        add(f"malformed-{mutation}-{i}", query, fixture="clean", mutation=mutation)

for source_count in [1, 2, 64]:
    for scoped in [False, True]:
        for guard in ["n.graphId = 'g00'", "n.graphId IN ['g00','g01']",
                      "n.graphId = 'g00' AND n.graphId = 'g01'",
                      "n.graphId = 'g00' AND n.line >= 0"]:
            add(f"root-scope-{len(specs)}", prefix + " AND " + guard + " RETURN count(DISTINCT n.caller_name) AS x",
                sources=source_count, fixture="clean", qualified=True, scoped=scoped)
for graph_id in ["g00", "single", ""]:
    add(f"single-graph-guard-{len(specs)}", prefix + f" AND n.graphId = '{graph_id}' RETURN count(*) AS x",
        sources=1, fixture="clean")

for counted in ["*", "n.missing", "DISTINCT n.caller_name", "toLower(n.caller_name)"]:
    add(f"provenance-order-{len(specs)}", prefix + " RETURN count(" + counted + ") AS x",
        sources=8, fixture="clean", graphIDs=["z", "tika", "\ue000", "\U00010000", "hive", "android", "a", "\U0001f600"])

for spec in specs:
    base = (data / "main-string-source/all-types" if spec["fixture"] == "all-types" else
            data / "traversal" if spec["fixture"] == "traversal" else data / "candidate-index" / spec["fixture"])
    for i in range(spec["sources"]):
        target = out / "fixtures" / spec["name"] / f"g{i:02d}"
        shutil.copytree(base, target)
        if i != 0:
            continue
        mutation = spec.get("mutation")
        if mutation in ("bad-return-type", "caller-name-max", "caller-name-negative"):
            file = target / "graph.nodedata"
            value = bytearray(file.read_bytes())
            struct.pack_into(">i", value, 98 if mutation == "bad-return-type" else 74,
                             -1 if mutation == "caller-name-negative" else 2147483647)
            file.write_bytes(value)
        elif mutation == "offset-negative-2":
            file = target / "graph.nodeoffsets"
            value = bytearray(file.read_bytes())
            struct.pack_into(">q", value, 8 + 2 * 8, -1)
            file.write_bytes(value)
        elif mutation == "missing-index":
            (target / "graph.callsite-string-index").unlink()
        elif mutation == "traversal-bad-tag":
            file = target / "graph.nodedata"
            value = bytearray(file.read_bytes())
            value[12] = 255
            file.write_bytes(value)
            index = bytearray(struct.pack(">II", 0x47524903, 8))
            for node in range(8):
                index.extend(struct.pack(">IBQ", node, 0, 8 + node * 9))
            (target / "graph.nodeindex").write_bytes(index)

(out / "cases.json").write_text(json.dumps(specs, indent=2) + "\n")
manifest = [{"file": str(file.relative_to(out)), "bytes": file.stat().st_size,
             "sha256": hashlib.sha256(file.read_bytes()).hexdigest()}
            for file in sorted((out / "fixtures").rglob("*")) if file.is_file()]
(out / "fixture-before.json").write_text(json.dumps(manifest, indent=2) + "\n")
