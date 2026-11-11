#!/usr/bin/env python3
"""Verify retained evidence bytes, optionally extract regular files to a fresh directory."""
import argparse
import hashlib
import json
import tarfile
from pathlib import Path, PurePosixPath


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--extract", type=Path)
    parser.add_argument("--manifest", type=Path, default=Path("archive-manifest.json"))
    args = parser.parse_args()
    root = Path(__file__).resolve().parent
    manifest_path = args.manifest if args.manifest.is_absolute() else root / args.manifest
    manifest = json.loads(manifest_path.read_text())
    archive = root / manifest["archive"]
    assert hashlib.sha256(archive.read_bytes()).hexdigest() == manifest["archiveSha256"]
    assert archive.stat().st_size == manifest["archiveBytes"]
    expected = {row["path"]: row for row in manifest["files"]}
    assert len(expected) == len(manifest["files"])
    if args.extract:
        destination = args.extract.resolve()
        destination.mkdir(parents=True, exist_ok=True)
        assert not any(destination.iterdir()), "Extraction requires an empty directory"
    seen = set()
    with tarfile.open(archive, "r:gz") as tar:
        for entry in tar:
            path = PurePosixPath(entry.name)
            assert entry.isfile() and not path.is_absolute() and ".." not in path.parts
            assert entry.name in expected and entry.name not in seen
            seen.add(entry.name)
            body = tar.extractfile(entry).read()
            row = expected[entry.name]
            assert len(body) == row["bytes"] and hashlib.sha256(body).hexdigest() == row["sha256"]
            if args.extract:
                target = destination.joinpath(*path.parts)
                target.parent.mkdir(parents=True, exist_ok=True)
                with target.open("xb") as output:
                    output.write(body)
    assert seen == set(expected)
    print(json.dumps({"verifiedFiles": len(seen), "archiveSha256": manifest["archiveSha256"],
                      "extractedTo": str(destination) if args.extract else None}))


if __name__ == "__main__":
    main()
