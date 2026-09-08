# Built-index and trigram publication oracle

This independent supplement retains all earlier captures unchanged. It executes eight public CrossGraph cases with 40 independent mapped graph objects, each containing five ordered operations: query, new high-budget context on the same stores, retry, new budget-1 context, and query. Both actual-main JVM processes completed with exit 0 using Java 17 and the original public configuration (`-Xmx512m`, no dispatcher or FastThrow override). All 40 ordered operations, complete raw states, errors/stacks and fixture identities match exactly between the repeats. No native parity or performance result is claimed.

Inputs reuse the immutable actual-main `bad-magic` and `empty` fixture bytes; the archive has 33 regular files. The query is `MATCH (n:CallSite) WHERE n.caller_name CONTAINS 'absentzz' RETURN n.caller_name AS name LIMIT 1`. Budgets are 258, 259, 387, 388, 516, 517, 518 and 519. The source-backed phase boundaries were declared before capturing results; the oracle calls the actual public executor and does not inject private work consumption.

## Source order and actual publication

Pinned main `4e328b0109e13c896b74004823fb049fcb19251a` implements the builder in `MappedWebGraphBackedGraph.kt:2222–2336`. It performs two raw CallSite traversals, each consuming and finally flushing 128 units, before assigning the completed index. The malformed sidecar reaches that builder after one mapped-identity unit plus a retained-load identity unit and one bad-magic reader unit.

`MappedCallSiteStringIndex.kt:671–708` derives the union of used string IDs and populates metadata. The original population routine at lines 2425–2452 consumes each of the 129 used IDs and flushes in finally; publication occurs after it returns. Failure clears partially written signatures and metadata arrays. The postings routine at lines 2457–2480 independently consumes the same 129 used IDs and finally flushes. Its failure preserves already completed metadata and the intermediate arrays; successful completion publishes postings and releases those arrays. Subsequent matching still consumes work.

The harness extends the earlier schema with `storage[].buildTrigram`: `metadataReady`, `postingsReady` (initialized and non-null postings), and `metadataArraysPresent` (both original intermediate arrays exist). These are reads of original private fields, with all three null when no retained object exists. Existing request diagnostics, remaining/cancellation fields, storage counters, mapped-unavailable state and persisted/cache observations remain intact.

| Initial budget | Actual first-query state | Fresh-context retry work |
| --- | --- | ---: |
| 258 | Failure; built index not published | 518 |
| 259, 387 | Failure; index published, metadata not ready | 260 |
| 388, 516 | Failure; metadata ready and its arrays retained, postings not ready | 131 |
| 517, 518 | Failure; postings ready, intermediate arrays released; matching fails | 2 |
| 519 | Query succeeds; metadata and postings ready | 1 |

Every final budget-1 query succeeds at one unit. Complete errors identify the failing phase: raw builder, metadata population, postings population, or candidate matching. The table is an index into actual records, not a replacement implementation or a budget-total-only oracle. `main.json` retains all eight request diagnostics and every source's before/after state for every operation.

## Runtime files and limits

All eight cases finish with a completed built index after their high-budget retry. Actual main rewrites each malformed `graph.callsite-string-index` during the complete runtime lifecycle. The original before/after manifests preserve those changes; `runtime-changed-files.tar.gz` in each capture preserves the resulting sidecar bytes, and repeats produce the same bytes. These are captured runtime writes, not modified input fixtures or a claim that native disk-state persistence matches.

Original commands, stdout/stderr, input/JDK/JAR/source hashes, compiled harnesses, full results and terminal receipts are retained in main/repeat directories. `verify.py` checks these artifacts without running code under test. This supplement does not cover memory-denied trigram construction, concurrency, other predicates or transformations, unused-string cases, every source-selection route, HTTP execution, or real64 P95 acceptance. Private JVM state remains observable after Close; native public observers may instead return Store-closed. Capturing all eight request counters does not establish unrelated counter paths.
