# Persisted store compatibility audit

This is correctness evidence, not a performance experiment. The native store
was copied from `e69c477562202b87fcafa5e4aeeeeb5a4452636c` into an isolated
temporary Go module. The JVM oracle uses the frozen main
`4e328b0109e13c896b74004823fb049fcb19251a` explore jar, Java 17.0.18 ARM64,
`-Xmx128m`. No production code changed during this audit. These tiny probes
coexisted with a separate 64-graph HTTP replay; no latency, memory, allocation,
or speed conclusions are drawn here.

Inputs are the existing JVM-produced eight-node, fifteen-edge traversal
correctness fixture, and a fresh main `GraphStore.save` of that graph. The
first omits mapped indexes and newer optional sidecars; the second has the
complete current sidecar set. Both have v3 record headers. **The first is a
legacy-style directory layout, not a historical v1/v2 binary fixture.**

The archive contains the complete copied store source, both input graphs,
mutated graph copies, Java/native probe source, stdout/stderr, and reproduction
scripts. JVM probes can rebuild optional sidecars in their private copies;
archived directories reflect the post-probe state. Scripts reconstruct each
input mutation from its preserved baseline. `identity.json` records baseline
files, source hashes, binary hash, and the exact JVM jar hash/path.

## Findings

| Case | main JVM | Native audited store |
| --- | --- | --- |
| Both pristine layouts, EAGER/MAPPED | 8 nodes, 15 outgoing and 15 incoming arcs | Same |
| Missing nodeindex, MAPPED | Scans records and persists replacement; may build nodeoffsets/typeindex | Scans records in memory |
| Corrupt nodeindex, valid nodeoffsets + typeindex present | Loads; those mapped indexes are sufficient | Rejects corrupt nodeindex |
| Corrupt nodeindex without complete mapped indexes | Rejects | Rejects |
| Missing either mapped index | Rebuilds both from nodeindex | Ignores those sidecars |
| Corrupt nodeoffsets/typeindex, both files present | MAPPED load rejects; EAGER ignores | Ignores and loads |
| Missing/corrupt labelprefix | Recomputes from forward adjacency | Ignores and derives adjacency |
| Missing/corrupt backward graph, properties, or offsets | Incoming traversal rebuilds; 15 arcs preserved | Always derives reverse arcs; same 15 |
| Missing forward.offsets | Load rejects | Loads by sequential decoding |
| Corrupt forward.offsets | Load rejects | Loads by sequential decoding |
| Missing strings.identity / wrong-length identity | Loads; identity computed when needed | Ignores identity sidecar |
| Missing classoverview | Loads; accessor returns null | Same |
| Corrupt classoverview | Loads; accessor throws | Loads; accessor returns error; server-level fallback is separate |
| Missing resources | Loads; empty list, unavailable accessor | Loads; empty list and unavailable reason |
| Corrupt resources, EAGER | Initial load fails | Load succeeds; resource accessor fails later |
| Corrupt resources, MAPPED | Initial load succeeds; accessor fails | Same timing |
| Missing/corrupt metadata header or comparisons | Initial load fails | Same phase; error text differs |
| Truncated metadata body, valid count/header, MAPPED | Load succeeds; metadata access fails | Initial load fails |
| Truncated metadata body, EAGER | Initial load fails | Same phase |
| Four-byte labels instead of required fifteen | Initial load succeeds; traversal fails later | Initial load rejects length mismatch |

The nodeindex differences depend on whether **both** newer indexes exist.
When either is missing, main rebuilds both and therefore overwrites a corrupt
remaining sidecar. Treating every corrupt sidecar as recoverable would not
match main. Conversely, several native early rejections are more strict than
main's lazy access behavior; these are reported separately from valid-format
support.

## BVGraph format boundaries

