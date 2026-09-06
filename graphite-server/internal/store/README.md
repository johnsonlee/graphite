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
- `graph.callsite-string-index`: optional read-only v2 CSR/string-signature/trigram index, validated on first request and owned by the Store until `Close`. See the API and trust boundary below.

The node count is the actual serialized record count. `NodeSpan` is the BVGraph node-ID span, which can be much larger for sparse graphs. `NodeIDs` and `NodesOfKind` preserve persisted ordering. Metadata `MethodList` preserves Kotlin's insertion order, including replacement of duplicate signatures without moving their original position. `MemberAnnotationOrder` preserves annotation insertion order for stable endpoint extraction.

Current implementation limits:

- This reader does not yet restore optional persisted type, general string-search, label-prefix, or backward-graph accelerators. Reverse traversal is reconstructed correctly from authoritative forward adjacency.
- Strings, metadata, adjacency and node lookup indexes are currently eager Go heap structures in MAPPED mode; node records and the optional CallSite string index are OS mapped. Forward and reverse adjacency currently retain edge structs. This is not a claim of memory parity with Kotlin's mapped implementation.
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

## Optional CallSite string index

`TryCallSiteStringIndex(ctx)` returns `(view, true, nil)` only after the complete v2 sidecar passes header/count/layout, identity, CRC32, CSR directory, posting order and trigram validation. Missing, unreadable, unsupported or corrupt optional indexes return `(nil, false, nil)` and a diagnostic from `CallSiteStringIndexUnavailableReason()`. The reader neither builds nor writes an index. Missing files are retried on a later request; other optional failures are cached for the Store lifetime. Cancellation, `ErrStoreClosed` and required node-data failures (`ErrInvalidGraphData`) return errors and do not publish a view or cache unavailability. This API is not called by query execution in this change. Counts require positive CallSite and trigram posting counts: main’s MappedWebGraphBackedGraph preparation rejects zero CallSites before loading, and both its persistent readers require positive trigram postings. A valid index with an empty lookup result is supported; this does not promise an available index for a zero-CallSite graph.

The Store owns the mapping. Concurrent first requests share one load; waiting callers can cancel independently. `Close` waits for active index/raw reads and first-load cleanup. Index methods return copies, never mapped slices, and fail with `ErrStoreClosed` after closure; copies obtained earlier remain valid. This guarantee covers these new APIs, not concurrent `Close` with every existing Store API. Graph files and exported Store tables must remain immutable for the Store lifetime, as with existing mapped node reads.

The four `CallSiteStringProperty` values are `CallerClass`, `CallerName`, `CalleeClass` and `CalleeName`, in disk order. View methods are:

- `Info(ctx)`: validated counts and content identity, copied by value.
- `Directory(ctx, property)`: ascending string IDs and posting counts.
- `Postings(ctx, property, stringID)`: source-local node IDs in strictly increasing node-data offset order, preserving main's encounter order rather than numeric ID order. Missing string IDs return an empty slice. Combining multiple rows requires an ordered, deduplicated merge by the caller.
- `Signature(ctx, stringID)` and `TrigramStringIDs(ctx, hash)`: persisted candidate filters. Trigrams use main's lowercase UTF-16 three-unit hash. Neither API verifies a full string predicate.

`RawCallSiteStringIDs(ctx, nodeID)` returns the four original serialized string-table IDs and node-data offset without constructing a Node or formatting a MethodDescriptor. IDs are checked against the table. MAPPED reads the node mapping; EAGER now retains record locations and opens a temporary read handle for each raw access, without retaining a second node mapping. This path requires the original node-data file even in EAGER mode. It reads caller class/name, skips the serialized caller parameter IDs and return-type ID, then reads callee class/name. These raw class values can differ from formatted method projections with generic types, so a future planner must preserve the executor's expression semantics.

### File validation and trust boundary

All disk numbers are big-endian. The 76-byte header is magic `GRCS`, version 2, string count, CallSite count, 32 identity bytes, four unique-string counts, trigram count and an int64 retained-byte estimate. Each property stores int32 string IDs, cumulative posting ends and node IDs. Next come one int64 signature per string, sorted int64 `(trigram hash, string ID)` pairs, then an int64 CRC32 value. With `U` the sum of unique counts, `C` CallSites, `S` strings and `T` trigrams, payload bytes are `8U + 16C + 8S + 8T`; file length must equal payload + 84, and retained bytes payload + 480. All arithmetic is widened before conversion; files larger than signed int32 are unavailable.

**The CRC is not the CRC of raw file bytes.** Main's `CRC32.updateInt/updateLong` feeds each numeric value least-significant byte first, while the identity's bytes retain their original order. The footer is excluded. The native reader reproduces this numeric little-endian checksum over the big-endian file. The actual JVM-written fixture verifies this distinction; an earlier read-only audit's raw-file CRC description was incorrect.

An existing regular 32-byte `graph.callsite-string-content.identity` is trusted exactly as main trusts it. If unavailable or malformed, the reader derives SHA-256 over the string identity, CallSite count and each persisted-order CallSite's node ID, int64 offset and four raw string IDs, using big-endian numeric encoding. A regular 32-byte `graph.strings.identity` is likewise trusted; otherwise it is derived from table size and length-prefixed Java UTF-8 string bytes. Java replaces isolated UTF-16 surrogates with `?` for this hash; internal table strings remain WTF-8. No derived identity is written to disk.

CSR validation requires ascending valid string IDs, increasing cumulative ends that cover all postings, existing node IDs and strictly increasing persisted offsets within each row. Trigram keys must be ordered and refer to valid string IDs. CRC and these structural checks detect corruption, not hostile forgery: they do not authenticate sidecars, rederive trusted 32-byte identities, verify every property's association with node contents, prove uniqueness across different rows, or recompute signature/trigram meaning. A party able to replace an index and its checksum/identity can forge valid-looking content, as with main's persisted index trust model. A future query consumer must still evaluate residual predicates and preserve expression semantics; this reader alone establishes no query or performance improvement.

`testdata/callsite-index` is generated and accepted from persistence by pinned main. Both load modes compare complete raw IDs, directory memberships and posting order. Tests also cover missing/invalid identity derivation with an isolated surrogate, optional corruption, required-data errors, cancellation during load/derivation/waiting, retry, concurrent first loads and reads racing `Close`. See its provenance and reproduction commands. The separate `testdata/callsite-index/real64-correctness` run validates all 64 original graph indexes, one open Store at a time, with per-file SHA-256 and counts; all passed and every graph contained zero AnnotationNodes. This adds structure/integrity evidence, not query or performance evidence.
