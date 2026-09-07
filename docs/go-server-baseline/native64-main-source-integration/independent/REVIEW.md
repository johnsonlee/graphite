# Independent lazy main candidate-source review

Status: targeted review passed. All sixteen frozen author production files match the independently tested copy exactly; see `final-production-receipt.json`. This is finite correctness evidence, not proof of complete main parity or a performance result.

The review used pinned Kotlin main 4e328b0109e13c896b74004823fb049fcb19251a and its existing executable JAR (SHA256 91c3a1d154ca96004c55df195d9f752e077cab3e33ca1570b2c88b872d9bc34d), Java 17.0.18, Go 1.22.0 darwin/arm64. No real64 server or benchmark was started. Author and root production files were not edited. All Go tests ran in independent copied modules.

## Findings and disposition

1. **Mapped versus retained reader consumption:** the initial candidate still applied strict all-node offset validation through the shared reader. Author added independent main retained/mapped publication and rejection states, with the existing strict A6 reader preserved. Retained reads canonical offsets for all postings; the mapped reader checks node-ID capacity and defers canonical offset validation to selected ranges. A mapped rejection cannot poison strict or retained loader state. Main range order accepts a first negative offset in retained loading but rejects negative order in mapped selected-range validation. The latter finishes the entire current range before declining, so a later accessor exception is not suppressed.

2. **Mapped hot history:** independent review found that the first repair still revalidated and sorted every selected posting on every request. Main caches a range's valid/invalid outcome; cold construction reads all canonical orders, while a hot cursor reads only its initial order and subsequent orders on demand. Author replaced eager sorting with a separate fixed 1024-slot validation cache and synchronous heap cursor. A further cancellation checkpoint was added to the Go-specific owned-ID copy so a large range does not hold the Store read lock through an uninterruptible copy.

3. **Loader lifetime and isolation:** no additional defect found in the reviewed final loader. Independent tests cover a canceled waiter leaving the loader owner intact, owner cancellation allowing another request to retry, and both concurrent Close calls waiting for all three strict/retained/mapped loading tasks. The loader cleans unpublished mappings before signaling completion. The published view and node-data mappings are protected by the shared Store lifetime lock; subsequent reads fail with ErrStoreClosed.

4. **Demand and owned nodes:** independent tests cover completed-wave context cancellation without poisoning a new wave, stable owned node payloads across cursor advance and Store.Close, node-ID cache publication only after natural exhaustion, and no cache publication after selected full-node decode failure. The cold/hot mapped cursor tests distinguish lower-level pre-read orders from hot Store reads, and check that take-one does not advance the second posting. These lower-level tests do not claim a complete query can decode nodes after Store.Close.

## Independent actual-main observations

`main.json` contains eight full query responses, preserved without sorting or error normalization. An independently copied Java-saved all-type graph has EnumConstant ID14's tag changed to127 and FieldNode ID18's type SID changed to2147483647; `mutation.json` records exact byte edits. Both scoped and two-source forms of name='field' OR name='RED', in both textual orders, throw `IllegalArgumentException: Unknown node tag: 127`. This confirms concrete type stream-head preparation order rather than textual predicate order. Restricting to FieldNode exposes the exact out-of-range SID error. Restricting to nonmatching EnumConstant returns the complete empty result and does not decode its bad tag. All eight native results match class, message, columns and rows exactly.

`range-oracle/main.json` separately records actual main `MappedCallSiteStringIndexView` nodeOrder callbacks from a small reflection-constructed view. This probes the actual class implementation rather than emulating it. Cold construction reads [10,20,30]; hot construction reads only [10], with 20 and30 consumed by later iterator hasNext calls. A callback failure at30 occurs before the cold sequence is returned, whereas hot iteration emits10 and20 before that same error. It uses a tiny in-memory posting range for source-access correctness only. Its initial fixture-directory path error and corrected command output are both retained.

## Validation scope

- Initial snapshot: eight main response comparisons and three added query lifecycle tests, race repeated10; ten existing lifecycle tests repeated10; query/store vet passed.
- Loader snapshot: three added loader tests plus author loader isolation/cancel/close tests, race repeated20; main-source/query tests repeated5.
- Hot cursor snapshot: all independent tests plus main loader/range tests, race repeated10; query/store vet passed.
- Final copy-check delta: targeted final race log and production identity receipt are recorded separately.

The copied module paths, source snapshots, test sources and raw logs are preserved. The author continues to own full-module regression and original corpus comparisons. This independent receipt does not replace those results or remove any original mismatch from its denominator.

## Explicit remaining limits

The JVM global memory budget and its range-cache admission denial are not replicated by this bounded feature. Exact GraphWork accounting and JVM interruption polling counts are not asserted equivalent to Go context checks; Go's owned-copy checks are a runtime lifetime guarantee. JVM process-dependent Class identity HashMap iteration order, the original remaining82 corpus differences, and previously recorded raw/ordinary projection limitations remain separate work. One complete-result oracle run or a finite race repetition cannot establish all possible concurrent schedules. No latency, CPU, allocation, speedup, P95, or 64-graph acceptance is claimed here.
