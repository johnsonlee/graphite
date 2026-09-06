# Consumed node tag error compatibility

This is a bounded functional correction against main `4e328b0109e13c896b74004823fb049fcb19251a`, Java 17.0.18. No performance workload or 64-graph runtime was run. The fixture is the existing eight-IntConstant, fifteen-edge traversal correctness fixture. Only the final node record tag at byte offset 75 is changed, to 16, 127, 128 or 255.

## Evidence and results

| Evidence | Coverage | Before | After |
| --- | --- | --- | --- |
| Primary raw HTTP | 104 observations, two initial load modes, four bad tags, thirteen requests each | 64 equal, 40 different | 104 equal, 0 different |
| Direct main lifecycle | 72 load/node/query/cross observations across MAPPED/EAGER/AUTO and mutation before/after load | captured | permanent native tests assert all 72 lifecycle outcomes; query class/message/rows compare completely |
| Prior early-limit corrupt-tail oracle | twelve scoped/cross observations | eight equal, four class/message differences | twelve equal, zero differences |

`main/`, `native-before/`, `native-after/` and final frozen-source `native-final/` preserve raw bodies, status, content type, commands and full process logs. The comparison parses JSON and normalizes only successful graph descriptor path and loadedAt. All response data, errors, nulls and array order remain significant; nothing is excluded. `before-differences.json` and `after-differences.json` / `final-differences.json` preserve the result. Direct Store API errors deliberately retain their unsigned diagnostic rather than claiming to be Java exception objects. The lifecycle test checks that typed Store errors identify the same consumed tag, and independently compares query errors with main.

`main-lifecycle.json` and `NodeTagOracle.java` record actual main classes/messages and cached node values. `native-early-corrupt.json` is a new receipt for the old oracle; the earlier frozen capture and verification remain unchanged.

## Contract confirmed by source and oracle

At the pinned revision:

- `graphite-webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/NodeSerializer.kt:488` reads a signed Java byte and line 567 throws `IllegalArgumentException("Unknown node tag: $tag")`. Thus byte 255 reports -1 and byte 128 reports -128.
- `MappedWebGraphBackedGraph.kt:2539` delegates actual node reads to that serializer. MAPPED with an existing valid index can load first and throw later upon consuming a corrupt record.
- `GraphStore.kt:797` dispatches load modes. EAGER and tiny AUTO consume records during loading. Mutation after those modes finish loading does not change cached nodes. A new EAGER/AUTO load does consume and fail.
- `graphite-explore/src/main/kotlin/io/johnsonlee/graphite/cli/ExploreRoutes.kt:279` reads nodes without converting decoder exceptions locally. The node and subgraph routes return Javalin's generic JSON 500 problem response. They do not expose the decoder text.
- `ExploreRoutes.kt:797` catches graph load exceptions and returns 400 with `{ "error": error.message }`. Cypher handlers return 400 with the signed message and `code: cypher_query_failed`; direct Cypher execution preserves IllegalArgumentException.

Outgoing edges and missing node IDs do not consume the corrupt node. A bounded initial MATCH returning the first node succeeds. A throwing first projection still fails with its original ClassCastException before reaching the corrupt tail. The existing relationship-tail cases verify actual traversal consumption. This change does not change candidate enumeration, add reads, reorder evaluation, or implement raw projection shortcuts.

## Native change and downstream audit

`store.UnknownNodeTagError` is produced only after a node record supplies an unknown tag. Its Error string remains the previous unsigned `unknown node tag N`. Missing IDs, index header/record errors, truncation, index/node mismatches and other storage errors remain unchanged.

The complete direct node-read inventory at effective base `f0e5881c35aa5deb9f797f4a51ad301a30a89f32` is:

