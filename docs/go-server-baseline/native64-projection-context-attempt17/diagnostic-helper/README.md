# A15 cancellation-lock diagnostic proposal

This bundle is a read-only audit and a diagnostic harness overlay. No query,
Store, evaluator, context implementation, task strategy, or cache code changes.
No graph query, JVM, synthetic performance run, or real64 process was started.
The only executed Go tests inspect profiler lifecycle; they do not perform a
contention workload or report timings as evidence.

## Confirmed source mechanism

The supplied `/tmp/graphite-go-binding-root-2ab90cdc/graphite-server` matches all
2405 files in the archived A15 candidate source manifest. The existing
`cmd/profile-query/main.go` matches archived harness SHA
`9f13d1d77cc641eb38fdcadc724de7283e7c926325242fb3ce12ee8e8adda70e`.
This is 2ab90cdc + CDE + A15, without B. The archived original binary identity,
config, source manifest, and exact audited source excerpts are retained.

* `query/eval.go:44`: A12's check reads Done without blocking, and calls Err
  only after Done closes. It did not change the Store APIs below.
* `query/indexed_distinct.go:202` (`runDistinctTasks`): a task group creates one
  `context.WithCancel(ctx)` and passes the same local context to each launched
  worker. On failure/cancellation it cancels and joins active tasks. The generic
  DISTINCT remaining phase schedules all source scanners through this same
  group, limited to the existing parallel count (8 on this 16-CPU/64-source
  configuration).
* `query/generic_distinct.go:184-217`: each scanner cursor also has a private
  lifetime context. `read` checks that private context, but calls `advance`
  with the current **request/task context**. `next` checks the request Err
  before advance and before delivery (an additional check on exhaustion).
* `query/generic_distinct.go:281` and `main_string_source.go:145`: the current
  task context is propagated through candidate lookup into Store. A15 only
  reuses projection bindings; it did not alter this chain.
* `store/projection_node.go:12-112`: each successful mapped CallSite decode
  checks Err inside `prepareProjectionOffsets`, once under its lifetime read
  lock, and at **every** primitive `read`. A node with p caller parameters,
  q callee parameters, and a arguments performs 13+p+q+a primitive-read
  checks, plus those two outer checks. This is a code-derived call count for
  this successful decoder branch, not a measured count for an entire query.
* `store/distinct_projection.go:54,101`: even warm offset preparation takes
  the Store's exclusive lock and checks Err before observing offsetsLoaded.
  Each ProjectionNodeOrder then takes a read lock and checks Err again.
  Range/head merging can invoke this accessor repeatedly. String/cache/range
  APIs also contain their own checks; lookup state decides which are used.
* Go1.22 `context/context.go:453`: cancelCtx.Err always locks its mutex, reads
  err, and unlocks. By contrast, Done normally reads an already initialized
  channel using an atomic value; its first initialization can still lock.

Therefore simultaneous source workers can contend on one cancellation mutex
while decoding different Stores. A private cursor's Err lock is a separate
mutex with one normal owner; attributing every Err call to cross-worker
contention would be incorrect. Store lifetime/cache locks are another distinct
candidate, especially where storage itself introduces within-source work.

The observed A15 allocation improvement with nearly unchanged wall/rusage CPU
motivates investigating remaining work. It does not identify a cause. Possible
alternatives include full-node construction/decoding, necessary canonical order
validation/merges, string matching, equality/hash work, task scheduling, Store
locks, or atomic/cache-line costs without blocking. No CPU-asynchronous profile
from Go1.22 is used as reliable quantitative evidence here.

## What the proposed evidence can distinguish

Mutex samples identify the **unlock stack** responsible for sampled contention,
not the waiting goroutine's instruction stream. A delta dominated by
`context.(*cancelCtx).Err` under `ProjectionCandidateNode`/`ProjectionNodeOrder`
would establish that these cancellation checks caused blocking in this real
execution. Stacks under Store/cache methods without the context Err frame
instead implicate their mutexes; runtime-internal lock samples must be reported
separately. A low or absent context stack weakens the blocking-contention
hypothesis but does not exclude uncontended mutex/CAS/spin/cache-line CPU cost.

Go1.22's mutex profile reports an approximate sum of other goroutines' blocked
wait time, sampled at roughly 1/rate contended releases. It can exceed elapsed
wall time. It is **not** CPU time, lock-hold duration, fraction of total query
CPU, or an estimate of speedup after deleting a lock. It cannot by itself prove
that necessary decoding/equality work is inexpensive. These limits follow
`runtime/pprof/pprof.go:150-170` and `runtime/mprof.go:771-794`, copied here from
the actual Go1.22 runtime used to build the diagnostic.

