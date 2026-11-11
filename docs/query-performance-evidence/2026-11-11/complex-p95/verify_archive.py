#!/usr/bin/env python3
"""Verify retained evidence bytes, optionally extract regular files to a fresh directory."""
import argparse
import hashlib
import json
import tarfile
from pathlib import Path, PurePosixPath


def require(condition, message):
    if not condition:
        raise ValueError(message)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--extract", type=Path)
    parser.add_argument("--archive", type=Path, help="External archive path; defaults to the manifest-adjacent archive")
    parser.add_argument("--manifest", type=Path, default=Path("archive-manifest.json"))
    args = parser.parse_args()
    root = Path(__file__).resolve().parent
    manifest_path = args.manifest if args.manifest.is_absolute() else root / args.manifest
    manifest = json.loads(manifest_path.read_text())
    archive = args.archive if args.archive else manifest_path.parent / manifest["archive"]
    require(hashlib.sha256(archive.read_bytes()).hexdigest() == manifest["archiveSha256"],
            "Archive SHA256 mismatch")
    require(archive.stat().st_size == manifest["archiveBytes"], "Archive size mismatch")
    expected = {row["path"]: row for row in manifest["files"]}
    require(len(expected) == len(manifest["files"]), "Duplicate manifest member")
    if args.extract:
        destination = args.extract.resolve()
        destination.mkdir(parents=True, exist_ok=True)
        require(not any(destination.iterdir()), "Extraction requires an empty directory")
    seen = set()
    with tarfile.open(archive, "r:gz") as tar:
        for entry in tar:
            path = PurePosixPath(entry.name)
            require(entry.isfile(), f"Non-regular archive member: {entry.name}")
            require(not path.is_absolute() and ".." not in path.parts,
                    f"Unsafe archive member path: {entry.name}")
            require(entry.name in expected, f"Unexpected archive member: {entry.name}")
            require(entry.name not in seen, f"Duplicate archive member: {entry.name}")
            seen.add(entry.name)
            body = tar.extractfile(entry).read()
            row = expected[entry.name]
            require(len(body) == row["bytes"], f"Member size mismatch: {entry.name}")
            require(hashlib.sha256(body).hexdigest() == row["sha256"],
                    f"Member SHA256 mismatch: {entry.name}")
            if args.extract:
                target = destination.joinpath(*path.parts)
                target.parent.mkdir(parents=True, exist_ok=True)
                with target.open("xb") as output:
                    output.write(body)
    require(seen == set(expected), "Missing archive members")
    print(json.dumps({"verifiedFiles": len(seen), "archiveSha256": manifest["archiveSha256"],
                      "extractedTo": str(destination) if args.extract else None}))


if __name__ == "__main__":
    main()
