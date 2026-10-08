#!/usr/bin/env python3
"""Independently inventory JVM method identities; this is not a performance benchmark."""
import argparse
import collections
import hashlib
import json
from pathlib import Path
import re
import subprocess
import zipfile


class Reader:
    def __init__(self, data):
        self.data, self.position = data, 0

    def integer(self, size):
        value = int.from_bytes(self.data[self.position:self.position + size], "big")
        if self.position + size > len(self.data):
            raise ValueError("Truncated classfile")
        self.position += size
        return value

    def skip(self, size):
        self.position += size
        if self.position > len(self.data):
            raise ValueError("Truncated classfile")

    def attributes(self):
        for _ in range(self.integer(2)):
            self.integer(2)
            self.skip(self.integer(4))


def methods(data):
    reader = Reader(data)
    assert reader.integer(4) == 0xCAFEBABE
    reader.skip(4)
    count = reader.integer(2)
    pool = [None] * count
    index = 1
    while index < count:
        tag = reader.integer(1)
        if tag == 1:
            length = reader.integer(2)
            pool[index] = data[reader.position:reader.position + length].decode("utf8", "replace")
            reader.skip(length)
        elif tag in (7, 8, 16, 19, 20):
            pool[index] = reader.integer(2)
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            reader.skip(4)
        elif tag in (5, 6):
            reader.skip(8)
            index += 1
        elif tag == 15:
            reader.skip(3)
        else:
            raise ValueError(f"Unknown constant-pool tag {tag}")
        index += 1
    reader.integer(2)
    owner = pool[pool[reader.integer(2)]]
    reader.integer(2)
    reader.skip(2 * reader.integer(2))
    for _ in range(reader.integer(2)):
        reader.skip(6)
        reader.attributes()
    result = []
    for _ in range(reader.integer(2)):
        reader.integer(2)
        name, descriptor = pool[reader.integer(2)], pool[reader.integer(2)]
        result.append((name, descriptor))
        reader.attributes()
    return owner, result


def parameters(descriptor, collapse_dimensions):
    position, result = 1, []
    primitives = dict(B="byte", C="char", D="double", F="float", I="int", J="long", S="short", Z="boolean")
    while descriptor[position] != ")":
        dimensions = 0
        while descriptor[position] == "[":
            dimensions += 1
            position += 1
        if descriptor[position] == "L":
            end = descriptor.index(";", position)
            name = descriptor[position + 1:end].replace("/", ".")
            position = end + 1
        else:
            name = primitives[descriptor[position]]
            position += 1
        result.append(name + "[]" * (int(bool(dimensions)) if collapse_dimensions else dimensions))
    return ",".join(result)


def audit(jar, verify_javap):
    old, accurate, source_rows = collections.defaultdict(set), set(), []
    skipped = 0
    with zipfile.ZipFile(jar) as archive:
        for path in archive.namelist():
            if not path.endswith(".class") or path.endswith("module-info.class"):
                continue
            owner, declarations = methods(archive.read(path))
            if path[:-6] != owner:
                skipped += 1
                continue
            owner = owner.replace("/", ".")
            for name, descriptor in declarations:
                legacy = f"{owner}.{name}({parameters(descriptor, True)})"
                current = f"{owner}.{name}({parameters(descriptor, False)})"
                old[legacy].add(current)
                accurate.add(current)
                source_rows.append((owner, name, descriptor, legacy))
    collisions = {key: sorted(values) for key, values in sorted(old.items()) if len(values) > 1}
    verified = 0
    if verify_javap:
        targets = collections.defaultdict(set)
        for owner, name, descriptor, legacy in source_rows:
            if legacy in collisions:
                targets[owner].add((name, descriptor))
        for owner, declarations in sorted(targets.items()):
            text = subprocess.check_output(
                ["javap", "-J-Xmx512m", "-p", "-s", "-classpath", str(jar), owner], text=True
            )
            found, heading = set(), ""
            for line in text.splitlines():
                if "descriptor:" in line:
                    descriptor = line.split("descriptor:", 1)[1].strip()
                    for name, expected in declarations:
                        token = owner if name == "<init>" else name
                        if descriptor == expected and re.search(r"(?:^|\s)" + re.escape(token) + r"\(", heading):
                            found.add((name, descriptor))
                elif line.strip():
                    heading = line.strip()
            assert found == declarations, (owner, declarations - found)
            verified += len(found)
    return {
        "sha256": hashlib.sha256(jar.read_bytes()).hexdigest(),
        "legacy_methods": len(old), "accurate_methods": len(accurate),
        "recovered_methods": len(accurate) - len(old), "collision_groups": collisions,
        "skipped_path_mismatches": skipped, "javap_verified_descriptors": verified,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", action="append", required=True, metavar="NAME=PATH")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--javap", action="store_true")
    args = parser.parse_args()
    result = {}
    for specification in args.jar:
        name, path = specification.split("=", 1)
        result[name] = audit(Path(path), args.javap)
    args.output.write_text(json.dumps(result, indent=2) + "\n")


if __name__ == "__main__":
    main()
