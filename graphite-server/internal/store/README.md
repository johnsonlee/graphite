# Native persisted store

`Open(dir)` defaults to `MAPPED`. `OpenMode(dir, mode)` supports `MAPPED`, `EAGER`, and `AUTO`; AUTO selects EAGER below 1,000,000 serialized nodes and MAPPED otherwise, matching `GraphStore.load`. MAPPED uses a read-only OS mapping for node records on macOS and Linux. EAGER decodes the complete node table before returning. `Close` releases the mapping/file.

Implemented formats:

- `graph.strings`: native, passive reader for Java-serialized dsiutils `FrontCodedStringList`, with fastutil `CharArrayFrontCodedList` or `ByteArrayFrontCodedList` storage. Current `StringTable.build` writes UTF-16 storage. Isolated UTF-16 surrogates remain WTF-8 internally; the query/HTTP layer controls their final wire encoding.
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
- The passive Java serialization reader supports the class/array shapes emitted by these two FrontCodedStringList variants, not arbitrary serialized Java objects. It checks the three ordinary classes' serialVersionUID (1), ignores array UID changes like Java, and ignores bytes after the first serialized root. Custom serialization hooks, class annotations, proxy/reset tokens inside the root, and a general modified-UTF schema-name decoder remain outside its scope.
- UTF-8 storage follows main's `FrontCodedStringList.get()` sizing behavior. That JVM implementation fails on some valid Unicode combinations, such as a supplementary character followed by ASCII (`"😀A"`). Native table decoding rejects the same captured case eagerly; JVM deserialization succeeds and only `get()` fails. Arbitrarily malformed UTF-8 byte arrays are not covered by the valid writer-output contract.
- MAPPED currently supports macOS and Linux. Other platforms receive an explicit error.

## Correctness evidence

`go test -race ./internal/store` and `go vet ./internal/store` exercise all node kinds, full metadata, typed/nested values, version rules, Unicode/NUL strings, exact JVM-written adjacency, edge-label versions, forward/reverse edges, resource availability/content/globs, and load-mode behavior. The Linux test binary also cross-compiles from macOS.

The committed `testdata/jvm-v3` fixture uses the original JVM FrontCodedStringList and BVGraph implementations plus Java DataOutputStream records following NodeSerializer. It is a correctness fixture, not a performance fixture. Regeneration sources are included. `testdata/bvgraph` independently covers reference blocks, intervals, and residuals with an exact expected adjacency list.

`testdata/compression` contains 28 Java-written compressed graphs. The generator reloads each with main's BVGraph, verifies every edge against its input, and saves the complete adjacency for Go's per-node comparison. Cases cover every published compression flag, combinations, window 0/128, disabled intervals, zeta 1/3/5, and Golomb moduli 0/1/3/257. The three nondefault Golomb fixtures explicitly append their modulus to properties: WebGraph 3.6.12's writer omits `zetak` for Golomb despite its reader using that property. `provenance.json` records the exact command, jars, source hashes, and fixture hashes. This establishes format correctness, not performance.

`testdata/stringtables` contains 18 independently generated/read Java table cases and a three-node graph produced by main `GraphStore.save`. Successful cases compare every string's complete UTF-16 unit sequence and the complete ordered-table SHA-256, including front-coding ratios 1/3/128, lengths and prefixes up to 40,001 units, empty tables, NUL, supplementary characters, and isolated surrogates. MAPPED and EAGER node reads preserve the same WTF-8 values. Object UID mismatches compare exact captured Java messages; changed array UIDs and trailing bytes are accepted in both readers. The Java UTF-8 writer replaces isolated surrogates with `?` before serialization, while char storage preserves the units. The one captured JVM UTF-8 sizing failure is recorded with its actual failure phase. Generator commands, source/jar hashes, and fixture hashes are in its `provenance.json`; no performance conclusions follow from these correctness cases.

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