Java actually recompressed the same graph with `OUTDEGREES_DELTA`,
`RESIDUALS_GAMMA`, `REFERENCES_GAMMA`, and `OFFSETS_DELTA`. Every resulting
graph loads and retains fifteen outgoing/incoming arcs in main, but this
audited native version rejects each nonempty `compressionflags` value.
An explicit `OUTDEGREES_GAMMA` declaration over the default stream also loads
in main and is rejected natively, even though it is semantically the default.
A different window size (`windowsize=0`, flags zero) works in both readers.
These are real format support gaps, not corruption behavior.

The native parser also accepts only `key=value` property lines, while Java
uses its general Properties reader. It imposes additional bounds on window
size and zeta parameter. Only the cases above were executed in this audit;
the wider flag combinations and property syntax differences are source
findings pending dedicated fixtures.

## Java serialization boundaries

The native reader is a passive parser for the UTF-16-backed
`FrontCodedStringList` / `CharArrayFrontCodedList` object shape. It handles
null, references, short strings, ordinary class descriptors, objects, char
arrays, and arrays of char arrays. It accepts `SC_SERIALIZABLE` fields of
types boolean, int, object, and array. It does not instantiate classes or run
serialized code.

The direct string oracle verifies complete ordered UTF-16 content with a
length-prefixed SHA-256 hash, not a sample. Normal Unicode, NUL, supplementary
characters, ratio 1/8/128, and 40,000-code-unit strings/common prefixes match.
Isolated high/low surrogates do **not** match: Java preserves the original
UTF-16 units, while native decoding substitutes U+FFFD. Both hashes and exact
fixture construction are preserved.

Additional graph-level cases show:

* UTF-8-backed FrontCodedStringList is valid for main's loader but rejected
  natively (`unsupported serialized array [[B`). Current GraphStore writes
  `utf8=false`, so this is outside the current writer's output shape.
* Changing the root serialVersionUID causes JVM InvalidClassException, while
  native loading succeeds: the native parser currently reads but does not
  validate serialVersionUID.
* Appending bytes after the serialized root is ignored by main's single-object
  loader and rejected by the native reader.

Class annotations, custom serialization flags/block data, proxy class
descriptors, reset tokens, long serialization strings, and byte-backed arrays
are outside the native parser. Class descriptor names are decoded as plain
bytes, which is sufficient for the ASCII schemas emitted by this writer.
The source also lacks a general Java modified-UTF decoder; this does not
affect ordinary graph strings because their content is stored as char arrays.

## Version and untested boundaries

Both readers' source accepts record format versions 1, 2, and 3. Version 1
annotation values are string-table references (empty becomes null); v2 adds
typed/list annotation values; v3 adds artifact metadata and changes edge-label
bit allocation. Existing native unit tests cover those branches. This audit
does not add independently produced historical v1/v2 complete stores, so it
does not extend that evidence to every historical node/metadata combination.

The native store ignores persisted CallSite string/trigram accelerators and
content-identity sidecars, so their stale/corrupt restore logic was not probed
here. Valid-but-stale backward/type/index/labelprefix contents are not covered
by the corruption cases: main can trust structurally valid sidecars. Failures
caused by filesystem permissions, read-only directories, mapped files over
2 GiB, very large string-array segments, and arbitrary hostile serialization
cycles were not tested.

## Reproduction

The scripts use `/tmp/graphite-store-format-audit-e69c477` and the jar path in
`identity.json`. Restore the archive there, then:

```sh
cd /tmp/graphite-store-format-audit-e69c477/go
go build -o ../go-probe ./cmd/probe
cd ..
javac -cp /tmp/graphite-go-main-baseline-clone-4e328b0/graphite-explore/build/libs/graphite-explore.jar Probe.java Formats.java StringFormats.java
python3 run.py
python3 run-current.py
python3 run-formats.py
python3 run-backward.py
python3 run-strings.py
```

The JSON files retain results for every phase: load, node decoding, forward
and reverse traversal, metadata, resources, and classoverview. Counts and
failure phases were compared; error strings are preserved but not normalized
into an assertion of exact error-message parity.
