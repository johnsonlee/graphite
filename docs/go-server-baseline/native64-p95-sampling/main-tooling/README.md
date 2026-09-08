# Original-main repeated-sampling launcher

This directory is an independent diagnostic tool. It does not change the pinned
main engine, workload, validation requirements, existing pilot or previous gate
failures. Each launch contributes **one observation per original case**. Repeated
fresh launches are scheduled by the external controller; no P95 is estimated here.

Compile and statically audit (no graph load or query execution):

```sh
python3 docs/go-server-baseline/native64-p95-sampling/main-tooling/build.py
python3 docs/go-server-baseline/native64-p95-sampling/main-tooling/build.py --verify-only
```

The resulting `classpath.txt` contains the complete classpath. The controller's
launch argument vector is:

```text
java -Xmx8g -cp <contents of classpath.txt> MainLatencyCapture <relocated graphs.tsv> <state> <new capture directory> <absolute path to this directory>
```

States are `cold`, `startup-prepared`, and `warm-after-failed-prewarm`. The last
sets original benchmark `indexState=warm` but is explicitly a diagnostic
continuation after the original warm gate fails. Headers and completions retain
`formalWarmPrepared=false`, `warmOriginalGatePassed=false`, and `diagnosticOnly=true`.
The header alias `diagnosticWarmupContinued` selects this diagnostic mode; the
completion alias becomes true only after verified prewarm and original setup tail.
It must not be renamed to a completed original formal warm benchmark.

Cold and startup-prepared call original `setupTrial`, `setupInvocation`,
`replayBroadQueries`, and `tearDownTrial`. Warm uses original `setupTrial`, then
reflects the exact original warm invocation body: clear each graph's string
indexes, reset metrics, call original private `replay(true)`, and call original
`enforceCorrectness(samples)`. Splitting this body is necessary because the
original `setupInvocation` loses its local samples when its final gate throws.
The launcher preserves the samples as the original correctness/observations TSVs
and `warmup-records.json`, and records the original gate exception. It continues
only if all 1,267 complete original record signatures match the frozen reference
in original order, with precisely the known failure at index821 and no timeout
or extra failure. It then runs the original reset, original `forcePressureGc`,
and original sampler.start tail. Prewarm capture locals leave their stack frame
before that GC boundary. Formal sampling uses **original replayBroadQueries** in
all states, including its CPU/GC/resource counters, timers, original output
writers and original final gate.

The per-case timer remains inside original `replay(true)`: context/source
selection, persistent executor dispatch, query execution and Future.get are
included; canonicalization and per-graph observations are outside its successful
latency boundary. Canonicalization and metrics still affect the complete replay
wall time and later cases. Resource sampling remains enabled. No worker is
replaced, no per-query observer is installed and no second query timer is used.
The worker and sampler identities are checked throughout and at teardown.

`diagnostic-reference.tsv` is **not a valid all-success correctness oracle**.
It is copied from the original cold manifest; the build independently verifies
all its signatures against the archived complete cold, warm-prewarm and startup
captures (all 1,267 are identical across those states). The sole failure remains
`four-or-graph-id-targeted`, `java.lang.IllegalStateException`. The error message
`Unsafe expression reached parallel string projection` is proven by those
archived full canonical captures. Current original latency samples retain the
exception class only; the header explicitly identifies the message's provenance.
The original expectedRowCountRange/expectZeroRows validation is always enabled;
validation failures or any other changed result abort diagnostic continuation.

Expected completed diagnostic launches still **exit1** with the original formal
gate exception. `completion.json` separately reports `diagnosticReplayComplete`,
full signature matching, unchanged worker/sampler identities, legacy
`exceptionClass`/`exceptionMessage` aliases for the terminal exception, and the original
prewarm/formal gate errors. An exit1 alone is not evidence of a complete capture.
The known failed case is time-to-failure, never successful latency. Timeout
samples are censored and must not be treated as successful latency either.

Outputs include `header.json`, exact `actual-cases.json`, untouched original
`main-correctness.tsv` and `main-observations.tsv`, `original-counters.json`, and
`completion.json`. The diagnostic warm state additionally has original
`warmup-correctness.tsv`, `warmup-observations.tsv`, and `warmup-records.json`.
The reference bundle also includes unchanged `expected-workload.json`; the
launcher checks full actual case definitions and source order before invocation.

`build-verification.json` freezes all330 original classpath inputs, reference
archives, workload, launcher/source/class hashes and extracted original bytecode.
`--verify-only` checks those hashes without launching the engine. The controller
must independently clone/audit the real64 fixture, freeze JVM and all launch
inputs, run benchmark processes serially, and audit files again afterward. This
helper intentionally has no graph-runtime launch option.
