# Persisted retained-index accounting: source audit

This note records a read-only audit against main `4e328b0109e13c896b74004823fb049fcb19251a` and native baseline `5cf282278c132c2bc987d217a9453ccfafc32b44`, plus the working raw-leading changes. It contains implementation recommendations and unexecuted test designs. No runtime, build, test, or production edit was performed for this audit. The two prepared-sidecar captures reported by the parent remain evidence of a different path; the corrected no-sidecar captures must not replace them.

## Decision

Raw-leading probe/hit accounting can be implemented and validated independently on the explicitly no-sidecar fixtures. It is **not sufficient to make prepared-sidecar fallback controls equivalent**. A prepared leading source can pass through main's persisted retained-index loader after the raw probe returns null. Those controls need identity/read accounting and then the retained lookup's own accounting. A correct loader alone does not establish that subsequent matching is fully charged.

There is no existing Store work-consumer callback in `DistinctProjectionOptions` or `tryMainCallSiteStringIndex`; `context.Context` provides cancellation, and `CannotMatch` is a planner callback with a different purpose. Do not repurpose either as work accounting. Adding a lump-sum cost to `ordinaryLeadingRows` would charge warm retained hits again, charge the wrong request under concurrent preparation, and lose read/failure order.

## Main's units and operation order

References use the pinned checkout's `graphite-webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/` directory.

`MappedWebGraphBackedGraph.kt:2342–2409` checks capability/file presence, reuses an existing retained index, otherwise serializes loading. The preferred-persisted route sets its retain-persisted policy before loading. Successful loading is assigned to `callSiteStringIndex` only after the reader and trailing-data check succeed. A failed optional file is not a successful retained lookup.

Before opening/parsing the sidecar, `persistedCallSiteStringIndexContentIdentity` (`:2413–2423`) reads the separate content-identity file. Exactly 32 bytes cost one immediate unit. Without that valid identity, main derives it from graph content:

- `StringTable.contentIdentity` (`StringTable.kt:70–81`) reuses the string table's cached/persisted identity without new units. If absent, semantic identity scans one unit per string, buffered and finally flushed (`:129–149`); it publishes the cached identity only after successful return.
- `callSiteStringIndexContentIdentity` (`MappedWebGraphBackedGraph.kt:216–231`) then scans one unit per CallSite, before its raw four-field read, with final flush (`:2426–2447`). The second node-offset lookup used in the digest does not add another unit. Graph-derived identity is not simply a one-unit replacement for the persisted identity file.

`MappedCallSiteStringIndex.readPersistent` (`MappedCallSiteStringIndex.kt:1475–1616`) uses `PersistentIndexReadWork`. Every `readInt` or `readLong` calls `consume()` **before** the actual read. The 32 identity bytes use `readFully` first, followed by 32 consumes: truncated identity bytes therefore do not receive those 32 units. Validation follows each relevant read; CRC is accumulated in that same traversal, not checked in an earlier full-file pass.

For a valid v2 sidecar let N be CallSite count, S string count, U the sum of the four unique-string counts, and T trigram posting count. The successful read costs:

| Part | Units |
| --- | ---: |
| magic, version, string count, CallSite count | 4 |
| complete 32-byte persisted content identity | 32 |
| four unique counts, trigram count, retained-byte long | 6 |
| four CSR blocks: unique IDs, posting ends, node IDs | 2U + 4N |
| S signatures and T trigram postings | S + T |
| final checksum long | 1 |
| Total inside `readPersistent` | **43 + 2U + 4N + S + T** |

Successful loading with a valid separate identity file and successful EOF check totals **45 + 2U + 4N + S + T**. The earlier failed raw probe and subsequent index query are additional work. This formula is a successful-read cross-check, **not an implementation substitute for per-operation accounting**.

Within each property, main reads all used string IDs, then all posting ends, then all N node IDs. Every node-ID read precedes its capacity and `nodeOrder(nodeId)` checks; `nodeOrder` itself adds no read unit. The current Go parser interleaves each directory row with that row's postings. Charging that existing loop would expose a different first failed operation.

`PersistentIndexReadWork` (`:1676–1702`) checks thread interruption on the first and every 1024th logical consume, then delegates to the 1024-unit buffered consumer. Delegate failures from either consume or flush are wrapped in `MappedCallSiteStringIndexReadAbortedException`. Pending work is cleared before submission. The reader flushes explicitly before constructing the loaded index, and again in `finally` (normally empty). Its catch closes an acquired memory reservation and rethrows; a final flush can replace an earlier malformed-read error with a work-abort error.

Memory reservation denial is a different failure from query work budget exhaustion. Main catches `MappedCallSiteStringIndexPersistenceBudgetDeniedException`, sets the persistence-budget-denied flag, and returns unavailable. An aborted accounting wrapper is unwrapped to its original cause and **must escape**, not become optional-file fallback. Cancellation also escapes; ordinary parse/validation exceptions are swallowed as unavailable (`MappedWebGraphBackedGraph.kt:2400–2409`).

After the loaded object exists, a separate `PersistentIndexReadWork` consumes one, reads EOF, requires -1, then flushes (`:2389–2400`). Failure closes that temporary loaded object before propagation/fallback. There is no finally-flush for this separate trailing check: if an extra byte causes the require to fail, its pending one-unit batch is not submitted. This differs from a successful EOF or an EOF-flush budget failure. Preserve it rather than mechanically adding a universal trailing finally.

