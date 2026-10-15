#!/usr/bin/env python3
"""Wall time and peak RSS of the Apple frontend over an already built SwiftPM package or
Xcode project.

The benchmark gate (`.github/workflows/benchmark.yml`, jobs `apple-frontend` and
`apple-frontend-xcode`) runs this against the base and the candidate `graphite-frontend-apple`
on a pinned corpus: `apple-frontend-corpus.json` (a real 181-file package, the fast smoke),
`apple-frontend-corpus-large.json` (a generated package at iOS-app scale, Linux) and
`apple-frontend-corpus-xcode.json` (the same scale as an iOS `.xcodeproj`, macOS). Every
repetition is one `build --package <dir> --skip-build` or `build --project <xcodeproj>
--derived-data <dir> --skip-build` invocation: the index store already exists, so the
measurement is the frontend's own work (source discovery through `swift package describe` or
the project directory, IndexStoreDB, SwiftSyntax, demangling, IR emission) and never
`swift build` or `xcodebuild`. Rows are written in the JMH result shape the comparator and
the observatory page understand:

    benchmark      apple.frontend.wall            apple.frontend.rss
    params         {"corpus": <label>}            {"corpus": <label>}
    mode           sequential-run
    primaryMetric  score = median over repetitions; ms/op for wall, MiB for peak RSS;
                   scoreConfidence = [min, max]; rawData = every sample

Peak RSS is `ru_maxrss` of the waited frontend process (its `swift-demangle` and
`swift package describe` children included where the kernel folds them in).

A sample is accepted only for valid, equivalent work. Every measured IR is walked as the
stream `ir/graphite_ir.proto` defines (length-delimited chunks: a header first, a trailer
last and nothing after it) and its nodes, edges and strings are counted; the trailer must
agree with those counts, and both must agree with the summary the frontend printed. The
summary's graph shape (files, types, methods, fields, call sites, constants, annotations,
nodes, edges, strings) is recorded on every row, must be the same for every run, and must
equal the shape the corpus manifest pins when it pins one: a truncated or corrupt output,
a run that emits less of the graph and so gets faster, an IR that differs between runs, a
failing run, or a shape other than the pinned one fails the snapshot (exit 2). Changing
the pinned shape is the explicit transition when the frontend intentionally emits more or
less for the same corpus. A manifest's `input` (`package`, the default, or `xcodeproj`) must
match how the harness is invoked, so a project corpus is never measured through the package
path or the other way round.

The framing walk is not the reader's semantic contract (node kinds, dense ids, string ids,
edge endpoints), so `--verify <command>` binds the sample to production validity: after the
runs, the last measured IR (every run wrote the same bytes, or the snapshot was already
refused) is handed to the command with `{ir}` and `{out}` substituted, normally
`java -jar graphite.jar import {ir} -o {out}`, the production importer built from the revision
under test; a non-zero exit refuses the snapshot, and the rows record the verification the
comparator requires.
"""
import argparse, hashlib, json, os, pathlib, re, resource, statistics, subprocess, sys, tempfile, time

BENCHMARK_PREFIX = "apple.frontend."
MODE = "sequential-run"
SUMMARY = re.compile(
    r": (?P<files>\d+) files, (?P<types>\d+) types, (?P<methods>\d+) methods, (?P<fields>\d+) fields, "
    r"(?P<callSites>\d+) call sites, (?P<constants>\d+) constants, (?P<annotations>\d+) annotations; "
    r"(?P<nodes>\d+) nodes, (?P<edges>\d+) edges, (?P<strings>\d+) strings"
)
SHAPE_KEYS = ["files", "types", "methods", "fields", "callSites", "constants", "annotations", "nodes", "edges", "strings"]

# Chunk field numbers of ir/graphite_ir.proto; a batch's repeated entries are field 1.
CHUNK_HEADER, CHUNK_STRINGS, CHUNK_NODES, CHUNK_EDGES, CHUNK_TRAILER = 1, 2, 3, 4, 15


class InvalidIR(Exception):
    pass


def fail(message):
    """A refused snapshot: the reason on stderr, exit 2 like the other harnesses."""
    print(message, file=sys.stderr)
    sys.exit(2)


def read_varint(data, at):
    result, shift = 0, 0
    while True:
        if at >= len(data):
            raise InvalidIR("varint runs past the end of the stream")
        byte = data[at]
        at += 1
        result |= (byte & 0x7F) << shift
        if byte < 0x80:
            return result, at
        shift += 7
        if shift > 63:
            raise InvalidIR("varint longer than 64 bits")


