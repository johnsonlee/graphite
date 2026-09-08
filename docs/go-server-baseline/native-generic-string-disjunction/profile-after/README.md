# Attempt24 profile-after full-real64 diagnostic profile

This capture profiles the frozen Go source at `acb065745f123d1dc362e24574628e40a05b4e91`. It is a
correctness diagnostic with **zero acceptance performance measurements**. All
latency fields are explicitly profile-contaminated; no per-case P95,
statistical speedup, or causal performance estimate is inferred.

All 1,267 original cold cases execute in their original order on the same 64
real persisted graphs, with no synthetic data, extra case, new warmup, or added
forced GC. Original indices 883–888 alone receive profiles and process counter
brackets. The original measure/worker/parse/execute path remains intact. The
helper and command overlay are byte-identical between before and after.

Both independent original-main canonical comparisons verify all 1,267 cases:
1,266 successes and the retained case821 original IllegalStateException, with no
new error or timeout. Both runtime exits are 1 because the original all-success
gate remains active; both diagnostic controllers exit 0. Every compared case
field other than the contaminated clock is identical across before and after.
All 1,152 original fixture files and every frozen/built module input remain
unchanged. This is not a claim that every server function or strict state-counter
replay has passed.

This runtime was PID 5201, from `2026-09-08T08:04:32.230742+00:00` to
`2026-09-08T08:05:39.410541+00:00`. The exact executable SHA256 is
`5cc37aee318baf9334bbc4c94ab520cc30dd07acbfe02577697114aabf416d99`. The before process ends before the after process begins.
The original snapshots remain untouched; complete private source copies and
transitive compiler/dependency identities are recorded. The two production
changes are indexed_distinct.go and main_string_candidates.go. The snapshots
also differ by a correctness-test file: candidate adds generic_disjunction_test.go
while baseline retains its earlier diagnostic replay helper. Neither test file
is part of the profiler command build.

The following are single-invocation diagnostic observations. TotalAlloc and
Rusage deltas were independently recomputed from every raw before/after pair;
they include all process goroutines and instrumentation inside the brackets.
They are not allocations or CPU exclusively attributable to the query engine.

| Original case | Before TotalAlloc (bytes) | After TotalAlloc (bytes) | Before process CPU (s) | After process CPU (s) |
| --- | ---: | ---: | ---: | ---: |
| global-six-or-zero | 1,512,904,392 | 83,735,392 | 17.680708 | 6.022078 |
| global-six-or-targeted | 1,785,231,040 | 376,804,616 | 13.565079 | 6.676716 |
| global-six-or-dense | 1,711,444,576 | 294,379,144 | 14.214895 | 6.396731 |
| global-six-or-wrapped-zero | 1,632,995,480 | 163,166,408 | 18.012802 | 7.344588 |
| global-six-or-wrapped-targeted | 1,882,581,256 | 421,672,936 | 19.750954 | 11.768850 |
| global-six-or-wrapped-dense | 1,833,654,184 | 379,019,200 | 24.346378 | 7.535464 |

**CPU sample totals disagree strongly with the exact Rusage brackets.** For
example, after global-six-or-zero has 73.16 seconds of sampled CPU mass but
6.022078 seconds of process CPU inside the Rusage bracket. CPU profiles and
counter brackets have different boundaries, and runtime.usleep plus
runtime.pthread_cond_wait dominate the samples. These data do not establish a
Darwin/runtime bug or support treating sampled percentages as precise CPU shares.
The next optimization must not rest solely on these runtime-wait samples.

| Original case | Before sampled CPU (s) | After sampled CPU (s) | Before GC-stack samples | After GC-stack samples |
| --- | ---: | ---: | ---: | ---: |
| global-six-or-zero | 46.07 | 73.16 | 252 | 0 |
| global-six-or-targeted | 40.65 | 72.70 | 0 | 0 |
| global-six-or-dense | 51.70 | 66.38 | 0 | 0 |
| global-six-or-wrapped-zero | 47.45 | 57.13 | 0 | 0 |
| global-six-or-wrapped-targeted | 53.77 | 49.39 | 0 | 240 |
| global-six-or-wrapped-dense | 53.84 | 48.76 | 268 | 0 |

The inclusive source-frame groups remain archived in profile-summary.json,
including ProjectionPropertyStringID, ProjectionArrayString,
mainGenericStringCandidates/mainStringCandidatesExcludingCallSites, and GC
stacks. Each group counts a sample once; groups overlap and must not be summed.
Their fractions describe sampled stack mass only. Zero samples in a GC group
cannot establish zero GC work. Exact NumGC/pause deltas and all raw MemStats
are retained. Sampled allocation/heap snapshot differences may lag GC, include
earlier-query allocations, or omit recent allocations; they do not replace
exact process allocation counters.

All CPU/heap/allocation profiles, raw pprof/top/cumulative output, commands,
complete source archive and executable, raw observations, full MemStats/Rusage
pairs, fixture audits, host snapshots, and clock/source/build hashes are retained.
No profiling invocation was retried or discarded. The retained runtime gate
failure is expected original behavior, not a hidden successful-case filter.

Reproduce only after all formal timing is terminal, using fresh directories:

```sh
python3 docs/go-server-baseline/native-generic-string-disjunction/profile-after/run-profile.py \
  --output /absolute/path/to/fresh-profile-after
```

Run before then after serially. After both runtimes/controllers are terminal,
run analyze-profile.py with --directory for each output, then compare-profiles.py
from profile-after with --before, --after and a fresh --output JSON file.
archive-profile.py records a terminal analyzed run and refuses existing artifact
names. verify-profile.py --directory accepts this archive and independently checks
source/binary bytes, fixture receipts, the complete case/error gate, and all six
raw counter brackets. Both scripts use immutable sources specified in
run-profile.py; never point the runtime at the graph reference itself.
