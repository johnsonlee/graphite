# 64-graph cross-graph P95

This is the measurement against the baseline this repository actually gates on. An
earlier revision of this document reported a negative result: the Rust port was slower
than the Kotlin server. That has been fixed, and the numbers below replace it.

**Result: P95 is 16.7x lower than the Kotlin baseline (10.9x in the worst pairing of
three repetitions), with all results byte-identical.**

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

Most of this is algorithmic, not linguistic. The largest single factor is reading an
index the Kotlin build already produces and the Kotlin server already uses; the second
is not doing 64 graphs' worth of planning to answer a query that stops after 200 rows.
Both are changes Kotlin could make, and one of them is a bug that only this port had.
An earlier measurement on a single graph put the language-level component — the same
work, no fast paths on either side — at roughly 1–2x. Nothing here contradicts that.

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
