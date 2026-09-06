# Native persisted store

`Open(dir)` defaults to `MAPPED`. `OpenMode(dir, mode)` supports `MAPPED`, `EAGER`, and `AUTO`; AUTO selects EAGER below 1,000,000 serialized nodes and MAPPED otherwise, matching `GraphStore.load`. MAPPED uses a read-only OS mapping for node records on macOS and Linux. EAGER decodes the complete node table before returning. `Close` releases the mapping/file.

Implemented formats:

- `graph.strings`: native, passive reader for the Java-serialized dsiutils `FrontCodedStringList` / fastutil `CharArrayFrontCodedList` written by `StringTable.build`, including front-coded UTF-16 strings.
- `graph.nodedata` and optional `graph.nodeindex`: all 16 node kinds, format versions 1–3, nullable fields, typed/nested annotation and enum values. An absent index is reconstructed in memory for mapped reads.
- `graph.metadata`: methods (including insertion order), hierarchy, enum values, class origins, artifact dependencies, annotations, branch scopes.
- `graph.comparisons`: condition operators and comparand node IDs.
- `forward.graph`/`forward.properties`: native BVGraph version 0 decoder with declared gamma/delta degrees and blocks, unary/gamma/delta references and block counts, gamma intervals, and gamma/delta/zeta/Golomb/nibble residuals. Explicit default flags and supported flag combinations are accepted. Every decoded arc count is checked against the persisted properties.
- `graph.labels`: all versioned data-flow, call, type, control-flow and resource edge labels. Incoming edges are derived from forward edges with labels/comparisons preserved.
- `graph.classoverview`: optional v1–v3 aggregate with bounded class filtering, cache growth/truncation, and the server fallback when missing or corrupt. `Overview(limit)` returns server-compatible class nodes and call edges.
- `graph.resources`: lazily decoded v1 resource records, exact missing-store availability reason, content streams and Unix Java-style resource globs.

The node count is the actual serialized record count. `NodeSpan` is the BVGraph node-ID span, which can be much larger for sparse graphs. `NodeIDs` and `NodesOfKind` preserve persisted ordering. Metadata `MethodList` preserves Kotlin's insertion order, including replacement of duplicate signatures without moving their original position. `MemberAnnotationOrder` preserves annotation insertion order for stable endpoint extraction.

Current implementation limits:

- This reader does not yet restore optional persisted type, string-search, label-prefix, or backward-graph accelerators. Reverse traversal is reconstructed correctly from authoritative forward adjacency.
- Strings, metadata, adjacency and node lookup indexes are currently eager Go heap structures in MAPPED mode; only node records are OS mapped. Forward and reverse adjacency currently retain edge structs. This is not a claim of memory parity with Kotlin's mapped implementation.
- BVGraph windows are bounded at 1,024, zeta parameters at 1–31, and node spans at signed 32-bit range. Properties currently require `key=value` syntax. Offset gamma/delta declarations are accepted, but offset sidecars are not consumed by the sequential decoder. Invalid or unknown compression combinations fail explicitly.
- A UTF-8-backed serialized FrontCodedStringList is rejected: GraphStore writes its UTF-16-backed variant. Isolated UTF-16 surrogate code units currently decode to Unicode replacement characters; full malformed-string parity remains outstanding.
- MAPPED currently supports macOS and Linux. Other platforms receive an explicit error.

## Correctness evidence

`go test -race ./internal/store` and `go vet ./internal/store` exercise all node kinds, full metadata, typed/nested values, version rules, Unicode/NUL strings, exact JVM-written adjacency, edge-label versions, forward/reverse edges, resource availability/content/globs, and load-mode behavior. The Linux test binary also cross-compiles from macOS.

The committed `testdata/jvm-v3` fixture uses the original JVM FrontCodedStringList and BVGraph implementations plus Java DataOutputStream records following NodeSerializer. It is a correctness fixture, not a performance fixture. Regeneration sources are included. `testdata/bvgraph` independently covers reference blocks, intervals, and residuals with an exact expected adjacency list.

`testdata/compression` contains 28 Java-written compressed graphs. The generator reloads each with main's BVGraph, verifies every edge against its input, and saves the complete adjacency for Go's per-node comparison. Cases cover every published compression flag, combinations, window 0/128, disabled intervals, zeta 1/3/5, and Golomb moduli 0/1/3/257. The three nondefault Golomb fixtures explicitly append their modulus to properties: WebGraph 3.6.12's writer omits `zetak` for Golomb despite its reader using that property. `provenance.json` records the exact command, jars, source hashes, and fixture hashes. This establishes format correctness, not performance.

Real Kotlin-produced graph checks accept an external fixture and an independently computed JVM edge hash:

```sh
java -cp /path/to/graphite-webgraph-jmh.jar internal/store/testdata/ReferenceEdges.java /path/to/graph
GRAPHITE_TEST_GRAPH=/path/to/graph GRAPHITE_TEST_EDGE_SHA256=<JVM-hash> go test ./internal/store -run TestReal -v
```

On the available `pr113-exp037` real corpus, all ordered `(source,target)` pairs matched the independent JVM decoder:

| Graph | Serialized nodes | Methods | Edges | SHA-256 of ordered edge pairs |
| --- | ---: | ---: | ---: | --- |
| fixture-hive-11 | 352,533 | 25,942 | 364,570 | `edde4a59450ea946b8e1d110134ab47767f78375383cc38e2f2bd47ee96db4ae` |
| fixture-android-12 | 434,497 | 28,417 | 477,311 | `f30c9cb53d84ced1108f01b48db4e0ae5f06756c5d7ade6b82c9becb6ac53a68` |

The decoded complete string tables also matched each graph's Kotlin-generated `graph.strings.identity`. These checks establish correctness for those inputs, not latency, allocation or memory performance. Full 64-graph performance evidence remains separate and required.
