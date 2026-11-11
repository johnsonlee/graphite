"""Recompute retained real64 observations; no benchmark or performance acceptance."""
import argparse
import collections
import gzip
import hashlib
import json
import math
from pathlib import Path

ROOT = Path(__file__).resolve().parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("raw_root", nargs="?", type=Path)
parser.add_argument("--requests-dir", type=Path, default=ROOT, help="Directory containing the two pinned request gzip files")
args = parser.parse_args()
report = json.loads((ROOT / "ci-report.json").read_text())
suite = next(s for s in report["suites"] if s["suite"] == "native-real64")
assert report["passed"] and not report["performanceAcceptance"]
assert not report["errors"]
assert all(c["passed"] for s in report["suites"] for c in s["checks"])
audit = {"measuredHead": report["headSha"], "sourceTree": suite["inputs"]["head"]["sourceTree"],
         "performanceAcceptance": False, "arms": {}, "cases": [], "sha256": {}}
groups = {}
for arm in ("base", "head"):
    path = args.requests_dir / f"{arm}-requests.json.gz"
    records = json.loads(gzip.decompress(path.read_bytes()))
    assert len(records) == 4399
    assert all(r["passed"] and r["error"] is None for r in records)
    assert all(len(set(r["graphIds"])) == 64 for r in records)
    stage_counts = dict(collections.Counter(r["stage"] for r in records))
    audit["arms"][arm] = {"requests": len(records), "stages": stage_counts,
                           "failures": 0, "declaredGraphsPerRequest": 64}
    grouped = collections.defaultdict(list)
    for row in records:
        grouped[(row["case"], row["stage"])].append(row["requestMs"])
    groups[arm] = grouped
    for timing in suite["timings"]:
        if timing["stage"] not in ("pressure", "isolated"):
            continue
        samples = sorted(grouped[(timing["case"], timing["stage"])])
        assert len(samples) == timing["samples" + arm.title()]
        if not samples:
            assert timing[arm + "Ms"] is None
            continue
        percentile = .95 if "p95" in timing["method"] else .5
        value = samples[math.ceil(len(samples) * percentile) - 1]
        assert abs(value - timing[arm + "Ms"]) < 1e-9

for case, stage in groups["head"]:
    if stage != "pressure":
        continue
    entry = {"case": case}
    for arm in ("base", "head"):
        samples = sorted(groups[arm][(case, stage)])
        assert len(samples) == 20
        entry[arm] = {"minMs": samples[0], "p50Ms": samples[9],
                      "p95Ms": samples[18], "maxMs": samples[-1]}
    isolated = sorted(groups["head"].get((case, "isolated"), []))
    if isolated:
        assert len(isolated) == 40
        entry["headIsolated"] = {"p50Ms": isolated[19], "p95Ms": isolated[37]}
    audit["cases"].append(entry)
audit["cases"].sort(key=lambda c: c["head"]["p95Ms"], reverse=True)
assert len(audit["cases"]) == 73
audit["identicalNativeBinary"] = suite["inputs"]["base"]["binarySha256"] == suite["inputs"]["head"]["binarySha256"]
for name in ("ci-report.json", "base-requests.json.gz", "head-requests.json.gz"):
    audit["sha256"][name] = hashlib.sha256(((args.requests_dir if name.endswith(".gz") else ROOT) / name).read_bytes()).hexdigest()
(ROOT / "audit.json").write_text(json.dumps(audit, indent=2) + "\n")
print("Verified 8798 recorded requests and every reported pressure/isolated percentile; 73 cases; report-only.")

# Optional extracted benchmark-evidence artifact, including complete response bodies.
if args.raw_root:
    raw_root = args.raw_root
    oracles = {}
    count = 0
    for arm in ("base", "head"):
        records = json.loads(gzip.decompress((args.requests_dir / f"{arm}-requests.json.gz").read_bytes()))
        for row in records:
            path = raw_root / Path(row["bodyPath"]).relative_to("benchmark-run")
            body = path.read_bytes()
            value = json.loads(body)
            receipt = json.loads(path.with_suffix(".receipt.json").read_text())
            assert receipt["passed"] and receipt["complete"] and receipt["status"] == 200
            assert receipt["bodySha256"] == hashlib.sha256(body).hexdigest()
            assert receipt["bodyBytes"] == len(body)
            assert value["graphCount"] == 64 and value["rowCount"] == len(value["rows"])
            oracles.setdefault(row["case"], value)
            assert value == oracles[row["case"]]
            count += 1
    result = {"verifiedBodies": count, "cases": len(oracles), "completeHTTP200": True,
              "receiptHashesMatch": True, "graphCountEveryResponse": 64,
              "exactParsedResponsesAcrossRevisionsAndRepeats": True,
              "artifactId": 13809902215, "runId": 42912084315}
    (ROOT / "body-audit.json").write_text(json.dumps(result, indent=2) + "\n")
    print(f"Verified {count} full response bodies and receipt hashes.")