def fields(message):
    """(field number, wire type, value) for every field of a serialized message; values of
    length-delimited fields are their bytes, of the others their number."""
    at = 0
    while at < len(message):
        tag, at = read_varint(message, at)
        number, wire = tag >> 3, tag & 7
        if number == 0:
            raise InvalidIR("field number 0")
        if wire == 0:
            value, at = read_varint(message, at)
        elif wire == 1:
            value, at = message[at:at + 8], at + 8
        elif wire == 2:
            length, at = read_varint(message, at)
            if at + length > len(message):
                raise InvalidIR("length-delimited field runs past the end of its message")
            value, at = message[at:at + length], at + length
        elif wire == 5:
            value, at = message[at:at + 4], at + 4
        else:
            raise InvalidIR(f"unsupported wire type {wire}")
        if at > len(message):
            raise InvalidIR("fixed-width field runs past the end of its message")
        yield number, wire, value


def validate_ir(path, shape):
    """Walks the IR stream and returns (nodes, edges, strings) counted from its batches,
    after checking the framing and that the trailer and the summary agree with them."""
    data = pathlib.Path(path).read_bytes()
    if not data:
        raise InvalidIR("the IR is empty")
    counts = {CHUNK_NODES: 0, CHUNK_EDGES: 0, CHUNK_STRINGS: 0}
    trailer = None
    at, index = 0, 0
    while at < len(data):
        if trailer is not None:
            raise InvalidIR("the IR continues after its trailer")
        length, at = read_varint(data, at)
        chunk = data[at:at + length]
        if len(chunk) != length:
            raise InvalidIR("the IR is truncated inside a chunk")
        at += length
        kinds = [(number, value) for number, wire, value in fields(chunk) if wire == 2]
        if len(kinds) != 1:
            raise InvalidIR(f"chunk {index} carries {len(kinds)} payloads, expected one")
        number, payload = kinds[0]
        if index == 0 and number != CHUNK_HEADER:
            raise InvalidIR("the IR does not start with a header")
        if number in counts:
            counts[number] += sum(1 for n, w, _ in fields(payload) if n == 1 and w == 2)
        elif number == CHUNK_TRAILER:
            trailer = {n: v for n, w, v in fields(payload) if w == 0}
        index += 1
    if trailer is None:
        raise InvalidIR("the IR has no trailer")
    declared = (trailer.get(1, 0), trailer.get(2, 0), trailer.get(3, 0))
    counted = (counts[CHUNK_NODES], counts[CHUNK_EDGES], counts[CHUNK_STRINGS])
    if declared != counted:
        raise InvalidIR(f"the trailer declares nodes/edges/strings {declared}, the stream carries {counted}")
    reported = (shape["nodes"], shape["edges"], shape["strings"])
    if reported != counted:
        raise InvalidIR(f"the frontend reported nodes/edges/strings {reported}, the IR carries {counted}")
    if counted[0] == 0:
        raise InvalidIR("the IR carries no nodes")
    return counted


def parse_summary(log):
    """The frontend's summary line as a shape dict, or an InvalidIR when it printed none."""
    matches = [SUMMARY.search(line) for line in pathlib.Path(log).read_text(errors="replace").splitlines()]
    matches = [m for m in matches if m]
    if not matches:
        raise InvalidIR(f"the frontend printed no summary line; see {log}")
    return {key: int(value) for key, value in matches[-1].groupdict().items()}


def input_arguments(args):
    """The frontend arguments naming the corpus: a package root, or a project with the derived
    data its index store lives in."""
    if args.package is not None:
        return "package", ["--package", args.package]
    if args.derived_data is None:
        fail("--project needs --derived-data: the derived data of the build whose index store is measured")
    return "xcodeproj", ["--project", args.project, "--derived-data", args.derived_data]


def corpus_reference(corpus):
    """What the rows record about where the corpus came from: a repository at a commit, or
    the generator block a generated corpus is reproduced from."""
    if "generator" in corpus:
        return {"generator": corpus["generator"]}
    return {"repository": corpus["repository"], "commit": corpus["commit"]}


def run_once(frontend, corpus_arguments, out, log):
    """One skip-build run: wall milliseconds, peak RSS bytes, graph shape, IR sha256.
    The IR is validated against the shape the frontend reported before it counts."""
    started = time.perf_counter()
    with open(log, "ab") as sink:
        process = subprocess.Popen(
            [frontend, "build", *corpus_arguments, "--skip-build", "--out", str(out)],
            stdout=sink, stderr=sink,
        )
        _, status, usage = os.wait4(process.pid, 0)
    wall_ms = (time.perf_counter() - started) * 1000
    code = os.waitstatus_to_exitcode(status)
    if code != 0:
        fail(f"frontend exited with status {code}; see {log}")
    # Linux reports kilobytes, macOS bytes.
    max_rss = usage.ru_maxrss * (1 if sys.platform == "darwin" else 1024)
    try:
        shape = parse_summary(log)
        validate_ir(out, shape)
    except InvalidIR as error:
        fail(f"the measured IR is not valid Graphite IR: {error}")
    digest = hashlib.sha256(pathlib.Path(out).read_bytes()).hexdigest()
    return wall_ms, max_rss, shape, digest


