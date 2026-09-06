#!/usr/bin/env python3
"""Verify downloaded release archives and stage a shell/JVM-free Docker context."""
import argparse
import hashlib
from pathlib import Path, PurePosixPath
import re
import struct
import tarfile


def file_hash(path):
    value = hashlib.sha256()
    with path.open("rb") as file:
        for block in iter(lambda: file.read(1024 * 1024), b""): value.update(block)
    return value.hexdigest()


def stage(assets, version, output):
    if not re.fullmatch(r"[A-Za-z0-9._+-]+", version): raise ValueError("invalid version")
    sums = {}
    for line in (assets / "SHA256SUMS").read_text().splitlines():
        match = re.fullmatch(r"([0-9a-f]{64})  ([A-Za-z0-9._+-]+)", line)
        if not match or match[2] in sums: raise ValueError("invalid or duplicate release checksum")
        sums[match[2]] = match[1]
    if output.exists() and any(output.iterdir()): raise ValueError("Docker staging output must be empty")
    # Validate every selected member before publishing any Docker input.
    contents = {}
    for arch, machine in (("amd64", 62), ("arm64", 183)):
        filename = f"graphite-server-{version}-linux-{arch}.tar.gz"
        if sums.get(filename) != file_hash(assets / filename): raise ValueError("archive checksum mismatch: " + filename)
        files = {}
        total = 0
        with tarfile.open(assets / filename, "r:gz") as archive:
            for member in archive:
                path = PurePosixPath(member.name)
                if (not member.isfile() or path.is_absolute() or ".." in path.parts or str(path) != member.name
                        or member.name in files or member.size < 0 or member.size > 128 << 20):
                    raise ValueError("unsafe archive member: " + member.name)
                if member.name not in ("graphite-server", "LICENSE", "VERSION", "README.md") and not member.name.startswith("licenses/"):
                    raise ValueError("unexpected archive member: " + member.name)
                total += member.size
                if total > 160 << 20: raise ValueError("oversized native archive")
                with archive.extractfile(member) as file: files[member.name] = file.read()
        binary = files.get("graphite-server", b"")
        if len(binary) < 64 or binary[:6] != b"\x7fELF\x02\x01" or struct.unpack_from("<H", binary, 18)[0] != machine:
            raise ValueError("wrong native ELF architecture: " + arch)
        if files.get("VERSION") != (version + "\n").encode() or "LICENSE" not in files:
            raise ValueError("incomplete or wrong-version archive")
        contents[arch] = files
    output.mkdir(parents=True, exist_ok=True)
    for arch, files in contents.items():
        for name, data in files.items():
            path = output / ("linux-" + arch) / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
            path.chmod(0o755 if name == "graphite-server" else 0o644)
    # These directories are copied with uid/gid 1000; empty directories are part
    # of Docker's context tar and need no runtime shell to create them.
    for name in ("app", "data", "tmp", "home/graphite"):
        (output / "writable" / name).mkdir(parents=True, exist_ok=True)
    etc = output / "rootfs/etc"; etc.mkdir(parents=True)
    (etc / "passwd").write_text("graphite:x:1000:1000:Graphite:/home/graphite:/sbin/nologin\n")
    (etc / "group").write_text("graphite:x:1000:\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--assets", type=Path, required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    stage(args.assets.resolve(), args.version, args.output.resolve())


if __name__ == "__main__": main()
