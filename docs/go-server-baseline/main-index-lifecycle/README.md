# Pinned-main mapped CallSite index lifecycle audit

The original 64-graph c4 result remains **190 HTTP 400 failures out of 8,400 attempts**. This audit deterministically reproduces the same exception using the unmodified main `4e328b0109e13c896b74004823fb049fcb19251a` jar and a temporary tiny persisted graph. It does not replace those failures, rerun 64 graphs, or provide performance evidence.

## What closes an in-use index

`QueryPipeline.kt:2231–2236` releases a graph's disjunction cache when one source of a multi-source indexed DISTINCT projection contributes zero rows. That is a completed source scan in one request; another request can still be using the same graph and index.

`MappedWebGraphBackedGraph.kt:2227–2229` returns the shared index reference. The graph's index lock protects publication/removal, but does not lease that reference through the caller's subsequent index operations. `aggregateStringPropertyDisjunctionInternal` obtains the reference at line 364 and invokes `index.aggregate` at line 365.

`releaseStringPropertyDisjunctionCache` calls `closeCallSiteStringIndex(force=false)` at lines 2005–2007. Lines 2020–2033 retain certain persisted split-query indexes or completed newly built trigram indexes. These are optimization-state conditions, not active-reader counts. Otherwise lines 2050–2053 close the index and clear its graph reference. `retainPersistedCallSiteStringIndex` is an `AtomicBoolean`, set for particular consumer types at lines 2057–2060; it is not a retain/release count.

`MappedCallSiteStringIndex.matchingStringIds` checks its cache under the index monitor, computes candidate matches outside that monitor, then calls `cacheMatchingStringIds` (lines 339–362). `close()` holds the monitor only while clearing caches and closing the reservation (lines 995–1002). Therefore another caller can close the reservation between candidate matching and the cache insertion. That insertion reaches `Reservation.tryGrowTo` at lines 438–448; line 2183 checks the reservation's closed flag and throws exactly `Cannot grow a closed mapped CallSite string-index reservation`.

The pinned graph interface itself describes `releaseStringPropertyDisjunctionCache` as a rebuildable-cache hook (`graphite-core/.../Graph.kt:294–300`). Rebuilding the graph's next index does not restore a previous index reference already held by another thread. This is separate from GraphRegistry graph leases or graph unload.

The captured 64-graph HTTP bodies do not include a stack trace or a graph identity, so the specific overlap behind each of the 190 failures cannot be reconstructed from those bodies. The deterministic probe establishes that this source path produces the identical exception, rather than claiming all 190 individual interleavings were traced.

## Deterministic correctness probe

`IndexLifecycleProbe.java` runs against the existing jar; it does not compile or modify Kotlin production code. It copies the tiny JVM fixture, ensures its required node index, prepares and persists a CallSite sidecar, closes that graph, then loads the sidecar again with the default configuration.

Thread B enters a real aggregate lookup and pauses through the supported `GraphWorkConsumer` callback while its stack is inside `MappedCallSiteStringIndex.candidateStringIds`. Thread A calls the same public release hook that the zero-hit planner path invokes. Thread B resumes and reaches the cache insertion with a closed reservation. `default.log` records:

```text
prepared=true
loadedFromPersistence=true
borrowerPausedInsideIndex=true
indexClosedWhileBorrowed=true
borrowerFailure=java.lang.IllegalStateException: Cannot grow a closed mapped CallSite string-index reservation
```

The full stack is retained. Exit 0 means the probe asserted this exact failure. The first attempt lacked the fixture's required `graph.nodeindex`; those failed setup logs are retained separately as `initial-missing-index-*`. The successful probe then used main's existing `GraphStore.ensureNodeIndex` on the temporary fixture. No original persisted fixture was modified.

## Configuration outcome: zero budget is rejected

Main documents `-Dgraphite.webgraph.callSiteStringIndexBudgetBytes=N` in `docs/large-broad-query-pressure-benchmark.md:204`. `MappedCallSiteStringIndexMemoryBudget` reads it once lazily, clamps it at zero, and otherwise defaults to half of maximum heap (lines 2157–2172). Setting it to zero in a fresh JVM prevents this retained index from being created: the probe reports `prepared=false` and `budgetDeniedNoIndex=true`.

That storage-level result is **not** enough to establish equivalent HTTP behavior. Two independent tiny-server forks, default and zero-budget, each received the same 24 requests with four concurrent workers. Six actual CallSite queries were sent across root, scoped, explicit selection and fanout including graph rows. All default responses were 200. Only **14/24** responses were equivalent under zero budget:

- Eight original successes became HTTP 400 with `Distinct projection capability became unavailable`.
- Two scoped/fanout successes lost `$metadata` in their result rows.
- Fourteen remained equivalent.

The DISTINCT error is visible in `QueryPipeline.kt:2191–2198`: after selecting the projection capability, a null storage result is treated as an error. Thus a configured budget denial is not a transparent end-to-end fallback for this suite. Raw bytes, headers, request queries and complete server logs are in `http-default/` and `http-zero/`; `http-comparison.json` preserves the complete 24-case denominator.

**Do not use budget=0 as a comparable baseline or as a way to hide the 190 failures.** This audit has not validated any original configuration that both removes the competition and preserves complete query response semantics. It makes no claim that no conceivable configuration exists. No further configuration search was performed. The default main c4 failure remains reported; any future baseline configuration would require its own explicit protocol and complete response-equivalence gate.

## Reproduction and scope

`default-command.json` and `zero-budget-command.json` contain exact Java commands and exits. Compile the standalone probe with the pinned jar on `javac -cp`, then run it against a fresh temporary copy of `graphite-server/internal/store/testdata/jvm-v3`. `capture-budget.py --out <fresh-directory>` captures the default HTTP fork; add `--budget 0` for the counterexample. Existing output directories are rejected. All dedicated processes have exited.

`verification.json` records the exact jar SHA, Java version, relevant pinned source hashes, temporary fixture hashes, commands, raw evidence hashes and observed results. This was a bounded correctness/source audit only: no 64-graph runtime, no benchmark, no production changes, no new performance claim, and no overwrite of earlier evidence.
