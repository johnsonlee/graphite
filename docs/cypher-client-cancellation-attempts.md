# Cypher Client Cancellation Attempts

This log records each implementation attempt to stop server-side Cypher work after an observable HTTP connection
failure.
Each attempt is isolated in one commit. The acceptance criteria are:

- a real HTTP/1.1 client reset stops an active broad graph scan promptly;
- the query releases its concurrency permit and graph leases only after execution exits;
- single-graph, cross-graph, and fanout queries retain their existing results and limits;
- normal connected-query throughput does not regress materially against `main`.

The contract deliberately excludes a clean input FIN. Before response I/O, the server cannot distinguish a client
that called `close()` from one that sent a valid `SHUT_WR` after its complete request and is still waiting to read the
response. Cancelling either would break HTTP input half-close. Cancellation therefore requires a TCP reset, servlet
timeout, or Jetty/connection error.

## Baseline

The Cypher endpoints executed synchronously on the Jetty request thread. Closing the client connection while a query
was scanning did not affect `CypherExecutor`; the query kept its concurrency permit and graph lease until it completed
or exhausted the work budget.

## Attempt 1: async execution with disconnect listeners

Status: **rejected as incomplete**.

Changes:

- added a request-scoped `CypherCancellationSignal` checked by the existing graph work tracker;
- moved HTTP Cypher execution to a dedicated bounded executor while preserving the non-queuing concurrency permit;
- attached Servlet async, Jetty `HttpChannel`, and Jetty connection failure listeners;
- kept graph leases until the worker actually completed and cancelled workers during server shutdown.

Behavior test:

```text
./gradlew :explore:test --tests '*CypherClientCancellationTest*' --no-daemon
```

The test sends a broad infinite scan over a raw HTTP/1.1 socket, waits for at least 1,000 candidates, then closes the
socket with `SO_LINGER=0` to send a TCP RST. Result: the only Cypher permit was still unavailable after 5 seconds and
the scan continued. Jetty does not emit the registered failure/close callbacks while an async request has finished
reading its input and performs no network I/O.

Conclusion: cooperative cancellation and bounded worker ownership are useful foundations, but listeners alone do not
detect this disconnect window. Performance comparison is deferred because this attempt does not satisfy the required
behavior.

## Attempt 2: keep Jetty read interest while a query runs

Status: **rejected as unsafe**.

Jetty read the reset as an input shutdown but intentionally kept the output side open for the pending async response,
so connection-close listeners still did not fire. This attempt added a request-scoped monitor that:

- starts after 10 ms, so normal fast requests usually finish before its first run;
- kept Jetty's own HTTP connection read callback registered instead of reading protocol bytes itself;
- checks the endpoint every 50 ms and cancels when the input is shut down or the endpoint closes;
- stopped its scheduler with the query, leaving no permanent polling task or extra application thread.

This detected FIN and RST for graph scans, but two correctness gaps remained. Normal request completion did not remove
the pending Jetty fill callback, so `HttpConnection.onCompleted()` aborted healthy keep-alive connections with
`IOException("Pending read in onCompleted")`. Cancellation was also only checked while consuming graph work, allowing
`range()`, `UNWIND`, projection, and result materialization to continue allocating after disconnect.

## Attempt 3: owned read interest and cancellation-only checkpoints

Status: **accepted**.

The retained implementation:

- registers a monitor-owned Jetty read callback after 10 ms and explicitly removes that callback before normal async
  completion, allowing `HttpConnection` to resume keep-alive reads;
- delegates readable sockets back to Jetty, cancels on reset/connection error, and retires the monitor without
  cancellation on a clean input half-close so the client can still read the response;
- replays only the currently readable bytes that fit in Jetty's request buffer, leaving any excess in the socket buffer
  instead of consuming and dropping it, then restores read interest while capacity remains so a later reset is still
  observed;
- adds cancellation-only checkpoints to graph-free expression, clause, aggregation, ordering, and result-materializing
  loops without consuming graph work budget;
- polls through aggregation deduplication, numeric conversion, sorting, and multi-pass statistics; literal, prefix, and
  pairwise-disjoint ASCII range sequences use semantics-preserving cancellable scanners, while every remaining tracked
  Java `Pattern` receives a cancellation-aware `CharSequence` that checks after each 1,024 character accesses;
- checks cancellation again after the query block returns, so an interrupt-ignoring block cannot publish a successful
  result after cancellation;
- registers the route continuation before scheduling work, keeps the concurrency permit and graph leases through JSON
  response materialization, then stops metrics, releases the permit, and publishes final task completion.

The monitor never writes a heartbeat or commits an early response. The same signal covers single-graph, cross-graph,
and fanout execution, including all sequential executors in one request. An ordinary client `close()` that reaches
the server as a clean FIN has the same deliberate limitation as `SHUT_WR`; only a reset or later response-write error
makes abandonment observable.

Behavior verification:

```text
./gradlew :cypher:test :explore:test :cypher:detekt :explore:detekt --no-daemon
```

