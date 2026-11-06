# Apple client / JVM service correctness fixture

`PaymentService` registers a POST endpoint at `/v1/charge`, the path passed to
`send(path:retries:)` by the existing AcmeShop Swift fixture. It uses the JDK HTTP
server and has no external dependencies.

The Apple workflow compiles this fixture, builds its graph through the JVM
frontend, and serves it alongside the independently built Swift graph. The
cross-graph check joins their literal arguments by path and verifies each call's
declaring class, method, graph identity, and the result's two-graph provenance.
Running the same join against either graph alone must produce no rows.

This small fixture checks correctness only; it provides no performance evidence.
