# 64-graph cross-graph P95

This is the measurement against the baseline this repository actually gates on. An
earlier revision of this document reported a negative result: the Rust port was slower
than the Kotlin server. That has been fixed, and the numbers below replace it.

**Result: P95 is 16.7x lower than the Kotlin baseline (10.9x in the worst pairing of
three repetitions), with all results byte-identical.**

That headline number rests almost entirely on one query shape, and the "Attribution"
section below takes it apart: neutralise that shape and the gap is about 3x. Read both
before quoting either.

## Setup

The corpus is built by the repository's own `Fixture64GraphPreparation` from the four
pinned fixture jars, whose sizes match the fingerprints in
`docs/large-corpus-performance-baseline.md`. Both servers load all 64 graphs and report
identical totals:

| | |
|---|---:|
| Graphs | 64 |
| Nodes | 19,431,891 |
| Edges | 20,448,885 |
| Call sites | 5,046,935 |

Queries are the ten `global-wide` shapes at three selectivities plus the four
`global-wide-distribution-v1` cases from the manifest, 34 in total. Each runs once,
cold, single-threaded, with no warmup, through `/api/cypher` so every graph
participates. P95 is taken across queries with the same formula as
`.github/scripts/benchmark-gate.mjs`. Hardware: 4 vCPU, 15 GiB; Kotlin on OpenJDK 21
with `-Xmx8g`; Rust built `--release`.

Two things about the method are worth stating plainly, because both were wrong in
earlier rounds of this work:

* **Each server is measured alone.** Both together do not fit in 15 GiB — the JVM was
  OOM-killed once with the Rust process resident — and contention would taint whichever
  one is being timed. `fixture64.py --only <server>` runs the plan against one server
  and records a SHA-256 per query so the two runs can still be diffed.

* **Every run is a cold start.** The Kotlin server caches matching string ids, node ids
  and projected rows per predicate, so a second run of the same queries against a live
  server is several times faster than the first. Re-using a warm server would have
  measured its cache, not the gate's baseline. Both servers are restarted for every
  repetition.

## Result

Three repetitions, each a fresh start of both servers:

| Repetition | Kotlin P95 | Rust P95 | Kotlin P50 | Rust P50 |
|---|---:|---:|---:|---:|
| 1 | 145.7 ms | 7.9 ms | 12.3 ms | 2.1 ms |
| 2 | 153.9 ms | 9.0 ms | 13.1 ms | 2.4 ms |
| 3 | 151.2 ms | 13.3 ms | 12.5 ms | 3.5 ms |

| | |
|---|---:|
| **P95, median of three vs median of three** | **16.7x** |
| **P95, worst case (Kotlin's best vs Rust's worst)** | **11.0x** |
| P50, median vs median | 5.3x |
| Identical results | 102 of 102 |

The worst-case row is the one to hold this to. P95 over 34 samples is the second-largest
of them, which is a volatile statistic — the previous round of this work made exactly
the mistake of quoting a single flattering draw from it. Pairing Kotlin's *best*
repetition against Rust's *worst* still clears 10x.

Correctness is compared, not assumed: each of the 34 queries is hashed over its ordered
columns and rows in every repetition, and all 102 comparisons between the Kotlin and
Rust runs agree, as do the digests across repetitions on each server.

