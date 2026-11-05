#!/usr/bin/env python3
"""Wall time and peak RSS of the Apple frontend over an already built SwiftPM package or
Xcode project.

The benchmark gate (`.github/workflows/benchmark.yml`, jobs `apple-frontend` and
`apple-frontend-xcode`) runs this against the base and the candidate `graphite-frontend-apple`
on a pinned corpus: `apple-frontend-corpus.json` (a real 181-file package, the fast smoke),
`apple-frontend-corpus-large.json` (Swift Package Manager, Linux) and
`apple-frontend-corpus-xcode.json` (Signal's mixed-language workspace, macOS). Every
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
import argparse, hashlib, json, os, pathlib, re, resource, shlex, statistics, subprocess, sys, tempfile, time

# graphite-apple-real-source-v1: transition marker for base-owned CI controls.
CORPUS_SCHEMA = "graphite-apple-frontend-corpus-v2"

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


class SnapshotRefused(SystemExit):
    def __init__(self, message):
        super().__init__(2)
        self.message = message

    def __str__(self):
        return self.message


def fail(message):
    """A refused snapshot: retain the reason in the journal and exit 2."""
    print(message, file=sys.stderr)
    raise SnapshotRefused(message)


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


def parse_summary(log, start_offset=0):
    """The frontend's summary line as a shape dict, or an InvalidIR when it printed none."""
    with open(log, "rb") as source:
        source.seek(start_offset)
        lines = source.read().decode(errors="replace").splitlines()
    matches = [SUMMARY.search(line) for line in lines]
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
    """Bind measurements to actual application sources, never a generated graph."""
    if corpus.get("schema") != CORPUS_SCHEMA or corpus.get("kind") != "real-source" or "generator" in corpus:
        fail("performance measurements require a v2 real-source corpus; generated corpora are correctness-only")
    if not corpus.get("repository") or not re.fullmatch(r"[0-9a-f]{40}", corpus.get("commit", "")):
        fail("the real-source corpus must pin a repository and full commit SHA")
    return {key: corpus[key] for key in ("kind", "repository", "commit", "submodules", "dependencyLock") if key in corpus}


def verify_source(corpus, root):
    """Check the actual checkout before timing, including pinned dependency sources."""
    def git(*args):
        result = subprocess.run(["git", "-C", str(root), *args], capture_output=True, text=True)
        if result.returncode:
            fail(f"cannot verify corpus checkout: {result.stderr.strip()}")
        return result.stdout.strip()
    if git("rev-parse", "HEAD") != corpus["commit"]:
        fail("the corpus checkout does not match its pinned commit")
    if git("status", "--porcelain", "--untracked-files=no", "--ignore-submodules=untracked"):
        fail("the corpus has tracked changes; performance requires the pinned sources")
    for path, commit in corpus.get("submodules", {}).items():
        if git("-C", path, "rev-parse", "HEAD") != commit:
            fail(f"corpus submodule {path} does not match its pinned commit")
    if corpus.get("dependencyLock"):
        lock = pathlib.Path(root) / "Package.resolved"
        if not lock.is_file() or hashlib.sha256(lock.read_bytes()).hexdigest() != corpus["dependencyLock"]["sha256"]:
            fail("the corpus Package.resolved does not match its dependency pin")


def expected_shape(corpus, baseline_revision):
    if baseline_revision is None:
        return corpus.get("shape"), corpus.get("files")
    if not re.fullmatch(r"[0-9a-f]{40}", baseline_revision):
        fail("baseline revision must be a full commit SHA")
    transition = corpus.get("baselineShapes", {}).get(baseline_revision)
    if transition is None:
        return corpus.get("shape"), corpus.get("files")
    shape = transition.get("shape")
    if not transition.get("reason") or not isinstance(shape, dict) or any(
            not isinstance(shape.get(key), int) or isinstance(shape[key], bool) or shape[key] < 0 for key in SHAPE_KEYS):
        fail("a baseline shape transition requires a complete measured shape and explicit reason")
    return shape, shape["files"]


def run_once(frontend, corpus_arguments, out, log, report=lambda sample: None):
    """One skip-build run: wall milliseconds, peak RSS bytes, graph shape, IR sha256.
    The IR is validated against the shape the frontend reported before it counts."""
    started = time.perf_counter()
    with open(log, "ab") as sink:
        log_start = sink.tell()
        process = subprocess.Popen(
            [frontend, "build", *corpus_arguments, "--skip-build", "--out", str(out)],
            stdout=sink, stderr=sink,
        )
        _, status, usage = os.wait4(process.pid, 0)
        process.returncode = os.waitstatus_to_exitcode(status)
    wall_ms = (time.perf_counter() - started) * 1000
    code = os.waitstatus_to_exitcode(status)
    # Retained even for a failed process or invalid output. RSS is the waited
    # process's high-water mark, not a sampled allocation or JVM heap size.
    max_rss = usage.ru_maxrss * (1 if sys.platform == "darwin" else 1024)
    report({"wallMs": wall_ms, "cpuMs": (usage.ru_utime + usage.ru_stime) * 1000,
            "userMs": usage.ru_utime * 1000, "systemMs": usage.ru_stime * 1000,
            "peakRssBytes": max_rss, "exitCode": code})
    if code != 0:
        fail(f"frontend exited with status {code}; see {log}")
    try:
        shape = parse_summary(log, log_start)
        validate_ir(out, shape)
    except InvalidIR as error:
        fail(f"the measured IR is not valid Graphite IR: {error}")
    digest = hashlib.sha256(pathlib.Path(out).read_bytes()).hexdigest()
    return wall_ms, max_rss, shape, digest