`CypherClientCancellationTest` sends TCP RST during an infinite candidate scan and a graph-free ten-million-element
`range`/`UNWIND` query. Each query stops and its only permit is reusable within 2 seconds. A half-close regression sends
`SHUT_WR` after a complete request and still receives the full HTTP 200 response. Raw-socket regressions run a slow
Cypher query and `/openapi.json` both sequentially and pipelined over the same keep-alive connection; all responses are
HTTP 200. One regression pipelines 150 requests, exceeding Jetty's request buffer, and verifies that all 151 responses
arrive. A pipeline-then-RST regression proves the monitor continues observing the socket after buffering the next
request. Deterministic barriers prove cancellation occurs inside graph-free loops, percentile sorting, and regular
expression matching. Compatibility tests preserve Java backreferences, look-around, possessive quantifiers,
character-class intersections, and all five default line terminators. Guard tests prove cancellation wins after an
interrupt-ignoring block, a registered blocking continuation retains the only permit, response failures stay
exceptional, and task completion is not visible until metrics and permit teardown finish.
Exact response tests preserve the pre-existing scoped and multi-graph 404 JSON contracts.
The regex regressions include a short, group-free `a?a?...` pattern that backtracks exponentially; cancellation begins
only after the matcher reaches an internal checkpoint and the worker still stops within 2 seconds.

Performance environment: Apple M3 Max, macOS 14.3 (`arm64`), OpenJDK 17.0.18. Base is `v2.4.0`; base and candidate
JMH jars ran sequentially on the same machine. Lower is better.

Standard Cypher gate command:

```text
java -jar <cypher-jmh.jar> 'io.johnsonlee.graphite.cypher.CypherBenchmark.*' \
  -foe true -rf json -rff <result.json>
```

All ten final `CypherBenchmark` methods pass the repository's 15% gate after restoring Java regex compatibility.
Deltas range from `-8.8%` to `+7.0%`; `regexFilter` is `23.266 us/op` versus `25.499 us/op` on the base (`-8.8%`).
The unbudgeted path invokes the original matcher and query loops without cancellation polling; budgeted execution
uses the tracked variants.

| Benchmark | v2.4.0 | Candidate | Delta |
|---|---:|---:|---:|
| `aggregationCountGroupBy` | 34.205 us/op | 35.412 us/op | +3.5% |
| `countStar` | 2.398 us/op | 2.391 us/op | -0.3% |
| `functionCalls` | 25.147 us/op | 24.576 us/op | -2.3% |
| `nodeMatchWithWhere` | 57.628 us/op | 58.613 us/op | +1.7% |
| `regexFilter` | 25.499 us/op | 23.266 us/op | -8.8% |
| `returnDistinct` | 95.471 us/op | 102.116 us/op | +7.0% |
| `simpleNodeMatch` | 20.307 us/op | 20.571 us/op | +1.3% |
| `singleHopRelationship` | 29.865 us/op | 30.173 us/op | +1.0% |
| `variableLengthPath` | 26.185 us/op | 25.566 us/op | -2.4% |
| `withPipeline` | 79.423 us/op | 78.068 us/op | -1.7% |

The 5,986,673-node Hive corpus also exercises 1,437,647 call sites. On the same machine, the base query took
`2,901 ms`; candidate observations ranged from `2,679 ms` to a final conservative `3,512 ms`. The slowest delta is
`+21.1%`, below the 25% large-corpus gate. The final pipeline took `31,731 ms` and peak heap was `4,011,753,472`
bytes. Unbudgeted projection, filtering, grouping, deduplication, `UNWIND`, and ordering use their original loops
without per-row cancellation polls; budgeted HTTP execution retains the tracked variants.

Cancellation hot-path command:

```text
java -jar <cypher-jmh.jar> \
  'io.johnsonlee.graphite.cypher.BudgetedCypherBenchmark.budgeted.*' \
  -f 1 -foe true -rf json -rff <result.json>
```

| Benchmark | v2.4.0 | Candidate | Delta |
|---|---:|---:|---:|
| `budgetedNodeScan` | 52.097 us/op | 50.262 us/op | -3.5% |
| `budgetedRelationship` | 158.963 us/op | 145.894 us/op | -8.2% |
| `budgetedVariableLengthPath` | 252.135 us/op | 237.207 us/op | -5.9% |
| `budgetedGeneralRegex` | 1,117.394 us/op | 1,113.949 us/op | -0.3% |

`budgetedGeneralRegex` executes the reviewed 10,000-row, 114-character `[a-z]+[0-9]+` production path. The same JMH
harness was copied into the base checkout before both jars were built. Its 99.9% intervals are `1,103.873-1,130.915`
us/op for `v2.4.0` and `1,066.296-1,161.602` us/op for the candidate. Deferring the first internal poll until the 1,024th
character access removes the previous normal-match regression while every tracked pattern remains cancellable.

Connected HTTP fixed-cost command, using the same `CypherHttpBenchmark` source in both checkouts:

```text
java -jar <explore-jmh.jar> \
  'io.johnsonlee.graphite.cli.CypherHttpBenchmark.connectedScalarQuery' \
  -foe true -rf json -rff <result.json>
```

The final metrics-disabled result is `73.063 us/op` versus `67.921 us/op` on the base (`+7.6%`). The 99.9%
confidence intervals overlap, and the result remains below the 15% gate.

| Benchmark | v2.4.0 | Candidate | Delta |
|---|---:|---:|---:|
| `connectedScalarQuery` | 67.921 us/op | 73.063 us/op | +7.6% |

The HTTP comparison passes the 15% gate and its 99.9% confidence intervals overlap. Prometheus instrumentation is
opt-in through `--metrics`, so this benchmark also verifies that the default request path does not pay Micrometer's
measured per-request cost. Method-level and connected HTTP results therefore show no material performance regression.
