`main64.json` is the byte-exact output of the pinned main JVM workload generator,
not a Go-generated fixture. It contains all 1,267 cases for 64 graph sources in
original manifest/replay order. Query strings, parameters, request selections,
expected row ranges, timeout overrides and workload identities remain intact.

Provenance and the two identical actual-JVM exports are recorded in
`docs/go-server-baseline/native64-testcase-audit-20260908/` at repository root.
This fixture supports definition/parser checks only. It is neither query-result
parity evidence nor a performance dataset.
