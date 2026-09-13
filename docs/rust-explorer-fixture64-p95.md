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
can be split into engine and everything-else by bracketing it with two scrapes.
`backtest.py --split` does exactly that for every measured query and records the
response size alongside; the raw output of the run below is committed as
`rust/bench/results/backtest-v2-split.json` (every query, its status, timings and
bytes; 170 of 170 succeeded). Over the v2 population, paired per request:

| | P50 | P95 |
|---|---:|---:|
| total | 0.90 ms | 3.7 ms |
| engine | 0.45 ms | 3.27 ms |
| outside the engine | 0.41 ms | 0.57 ms |
| response body | 9.4 KB | 57.7 KB |

Outside the engine is nearly constant: HTTP, JSON and the body on the wire cost about
0.4 ms whatever the query, and an empty response (`RETURN 1`, 0.2 KB) costs 0.19 ms,
which is the round trip itself and is not going anywhere. At the median the engine
and everything around it are the same size, so halving the engine again would move
P50 by a quarter. (An earlier revision of this section quoted 2.18 / 1.51 / 0.63 ms
over the v1 mix from an uncommitted script; those figures are superseded by this
committed, fail-closed measurement.)

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

Where the median request's time goes, from phase timers compiled into a throwaway
build and run over the backtest itself (the `wide-or` shape is sixty of the 170
queries and sits at the median):

| Phase | Median | Note |
|-------|-------:|------|
| HTTP round trip, `RETURN 1` | 0.17 ms | client and server, no engine work |
| Engine | 0.46 ms | 5 graphs planned in 2 batches before LIMIT 200 lands |
| — dictionary resolution | 0.07 ms | trigram runs sized, intersected, ≈190 candidates verified |
| — posting ranges and union | 0.12 ms | |
| — rows | 0.12 ms | 200 rows, one record read each |
| JSON body, 50 KB | 0.10 ms | |
| First-touch cost of an unseen term | ≈0.2 ms | the same query run again is 0.78 → 0.85 ms faster at P50 |

Running every query three times in a row gives P50 1.0 / 0.85 / 0.78 ms: even a fully
hot median is above the 0.66 ms that 10x would require. That third run is also the
ceiling for any cache of dictionary resolutions or plans — it has every one of them
warm, and the rows, the body and the round trip still cost 0.78 ms. The only cache that
reaches 0.66 ms is one that returns the previous response's bytes, which is not a
faster query engine, and which this benchmark's different-seed warmup was written to
keep from counting. Transparent huge pages for the mappings were tried and made no
measurable difference, so the first-touch cost is not TLB misses. Neither a cache nor
a smaller response body is taken here.

## Properties that are not CallSite's

