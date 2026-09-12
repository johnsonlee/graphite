"""Recount existing collapsed samples and read only 76 bytes per sidecar; no JVM or measurement."""

import argparse
import collections
import hashlib
import json
import re
import struct
from pathlib import Path


def header_census(manifest):
    rows = []
    for line in manifest.read_text().splitlines():
        if not line or line.startswith("#"):
            continue
        fields = line.split("\t")
        path = Path(fields[1]) / "graph.callsite-string-index"
        with path.open("rb") as stream:
            header = stream.read(76)
        assert len(header) == 76
        magic, version, strings, nodes = struct.unpack_from(">4i", header)
        assert magic == 0x47524353 and version == 2
        unique = struct.unpack_from(">4i", header, 48)
        postings = struct.unpack_from(">i", header, 64)[0]
        assert strings > 0 and nodes > 0 and postings > 0
        assert all(0 <= count <= strings for count in unique)
        rows.append({
            "id": fields[0], "path": str(path), "bytesRead": len(header),
            "headerSha256": hashlib.sha256(header).hexdigest(),
            "stringCount": strings, "callSiteCount": nodes,
            "uniquePropertyStringCounts": list(unique), "trigramPostingCount": postings,
            "intCallbackCalls": 2 * sum(unique) + 4 * nodes,
            "longCallbackCalls": strings + postings,
        })
    assert len(rows) == 64 and len({row["id"] for row in rows}) == 64
    return rows


def samples(directory):
    counts = collections.Counter()
    inputs = []
    for metric in ("cpuSamples", "allocationSampledBytes"):
        files = sorted(directory.glob(f"*-or-four-broad-distinct-*.{metric}.collapsed"))
        ids = [int(re.search(r"-distinct-(\d+)\.", path.name)[1]) for path in files]
        assert sorted(ids) == list(range(40))
        for path in files:
            count = 0
            for line in path.read_text().splitlines():
                stack, weight = line.rsplit(" ", 1)
                weight = int(weight)
                assert weight > 0
                count += weight
                thread = stack.split(";", 1)[0]
                application = thread.startswith(("graphite-", "broad-query-pressure-worker"))
                validator = "PersistentIndexViewValidator" in stack
                counts[metric + "All"] += weight
                if application:
                    counts[metric + "Application"] += weight
                if validator:
                    counts[metric + "Validator"] += weight
                    if application:
                        counts[metric + "ApplicationValidator"] += weight
                leaf = stack.rsplit(";", 1)[-1].split("(", 1)[0]
                if metric == "allocationSampledBytes":
                    if leaf in ("java.lang.Integer.valueOf", "java.lang.Long.valueOf"):
                        counts["boxedLeafAll"] += weight
                        counts["boxedLeafValidator" if validator else "boxedLeafOutsideValidator"] += weight
                        if validator:
                            counts["validatorIntegerLeaf" if "Integer" in leaf else "validatorLongLeaf"] += weight
                            if "Long" in leaf:
                                key = "validatorDefaultLongLeaf" if "updateLongs$default" in stack else "validatorExplicitLongLeaf"
                                counts[key] += weight
                    if validator and leaf == "java.nio.HeapByteBuffer.<init>":
                        counts["validatorHeapByteBufferLeaf"] += weight
            inputs.append({"path": str(path), "weight": count, "fileBytes": path.stat().st_size})
    return dict(counts), inputs


def old_dense(root):
    rows = []
    for number in (3, 4, 5):
        directory = root / f"cpu-{number}-analysis-v1" / "collapsed"
        for metric in ("cpuSamples", "allocationSampledBytes"):
            path = directory / f"30-global-wide-wrapped-case-insensitive-distinct-dense.{metric}.collapsed"
            total = validator = 0
            for line in path.read_text().splitlines():
                stack, weight = line.rsplit(" ", 1)
                total += int(weight)
                if "PersistentIndexViewValidator" in stack:
                    validator += int(weight)
            rows.append({"recording": number, "metric": metric, "path": str(path),
                         "totalWeight": total, "validatorWeight": validator})
    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--cold-collapsed", type=Path, required=True)
    parser.add_argument("--old-profile-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    headers = header_census(args.manifest)
    counts, inputs = samples(args.cold_collapsed)
    totals = {key: sum(row[key] for row in headers) for key in
              ("stringCount", "callSiteCount", "trigramPostingCount", "intCallbackCalls", "longCallbackCalls")}
    totals["uniquePropertyStringCount"] = sum(sum(row["uniquePropertyStringCounts"]) for row in headers)
    receipt = {
        "diagnosticOnly": True,
        "scope": "40 DISTINCT query windows in one existing CPU/alloc recording; not 40 independent JVM recordings",
        "manifest": str(args.manifest), "headerBytesRead": 76 * len(headers),
        "headerTotals": totals, "sampleCounts": counts,
        "percentages": {
            "validatorOfAllCpu": 100 * counts["cpuSamplesValidator"] / counts["cpuSamplesAll"],
            "validatorOfApplicationCpu": 100 * counts["cpuSamplesApplicationValidator"] / counts["cpuSamplesApplication"],
            "boxedLeafOfAllSampledBytes": 100 * counts["boxedLeafAll"] / counts["allocationSampledBytesAll"],
        },
        "oldDense": old_dense(args.old_profile_root), "collapsedInputs": inputs,
        "limitations": [
            "Existing aligned collapsed evidence is recounted; JFR alignment is not regenerated here.",
            "Inclusive validator counts are stack unions; leaf boxing is exclusive sampled attribution.",
            "TLAB/outside-TLAB weights are sampled bytes, not exact allocations or object counts.",
            "Header formulas describe one successful full validation per view, not measured invocation frequency.",
            "Only headers were read; header hashes do not authenticate sidecar bodies or CRC integrity.",
        ],
    }
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "header-census.json").write_text(json.dumps(headers, indent=2) + "\n")
    (args.output / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
    print(json.dumps({"totals": totals, "counts": counts, "percentages": receipt["percentages"]}, indent=2))


if __name__ == "__main__":
    main()
