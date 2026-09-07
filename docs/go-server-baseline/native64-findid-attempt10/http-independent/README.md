# Independent findId candidate: all64 / 42 HTTP correctness

PASS: 42/42 responses are HTTP 200. Every full response value equals pinned main,
including array order, nulls, column names and graph provenance. No dynamic pointers,
query edits, data edits or output masks were used. Content-Type and Retry-After match;
all original request cases and complete response headers/bodies are retained. Header
Date and JSON whitespace/object-key order are not equality gates. The independent
verifier also checks returned Content-Length against the actual UTF8 body when present.

The full catalog contains exactly all 64 configured real graphs, in the expected order,
with matching per-graph node/edge/method/CallSite counts and total statistics.

Source: clean eebec091ae34bbfa9656d730b8f07ddc34967136 worktree, with only
`internal/query/indexed_distinct.go` and new `distinct_string_id.go` overlaid from the
parent's frozen candidate. Both requested source hashes were verified before build.
No profile_export or cmd/profile-query exists in the independent tree. Built the actual
`cmd/graphite-server` with Go 1.22.0 darwin/arm64 and -trimpath. No other code was copied.

Profiling environment GRAPHITE_NATIVE_CPU_PROFILE and GRAPHITE_PROFILE was explicitly
unset for build and execution. MAPPED/default 60,000 ms maximum query timeout, capacity4
(the runner passes the default value explicitly), default work budget, port18859.
Client HTTP timeout120s is the unmodified runner's fetch timeout; it does not override the
server's 60s guard. No extra query traffic was generated during the replay.

`http/observations.json` retains all42 baseline and candidate headers/full bodies.
`http/summary.json` has complete=true and no mismatches. `verify.py` independently
asserts every status and type-sensitive whole JSON value against the original pinned
main file, without trusting runner exit0 or its stored equal flags alone. It also checks
all source/embed/binary/config/harness hashes remained unchanged, all64 catalog entries,
server PID27384 exited, and port18859 is free. `verification.json` records those checks.
The runner exited0 and its finally block stopped the server; no64 process remains from
this task. `inputs/` preserves the exact harness/config/main records and two overlay files.

This is correctness evidence only. Runner elapsed fields are unaggregated diagnostic
bookkeeping; no latency, throughput, allocation, speedup or P95 conclusion is made.
No root production files or prior frozen artifacts were edited and no commit was made.
