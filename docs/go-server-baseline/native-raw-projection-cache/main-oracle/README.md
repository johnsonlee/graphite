# Pinned-main raw projection cache correctness oracle

The unchanged main JVM artifact at `4e328b0109e13c896b74004823fb049fcb19251a` passes 20 cache-primitive assertions and 18 graph-provider assertions. This is correctness evidence only, without performance measurements or a claim about full Cypher planner coverage.

Run from the repository root:

```sh
python3 docs/go-server-baseline/native-raw-projection-cache/main-oracle/run.py
```

The runner compiles two Java observation programs against the pinned fat JAR, invokes the actual private main classes/methods through reflection, records their results, and disassembles the observed cache classes and mapped graph. It never substitutes a Java reimplementation of the cache. `receipt.json` records the exact commands, zero exit codes, Java version, source hashes, class hashes, and before/after JAR SHA-256 `91c3a1d154ca96004c55df195d9f752e077cab3e33ca1570b2c88b872d9bc34d`.

`responses.jsonl` verifies capacity 16 and eldest eviction, promotion by successful `get`, duplicate `put` preserving both old IDs and eviction order, cached empty arrays versus missing entries, clear, and value-key identity. Key equality includes predicate order, property, transform, mode, expected string, and limit; the actual key has only predicates and limit fields, excluding projected columns. Reflection reads the entry map without promoting its keys.

`graph-responses.jsonl` directly invokes original `rawCallSiteStringProjection` on disposable copies of existing persisted correctness fixtures. `candidate-index/clean` has four CallSites: an all-match limit-two query caches IDs `[17,2]` and returns `[["caller","invoke"],["other","other"]]`. Reversing projected columns reuses the identical cached ID array and returns the reversed row values. An exhausted miss caches an empty entry; its repeat consumes one work unit. Ordinary retained-cache release preserves raw entries, whereas explicit clear and graph close empty them. `indexed-distinct/split-clean` has 4,096 CallSites: a missing limit-one query consumes 64 work units, returns null and publishes no entry; an early complete limit-two query caches `[0,1715]`. Interrupting before the first inspected node yields the original cancellation exception without publication. These work-unit observations are deterministic correctness counters, not timing measurements.

`graph-fixture-hashes.json` verifies every original fixture file is unchanged on both copies and sources after graph close, and no files were added. The immutable real64 dataset is not opened by this oracle.

The raw string-state source audit inventories SHA-256 hashes for all 97 Kotlin/Java production source files enumerated by `rg`. Its only `stateFor(` match is the method definition; the mapped graph references its raw-state object only for construction, clear, retained-byte count, and entry count. Mapped graph bytecode has no call to `RawStringMatchStates.stateFor`. Consequently the unused persistent raw string-state cache stays empty through these production paths; the per-query bounded matcher is a different object. The graph observations independently show `rawMatchCount = 0` throughout. This conclusion does not cover arbitrary external reflective mutation.

An initial harness assertion incorrectly assumed the small fixture contained 96 CallSites; its failure is preserved in `initial-incorrect-fixture-count.stderr`. The corrected oracle obtains the actual count (four) from the mapped JVM graph. No production source or fixture was modified to make the assertion pass.
