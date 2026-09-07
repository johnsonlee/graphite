# Attempt 11: bounded generic DISTINCT retention — rejected

The candidate retains selected DISTINCT values and materializes result maps only
for selected rows. It accepts direct properties/literals after indexed DISTINCT
declines, including unknown properties. Its pull/ack cursor preserves complete
candidate decoding, qualified-source exhaustion, scoped early stopping, source
errors and task cancellation/join. It does not bound every candidate-ID array.

Base is `87aaf0ad39c89189d52c859672e5420b26c3f5e9`. The provider patch and immutable
manifest are under `provider/`; their older worktree label is historical. The
measured candidate adds the independent correction under `root-correction/`:
Java-equivalent UTF16 strings can have different WTF8 byte representations, so
generic Java equality must compare decoded units. The actual Java oracle and
failing-before/passing-after Go evidence are under `root-equality-review/`.
The global comparator was not changed.

Root integration passed full-module race/vet and reproduced all 18 emitted
oracle files, including the additional generic fault audit. The original 1,048
cases have 741 main matches and 307 remaining differences in this candidate.
The provider also records 440 mixed-value/edge checks and 432 fault cases (360
matches, 72 pre-existing gaps). These do not establish universal compatibility.
Two initial evidence collector failures are preserved: the collector first
requested a separately generated summary file, then incorrectly expected 17
outputs instead of 18. Both were corrected before performance execution.

Both diagnostic processes completed successfully. Each freshly verified all
1,152 files (10,338,207,518 bytes) in the real 64-graph fixture and the complete
catalog. All five ordered response bodies match pinned main and each other.
`final-verification.json` additionally records post-run source and executable
hash checks. Production source in the root checkout was not changed.

| Query | Execute + marshal seconds, base → candidate | Process CPU seconds | Allocated GB (decimal) |
|---|---:|---:|---:|
| Prefix first | 20.189 → 25.136 | 25.819 → 141.585 | 11.136 → 3.869 |
| Prefix repeat | 11.397 → 25.175 | 18.144 → 135.718 | 8.550 → 1.283 |
| Indexed dense DISTINCT first | 8.631 → 8.797 | 43.560 → 44.641 | 5.804 → 5.804 |
| Indexed dense DISTINCT repeat | 8.799 → 9.011 | 41.455 → 42.162 | 0.368 → 0.368 |
| Subsequent ordinary dense | 11.121 → 11.144 | 11.102 → 11.129 | 5.641 → 5.641 |

**Reject this performance candidate.** Lower allocation and fewer collections
did not improve execution: prefix repeat took 2.21 times as long and consumed
7.48 times the CPU. The unchanged query paths are controls, not evidence of
small regressions from this single observation. Per-node cursor handshakes are
a follow-up source-audit hypothesis, not an established causal attribution.
No shipping HTTP replay was run for this rejected candidate.

Reproduction, sequentially, from this repository root:

```sh
python3 docs/go-server-baseline/native64-generic-distinct-attempt11/run-profile.py --worktree /tmp/graphite-go-generic-base-87aaf0ad --out /tmp/fresh-base-output
python3 docs/go-server-baseline/native64-generic-distinct-attempt11/run-profile.py --worktree /tmp/graphite-go-generic-root-87aaf0ad --out /tmp/fresh-candidate-output
```

Output directories must not exist. Exact commands, executable/source identities,
runtime environment and raw counters are in each run directory. Go 1.22.0 on
darwin/arm64; 16 CPUs, 64 GB host. CPU sampling was disabled. Forced GC and heap
profiling occur outside request counters; background correctness/build work
was present, with no overlapping 64-graph process. These are individual
instrumented diagnostics, not HTTP samples, P95, peak RSS, or a comparison of
latency against main. The 100% parity and main-relative 10× P95 goals remain open.