| Reader | Existing propagation | Change |
| --- | --- | --- |
| Store.OpenMode optional-index fallback / EAGER stream | decoder error, optionally wrapped with graph.nodeindex | only typed cause, original diagnostic retained |
| Store.Node | `node ID: %w` | typed cause survives wrapper |
| query/traversal.go candidate scan | query error | classify consumed tag as Java IllegalArgumentException |
| query/traversal.go relationship target loader | query error; missing IDs handled separately | same typed classification |
| server.NativeGraph.Node | returns Store error | unchanged |
| server node/subgraph handlers | writeError(500) | only typed unknown tag uses generic main 500 problem |
| Store.ClassOverview / analysis/c4.InferViewModel CallSite scans | return actual Node error to REST handler | typed cause remains; same unhandled 500 mapping |
| Registry.Load loader boundary | writeError(400) | only typed unknown tag uses signed main message |

No decoder error text is matched to decide its class. The new tests assert that a plain error containing `unknown node tag 255` and an unrelated truncation error keep their existing response, and that non-400/500 statuses retain their existing diagnostic. The Store decoder tests distinguish truncation from an actually consumed unknown tag.

Attempt 6's separate indexed candidate walker is not present in this base. On integration its actual CandidateNode consumption must call `failNodeRead`; certification/proof read failures must continue to return unavailable and fall back. That integration is outside this frozen delta and was communicated to root and the candidate provider owner.

## Separately observed missing-index side effect

`main-unindexed/` preserves the original full 104-observation exploration, not the primary comparison. In its initial EAGER process, the first new MAPPED load fails while creating a previously absent index. Main writes the final tag-16 index entry before decoding that corrupt node, leaving a partial/invalid index on disk. Subsequent MAPPED loads see the existing index and report `Unknown node tag for mapped type index: 16`, even after the node-data tag changes. Source: GraphStore.kt:1156 skips rebuilding existing indexes; buildNodeIndex at 1163 writes each index entry before readNode; mapped type-index writer at 175 rejects the unknown tag.

Native currently does not persist an optional index during this fallback, so later failure sequences can differ. That side effect is a separate compatibility gap; this change neither implements it nor suppresses its evidence. The primary pair deliberately starts both processes from the same valid 112-byte main-generated `prepared.nodeindex`, isolating actual node-record consumption without differing generated input state. This file was copied from the initial successful main MAPPED startup before corrupt loads; its bytes are preserved and hashed.

## Reproduction

From the module root, with new output directories to preserve frozen captures:

```sh
go build -o /tmp/graphite-node-tag-native ./cmd/graphite-server
python3 internal/server/testdata/node-tag/capture.py --runtime main --exe /tmp/graphite-go-main-baseline-clone-4e328b0/graphite-explore/build/libs/graphite-explore.jar --index-from internal/server/testdata/node-tag/prepared.nodeindex --out /tmp/node-tag-main-recapture
python3 internal/server/testdata/node-tag/capture.py --runtime native --exe /tmp/graphite-node-tag-native --index-from internal/server/testdata/node-tag/prepared.nodeindex --out /tmp/node-tag-native-recapture
python3 internal/server/testdata/node-tag/compare.py /tmp/node-tag-main-recapture/observations.json /tmp/node-tag-native-recapture/observations.json --out /tmp/node-tag-recapture-differences.json
python3 internal/server/testdata/node-tag/capture-lifecycle.py --java-home /opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home --jar /tmp/graphite-go-main-baseline-clone-4e328b0/graphite-explore/build/libs/graphite-explore.jar --out /tmp/node-tag-lifecycle-recapture
go test ./internal/query -run 'TestNodeTagLoadLifecycleMainOracle|TestEarlyMatchStopsBeforeCorruptTail' -count=1
go test ./internal/server -run 'TestNodeTagHTTPMainOracle|TestNodeTagBoundaryDoesNotReclassifyOtherErrors' -count=1
go test ./internal/store -run TestUnknownNodeTagKeepsStoreDiagnostic -count=1
go test -race ./...
go vet ./...
```

Dedicated tiny HTTP processes are terminated by each capture's finally block. No production runtime is needed for native tests.
