"""Recount existing Method gate collapsed profiles; no JVM, recording, or graph-body reads."""
import collections
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent
BENCHMARK = "io.johnsonlee.graphite.cli.MethodDiscoveryCompatibilityBenchmark."
ENGINE = "io.johnsonlee.graphite.cypher."


def sha_small(path):
    assert path.stat().st_size < 1_000_000
    return hashlib.sha256(path.read_bytes()).hexdigest()


def background(thread, frames):
    if "CompilerThread" in thread or any("CompileBroker::compiler_thread_loop" in f for f in frames):
        return "compiler"
    if thread.startswith(("Java: GC Thread", "Java: G1 ")) or any(
        "ConcurrentGCThread::run" in f for f in frames
    ):
        return "gcBackground"
    return None


def analyze(run_name):
    directory = ROOT / (run_name + "-analysis")
    source = directory / "analysis.json"
    data = json.loads(source.read_text())
    assert data["validation"]["passed"]
    assert data["validation"]["queryCount"] == 1 and data["validation"]["catalogOrderChecked"]
    assert len(data["queries"]) == 1
    query = data["queries"][0]
    assert query["id"] == run_name
    assert "methodScenarioGate-jmh-worker" in query["requestThread"]
    assert sha_small(Path(data["tsv"])) == data["tsvSha256"]
    result = {
        "id": run_name, "analysis": str(source), "sourceJfrSha256Recorded": data["jfrSha256"],
        "tsvSha256Verified": data["tsvSha256"], "validation": data["validation"],
        "requestThread": query["requestThread"], "traceDurationNanos": query["traceDurationNanos"],
        "jmhWallNanos": query["tsvLatencyNanos"], "latencyGapNanos": query["latencyGapNanos"], "metrics": {},
    }
    for metric in ("cpuSamples", "allocationSampledBytes"):
        path = directory / query["collapsed"][metric]
        groups = collections.Counter()
        inclusive = collections.Counter()
        thread_groups = collections.Counter()
        oracle_leaves = collections.Counter()
        engine_leaves = collections.Counter()
        oracle_by_thread = collections.Counter()
        engine_by_thread = collections.Counter()
        total = 0
        for line in path.read_text().splitlines():
            stack, raw_weight = line.rsplit(" ", 1)
            weight = int(raw_weight)
            assert weight > 0
            total += weight
            thread, *frames = stack.split(";")
            methods = [f.split("(", 1)[0] for f in frames]
            expected = BENCHMARK + "expected" in methods
            expected_by_graph = BENCHMARK + "expectedByGraph" in methods
            oracle = expected or expected_by_graph
            engine = any(f.startswith(ENGINE) for f in frames)
            jmh = thread == query["requestThread"]
            bg = background(thread, frames)
            server = thread.startswith(("graphite-cypher-", "JettyServerThreadPool-"))
            tgroup = bg or ("jmh" if jmh else "serverNamed" if server else "otherThread")
            thread_groups[tgroup] += weight
            for condition, label in [(expected, "expected"), (expected_by_graph, "expectedByGraph"),
                                     (oracle, "referenceUnion"), (engine, "enginePackageUnion"),
                                     (oracle and engine, "referenceEngineIntersection"),
                                     (jmh and not oracle, "jmhNonReference"),
                                     (jmh and engine, "jmhEngineIntersection")]:
                if condition:
                    inclusive[label] += weight
            if oracle:
                oracle_by_thread[thread] += weight
                oracle_leaves[methods[-1]] += weight
            if engine:
                engine_by_thread[thread] += weight
                engine_leaves[methods[-1]] += weight
            # Exhaustive disjoint partition. No background CPU is assigned to the action it overlaps.
            if bg:
                assert not oracle and not engine
                group = bg
            elif oracle:
                group = "reference"
            elif engine:
                group = "engineStack"
            elif jmh:
                group = "jmhNonReferenceNonEngine"
            elif server:
                group = "serverOther"
            else:
                group = "other"
            groups[group] += weight
        expected_metric = query["metrics"][metric]
        assert total == expected_metric["weight"] == sum(groups.values()) == sum(thread_groups.values())
        assert not expected_metric["missingStackEvents"] and not expected_metric["truncatedStackEvents"]
        assert inclusive["referenceEngineIntersection"] == 0
        assert inclusive["jmhEngineIntersection"] == 0
        result["metrics"][metric] = {
            "totalWeight": total, "eventCount": expected_metric["eventCount"], "unit": expected_metric["unit"],
            "inclusiveStackUnions": dict(inclusive), "disjointGroups": dict(groups),
            "threadGroups": dict(thread_groups), "percentOfAllWeight": {k: 100 * v / total for k, v in groups.items()},
            "referenceByThread": dict(oracle_by_thread), "engineByThread": dict(engine_by_thread),
            "referenceTopLeaf": oracle_leaves.most_common(8), "engineTopLeaf": engine_leaves.most_common(8),
            "collapsed": str(path), "collapsedBytes": path.stat().st_size,
            "weightConserved": True, "missingOrTruncatedStacks": 0,
        }
    return result


runs = [analyze(f"method-{scenario}-{i}") for scenario in ("4-count", "36-or") for i in (1, 2, 3)]
receipt = {
    "diagnosticOnly": True, "inputReceipt": str(ROOT / "input-receipt.json"),
    "inputReceiptSha256": sha_small(ROOT / "input-receipt.json"),
    "scriptSha256": sha_small(Path(__file__)),
    "rules": [
        "Reference union matches exact benchmark expected / expectedByGraph methods; count each stack once.",
        "Engine union is any io.johnsonlee.graphite.cypher.* frame, including descendants outside that package.",
        "Engine union is not engine-exclusive CPU: HTTP guard or serialization may also have cypher frames.",
        "Disjoint group precedence: compiler/GC background, reference, engine stack, other JMH client, other named server, other.",
        "Compiler is identified by thread name or CompileBroker::compiler_thread_loop for unnamed native threads.",
        "GC background is identified by GC/G1 thread names or ConcurrentGCThread::run; no JIT/GC is attributed to a co-occurring phase.",
        "JMH client non-reference includes response normalization/digest validation; it is not reference expected construction.",
        "All percentages use every recorded CPU sample or every recorded allocation weight as the denominator.",
        "Sampled TLAB/outside-TLAB bytes are not exact allocation totals or object counts.",
    ],
    "limitations": [
        "Existing exact Method trace analysis is consumed; JFR alignment is not independently regenerated.",
        "TSV hashes and collapsed totals are checked; no large JFR or graph hashes were recomputed.",
        "Input graph hashes are pre-capture evidence only; this audit does not perform the pending post-capture hash.",
        "Mac residentSet auxiliary fields are not treated as RSS.",
        "These main-only Mac profiles do not explain the original Linux CI comparison failures.",
    ],
    "runs": runs,
}
(ROOT / "independent-method-sample-receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
for run in runs:
    print(run["id"])
    for metric, result in run["metrics"].items():
        print(metric, "total", result["totalWeight"], "groups", result["disjointGroups"],
              "unions", result["inclusiveStackUnions"])
