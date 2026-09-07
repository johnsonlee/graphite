# Node streaming pagination and ORDER correctness

This is a correctness port of pinned main `4e328b0109e13c896b74004823fb049fcb19251a`,
`QueryPipeline.tryStreamingFilteredMatchLimit` (3311), its node binding source
(3418), parallel ordered projection (3444), serial distinct/ordered projection
(3540 onward), and `rankedRowComparator` (3615). There are no performance runs.

The implementation depends on the separately frozen lazy main string source and
C/D/E node consumers. The exact combination before this delta is Git tree
`faf2bee8fe4fc845442cf775f212cd70b0a96bbd` (main source tree
`baf9ae489cae2477bc52c7a83561cfd0ff449560` plus the CDE consumer delta). It does not
change those providers or Store. Parent integration may retain its later Store,
context, tuple, and mapped decoder changes; that composition requires its own
verification.

## Behavior

* Admission is a non-optional, one-node MATCH with WHERE, RETURN without an
  aggregate/star, optional ORDER/SKIP, and literal LIMIT. A nonliteral SKIP is
  zero and is not evaluated in this main planner. A negative literal skip or
  retained-count overflow declines. Numbers convert through Java `toLong` and
  clamp to Int; LIMIT 2147483648 is **not** a negative wrapped count.
* The source is created when demanded. Necessary direct string predicates use
  the existing main source; other predicates use the existing generic typed
  node source. Every matched node is owned before projection. The full WHERE
  still executes. Ordinary SKIP advances matches before evaluating RETURN.
* DISTINCT without ORDER uses Cypher visible-value equality. Scoped execution
  stops after enough distinct rows. Qualified execution exhausts sources to
  merge later provenance into selected rows.
* ORDER uses a bounded heap and encounter-order ties. Every necessary candidate
  is projected, including rows eventually rejected by the heap. A duplicate
  currently retained by DISTINCT merges provenance and skips ORDER evaluation;
  once evicted, the same visible key evaluates ORDER again when encountered.
* Eligible ordinary direct-string ORDER submits source waves. Successes merge
  in source order; the first completed failure cancels and joins the submitted
  wave. Future waves are not started. Each worker owns its heap, regex cache,
  bindings and iterator. EAGER has neither JVM lookup strategy nor prepared
  capability, so a nonempty eligible type allows this parallel branch.
* Main's signed `(sourceIndex << 56) + localOrder` tie ordinal is preserved,
  including signed overflow. It is not replaced by an idealized stable pair.
* Source scope supports finite AND/OR/IN constraints and inline graphId.
  Main applies this scope before creating the pagination pipeline, so the
  source count passed into storage is the selected pipeline count. The 40-source
  routed negative-offset probe disproved an earlier assumption that B retains
  the original full-catalog count.
* Unknown labels select main's empty general source before streaming dispatch.
  Their suffix expressions use the existing general projection operator; they
  do not inherit streaming's ignored nonliteral SKIP rule.
* Heap comparators poll their owning context at the first/every 1024th
  comparison, and the sorted result checks cancellation before returning.
  Main enables comparator/materialization polling with its work tracker; Go
  does not expose an equivalent unbudgeted switch. These tests establish real
  cancellation/join behavior, not exact JVM GraphWork poll counts.

The callable package seam for a future relationship consumer is
`streamingOrderedRows(plan, nextBindings)`. Its input yields owned bindings,
then the consumer evaluates WHERE and projection. Relationship sources must
follow their own lazy traversal and pushed-WHERE rules; they must not reuse an
already consumed node iterator or assume direct-string source waves.

## Evidence and tests

* `design-main.json`: 53 complete main responses and node-access traces;
  `design-repeat.json` is the independently captured repeat. Actual main fixture
  plus an observing/throwing Graph proxy is implemented by `StreamingOracle`.
  The Go test uses the production plan/consumers and an outer node-source
  observer. Four declined overflow/unbounded controls compose the corresponding
  existing operators explicitly; they do not claim admission to this B planner.
* `cases.json`: 48 queries across clean MAPPED/EAGER and three corrupt MAPPED
  fixtures, scoped and cross-source: 480 full responses. These include retained
  duplicates, eviction, aliases, metadata, whole/nested nodes, type equality,
  mixed/null ordering, missing names, parameters, count conversion and errors.
* `unknown-cases.json`: 24 full responses from clean/corrupt fixtures, including
  throwing/nonliteral SKIP in main's unknown-label general dispatcher.
* `source-cases.json`: 28 histories / 38 executions with 2, 9, or 40 tiny graph
  sources: late source errors, missing sidecars, reverse graph IDs, ties,
  repeats, and source routing/negative offsets. These are correctness fixtures,
  not a real-data performance corpus.
* Standard-context tests verify cancellation, producer/consumer error priority,
  joining before Store.Close, no future wave after error, owned nested values
  after Close, and partial-source cache nonpublication followed by a successful
  fresh request and natural exhaustion. Comparator tests cover heap and final
  ranking cancellation.

Comparisons cover every response field under JSON-value semantics, with numbers
compared by their JSON numeric value. They are not byte equality: Java `0.0`
and Go `0`, for example, remain different spellings. Booleans, strings, null,
errors, columns, rows, and row order are compared distinctly.

Java helpers load existing actual-main-written fixtures from `candidate-index`.
All mutable JVM runs use disposable copies. `StreamingPaginationOracle` writes
both semantic `*-main.json` and actual Java UTF-8 `*-wire.json`; tests compare
wire output so a lone UTF-16 surrogate is not accidentally decoded as U+FFFD by
Go's JSON reader.

Pinned oracle runtime: Java 17.0.18 ARM64, actual 16 processors, no processor
count override. Oracle JAR SHA256:
`91c3a1d154ca96004c55df195d9f752e077cab3e33ca1570b2c88b872d9bc34d`.
The independent review bundle records exact Java/Go commands, tool paths,
fixture hashes, initial failures, and the source manifest. For a local replay:

```sh
cd graphite-server
go test -race ./internal/query -run TestStreamingPagination -count=1
go test -race -count=1 ./...
go vet ./...
```

## Explicit boundaries

This delta implements node paths only. Method dispatch, relationships,
variable-length traversal, multiple patterns, OPTIONAL and named paths remain
outside this admission. Their existing fallback is not evidence of parity;
the separate relationship audit documents remaining work. EAGER's strategy
handling in the older generic DISTINCT/CDE helper remains a separately reported
scheduling difference; this delta changes only B's policy.

Parallel main creates `PriorityQueue(retainedCount)` before source tasks. On the
fixed default Java 17 compressed-class Object[] layout, capacities greater than
2147483645 throw `OutOfMemoryError: Requested array size exceeds VM limit`.
The port preserves this deterministic boundary and timing. The small-heap Java
probe also records that disabling compressed ordinary/class pointers changes
that boundary by one element. Environment-dependent `Java heap space` failures
below this bound are not modeled; no arbitrary lower guard substitutes for
available heap, and Go does not promise recovery from runtime memory exhaustion.

The original 1048 comparison remains intact: this delta repairs B28 on top of
CDE's 1016, producing 1044 equal with the existing four wrapper-empty-term
annotation/mixed differences remaining. The separate 432 generic fault audit
improves from 408 to 432 equal. No claim covers all possible Cypher queries,
JVM configurations, cancellation race positions, or whole-server parity.
