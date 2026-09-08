# Early Enum-candidate latency investigation

This is a fixed snapshot of the first nine completed pairs of the live
`p95-enum-f0838dda-200-v1` campaign: exactly three pairs per state. It is
insufficient for the required 200-sample per-case P95 acceptance. All results
apply to the frozen Enum candidate, not the later untested Annotation changes.

The unmodified `summarize.py` was run against a byte-preserved controller
snapshot, with links to the original completed trial records. It verifies the
record hashes and retains all 1,267 cases in each state. The command exits 1:
the report has 18 `query-failed` entries for original case 821 (three trials ×
three states × two runtimes), `measurementAcceptanceEligible=false`, and
`tenfoldP95AcceptanceProven=false`. Main's timed error-message provenance remains
`reference-derived`; it is not an independently observed message.

## Investigation priorities, not acceptance results

The following are **per-case medians of three observations** in
`startup-prepared`, in milliseconds. Selection examines the largest Go median
durations and lowest main/Go median ratios. The complete unfiltered report and
all failures remain preserved; no across-case average or pooled percentile is
used. These early observations can select work to investigate but cannot prove
a final P95 or a causal bottleneck.

| Case | Query shape | main median ms | Go median ms |
|---|---|---:|---:|
| 874 | Global wide, wrapped case-insensitive DISTINCT, absent term | 0.451 | 275.875 |
| 875 | Same shape, targeted term | 1.225 | 291.762 |
| 830 | Ordered properties, SKIP 10000 LIMIT 200, targeted | 753.395 | 4626.916 |
| 831 | Same shape, dense | 577.755 | 3578.020 |
| 887 | Six-property wrapped DISTINCT, targeted | 80.163 | 1645.388 |
| 888 | Same shape, dense | 58.413 | 1514.236 |
| 892 | Regex OR, absent term | 10985.578 | 11088.398 |
| 893 | Regex OR, targeted term | 3461.129 | 3511.209 |

All three states' original three-pair timings for these eight selected cases
are in `investigation-priorities.json`. Diagnostic warm remains named
`warm-after-failed-prewarm`; it is not a passing formal warm benchmark.

## Source investigation

For ordered-skip, the measured Go module admits the streaming pagination path.
Its bounded heap compares projected values through `values.go:330`;
`compareUTF16` converts both unequal byte strings to complete UTF-16 slices
before comparing their units. `internal/javastring/utf16.go:12` constructs those
slices with repeated append. This identifies avoidable conversion/allocation
work worth profiling; it does not quantify its share of the observed latency.
Both engines already use bounded heaps, so replacing a purported full sort is
not the supported diagnosis. A later comparison optimization must preserve Java
UTF-16 ordering, isolated surrogates, null/type ordering, stable ties and the
existing cancellation/work boundaries. No comparator change has been made.

The wrapped-DISTINCT investigation confirms a concrete branch difference. For
wide catalogs and sufficiently large CallSite indexes, Store's
`distinct_projection.go:316` selects `ParallelRaw`. The measured
`indexed_distinct.go:779–823` then reads each full property directory, decodes
every SID and evaluates the transformed predicate before determining whether
anything matches. Pinned main `MappedWebGraphBackedGraph.kt:398–414` first asks
the retained index or mapped view for exact matching string IDs. If all sets
are empty, it skips CallSite projection; otherwise it passes the sets to the
parallel raw projection. It does not skip generic/Annotation supplementation.

The existing Go `mainExactMatches`/`mainMappedStringMatches` and
`mainStringIndexMatches` helpers provide parts of this behavior. Mapped and
retained admission, caching, work consumption and cancellation differ; the
ordinary retained fallback is not automatically equivalent to the main cache
entry. DISTINCT preparation also needs inspection of its `MainSource` and
`ConsumeWork` bridge. A fix must retain actual identity/layout/CRC/EOF validation,
empty/short/unsupported-term fallbacks, source order, tuple probes, LIMIT,
generic supplements and cancellation/work-finalization behavior. Case 875 is
still a query over all 64 sources; its targeted term does not authorize graph
pruning. No profile quantifies how much of the elapsed time this extra directory
work explains, and no assertion is made that every source is still retained at
case 874 after earlier queries. No source optimization was executed for this
diagnostic snapshot.

## Evidence

Snapshot directory:
`/Users/johnsonlee/.codex/benchmarks/graphite/p95-enum-f0838dda-diagnostic-prefix-v1`.
Its original `controller.json` hash is
`69909bd8f98628808f2ab9f5d48cc48e4b9b1a0be50a73644c27920082936263`.
The full `summary.json` hash is
`e75c1b805277e9e2eefc067c9eb753d31bf815a49cea3cc3bc5bab3ebaf441f4`.
`snapshot-receipt.json` records the snapshot boundary and original campaign;
`investigation-priorities.json` binds both hashes and retains the selected raw
observations. Source identity, real64 fixture identity, timer, environment and
sampling protocol remain those of the original frozen build and live campaign.

## Deliberate early stop after the diagnosis

The candidate campaign was intentionally interrupted after this branch review,
rather than finishing 200 pairs before addressing an identified omission. This
does not change the final acceptance requirement. Session 68471 returned exit
130, controller PID 42719 disappeared, and all 25 owned runtime receipts were
verified terminal. The controller preserves 12 completed pairs (four/state) and
the unpaired Go run in `startup-prepared-0005`. That Go process exited with the
original gate code 1; no new runtime error or missing sample is hidden. No data
was deleted or resampled by the stop audit.

Decision, before/after controller snapshots, terminal audit and the full
four-sample partial summary are preserved in
`/Users/johnsonlee/.codex/benchmarks/graphite/p95-enum-f0838dda-stop-decision-v1`.
The partial summarizer exits 1 and continues to report insufficient samples and
no acceptance. The earlier nine-pair snapshot above remains unchanged. Runtime
correctness work may resume only after this verified terminal boundary; any
later optimized candidate needs its own checked source and new measurement
campaign. These partial timings cannot serve as its final P95 evidence.
