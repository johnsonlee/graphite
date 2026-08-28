# Cypher Client Cancellation Attempts

This log records each implementation attempt to stop server-side Cypher work after the HTTP client disconnects.
Each attempt is isolated in one commit. The acceptance criteria are:

- a real HTTP/1.1 client reset stops an active broad graph scan promptly;
- the query releases its concurrency permit and graph leases only after execution exits;
- single-graph, cross-graph, and fanout queries retain their existing results and limits;
- normal connected-query throughput does not regress materially against `main`.

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
- delegates readable sockets back to Jetty, then cancels when Jetty observes EOF, input shutdown, or connection error;
- adds cancellation-only checkpoints to graph-free expression, clause, aggregation, ordering, and result-materializing
  loops without consuming graph work budget;
- checks cancellation again after the query block returns, so an interrupt-ignoring block cannot publish a successful
  result after cancellation;
- keeps the concurrency permit and graph leases through inline completion callbacks, including JSON response
  materialization, and releases them only when the worker exits.

The monitor never writes a heartbeat or commits an early response. The same signal covers single-graph, cross-graph,
and fanout execution, including all sequential executors in one request.

Behavior verification:

```text
./gradlew :cypher:test :explore:test :cypher:detekt :explore:detekt --no-daemon
```

`CypherClientCancellationTest` sends both TCP RST and normal FIN disconnects during an infinite candidate scan and a
graph-free ten-million-element `range`/`UNWIND` query. Each query stops and its only permit is reusable within 2
seconds. A separate raw-socket regression runs a slow Cypher query and `/openapi.json` sequentially over the same
keep-alive connection; both responses are HTTP 200. Guard tests prove cancellation wins after an interrupt-ignoring
block returns and that a blocking completion callback retains the only permit. Exact response tests preserve the
pre-existing scoped and multi-graph 404 JSON contracts.

Performance environment: Apple M3 Max, macOS 14.3 (`arm64`), OpenJDK 17.0.18. Base is `v2.4.0`; base and candidate
JMH jars ran sequentially on the same machine. Lower is better.

Standard Cypher gate command:

```text
java -jar <cypher-jmh.jar> 'io.johnsonlee.graphite.cypher.CypherBenchmark.*' \
  -foe true -rf json -rff <result.json>
```

All ten `CypherBenchmark` methods pass the repository's 15% gate. Stable deltas range from `-5.8%` to `+10.5%`.
One full-suite `singleHopRelationship` sample reported `+23.8%` with overlapping confidence intervals; its isolated
confirmation was `30.483 us/op` versus `29.865 us/op` on the base (`+2.1%`). The class uses the unbudgeted path, so
this also verifies that cancellation polling is absent when no execution context is supplied.

Cancellation hot-path command:

```text
java -jar <cypher-jmh.jar> \
  'io.johnsonlee.graphite.cypher.BudgetedCypherBenchmark.budgeted.*' \
  -f 1 -foe true -rf json -rff <result.json>
```

| Benchmark | v2.4.0 | Candidate | Delta |
|---|---:|---:|---:|
| `budgetedNodeScan` | 52.097 us/op | 48.733 us/op | -6.5% |
| `budgetedRelationship` | 158.963 us/op | 149.419 us/op | -6.0% |
| `budgetedVariableLengthPath` | 252.135 us/op | 228.780 us/op | -9.3% |

Connected HTTP fixed-cost command, using the same `CypherHttpBenchmark` source in both checkouts:

```text
java -jar <explore-jmh.jar> \
  'io.johnsonlee.graphite.cli.CypherHttpBenchmark.connectedScalarQuery' \
  -foe true -rf json -rff <result.json>
```

| Benchmark | v2.4.0 | Candidate | Delta |
|---|---:|---:|---:|
| `connectedScalarQuery` | 67.921 us/op | 74.273 us/op | +9.4% |

The HTTP comparison passes the 15% gate and its 99.9% confidence intervals overlap. The candidate adds 6.352 us to a
trivial `RETURN 1` request; this is a fixed async ownership cost rather than graph-work amplification. Method-level and
connected HTTP results therefore show no material performance regression.
