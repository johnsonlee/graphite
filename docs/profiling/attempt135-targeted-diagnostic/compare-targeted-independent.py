import collections
import csv
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent
QUERY = "global-wide-wrapped-case-insensitive-distinct-targeted"

def sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()

def group(thread):
    if "CompilerThread" in thread:
        return "compiler"
    if "broad-query-pressure-worker" in thread:
        return "request"
    if "graphite-cypher-scan-" in thread:
        return "graphWorker"
    if "graphite-callsite-segment-" in thread:
        return "segmentWorker"
    return "other"

def method(frame):
    return frame.split("(")[0]

def categories(frames):
    result = set()
    for f in frames:
        if "parallelRawDistinctCallSiteStringProjection" in f:
            result.add("rawDistinct")
        if "exactMatchingStringIds" in f:
            result.add("exactDiscovery")
        if "kotlin.UnsafeLazyImpl" in f or "distinctStringPropertyDisjunction$lambda$" in f:
            result.add("lazyOrCallback")
        if "executeIndexedDistinctStringProjection$projectSource" in f:
            result.add("initialProjection")
        if "executeIndexedDistinctStringProjection$lambda$155$lambda$154" in f:
            result.add("provenanceTask")
        if "IntProgression.iterator" in f:
            result.add("rangeIterator")
        if "getIndices" in f:
            result.add("rangeIndices")
    return result

runs = []
for pair in range(1, 4):
    for side in ("base", "candidate"):
        prefix = ROOT / f"{side}-cpu-{pair}"
        directory = ROOT / f"{side}-cpu-{pair}-analysis"
        analysis_path = directory / "analysis.json"
        analysis = json.loads(analysis_path.read_text())
        assert analysis["validation"]["passed"] and analysis["validation"]["queryCount"] == 34
        assert analysis["validation"]["nonoverlapping"]
        assert sha(prefix.with_suffix(".tsv")) == analysis["tsvSha256"]
        assert sha(prefix.with_suffix(".jfr")) == analysis["jfrSha256"]
        query = next(q for q in analysis["queries"] if q["id"] == QUERY)
        tsv = next(row for row in csv.DictReader(prefix.with_suffix(".tsv").open(), delimiter="\t") if row["id"] == QUERY)
        assert query["ordinal"] == 29 and query["rowCount"] == 12 and query["outcome"] == "success"
        assert int(tsv["rowCount"]) == query["rowCount"]
        assert tsv["digest"] == query["digest"]
        assert tsv["workloadIdentity"] == query["workloadIdentity"]
        run = {"side": side, "pair": pair, "analysis": str(analysis_path), "analysisSha256": sha(analysis_path),
               "jfrSha256": analysis["jfrSha256"], "tsvSha256": analysis["tsvSha256"],
               "traceDurationNanos": query["traceDurationNanos"], "profiledLatencyNanos": query["tsvLatencyNanos"],
               "digest": query["digest"], "workloadIdentity": query["workloadIdentity"],
               "observations": {k: tsv[k] for k in ["rowCount", "limit", "graphWorkUnits", "parallelScanCount", "accessedGraphCount", "hitGraphIds"]}, "metrics": {}}
        for metric, relative in query["collapsed"].items():
            path = directory / relative
            weight = 0
            groups = collections.Counter()
            leaf = collections.Counter()
            inclusive = collections.Counter()
            exclusive = collections.Counter()
            stage_combo = collections.Counter()
            app_weight = 0
            for line in path.read_text().splitlines():
                stack, raw_weight = line.rsplit(" ", 1)
                w = int(raw_weight)
                frames = stack.split(";")
                g = group(frames[0])
                groups[g] += w
                weight += w
                if g not in ("request", "graphWorker", "segmentWorker"):
                    continue
                app_weight += w
                leaf[method(frames[-1])] += w
                cats = categories(frames[1:])
                for c in cats:
                    inclusive[c] += w
                for c in categories(frames[-1:]):
                    exclusive[c] += w
                stage_combo["+".join(sorted(cats & {"rawDistinct", "exactDiscovery", "lazyOrCallback"})) or "other"] += w
            expected = query["metrics"][metric]
            assert weight == expected["weight"]
            assert not expected["missingStackEvents"] and not expected["truncatedStackEvents"]
            assert sum(leaf.values()) == app_weight
            run["metrics"][metric] = {"weight": weight, "eventCount": expected["eventCount"], "threadGroups": dict(groups),
                "applicationWeight": app_weight, "inclusiveStackUnion": dict(inclusive), "exclusiveLeaf": dict(exclusive),
                "disjointStageCombinations": dict(stage_combo), "applicationTopLeaf": leaf.most_common(15),
                "collapsed": str(path), "collapsedSha256": sha(path), "weightConserved": True}
        runs.append(run)
assert len({q["digest"] for q in runs}) == 1
assert len({q["workloadIdentity"] for q in runs}) == 1
assert all(q["observations"]["graphWorkUnits"] == "106706" for q in runs)
receipt = {"diagnosticOnly": True, "query": QUERY, "pairedOrder": ["candidate/base", "base/candidate", "candidate/base"],
    "validationPassed": True, "scriptSha256": sha(Path(__file__)), "captureReceipt": json.loads((ROOT/"capture-receipt.json").read_text()),
    "semantics": ["Inclusive categories are unioned once per stack and overlap; do not sum them.",
        "Exclusive categories classify only the sampled leaf, not elapsed exclusive time.",
        "Allocation weights are sampled TLAB/outside-TLAB bytes, not exact query allocations or object-size accounting.",
        "CPU samples on all co-occurring threads do not establish causality or scheduling delay.",
        "Profiled latency is diagnostic only and cannot replace the rejected unprofiled performance result.",
        "No selected provenance stage executes: all 12 rows fit below LIMIT 200; initial projection passes selectedValues=null."],
    "bytecodeComparison": {"path": str(ROOT/"worker-bytecode-comparison.json"), "sha256": sha(ROOT/"worker-bytecode-comparison.json"),
        "reportedResult": json.loads((ROOT/"worker-bytecode-comparison.json").read_text()),
        "qualification": "Read existing parent-produced comparison; did not launch javap or independently regenerate it."},
    "conclusion": "The six profiles do not establish the cause of the original unprofiled regression. New lazy/callback inclusive samples all overlap exact discovery, with no sampled lazy/callback leaf. Dominant raw loops also dominate baseline; per-node and worker bytecode comparison reports equivalent instructions after naming normalization. No acceptance decision is changed.",
    "runs": runs}
(ROOT/"independent-targeted-comparison-receipt.json").write_text(json.dumps(receipt, indent=2)+"\n")
for run in runs:
    m=run["metrics"]["cpuSamples"]; a=run["metrics"]["allocationSampledBytes"]
    print(run["side"], run["pair"], "CPU", m["weight"], m["threadGroups"], "app",m["applicationWeight"],
          "inclusive",m["inclusiveStackUnion"],"leaf",m["exclusiveLeaf"],"alloc",a["weight"],a["inclusiveStackUnion"])
