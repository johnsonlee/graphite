# Actual-main mapped cursor method controls

The two final original-configuration JVM captures agree on every parsed field across **11 controls / 19 operations**. They invoke actual pinned-main storage methods, not public Cypher queries. Both use unchanged bytes from the earlier main-written large and hit64 archives (34 files); the writer, graph bytes, main JAR and old captures were not modified. See schema.md for explicit instrumentation and method-input scope.

Observed behavior:

| Control | Actual main result |
| --- | --- |
| C01: pre-interrupted position 1023 | Reads one order, callback `[1]`, returns cursor and publishes valid cache; interrupt remains set. |
| C02: pre-interrupted position 1024 | Throws CancellationException before order read or callback; no publication. |
| C03: pre-interrupted range 1023–1024 | Reads position 1023, checks at 1024, throws; finally callback `[1]`, no publication. |
| C04/C05: binary-search finally | Callback setting interrupt still returns row 0; callback throwing the original budget exception propagates that same instance. |
| C06: range-finally interrupt | Callback `[3]` sets interrupt, then valid cache and cursor are published. Warm validation is free, but cursor construction/advance perform three actual nodeOrder calls. |
| C07: range-finally throw | Reads all three orders, callback `[3]` throws original cause; cache stays empty. Retry validates/publishes; warm validation has no callbacks. |
| C08: invalid order | Even after an injected negative order, reads the complete three-position range and flushes `[3]`, then caches invalid. Warm invalid lookup returns null with no reads/callbacks. |
| C09: actual colliding keys | Property 0 row 0 and property 1 row 1 collide at slot 0. Callback failure preserves the old valid entry. A later completed invalid validation does replace it with the new invalid entry. |
| C10: binary interrupt → range 1023 | Without harness cleanup between calls, binary search and range both return, callbacks `[1,1]`, valid cache is published while interrupt remains set. |
| C11: empty range 1..0 | Cold validation publishes valid cache, then actual cursor construction throws ArrayIndexOutOfBoundsException with no work callback/order call. Warm construction reads node 11's actual order 57 and returns a cursor with hasCurrent=false. This is a method-level empty-input control, not proof of a public route. |

Cold cursor reads use the order array produced during validation; a warm cursor invokes the actual nodeOrder function during construction and advance. Instrumentation records actual order values before the explicitly selected negative-order substitution. Thread.interrupted() is called only after each operation's outcome, state and interrupt flag have been captured, to isolate serial controls. C10 keeps the two calls in one operation specifically to observe propagation.

`main-capture` and `repeat-capture` preserve complete output, class bytes, command receipts and fixture identities. `initial-capture` preserves the earlier successful 10-control run with its original sources under `initial-v2-inputs`. `failed-attempt-v1` preserves a pre-runtime source-path failure: the initial script looked for a standalone BufferedGraphWorkConsumer.kt; the first correction selected MappedCallSiteStringIndex.kt, and the final inputs additionally bind the actual defining source, MappedWebGraphBackedGraph.kt. None of these attempts is silently replaced.

The final external captures are `/Users/johnsonlee/.codex/benchmarks/graphite/mapped-cursor-work-accounting-v3` (JVM PID 31504) and `mapped-cursor-work-accounting-v4` (31509), both terminal exit 0. The only JVM option is the established `-Xmx512m`; no diagnostic FastThrow override or dispatcher forcing was added. `archive-verification.json` and `verify.py` check 45 copied artifacts against external bytes, the archived fixture bytes, and complete parsed repeat equality. This is correctness evidence only, with no timing, benchmark or P95 acceptance claim.