The first proposed run uses rate16 for the existing prefix-repeat query only.
All preceding fixed-config queries still execute, with sampling disabled, to
preserve index/cache history. Pair it with rate0 using the **same binary,
config, fixture, source order, environment and warmup history**. Root owns the
sequential real64 runs and fixture pre/post validation. Inspect rusage/wall
changes only to identify profiler perturbation, not as an optimization result.
If stacks are too sparse, root may separately authorize rate1; a single sparse
sample is not a zero-contention conclusion.

## Harness boundary and artifacts

`harness-only.patch` modifies only `cmd/profile-query/main.go` and adds two
files there. The copied module is provided solely for review/build convenience.
Every other archived file is hash-verified unchanged. There is no public HTTP
flag, environment override, custom context, cached Done channel, or fastpath.

* `--mutex-rate 0` is the diagnostic control. A positive rate requires
  `--mutex-query <existing diagnostic query name>`; an unknown target is rejected
  before graph loading. This selects an observation window, not query admission.
* Loading, catalog verification and pre-query forced GC run with sampling off.
  The cumulative `mutex-before.pprof` is serialized outside the request interval.
* Sampling starts immediately before the original, byte-identical
  `pprof.Do(... query.ExecuteCross(ctx, sources, q.Query, nil, 1000))` call and
  stops immediately after it returns and joins its task groups. No timeout,
  context or request semantic changes. Marshaling is excluded from sampling.
* `mutex-after.pprof` is written after request runtime/memory snapshots, with
  sampling off. Profiles are process-cumulative; subtract the matching before
  snapshot. `mutex-window.json` records the requested rate and boundary.
* The prior catalog/totals assertions, raw body SHA, canonical full expected
  response comparison, projection-state snapshots, heap profiles and rusage
  receipts remain. Profiler setup/writing does affect process allocations outside
  the request; old A15 heap-profile deltas are not a clean isolated comparison
  to this instrumented harness. Prefer its paired rate0/rate16 receipts.
* The profiler setting is process-global. The helper rejects an existing sampler
  or overlapping window, disables it on panic without swallowing the panic, and
  releases ownership on exit. Background runtime contention may still appear.
  Do not run two requests concurrently or treat pprof phase labels as mutex
  sample filtering; the Go1.22 mutex writer does not attach those labels.

Reproduce the helper build/check (these have already passed; no graph execution):

```sh
cd /tmp/graphite-a15-mutex-diagnostic/module
go test -race ./cmd/profile-query
go vet ./cmd/profile-query
go build -o /tmp/graphite-a15-mutex-diagnostic/mutex-profile-query ./cmd/profile-query
```

Proposed **root-run** commands; these were not executed by this task:

```sh
/tmp/graphite-a15-mutex-diagnostic/mutex-profile-query \
  --config /tmp/graphite-a15-mutex-diagnostic/baseline/config.json \
  --out /tmp/graphite-a15-mutex-control --mutex-rate 0
/tmp/graphite-a15-mutex-diagnostic/mutex-profile-query \
  --config /tmp/graphite-a15-mutex-diagnostic/baseline/config.json \
  --out /tmp/graphite-a15-mutex-rate16 --mutex-rate 16 \
  --mutex-query wrapped-firstLastGraphBimodalClassPrefix-repeat
```

After root verifies both complete outputs and source history, use the exact
recorded binary with the sampled query's profiles:

```sh
go tool pprof -sample_index=delay -top -nodecount=40 \
  -base mutex-before.pprof /tmp/graphite-a15-mutex-diagnostic/mutex-profile-query mutex-after.pprof
go tool pprof -sample_index=contentions -top -nodecount=40 \
  -base mutex-before.pprof /tmp/graphite-a15-mutex-diagnostic/mutex-profile-query mutex-after.pprof
go tool pprof -sample_index=delay -list='context.*Err|ProjectionCandidateNode|ProjectionNodeOrder' \
  -base mutex-before.pprof /tmp/graphite-a15-mutex-diagnostic/mutex-profile-query mutex-after.pprof
```

Also retain unfiltered raw profiles/top tables, runtime-lock totals and the
rate0 control, rather than reporting only a filtered context percentage. No
causal lock-removal test or implementation is authorized by this bundle.
