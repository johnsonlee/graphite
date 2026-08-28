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

Status: **accepted**.

Jetty read the reset as an input shutdown but intentionally kept the output side open for the pending async response,
so connection-close listeners still did not fire. This attempt adds a request-scoped monitor that:

- starts after 10 ms, so normal fast requests usually finish before its first run;
- keeps Jetty's own HTTP connection read callback registered instead of reading protocol bytes itself;
- checks the endpoint every 50 ms and cancels when the input is shut down or the endpoint closes;
- stops with the query, leaving no permanent polling task or extra application thread.

The monitor does not write a heartbeat or commit an early `200` response, so query errors retain their existing HTTP
status and body. The same cancellation signal covers single-graph, cross-graph, and fanout execution, including all
sequential executors in one request.

Behavior verification:

```text
./gradlew :cypher:test :explore:test :cypher:detekt :explore:detekt --no-daemon
```

`CypherClientCancellationTest` sends a TCP RST during an infinite candidate scan. The query stops, its only concurrency
permit is reusable within 2 seconds, and its visited-candidate counter remains unchanged afterward. The complete
Cypher/Explore suites also retain connected single-graph, cross-graph, fanout, budget, row-limit, and concurrency
behavior.

Performance environment: Apple M3 Max, macOS 14.3 (`arm64`), OpenJDK 17.0.18. Base is `v2.4.0`; base and candidate
JMH jars ran sequentially on the same machine. Lower is better.

Standard Cypher gate command:

```text
java -jar <cypher-jmh.jar> 'io.johnsonlee.graphite.cypher.CypherBenchmark.*' \
  -foe true -rf json -rff <result.json>
```

All ten `CypherBenchmark` methods pass the repository's 15% gate. Deltas range from `-5.0%` to `+11.6%`; the class
uses the unbudgeted path and no measured item exceeds the gate.

Cancellation hot-path command:

```text
java -jar <cypher-jmh.jar> \
  'io.johnsonlee.graphite.cypher.BudgetedCypherBenchmark.budgeted.*' \
  -f 1 -foe true -rf json -rff <result.json>
```

| Benchmark | v2.4.0 | Candidate | Delta |
|---|---:|---:|---:|
| `budgetedNodeScan` | 52.097 us/op | 48.905 us/op | -6.1% |
| `budgetedRelationship` | 158.963 us/op | 157.294 us/op | -1.0% |
| `budgetedVariableLengthPath` | 252.135 us/op | 240.992 us/op | -4.4% |

Connected HTTP fixed-cost command, using the same `CypherHttpBenchmark` source in both checkouts:

```text
java -jar <explore-jmh.jar> \
  'io.johnsonlee.graphite.cli.CypherHttpBenchmark.connectedScalarQuery' \
  -foe true -rf json -rff <result.json>
```

| Benchmark | v2.4.0 | Candidate | Delta |
|---|---:|---:|---:|
| `connectedScalarQuery` | 67.921 us/op | 72.286 us/op | +6.4% |

The HTTP comparison passes the 15% gate and its 99.9% confidence intervals overlap. The candidate adds 4.365 us to a
trivial `RETURN 1` request; this is a fixed async ownership cost rather than graph-work amplification. Method-level and
connected HTTP results therefore show no material performance regression.