That is evidence about cross-graph query results on `/api/cypher`, and nothing else. The
boundary of what has and has not been differentially tested is set out in
[What the parity suite covers, and what it does not](rust-explorer-parity-and-latency.md#what-the-parity-suite-covers-and-what-it-does-not).

## Why P50 does not reach 10x, and what would have to change

P95 clears 10x; P50 does not, and it is worth writing down why rather than leaving it as
an open task. The reason is measurable and it is not the query engine.

The `/metrics` histogram times only the engine — the `spawn_blocking` body — so a request
can be split into engine and everything-else by bracketing it with two scrapes. Over the
170-query backtest population, paired per request:

| | |
|---|---:|
| P50 total | 2.18 ms |
| P50 engine | 1.51 ms |
| P50 outside the engine | 0.63 ms |
| P50 response body | 63.5 KB |

The median request is a `wide-or` returning 200 rows — about 75 KB of pretty-printed
JSON. Measured against body size, the response path costs roughly **8.8 µs/KB**, so that
body alone is about **0.66 ms** to serialize and write. An empty response
(`RETURN 1`, 0.2 KB) costs 0.19 ms, which is the HTTP round trip and is not going
anywhere.

Kotlin's P50 on this corpus is 6.6 ms, so 10x means **0.66 ms total**. The median
query's response body already costs that much on its own, before the engine plans a
single graph — and the engine needs 0.88 ms just to prune and plan 64 graphs for a dense
term before producing one row.

So the target is not reachable by making the engine faster. It would need one of:

* **A smaller response.** Dropping pretty-printing cuts the body roughly threefold.
  The baseline pretty-prints (Gson `setPrettyPrinting`); this server now sends compact
  JSON on the Cypher routes, a deliberate departure — nothing consumes the whitespace,
  every client parses the body, and the differential suite compares JSON structurally.
  The CLI's `json` output keeps the Gson-compatible pretty form, since that one is
  compared byte for byte. Returning fewer rows would break the query's semantics and is
  not done.
* **A cross-request cache** of resolved dictionary entries or whole results. The baseline
  has one, which is why repeating an identical query set makes it look much faster than
  it is — an earlier round of this work was misled by exactly that and the benchmark now
  warms on a different seed deliberately. Introducing one here would be measuring a
  cache, not an engine, and the comparison would have to be redone warm on both sides to
  mean anything.
* **A different index.** The remaining engine cost concentrates in the graphs that
  actually contain the term — about 284 µs each — not in the 64-graph fan-out, which
  pruning has already made nearly free for a miss (≈11 µs).

What has been done instead is to take every reducible part, each from a profile: the
response path serializes in one pass and, by decision, without pretty-printing; the WHERE
re-check is skipped where the pushdown answer is exact; a disjunction's postings are
merged lazily; planning starts with one graph and fans out at most twice; a dense
conjunction sweeps bitsets instead of falling to the generic evaluator; trigram runs are
looked up rather than searched; a complete scan skips the provenance pass; and the
dominant query shape — one node, a pushed-down WHERE, a RETURN of that node's properties
— produces its rows as plain values with no per-row map at all.

Since then: a conjunction is decided on its smallest side's records rather than by
resolving every literal (and-of-or 2.8 → 1.6 ms), posting ranges are resolved once, and
the query runs on the worker that received it instead of being handed to the blocking
pool and back — that hand-off alone was 0.1–0.15 ms of every request. Last, a projected
row of CallSite properties reads its record once and takes the four string ids straight
off it, rather than decoding the node once per column (wide-or 1.10 → 0.90 ms).

Measured as an interleaved A/B on the same box (two rounds each, the only comparison this
machine's noise allows): **P50 1.0 ms against Kotlin's 6.6 ms, 6.6x; P95 3.7–3.9 ms
against 117.9 ms, 30x.** The per-request HTTP floor is 0.17 ms of that.

## What changed

The port already read the same graph directory as the Kotlin server, but it ignored a
file sitting in it: `graph.callsite-string-index`, about 7 MB per graph, written at
build time. It holds two structures, and the port now reads both straight out of the
mapping:

* **Four property CSRs** mapping each string id used by `caller_class`, `caller_name`,
  `callee_class` or `callee_name` to the CallSite nodes carrying it. "Which nodes hold
  this string" becomes a binary search and a slice instead of a sweep over 5M records.

* **A lowercase trigram index** over the dictionary. "Which strings match this literal"
  becomes an intersection of a few short posting lists instead of a pass over the whole
  dictionary. On one fixture graph the rarest trigram of `Activity` cuts 61,909 strings
  to 556 candidates, which are then checked exactly.

Alongside that, one plain bug: **the scan planned all 64 graphs up front**, even for a
query with `LIMIT 200` that the first graph or two already satisfies. Planning is now
done per graph, when the sweep reaches it. Two smaller fixes followed — a literal's
matching string ids are resolved once and shared across the four properties that test
it, and only a bounded handful of a long literal's trigrams are probed rather than one
per character.

The effect is visible in the shape of the cost. Before, the Rust port's latency was
nearly flat regardless of how selective the term was, because every query paid a
per-graph dictionary pass no matter what; a term matching nothing cost about 199 ms. It
now costs about 2 ms, because a graph whose dictionary cannot contain the term is
skipped outright.

### Attribution, honestly

**Both of the changes above were fixes to this port's own defects, not exploits of a
Kotlin weakness.** The Kotlin server has always read `graph.callsite-string-index`; this
port simply ignored a file sitting in the directory it was already reading. And planning
all 64 graphs up front for a query that `LIMIT 200` satisfies from the first one was a
bug that only this port had. Together they took the port from *slower than Kotlin* to
competitive. They do not explain why it now wins.

**What explains the 16.7x is one query shape.** Kotlin's P95 is
`global-wide-wrapped-case-insensitive-distinct`, and that shape is the only one where it
exceeds 30 ms:

| Kotlin's slowest | |
|---:|---|
| 883.0 ms | zero `four-properties` — each server's first query of the run, so the maximum, not the P95 |
| **151.2 ms** | dense `wrapped-case-insensitive-distinct` — *this is the P95* |
| **113.3 ms** | targeted `wrapped-case-insensitive-distinct` |
| 28.7 ms | targeted `four-properties` |
| 27.9 ms | localized-late `four-properties` |

Substitute, for those two rows, Kotlin's own timing for the same query *without* the
`DISTINCT` — same predicate, same selectivity, one keyword apart — and Kotlin's P95 falls
from 151.2 ms to 28.7 ms. **The ratio drops from 16.7x to 3.2x.**

So the honest reading is: if the Kotlin server got a fast path for the wrapped
case-insensitive `DISTINCT` shape, most of this result would go away. Rust's advantage on
that shape is not a language advantage either — it is `fastpath.rs`'s
`distinct_string_property`, a hand-written special case that Kotlin could equally write.
That shape is a known, actively-worked hot spot on the Kotlin side; see
`docs/wrapped-case-insensitive-query-optimization-attempts.md`.

What is left after neutralising it — roughly 3x — is the part that is about the
implementation rather than one query family, and an earlier single-graph measurement put
the purely language-level component (same work, no fast paths on either side) at roughly
1–2x. Those two figures are consistent with each other.

## Every query

Median of three repetitions.

| Selectivity | Shape | Kotlin | Rust | |
|---|---|---:|---:|---:|
| zero | `global-wide-four-properties` | 883.0 ms | 12.1 ms | 73.1x |
| zero | `global-wide-class-pair` | 19.4 ms | 2.4 ms | 7.9x |
| zero | `global-wide-name-pair` | 13.5 ms | 1.8 ms | 7.4x |
| zero | `global-wide-caller-class` | 14.7 ms | 1.7 ms | 8.7x |
| zero | `global-wide-callee-class` | 11.8 ms | 1.9 ms | 6.2x |
| zero | `global-wide-provenance` | 11.6 ms | 1.9 ms | 6.0x |
| zero | `global-wide-aliased` | 13.1 ms | 1.9 ms | 7.0x |
| zero | `global-wide-parameterized-as-literal` | 13.0 ms | 1.8 ms | 7.3x |
| zero | `global-wide-wrapped-case-insensitive` | 14.7 ms | 1.9 ms | 7.7x |
| zero | `global-wide-wrapped-case-insensitive-distinct` | 22.5 ms | 1.5 ms | 15.0x |
| targeted | `global-wide-four-properties` | 28.7 ms | 5.9 ms | 4.8x |
| targeted | `global-wide-class-pair` | 17.3 ms | 2.2 ms | 7.9x |
| targeted | `global-wide-name-pair` | 14.7 ms | 2.3 ms | 6.4x |
| targeted | `global-wide-caller-class` | 13.7 ms | 2.1 ms | 6.5x |
| targeted | `global-wide-callee-class` | 11.7 ms | 2.1 ms | 5.5x |
| targeted | `global-wide-provenance` | 12.5 ms | 2.5 ms | 5.1x |
| targeted | `global-wide-aliased` | 15.2 ms | 2.2 ms | 6.9x |
| targeted | `global-wide-parameterized-as-literal` | 11.2 ms | 2.3 ms | 4.9x |
| targeted | `global-wide-wrapped-case-insensitive` | 14.9 ms | 2.7 ms | 5.5x |
| targeted | `global-wide-wrapped-case-insensitive-distinct` | 113.3 ms | 2.4 ms | 48.1x |
| dense | `global-wide-four-properties` | 13.0 ms | 8.9 ms | 1.5x |
| dense | `global-wide-class-pair` | 10.5 ms | 5.6 ms | 1.9x |
| dense | `global-wide-name-pair` | 7.5 ms | 5.3 ms | 1.4x |
| dense | `global-wide-caller-class` | 6.2 ms | 5.3 ms | 1.2x |
| dense | `global-wide-callee-class` | 6.0 ms | 4.8 ms | 1.3x |
| dense | `global-wide-provenance` | 8.3 ms | 6.6 ms | 1.3x |
| dense | `global-wide-aliased` | 7.1 ms | 5.3 ms | 1.3x |
| dense | `global-wide-parameterized-as-literal` | 5.7 ms | 5.4 ms | 1.1x |
| dense | `global-wide-wrapped-case-insensitive` | 8.4 ms | 7.3 ms | 1.2x |
| dense | `global-wide-wrapped-case-insensitive-distinct` | 151.2 ms | 8.1 ms | 18.7x |
| localized-early | `global-wide-four-properties` | 8.7 ms | 2.1 ms | 4.1x |
| localized-middle | `global-wide-four-properties` | 22.7 ms | 4.8 ms | 4.7x |
| localized-late | `global-wide-four-properties` | 27.9 ms | 4.9 ms | 5.7x |
| broad-all-64 | `global-wide-four-properties` | 6.8 ms | 5.8 ms | 1.2x |

The first row is each server's first query of the run, so it carries cold-start cost —
for the JVM, class loading and JIT. It is the maximum, not the P95, on both sides.

The dense rows are where the two are closest. A term like `get` matches most of the
dictionary, so the trigram index cannot narrow it and the port falls back to scanning
the dictionary and sweeping records; what remains is `LIMIT 200` stopping both servers
early. That is the expected shape, not a defect.

## Reproducing

```bash
.github/scripts/prepare-fixture64-graphs.sh \
    graphite-webgraph/build/libs/webgraph-1.0.0-SNAPSHOT-jmh.jar \
    /path/to/fixture-jars /path/to/fixture64

# Start one server over all 64 graph directories listed in graphs.tsv, measure it,
# stop it, then do the same for the other.
cd rust/bench
python3 fixture64.py --manifest /path/to/fixture64/graphs.tsv --only kotlin --out kotlin.json
python3 fixture64.py --manifest /path/to/fixture64/graphs.tsv --only rust   --out rust.json
```

Each output carries a `digest` per query; comparing them across the two files is the
correctness check.
