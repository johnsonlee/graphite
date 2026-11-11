#!/usr/bin/env python3
"""Preserve a completed local-query run and optional controller log without rewriting source evidence."""
import argparse
import gzip
import hashlib
import io
import json
import tarfile
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("run", type=Path)
    parser.add_argument("--output-dir", type=Path, default=Path(__file__).resolve().parent)
    parser.add_argument("--retain-failed", action="store_true")
    args = parser.parse_args()
    run = args.run.resolve()
    report = json.loads((run / "report.json").read_text())
    assert report["complete"] is True or (args.retain_failed and report["errors"]), "Do not archive an active incomplete run"
    output = args.output_dir.resolve()
    output.mkdir(parents=True, exist_ok=True)
    archive = output / f"{run.name}.tar.gz"
    manifest_path = output / f"{run.name}-manifest.json"
    assert not archive.exists() and not manifest_path.exists(), "Preserve existing archives; use a distinct run name"
    records = []
    paths = sorted(p for p in run.rglob("*") if p.is_file())
    log = run.with_suffix(".log")
    if log.exists():
        paths.append(log)
    with archive.open("xb") as raw, gzip.GzipFile(filename="", fileobj=raw, mode="wb", compresslevel=1, mtime=0) as zipped, tarfile.open(fileobj=zipped, mode="w") as tar:
        for path in paths:
            assert not path.is_symlink()
            body = path.read_bytes()
            name = str(path.relative_to(run.parent))
            entry = tarfile.TarInfo(name)
            entry.size, entry.mode, entry.mtime = len(body), 0o644, 0
            tar.addfile(entry, io.BytesIO(body))
            records.append({"path": name, "bytes": len(body), "sha256": hashlib.sha256(body).hexdigest()})
    manifest = {"schema": "graphite-complex-p95-evidence-archive-v1", "archive": archive.name,
                "archiveSha256": hashlib.sha256(archive.read_bytes()).hexdigest(), "archiveBytes": archive.stat().st_size,
                "files": records, "mode": report["mode"], "order": report["order"], "workload": report["workload"],
                "notes": ["Original report, samples, complete/partial bodies, receipts, journals, seals and logs preserved.",
                          "Original graph payloads and executables excluded; their identity evidence retained.",
                          "No change to performance acceptance; interpret each round separately."]}
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")
    print(json.dumps({"files": len(records), "rawBytes": sum(r["bytes"] for r in records),
                      "archiveBytes": archive.stat().st_size, "archiveSha256": manifest["archiveSha256"]}))


if __name__ == "__main__":
    main()
