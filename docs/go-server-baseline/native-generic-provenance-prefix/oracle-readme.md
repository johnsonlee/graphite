# Actual main generic provenance prefix oracle

This is a correctness-only public executor oracle for pinned main
`4e328b0109e13c896b74004823fb049fcb19251a`, using its existing runnable JAR
`91c3a1d154ca96004c55df195d9f752e077cab3e33ca1570b2c88b872d9bc34d`
and Java 17. No latency, allocation, throughput, or other performance
measurements were taken on these synthetic graphs.

`QueryPipeline.kt` lines 2284–2322 collect CallSite selected-row hits, then
consume the generic candidate sequence. After each generic candidate, including
an unmatched or duplicate candidate, main checks whether the cumulative set
contains at least as many rows as the selected set. It does not skip the first
generic candidate when CallSite hits already cover every selected row.

`prepare.py` declares 13 cases. `PrefixFixture.java` uses actual main
`DefaultGraph.Builder` and `GraphStore.save` to write 27 graph variants. An
ordered `Graph.nodes` proxy fixes persisted encounter order. CallSite ID 1
precedes LocalVariable IDs 10, 20, 30, 40. After actual main generates its
mapped-load sidecars, the declared bad LocalVariable has only its name SID
replaced with `2147483647`. The recorded original SID and byte offset are in
`mutations.json`; no other node payload field is changed.

Each case has 40 independently loaded persisted sources. The first source
supplies the selected LIMIT prefix; the second supplies the adversarial
provenance candidates; 38 empty sources complete the default balanced planner
configuration. Source IDs `g00` through `g39` and variant names are explicit in
`cases.json`. No parallelism setting is overridden. Every query uses the
six-property wrapped OR family and projects only `n.name` or `n.class`.

The ten successful cases preserve full ordered rows and graph provenance. Three
cases must fail atomically with the actual main `ArrayIndexOutOfBoundsException`
class and exact message. The matrix covers:

- All selected rows already found by CallSite: consume one unmatched or duplicate
  generic candidate, skip a later corrupt record, but fail if the first generic
  candidate itself is corrupt; natural empty generic iteration succeeds.
- A CallSite hit plus a generic hit completes coverage, including an intervening
  unmatched generic candidate. A corrupt record before completion still fails.
- No raw selected hits: matching generic prefixes, same-row duplicates, an
  unmatched prefix, absent CallSite nodes in the second graph, and incomplete
  natural exhaustion. Duplicates do not count twice toward completion.

`main-capture` and `repeat-capture` contain independent Java compile, writer,
fixture copies, and public executor runs. Their complete parsed output JSON is
exactly equal, including ordered rows, errors and stacks, diagnostics, and
before/after index states. Raw JSON object key order differs between processes;
both originals are retained. Each generated fixture set has 457 regular files. The independent main
writers produce different timestamp comments in 27 `forward.properties` files;
the remaining 430 files are byte identical. `repeat-audit.json` records every
comment difference without altering either capture. Original fixture files are
unchanged during execution; named CallSite index sidecars are separately audited.

`initial-missing-callsite-control` preserves the first complete capture and its
exact input sources. Six initial fixtures had no CallSite in the first graph,
so main failed earlier with `Distinct projection capability became unavailable`.
The final design gives those first graphs a nonmatching CallSite, ensuring an
available raw projection capability with no matching raw rows. This fixture
correction is not a discarded timing run or a changed expected-value rule.
The initial capture remains a valid public behavior observation: an initial
generic-only graph can reach main's capability-unavailable failure. It is not
an invalid fixture or an ignorable result. It does not exercise the intended
provenance stopping boundary, and any Go mismatch for that earlier capability
failure requires a separate fidelity correction.

The root `main.json`, `fixtures.tar.gz`, `fixture-variants.json`, and
`mutations.json` are exact copies from `main-capture` for Go regression tests.
The fixture archive contains only regular members named `variant/file`; it has
457 members. `input-sources.tar.gz` in each capture freezes the source bytes
behind the external input identities, including main's relevant Kotlin files.
JAR and JDK binaries are identified by the pre/post-checked SHA256 inputs rather
than duplicated here. Every command and exit code is retained.

Reproduce with `python3 run.py /absolute/fresh/output` from this directory.
Verify the archived evidence with `python3 verify.py`. These bounded cases do
not establish full Cypher fidelity or any real64 P95 result.
