# Actual main serial raw work accounting oracle

This is a correctness capture against pinned main `4e328b0109e13c896b74004823fb049fcb19251a`, supporting the native follow-up from Go baseline `cf920cd4`. It contains no performance measurements. Synthetic persisted graphs establish work-accounting and error boundaries only.

`main.json` contains 43 public scenarios and 57 ordered operations. `buffered-main.json` contains 12 original buffered-consumer scenarios and 39 ordered operations. Each was captured twice using the original JVM configuration, and twice more with the explicit diagnostic flag `-XX:-OmitStackTraceInFastThrow`. All four captures and their actual commands, stdout/stderr, inputs, class bytes, fixture changes, outputs and terminal receipts remain separate.

## Public input and result contract

Each `cases.json` entry has `name`, `mode: "context"`, decimal-string `budget`, `sources: [{"fixture": ..., "graphId": ...}]`, and ordered `operations`. Every source gets its own mapped graph loaded from `fixtures/<case-name>/store<source-index>`. One source uses the actual `CypherExecutor`; two sources use `CrossGraphCypherExecutor` with `graphSourceScopeApplied=false`. Each case owns one explicit context across its operations. `freshExecutor` creates another executor using the same mapped source objects and context. No context is shared across concurrent public executions.

Each output case records the input specification, construction outcome, operation `before` and `after` snapshots, actual public result or full failure, and final snapshot. Snapshots contain all eight original execution diagnostics, decimal-string remaining work, cancellation state/reason, and read-only storage observations. Error records preserve actual simple and qualified classes, nullable message, available exception getters, and full observed stack. Result rows and provenance metadata are kept unchanged. Numeric operation parameters follow the same Gson `Map` conversion as the previous work-context harness.

`fixtures.tar.gz` contains exactly **329 regular files in 20 variants**. `fixture-variants.json` gives each member's name, byte length and SHA-256. Each nonempty fixture has 1,025 nodes of one kind: `LocalVariable` or `CallSite`; empty controls have none. IDs are `10 + encounterIndex`, and the writer proxy supplies that exact node order to the actual main `GraphStore.save`. Values are `miss-<index>` except declared `hit-<index>` entries. The writer loads and closes each intact persisted graph before changing declared name SIDs to `Integer.MAX_VALUE`. `mutations.json` records precise original and replacement bytes. Each public capture has 1,048 fixture files before execution, with none added, removed, or changed during execution.

The public queries project only a node property and use a literal bounded LIMIT. Local scenarios use `n.name CONTAINS 'hit' OR n.name CONTAINS 'zzz'`. CallSite scenarios use `n.caller_name CONTAINS 'hit' OR n.callee_name CONTAINS 'zzz'`, with exactly two sources to select serial storage. Four single-predicate Local controls are retained as well.

## Actual routes and observations

Source inspection initially distinguished `rawStringPropertyScan`, which calls `workConsumer.consume()` directly, from buffered disjunction lookup. Actual public error stacks then established that **even the single-predicate Local name query in this matrix uses the fused buffered disjunction route**: `executeDirectStringFilter` selects `executeDirectStringDisjunctionRows`, reaching `lookupStringPropertyDisjunction`. These controls do not establish behavior for the separate `rawStringPropertyScan` admission/index-building path.

The two-source CallSite errors identify `serialRawCallSiteStringDisjunction`, and the captured `callSiteParallelScanCount` stays zero. No parallel scan, mapped index, retained CallSite index or raw projection cache is exercised here. Repeated same-query executions inspect 1,025 nodes each and accumulate 1,025 / 2,050 / 3,075 work; this matrix does not claim a cache-hit reduction.

The original `BufferedGraphWorkConsumer` batches at 1,024 units. It clears pending work before invoking its delegate. An automatic or manual flush that throws is therefore not resubmitted by a following empty flush. Unit-only delegates receive immediate calls; a null delegate receives none. The primitive harness reflects the original class constructor, `consume`, `flush`, and `pending`; its delegate only records actual callbacks and injects explicitly declared failures.

Public misses consume 1,025 units with an exact budget. A short budget throws atomically, without partial result rows, and records consumed work at the budget ceiling. The first hit and a hit at the 1,024th position consume 1 and 1,024 units respectively. Early LIMIT flushes before exposing the matched row. LIMIT 0 succeeds without scanning even with an already exhausted context and a bad first SID. Consuming zero after a budget-exceeded operation succeeds; the budget exception did not cancel the request.

Bad-SID and budget errors have distinct timing. At encounter index 1,022, the raw lookup first reaches the bad SID; a short budget then throws from the `finally` flush, replacing that error. An exact 1,023 budget preserves the bounds error. At index 1,023, the automatic 1,024-unit flush occurs before the SID access, so budget 1,023 fails there. Error stacks in the raw records preserve these distinctions.

## Repeat evidence and error variability

The original-config public captures match completely in 41 of 43 scenarios. The two exceptions are `call-bad1023-budget1024` and `call-bad1024-budget1025`: the first capture has `ArrayIndexOutOfBoundsException` with `Index 2147483647 out of bounds for length 1026` and a stack; the second has the same class with a null message and empty stack. All other fields, including all eight diagnostics, remaining work, cancellation, storage observations and public rows, match. `repeat-audit.json` preserves each differing field and both original values.

The two diagnostic captures disable HotSpot fast-throw stack omission and match completely. This supports fast-throw optimization as the explanation for the original variability; those controlled diagnostic runs do **not** replace acceptance under the original JVM configuration. A native oracle comparison must retain the original observed outcome sets for the two affected error cases rather than silently discard all messages or use only the diagnostic configuration. The 12 primitive cases match completely across both original captures.

These captures cover serial buffered raw lookup and the buffer primitive. They do not establish indexed work accounting, parallel accounting, every cache producer, server integration, or latency/P95 acceptance.
