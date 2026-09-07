# Attempt 12: evaluator cancellation checkpoint

The hypothesis is to avoid taking cancelCtx's mutex at every live evaluator
checkpoint. `evaluator.check` selects the current `e.ctx.Done()` without blocking;
if closed it preserves the original `Err` check and panic. It caches no channel,
changes no checkpoint locations, and leaves Store/regex checks, worker scheduling,
source ordering, Cause selection and Close/join behavior unchanged. Concurrent
cancellation can be observed at a different instant; exact scheduling equivalence
is not claimed.

The native comparison starts at `edefd884`, which differs from `87aaf0ad` only by
the rejected Attempt11 evidence. The provider patch is
`734d415a8b13f638f7941b6e7bb1d09e33d9bdc4a85c6e864c5bdbd26963951c`;
its only production change is `internal/query/eval.go`. The eight migrated tests
replace invalid Err-only cancellation injectors with real WithCancel contexts,
observing both Done and Err to retain downstream Store/regex coverage. No old
cancellation failure assertion was dropped. Provider and independent review
records retain the original injectors and explain each migration.

Provider, independent review and clean root integration passed their recorded
race/vet checks. Root regenerated all 17 original complete oracle JSON files;
each equals the provider and base. The original 1,048 cases still include 739
main matches and 309 pre-existing differences. This change does not resolve
those differences. Seven new provider tests cover real standard contexts,
worker-local rebinding, mixed components, Close and actual ordered/unordered task
joins. Two independent tests add external cancellation across 16 goroutines and
repeated live/canceled child rebinding plus a real deadline, without overriding
Done or Err. These two tests are also included in the root integration.

Both real64 diagnostic processes completed with exit 0, sequentially. Each
freshly checked all 1,152 frozen files (10,338,207,518 bytes), all 64 catalogs
and all five complete pinned-main outputs. `comparison.json` records post-run
source and binary verification. Exact sources, commands, raw counters, full
bodies, heap profiles and environment receipts are retained per process.

| Query | Execute + marshal seconds, base → candidate | Process CPU seconds | Allocation bytes, base → candidate |
|---|---:|---:|---:|
| Prefix first | 20.290 → 21.873 | 25.756 → 27.556 | 11,136,160,056 → 11,136,071,248 |
| Prefix repeat | 12.033 → 12.735 | 18.384 → 19.274 | 8,549,761,920 → 8,549,737,712 |
| Dense DISTINCT first | 10.815 → 4.075 | 55.847 → 22.092 | 5,804,030,616 → 5,804,037,976 |
| Dense DISTINCT repeat | 9.558 → 4.094 | 45.110 → 18.961 | 367,929,816 → 367,926,752 |
| Subsequent ordinary dense | 11.096 → 11.852 | 11.075 → 11.837 | 5,640,632,776 → 5,640,917,880 |

Dense DISTINCT improves in this observation, while prefix and ordinary execution
and CPU increase. Allocation and collection counts are effectively unchanged;
this is not a GC optimization. The modest increases on the other paths remain
an unresolved tradeoff, rather than being labelled noise or silently omitted.

**Keep the measured dense CPU/latency improvement with this explicit tradeoff.**
This decision does not establish a uniform improvement or satisfy the final P95
gate. No generic DISTINCT cursor or ordinary projection change is included.

The independent actual shipping binary passed 42/42 HTTP 200 responses, complete
type-sensitive main bodies, Content-Type/Retry-After and the complete 64-graph
catalog. It was built from clean `edefd884` with only the production eval.go
overlay, without profiling helpers. Default 60-second timeout and capacity 4,
profiling environment unset. `http-independent/` retains the independent verifier,
all raw responses, 210 unchanged source/embed hashes, executable identity and
process exit/port-free checks. PID 47678 exited and port 18860 was released.
The binary SHA256 is
`eefb7f754fbb864550899920fc204a3e4b72cc247072e48bdcc43b6675b2363d`.
HTTP elapsed bookkeeping is correctness-run context, not P95 evidence.

Reproduce the diagnostic sequentially from the repository root with fresh
output directories:

```sh
python3 docs/go-server-baseline/native64-context-check-attempt12/run-profile.py --worktree /tmp/graphite-go-context-base-edefd884 --out /tmp/fresh-context-base
python3 docs/go-server-baseline/native64-context-check-attempt12/run-profile.py --worktree /tmp/graphite-go-context-root-edefd884 --out /tmp/fresh-context-candidate
```

Go 1.22.0, darwin/arm64, 16 CPUs, 64 GB host. CPU sampling is disabled; CPU
figures use process getrusage counters. Heap profiling and forced GC occur
outside request counters. Background correctness/build work was present, and
the two 64-graph processes did not overlap. These are single instrumented
observations, not HTTP/P95, peak RSS, or main-relative latency acceptance.
Full behavioral parity and repeated main-relative 10× P95 remain unproven.
