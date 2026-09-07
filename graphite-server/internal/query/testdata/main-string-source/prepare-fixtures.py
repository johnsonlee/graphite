"""Copy immutable JVM fixtures, then apply the named correctness mutations."""
import argparse
import json
import pathlib
import shutil
import struct

parser = argparse.ArgumentParser()
parser.add_argument("destination", type=pathlib.Path)
args = parser.parse_args()
here = pathlib.Path(__file__).resolve().parent
mutations = json.loads((here / "property-mutations.json").read_text())
names = {name for file in ("cases.json", "property-cases.json", "offset-cases.json")
         for case in json.loads((here / file).read_text()) for name in case["fixtures"]}
args.destination.mkdir(parents=True, exist_ok=True)
for name in sorted(names):
    base = "clean" if name in ("valid-tag", "unknown-tag") or name.startswith("offset-negative-") else name
    source = here / "all-types" if name in mutations or name == "all-types" else here.parent / "candidate-index" / base
    target = args.destination / name
    if target.exists():
        raise SystemExit(f"Refusing to overwrite {target}; choose a fresh directory")
    shutil.copytree(source, target)
    if name in mutations:
        mutation = mutations[name]
        file = target / "graph.nodedata"
        data = bytearray(file.read_bytes())
        struct.pack_into(">i", data, mutation["offset"], mutation["SID"])
        file.write_bytes(data)
    if name in ("valid-tag", "unknown-tag"):
        file = target / "graph.nodedata"
        data = bytearray(file.read_bytes())
        data[69] = 0 if name == "valid-tag" else 127
        file.write_bytes(data)
    if name.startswith("offset-negative-"):
        node = int(name.rsplit("-", 1)[1])
        file = target / "graph.nodeoffsets"
        data = bytearray(file.read_bytes())
        struct.pack_into(">q", data, 8 + node * 8, -1)  # stored=offset+1
        file.write_bytes(data)
