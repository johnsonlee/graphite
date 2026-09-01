#!/usr/bin/env python3

"""Hash ZIP/JAR entry names and bytes while ignoring archive metadata and ordering."""

import hashlib
import struct
import sys
import warnings
import zipfile


def frame(digest: "hashlib._Hash", value: bytes) -> None:
    digest.update(struct.pack(">Q", len(value)))
    digest.update(value)


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit(f"Usage: {sys.argv[0]} <zip-or-jar>")
    digest = hashlib.sha256()
    with zipfile.ZipFile(sys.argv[1]) as archive:
        with warnings.catch_warnings():
            warnings.filterwarnings("ignore", message="Overlapped entries:.*", category=UserWarning)
            entries = [(entry.filename, archive.read(entry)) for entry in archive.infolist()]
        for name, content in sorted(entries):
            frame(digest, name.encode("utf-8"))
            frame(digest, content)
    print(digest.hexdigest())


if __name__ == "__main__":
    main()
