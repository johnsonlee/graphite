# Frozen native 64-graph correctness preflight

The fixed 42-case root `/api/cypher` manifest completed: **42/42 passed**, HTTP 200 throughout, zero full-response JSON/status mismatches and zero checked response-header differences. This includes all eight wrapped-discovery cases and all 34 global-wide cases; no scoped or fanout substitution was used. `queries/observations.json` preserves every request and both responses, and `queries/summary.json` records the manifest hash.

Both servers loaded the same complete 64-shard fixture with matching per-graph catalogs: 19,431,891 nodes, 20,448,885 edges, 1,374,983 methods, and 5,046,935 CallSites. See `catalogs.json`, `identity.json`, and the saved `*-command.json` files. Baseline is pinned remote main `4e328b0109e13c896b74004823fb049fcb19251a`; the tested native binary SHA-256 is `1e6d7f769c5b9ca7180d92bab7713028afd9a27c03d53e72943cb81da51830b8`, whose exact source manifest and immutable archive are recorded in `../native-initial-snapshot/manifest.json`.

Approximate complete differential wall time was **904.6 seconds (15 minutes 05 seconds, ±1 second)**. This is the entire sequential correctness driver wall time, not a native query latency or speedup. The frozen workload used the original `http_parity.py` version captured in the source snapshot; later helper changes do not alter this completed execution. No performance acceptance is implied. The separate main c4 diagnostic retained 190 HTTP 400 failures among 8,400 attempts and cannot establish successful-performance acceptance.

Separate correctness-only provenance coverage in `../native64-provenance` passed 3/5 cases. Two anonymous `MATCH (:IntConstant)` cases differ: main supplies an empty `$metadata.graphIds`, while the frozen native supplies contributing graph IDs. The current source fixes anonymous provenance, but this source was **not** used in the frozen run. `source-delta.json` inventories changed and newly added current-source files; it also includes parser cancellation/statement handling, UNION behavior, and newly integrated overview/endpoints/spec work. These changes require their own subsequent validation.

`completion.json` is the completion receipt. Both dedicated correctness servers were stopped after the full manifest finished: JVM PID 25444 exited 143 following SIGTERM, native PID 25446 exited 0. Their GC logs are from correctness execution only. Concurrent Go marking duration is not a stop-the-world pause; these logs do not establish the cause of query latency. No new performance run was started.

Reproduce after starting both saved commands against separately cloned fixtures:

```sh
python3 -u graphite-server/scripts/http_parity.py \
  --baseline-url http://localhost:18851 \
  --candidate-url http://localhost:18852 \
  --manifest graphite-server/scripts/real64-workload.json \
  --stop-on-mismatch \
  --output docs/go-server-baseline/native64-preflight/queries
```

Use a new output directory for reruns to preserve these observations. Full feature parity and the user-requested 10× P95 improvement remain unproven.