def verify(command, ir, scratch):
    """Runs the production reader over a measured IR: `{ir}` and `{out}` are substituted, a
    non-zero exit refuses the snapshot with the reader's last lines of output."""
    out = pathlib.Path(scratch) / "verified-graph"
    rendered = command.replace("{ir}", str(ir)).replace("{out}", str(out))
    completed = subprocess.run(rendered, shell=True, capture_output=True, text=True)
    if completed.returncode != 0:
        tail = "\n".join((completed.stdout + completed.stderr).strip().splitlines()[-20:])
        fail(f"the production reader refused the measured IR (exit {completed.returncode}): {rendered}\n{tail}")
    return {"command": command, "passed": True}


def metric(samples, unit):
    return {
        "score": round(statistics.median(samples), 3),
        "scoreUnit": unit,
        "scoreConfidence": [round(min(samples), 3), round(max(samples), 3)],
        "rawData": [[round(v, 3) for v in samples]],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--frontend", required=True, help="graphite-frontend-apple executable")
    corpus_input = parser.add_mutually_exclusive_group(required=True)
    corpus_input.add_argument("--package", help="SwiftPM package root, already built (index store present)")
    corpus_input.add_argument("--project", help=".xcodeproj or .xcworkspace, already built with xcodebuild into --derived-data")
    parser.add_argument("--derived-data", help="the derived data directory of the xcodebuild of --project (its index store is read)")
    parser.add_argument("--corpus", required=True, help="corpus manifest (apple-frontend-corpus*.json)")
    parser.add_argument("--repetitions", type=int, default=5)
    parser.add_argument("--warmup", type=int, default=1, help="unrecorded runs before the repetitions")
    parser.add_argument("--verify", help="a command run over the last measured IR with {ir} and {out} substituted, e.g. "
                        "'java -jar graphite.jar import {ir} -o {out}'; a non-zero exit refuses the snapshot")
    parser.add_argument("--log", required=True, help="where the frontend's stderr of every run goes")
    parser.add_argument("--out", required=True, help="result rows (JSON)")
    args = parser.parse_args()
    corpus = json.load(open(args.corpus))
    label = corpus["label"]
    kind, corpus_arguments = input_arguments(args)
    expected_input = corpus.get("input", "package")
    if expected_input != kind:
        fail(f"the manifest is a {expected_input} corpus, the harness was given a {kind}")
    pathlib.Path(args.log).write_bytes(b"")
    walls, rsses, shapes, digests = [], [], [], set()
    verified = None
    with tempfile.TemporaryDirectory(prefix="apple-frontend-bench-") as scratch:
        out = pathlib.Path(scratch) / "corpus.graphite-ir"
        runs = args.warmup + args.repetitions
        for index in range(runs):
            wall, rss, shape, digest = run_once(args.frontend, corpus_arguments, out, args.log)
            if index < runs - 1:
                out.unlink()
            if shape not in shapes:
                shapes.append(shape)
            digests.add(digest)
            if index < args.warmup:
                continue
            walls.append(wall)
            rsses.append(rss / (1024 * 1024))
        if len(digests) != 1:
            fail(f"the frontend wrote {len(digests)} different IR streams over {runs} runs")
        if len(shapes) != 1:
            fail(f"the graph shape changed between runs: {shapes}")
        if args.verify:
            verified = verify(args.verify, out, scratch)
    (shape,) = shapes
    count = shape["files"]
    expected = corpus.get("files")
    if expected is not None and count != expected:
        fail(f"the corpus has {count} Swift files, the manifest pins {expected}")
    pinned = corpus.get("shape")
    if pinned is not None and any(shape.get(key) != pinned.get(key) for key in SHAPE_KEYS):
        changed = ", ".join(f"{key} {pinned.get(key)} -> {shape.get(key)}" for key in SHAPE_KEYS if shape.get(key) != pinned.get(key))
        fail(f"the graph shape differs from the corpus pin ({changed}); pin the new shape in the manifest if the change is intended")
    (digest,) = digests
    common = {
        "params": {"corpus": label},
        "mode": MODE,
        "files": count,
        "shape": shape,
        "determinism": {"identical": True, "sha256": digest},
        "input": kind,
        "verified": verified,
        "corpus": corpus_reference(corpus),
    }
    rows = [
        {"benchmark": BENCHMARK_PREFIX + "wall", "primaryMetric": metric(walls, "ms/op"), **common},
        {"benchmark": BENCHMARK_PREFIX + "rss", "primaryMetric": metric(rsses, "MiB"), **common},
    ]
    json.dump(rows, open(args.out, "w"), indent=2)
    print(f"{label}: {count} files, {shape['nodes']} nodes, {shape['edges']} edges, wall {rows[0]['primaryMetric']['score']} ms, "
          f"peak RSS {rows[1]['primaryMetric']['score']} MiB over {args.repetitions} runs (+{args.warmup} warm-up), IR sha256 {digest[:12]}"
          + (", verified by the production reader" if verified else ", not verified by the production reader"))


if __name__ == "__main__":
    main()
