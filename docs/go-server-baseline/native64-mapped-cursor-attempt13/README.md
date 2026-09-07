# Attempt 13: mapped Node scalar cursor

Hypothesis: use a checked slice cursor for synchronous mapped Node scalar reads,
avoiding the escaping bytes.Reader and scalar temporary buffers. Complete Node
decoding, field order, owning returned values, decoder failure priority and Store
lifetime locking remain unchanged. This does not introduce field-only projection,
new caches, pools, source ordering or task changes.

Native base is `df8d2b40`, including Attempt12's cancellation checkpoint. Provider
patch SHA256 is `03c32896f4adbb409f4a86a5cfa9909acf038e29d05582f8d5568490fb456959`.
Its older correctness-only base is explicit in `provider/`; both production files
and the new test are byte-identical in the measured integration. The integrated
candidate retains the exact Attempt12 evaluator. Root separately ran whole-module
race/vet and reproduced all 17 original oracle JSON files unchanged. The original
1,048 cases still have 739 main matches and 309 pre-existing differences.

Provider tests cover all 16 JVM-written Node types and 431 byte-prefix cases,
version 1/2/3 decoding, malformed counts/SIDs/tags, nesting bounds, exact IEEE
bits, full trailing-field consumption and reader-versus-mapped first errors.
Actual Store tests cover outer error wrapping, record suffix-to-EOF bounds and
values retained after Close. Direct Node callers still must keep the Store open;
CandidateNode retains its existing lifetime lock. No arbitrary external mmap
mutation or bare-Node/concurrent-Close guarantee is added.

Both diagnostic processes completed with exit 0. Each freshly verified all 64
persisted graphs, 1,152 files/10,338,207,518 bytes and complete catalog counts.
All five ordered response bodies equal pinned main and each other. Post-run
source and executable hashes are checked in `comparison.json`.

| Query | Execute + marshal seconds, base → candidate | Process CPU seconds | Allocation bytes, base → candidate |
|---|---:|---:|---:|
| Prefix first | 21.474 → 18.449 | 27.125 → 24.839 | 11,136,059,528 → 9,102,986,400 |
| Prefix repeat | 12.683 → 12.012 | 19.387 → 18.719 | 8,549,709,704 → 8,320,020,376 |
| Dense DISTINCT first | 4.047 → 4.133 | 22.011 → 22.597 | 5,804,038,496 → 5,804,041,976 |
| Dense DISTINCT repeat | 4.079 → 4.051 | 18.781 → 18.744 | 367,927,024 → 367,920,320 |
| Subsequent ordinary dense | 11.847 → 11.547 | 11.827 → 11.536 | 5,640,808,432 → 5,474,290,592 |

Full-node paths allocate less, especially the first prefix execution with cold
candidate preparation. Raw indexed DISTINCT uses its existing projection reader
and has effectively unchanged allocation. The single-run timing differences do
not prove a uniform latency improvement. Collection counts are unchanged; no
retained-heap or peak-RSS reduction is claimed.

**Keep the verified allocation reduction and fixed-workload correctness.** An
independent actual shipping binary passed 42/42 HTTP 200 responses, complete
type-sensitive main bodies, protocol headers and the full 64-graph catalog.
The clean build starts at df8d2b40 and overlays only decode.go/store.go, without
diagnostic helpers or ordinary projection changes. Binary SHA256 is
`d01ddaf9025eb510bb559db523f9c3e77f821f83f60afaf46be65eb0fb16e603`.
All 212 source/embed hashes were reverified. Default 60-second timeout/capacity4,
profiling environment unset. PID 58917 exited and port 18861 became bindable.
The initial post-exit ordinary socket bind failed temporarily; raw failure and
intermediate no-listener/connect-refused/reuse-bind evidence are retained. The
unchanged verifier subsequently passed without restarting or repeating queries.
Root independently checked every complete response, source/binary identity,
catalog and cleanup again. HTTP timings are not used as P95 evidence.

Reproduction, sequentially from this repository root with fresh output paths:

```sh
python3 docs/go-server-baseline/native64-mapped-cursor-attempt13/run-profile.py --worktree /tmp/graphite-go-cursor-base-df8d2b40 --out /tmp/fresh-cursor-base
python3 docs/go-server-baseline/native64-mapped-cursor-attempt13/run-profile.py --worktree /tmp/graphite-go-cursor-root-df8d2b40 --out /tmp/fresh-cursor-candidate
```

Go 1.22.0 on darwin/arm64, 16 CPUs, 64 GB host. CPU sampling is disabled;
getrusage supplies process CPU. Heap profiles and forced GC are outside request
counters. Background correctness/build work was present; 64-graph processes
were sequential. Full commands, runtime environment, source/binary identities,
raw counters and complete bodies are retained. These are individual instrumented
observations, not HTTP/P95 or main-relative latency acceptance. Full functional
parity and the requested 10× main-relative P95 remain unproven.
