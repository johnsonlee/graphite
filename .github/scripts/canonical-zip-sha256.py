#!/usr/bin/env python3

"""Hash ZIP/JAR entry names and bytes while ignoring archive metadata and ordering."""

import hashlib
import binascii
import bz2
import struct
import sys
import zlib
import zipfile


def frame(digest: "hashlib._Hash", value: bytes) -> None:
    digest.update(struct.pack(">Q", len(value)))
    digest.update(value)


def read_entry(source, entry: zipfile.ZipInfo) -> bytes:
    """Read one central-directory entry without ZipFile's false-positive overlap guard."""
    source.seek(entry.header_offset)
    header = source.read(30)
    if len(header) != 30:
        raise zipfile.BadZipFile(f"Truncated local header for {entry.filename!r}")
    signature, _, flags, compression, _, _, _, _, _, name_length, extra_length = struct.unpack(
        "<IHHHHHIIIHH", header
    )
    if signature != 0x04034B50:
        raise zipfile.BadZipFile(f"Invalid local header for {entry.filename!r}")
    if flags & 1:
        raise zipfile.BadZipFile(f"Encrypted entry is unsupported: {entry.filename!r}")
    source.seek(name_length + extra_length, 1)
    compressed = source.read(entry.compress_size)
    if len(compressed) != entry.compress_size:
        raise zipfile.BadZipFile(f"Truncated data for {entry.filename!r}")
    if compression == zipfile.ZIP_STORED:
        content = compressed
    elif compression == zipfile.ZIP_DEFLATED:
        content = zlib.decompress(compressed, -15)
    elif compression == zipfile.ZIP_BZIP2:
        content = bz2.decompress(compressed)
    else:
        raise zipfile.BadZipFile(
            f"Unsupported compression method {compression} for {entry.filename!r}"
        )
    if len(content) != entry.file_size or (binascii.crc32(content) & 0xFFFFFFFF) != entry.CRC:
        raise zipfile.BadZipFile(f"Size or CRC mismatch for {entry.filename!r}")
    return content


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit(f"Usage: {sys.argv[0]} <zip-or-jar>")
    digest = hashlib.sha256()
    with open(sys.argv[1], "rb") as source, zipfile.ZipFile(source) as archive:
        entries = [(entry.filename, read_entry(source, entry)) for entry in archive.infolist()]
        for name, content in sorted(entries):
            frame(digest, name.encode("utf-8"))
            frame(digest, content)
    print(digest.hexdigest())


if __name__ == "__main__":
    main()