The production log's commonest queries are `n.caller_class CONTAINS "x" OR
n.callee_class CONTAINS "x" RETURN n LIMIT 25` and `n.value CONTAINS "x" RETURN n LIMIT 25`.
The second names a `StringConstant` property, and until now only CallSite's four string
properties had a raw path in the port: every other string predicate on an unlabelled
scan decoded every node of every type in every graph and evaluated WHERE on it. On the
64-graph corpus that was 14.4 s for `n.value CONTAINS "Spooler" RETURN n LIMIT 25`; the
Kotlin server, which has no raw path for `value` either, takes 19.5 s. On a corpus of
42 graphs ten times this size, a release build of `516451b` measured P50 3.5–4.2 s and
P95 42–47 s on both servers — the shape decides, not the language.

The port now reads such properties the way it reads CallSite's: a per-type string
column (node ids and the string id each carries), built on first use from the type's
records at a fixed offset, with a lowercase trigram index over the column's distinct
strings and a bounded cache of resolved terms — the structure the Kotlin server keeps
for the properties it does cover. Per type, the clause is pruned to the leaves the type
answers raw: a type without the property is skipped outright, one whose property needs
decoding (an annotation's value pairs, an enum's `value`) goes through WHERE, and the
rest are swept by column. Candidates of the types a graph contributes are merged by
node id, which is the order the Kotlin server produces where its own order is defined
(it merges direct-property candidates by record position; for the types it reaches
through a type-keyed hash map the order varies between JVM runs — two runs of
`n.type CONTAINS "String"` against the same Kotlin build listed `ParameterNode` rows
first in one and `LocalVariable` rows first in the other).

| Query, 64 graphs | Rust before | Rust now | Kotlin main |
|---|---:|---:|---:|
| `n.value CONTAINS "Spooler" RETURN n LIMIT 25` | 14.4 s | 1.3 ms | 19.5 s |
| `n.value CONTAINS "http" RETURN n LIMIT 25` | 0.8 s | 0.9 ms | 1.1 s |
| `n.value CONTAINS "zzqqxxvv"` (absent) | 14.2 s | 0.5 ms | 19.2 s |
| `n.name CONTAINS "size" AND n.type CONTAINS "int"`, 3 columns | | 1.4 ms | 33 ms |

Twenty-four differential queries over `value`, `name`, `type`, `class`, `path` and mixed
clauses, with `RETURN n`, `count(*)` and `DISTINCT`: sixteen byte-identical to Kotlin;
six where Kotlin main fails (`Unsafe expression reached parallel string projection` on
`RETURN n` with a rare term, `Java heap space` on `count(*)` over `value`) and the port
answers; two where the row set is the same and only Kotlin's inter-type order differs,
for the reason above. The v1 backtest is unchanged: 170/170 digests, and an interleaved
A/B against the previous build is within noise.

### The v2 backtest

`rust/bench/backtest.py --mix v2` (now the default) weights the 170 queries the way the
production log does: 50 class-pair `RETURN n`, 40 `value CONTAINS`, 30 wide-or, and the
rest as before. `--mix v1` keeps the earlier all-CallSite mix.

| v2 mix, 64 graphs | P50 | P95 | max |
|---|---:|---:|---:|
| Rust | 0.9 ms | 3.8 ms | 8.4 ms |
| Kotlin main (155 of 170 succeeded) | 14.2 ms | 18.8 s | 19.3 s |

Same protocol as the v1 numbers: one client, a 170-query warmup on a different seed,
each query once. Percentiles are over successful responses only — HTTP 200 with a
parseable body — and the backtest now counts anything else as a failure, reports the
status breakdown, and exits non-zero, so a failed run's numbers are never quoted by
accident. Kotlin main failed 15 of the 170 v2 queries with HTTP 400 (`Unsafe expression
reached parallel string projection`, on class-pair `RETURN n` queries); those fast
rejections are excluded above, which raises its P50 from the 11.2 ms a naive average
gave to 14.2 ms. Every Rust run in this document succeeded on all 170. On the
production-shaped mix the port is 16x at P50 and three orders of magnitude at P95,
because forty of the 170 queries are `value CONTAINS`, which the Kotlin server answers
by decoding every node.

## Relationship patterns with a predicate

`MATCH (c)-[r:DATAFLOW]->(n) WHERE n.callee_class CONTAINS "x" RETURN c, n LIMIT 25`
had no plan on either server: every node is enumerated as `c`, its edges walked, and
WHERE evaluated on each `n` reached. The Kotlin server's single-hop fast path covers
only the pattern without a WHERE. The port now anchors such a pattern on the end the
clause's pushable conjunct names — the target first, then the source — resolves that
end through the same scan the single-node shape uses, reaches the other end back across
the adjacency, and expands only those source nodes, in ascending id order, with the
whole WHERE still evaluated on every row. Eight such queries produce exactly the rows,
in exactly the order, that the exhaustive walk produced.

| 64 graphs, `RETURN c, n LIMIT 25` unless noted | Rust before | Rust now (warm) | Kotlin main |
|---|---:|---:|---:|
| `WHERE n.callee_class CONTAINS "Spooler"` | 98 ms | 20 ms | 904 ms |
| `WHERE c.value CONTAINS "Spooler"` | 2.6 s | 1.5 ms | 20.7 s |
| `WHERE n.callee_class CONTAINS "zzqqxxvv"` (absent) | 35.0 s | 0.7 ms | 60 s timeout |
| `WHERE n.callee_class CONTAINS "Spooler" RETURN count(*)` | 30.4 s | 34 ms | `Java heap space` |
| `(c)-[r]->(n) WHERE n.callee_class CONTAINS "Spooler"` | 88 ms | 18 ms | 158 ms |

The first query's remaining 20 ms is the expansion itself: the source nodes reached
from the matched CallSites are expanded through the general matcher, each edge's target
decoded for the WHERE. Against Kotlin the row sets differ only where the source end is
unlabelled, for the type-order reason given above; the Kotlin server also returns rows
without a key for a projected property that is null (`c.value` on a node without one),
which the port matches.

## `=~` patterns

The repository's own pressure benchmark writes the wide query as
`n.caller_class =~ '.*x.*' OR n.callee_class =~ '.*x.*'`, and the explorer UI writes a
package prefix as `n.callee_class =~ 'com.example.*'`. The port never pushed `=~` down:
every such query decoded every node of every graph and ran the pattern on each, which
is the shape of the seconds-long medians reported from a 42-graph production corpus
whose graphs reach 7.2M nodes.

A pattern made of literal characters, backslash-escaped metacharacters and the `.`,
`.*`, `.+` and `.?` wildcards has a longest literal run that every match must contain
(`example` in `com.example.*`, `x` in `.*x.*`). That run now drives the trigram
candidates exactly as a `CONTAINS` literal does, and the compiled pattern -- the same
one the evaluator would have run -- decides each candidate, so the string ids are
exact and everything downstream is unchanged. A pattern with a class, group,
alternation, anchor or a quantifier on a literal, or applied to a `toLower(...)`
operand, is left to the evaluator as before. Ten parity cases cover both kinds.

The v3 backtest mix is v2 with a third of its class-pair and value shapes written as
patterns (20 `regex-pair-node`, 10 `regex-prefix`); everything else is identical.
Per-shape medians, 64 graphs, two rounds each:

| v3 mix, 170 queries | before | after |
|---|---:|---:|
| P50 | 1.5 ms | 1.0 ms |
| P95 | 406 / 421 ms | 3.9 / 3.4 ms |
| max | 19.7 s | 9.4 ms |
| `regex-pair-node` median | 127 ms | 1.0 ms |
| `regex-prefix` median | 189 ms | 0.8 ms |
| every other shape | unchanged | unchanged |

## Component relationships, and a graph that has some

The C4 component level used to hard-code an empty relationship list, and the parity
corpus could not tell: a library graph infers no runtime container and therefore no
components. `rust/bench/fixtures/acme` is a six-class application with a `main` and
five packages calling each other; built as a graph it yields three components and
eight cross-capability call edges. The selector now accumulates call weights per
canonical component pair, ranks and reads them the way the baseline's
`ComponentSelector` does (architectural kinds over collaboration, transitive
reduction, two edges out of and into a component, twelve in a view, relaxed when that
leaves fewer than six), and the mapper hands out their ids after the container
level's. Every level in every format is byte-identical on that graph, on the library
graph, and on four fixture64 graphs, and the diagram planner gained the baseline's
visible-slice selection and truncation notes along the way, which the larger
graphs' context diagrams needed.

Serving two *different* graphs also exposed two cross-graph defects the single-graph
and same-graph-twice runs could not: the grouped `count(*)` fast path emitted one row
per graph instead of summing a value's counts across them, and the DISTINCT fast path
dropped a later graph from a value's provenance. Both are fixed and covered.

## Where the first hit sits

Graphs are planned and swept in id order, in batches: the first alone, then the next
`threads` graphs, then doubling up to `4 × threads` per batch, so a LIMIT satisfied
by an early graph pays for one plan and a late hit does not wait for every remaining
graph to be planned at once. `rust/bench/hit-position.py` measures that directly with
the production's commonest shape (`caller_class CONTAINS t OR callee_class CONTAINS t
RETURN n LIMIT 25`), choosing for each position a `callee_class` whose cross-graph
provenance is exactly that graph, plus a term present nowhere; every request must
succeed. 64 graphs, 11 repetitions, medians:

| First hit in | Graph | Median | Max |
|---|---|---:|---:|
| the first graph | `fixture-android-00` | 0.56 ms | 0.84 ms |
| the 33rd graph | `fixture-kotlin-compiler-00` | 1.06 ms | 1.55 ms |
| the last graph | `fixture-tika-15` | 0.84 ms | 1.56 ms |
| no graph | -- | 0.29 ms | 0.43 ms |

The late hit costs less than the middle one because the term's rarest trigram is
absent from most graphs' bitmaps, which settles them before any planning; the absent
term is the cheapest of all for the same reason.

## Debug builds

Every number in this document is from `cargo build --release`. The binary a plain
`cargo build` writes to `target/debug/` measures P50 6.7 ms and P95 41.8 ms on the same
corpus with the same protocol — 1.0x and 2.8x against Kotlin, not 6.6x and 30x — with
a maximum of 1.6 s. The explorer prints a warning at startup when it was built that way.

## Graphs without `graph.callsite-string-index`

The persisted CallSite string index was introduced on 2026-09-02 (#113). A graph built
before that has no `graph.callsite-string-index`, and the Kotlin server handles its
absence by building the same index in memory on first use. Until now the port only read
the file, so on such a graph every broad query fell to a dictionary scan and a record
sweep per graph, and a conjunction went to the generic evaluator over every record:

| Server, 64 graphs without the file | P50 | P95 | max |
|---|---:|---:|---:|
| Kotlin main (builds the index in memory) | 13.8 ms | 93.1 ms | 727 ms |
| Rust `1d8a52d`, with the file | 3.1 ms | 34.0 s | 34.4 s |
| Rust before this fix, without the file (162 of 170 succeeded) | 16.0 ms | 23.0 s | 53.9 s |
| Rust with this fix, without the file | 1.1 ms | 4.5 ms | 156 ms |

Eight of that run's queries failed with HTTP 504 at the 60 s timeout and are excluded
from its percentiles; every other row in the table succeeded on all 170. That is the
regression a measurement on pre-September graphs would see, and it is not a
regression of the engine but of what the port was willing to read. The port now builds
the index in memory at load when the file is absent, in the exact layout the Kotlin
writer persists: on a fixture graph that does have the file, the in-memory build is
byte-identical to it apart from the content identity, the size estimate and the
checksum (`in_memory_build_reproduces_a_real_persisted_index`). Startup for the 64-graph
corpus goes from 76 s to 80 s; the server names the graphs it built for at startup.

## Under concurrent load

The backtest above is one client. Under concurrent clients, both servers admit four
Cypher queries at a time by default (`--max-concurrent-cypher`) and answer the rest
with 429, so beyond four clients the percentiles below are over the admitted requests:

| Clients | Kotlin P50 / P95 | Rust P50 / P95 | Kotlin rps | Rust rps |
|---:|---|---|---:|---:|
| 1 | 6.4 / 140.6 ms | 1.0 / 4.2 ms | 37 | 439 |
| 4 | 13.3 / 314.9 ms | 2.1 / 8.1 ms | 70 | 1008 |
| 8 | 20.5 / 608.0 ms | 4.4 / 16.3 ms | 28 | 659 |
| 16 | 26.9 / 169.3 ms | 9.0 / 18.4 ms | 68 | 575 |
| 32 | 22.0 / 522.6 ms | 14.4 / 31.9 ms | 32 | 392 |

A load generator that counts a 429 as a failure, or that keeps its connections open past
the limit, measures the guard rather than the engine, on both servers alike.

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