## Native insertion points and minimum coherent change

Relevant paths are `internal/query/main_string_source.go`, `internal/store/distinct_projection.go`, `main_callsite_index.go`, `callsite_index.go`, and `callsite_strings.go`.

1. Add an optional batch-consumer contract such as `ConsumeWork func(int64) error` to `DistinctProjectionOptions`, forwarded into the **main retained** loader and identity helper. A nil callback means untracked work. Keep `mainMappedIndex` and `strictCandidateIndex` policy separate: their prevalidation rules and main mapped-view costs cannot be inferred from retained `readPersistent`.
2. The query-to-Store adapter must convert the existing work tracker's budget/cancellation panic into a typed returned error without introducing a Store→query dependency. Use a narrow aborted-read cause marker or equivalent. `failProjectionRead` currently falls through to `failNodeRead`, which turns an unfamiliar error into generic `CypherException`; a returned budget cause would lose its class there unless the bridge preserves it explicitly.
3. Route the callback through the actual cold preparation owner in `tryMainCallSiteStringIndex`. Do not store a request callback on the retained index or Store. Warm reuse has no persistent-read cost. A waiting request that receives another owner's completed view is not a second reader. A work-aborted attempt must return an error, leave no unavailable/loaded state, and permit retry with a fresh context.
4. Add a sequential, bounds-safe retained-reader traversal in the loader (or refactor its retained policy into one). Native `loadCallSiteStringIndexWithPolicy` currently rejects size/layout before identity, computes full CRC before CSR checks, and traverses CSR in a different order. It therefore cannot preserve malformed-file/budget priority by just charging its current loops. Main consumes before a failed primitive EOF; Go must not reject all truncated input at `stat.Size()<84` without those partial units. Keep the existing strict A6 and mapped-view readers unchanged unless separately proved.
5. Compute or read content identity at main's pre-parse point, including the immediate persisted-identity charge and legacy string/CallSite scans. The existing `callSiteContentIdentity` has no work argument and no cached semantic string-identity publication state. A complete legacy implementation must preserve that successful cache across later attempts instead of charging all strings again after a later sidecar failure.
6. Flush the reader before exposing a temporary loaded object, perform the separate EOF check, then publish. Return through the existing owner completion path so `state.loading` is cleared, waiters are released, Close can join, and unaccepted mappings are unmapped. A callback panic escaping before that path would strand `loading` and potentially hang Close; use returned errors and unconditional cleanup, not an unguarded call to query `consume` inside Store code.

Current `PrepareDistinctStringIndex` calls are made by `main_string_source.go` (preferred persisted, whole split fallback, mapped view, and other preparation), `filtered_string_count.go`, and `indexed_distinct.go`. The prepared ordinary leading fallback reaches the preferred-persisted call with `MainSource=true`, `SourceCount=1`, `RetainPersisted=true`. That is the first narrow integration point. To claim persisted accounting generally, inventory every real retained-load caller, preserve untracked callers, and distinguish indexed DISTINCT's strict-reader path from the main retained loader rather than blindly enabling a callback on every shared parser invocation.

The retain-persisted flag is allowed to survive a failed attempted load: main sets the preference before the operation. This is different from publishing the retained index itself. Keep both state observations in the oracle.

## Focused follow-up evidence

Use the original 40-source public ordinary query, with an actual prepared sidecar, alongside its separately preserved no-sidecar variant. Do not call the storage loader directly as the only proof. Capture full rows/errors, all request diagnostics, per-source retained/view/raw-cache state, source hashes, and complete main stacks. Proposed checks, not executed here:

- Valid identity + valid sidecar after a 64-node incomplete probe: budgets immediately around the derived persistent-read final batch and around the separate EOF unit. Distinguish load failure from later matching failure.
- Repeat on the same graph with a fresh context: successful retained publication skips identity/read costs; failed budget attempts do not become permanent unavailable or successful retained state.
- Bad magic/version with a valid identity file: identity is charged first, then only the primitive prefix actually read, flushed on rejection. Preserve subsequent optional fallback accounting.
- Truncated four-byte primitive versus truncated 32-byte identity payload: primitive consumes before EOF; identity payload consumes its 32 units only after complete `readFully`.
- Invalid unique ID, posting end, and node-order entries placed in different CSR sections: verify the exact first validation failure and partial final batch rather than a full-file CRC cost.
- Bad checksum versus valid checksum plus trailing byte: the former finishes the reader's charged checksum operation; the latter closes a temporary loaded index but does not submit its separate pending EOF unit.
- Missing/malformed separate content identity, with and without cached string identity: preserve identity-scan failure and successful intermediate string-identity publication across a later failed sidecar attempt.
- Real cancellation/work rejection while a second request waits for preparation, followed by Close and a fresh request: prove terminal owner/waiter completion and nonpoisoned retry state. Scheduling evidence must be labelled separately from default execution distribution.

The no-sidecar raw-leading fix need not absorb this larger loader change before it can be reviewed as a bounded improvement. Prepared controls must remain explicitly failing/unresolved until this accounting path and the subsequent retained matching path have their own main-backed comparison. They must not be relabelled as raw fallback or dropped from the original observations.
