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