def verify(command, ir, scratch, report=lambda result: None):
    """Runs the production reader over a measured IR: `{ir}` and `{out}` are substituted, a
    non-zero exit refuses the snapshot with the reader's last lines of output."""
    out = pathlib.Path(scratch) / "verified-graph"
    rendered = command.replace("{ir}", shlex.quote(str(ir))).replace("{out}", shlex.quote(str(out)))
    completed = subprocess.run(rendered, shell=True, capture_output=True, text=True)
    report({"command": command, "renderedCommand": rendered, "exitCode": completed.returncode,
            "stdout": completed.stdout, "stderr": completed.stderr, "passed": completed.returncode == 0})
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
    parser.add_argument("--baseline-revision", help="full baseline commit SHA; selects only its explicitly pinned baselineShapes entry")
    parser.add_argument("--repetitions", type=int, default=5)
    parser.add_argument("--warmup", type=int, default=1, help="runs retained in the journal but excluded from metric aggregation")
    parser.add_argument("--verify", help="a command run over the last measured IR with {ir} and {out} substituted, e.g. "
                        "'java -jar graphite.jar import {ir} -o {out}'; a non-zero exit refuses the snapshot")
    parser.add_argument("--log", required=True, help="where the frontend's stderr of every run goes")
    parser.add_argument("--out", required=True, help="result rows (JSON)")
    args = parser.parse_args()
    corpus = json.load(open(args.corpus))
    reference = corpus_reference(corpus)
    pinned, expected_files = expected_shape(corpus, args.baseline_revision)
    label = corpus["label"]
    kind, corpus_arguments = input_arguments(args)
    expected_input = corpus.get("input", "package")
    if expected_input != kind:
        fail(f"the manifest is a {expected_input} corpus, the harness was given a {kind}")
    if args.repetitions < 1 or args.warmup < 0:
        fail("repetitions must be positive and warmup nonnegative")
    root = pathlib.Path(args.package) if args.package else pathlib.Path(args.project).parent
    verify_source(corpus, root)
    samples_path = pathlib.Path(str(args.out) + ".samples.jsonl")
    verification_path = pathlib.Path(str(args.out) + ".verification.json")
    for path in [pathlib.Path(args.out), pathlib.Path(args.log), samples_path, verification_path]:
        if path.exists():
            fail(f"evidence path already exists; use fresh output/log paths: {path}")
    pathlib.Path(args.log).open("xb").close()
    samples_path.open("x").close()
    walls, rsses, shapes, digests = [], [], [], set()
    verified = None
    with tempfile.TemporaryDirectory(prefix="apple-frontend-bench-") as scratch:
        out = pathlib.Path(scratch) / "corpus.graphite-ir"
        runs = args.warmup + args.repetitions
        for index in range(runs):
            sample = {"run": index, "warmup": index < args.warmup, "corpus": reference,
                      "command": [args.frontend, "build", *corpus_arguments, "--skip-build", "--out", str(out)],
                      "status": "failed", "baselineRevision": args.baseline_revision}
            try:
                wall, rss, shape, digest = run_once(args.frontend, corpus_arguments, out, args.log, sample.update)
                sample.update({"status": "valid-ir", "shape": shape, "sha256": digest})
            except BaseException as error:
                sample["error"] = str(error)
                raise
            finally:
                with samples_path.open("a") as sink:
                    sink.write(json.dumps(sample) + "\n")
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
            verified = verify(args.verify, out, scratch,
                              lambda result: verification_path.write_text(json.dumps(result, indent=2) + "\n"))
    (shape,) = shapes
    count = shape["files"]
    if expected_files is not None and count != expected_files:
        fail(f"the corpus has {count} source files, the manifest pins {expected_files}")
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
        "corpus": reference,
        "samples": str(samples_path),
        "baselineRevision": args.baseline_revision,
    }
    rows = [
        {"benchmark": BENCHMARK_PREFIX + "wall", "primaryMetric": metric(walls, "ms/op"), **common},
        {"benchmark": BENCHMARK_PREFIX + "rss", "primaryMetric": metric(rsses, "MiB"), **common},
    ]
    with open(args.out, "x") as destination:
        json.dump(rows, destination, indent=2)
    print(f"{label}: {count} files, {shape['nodes']} nodes, {shape['edges']} edges, wall {rows[0]['primaryMetric']['score']} ms, "
          f"peak RSS {rows[1]['primaryMetric']['score']} MiB over {args.repetitions} runs (+{args.warmup} warm-up), IR sha256 {digest[:12]}"
          + (", verified by the production reader" if verified else ", not verified by the production reader"))


if __name__ == "__main__":
    main()
