# Complete38 independent verification

The terminal receipt is complete. Independently rerunning the frozen full-value verifier on all 12 raw outputs passed **456 query outputs**, including exact query order, values, result order, digest and provenance. All **228 pairs** and the original summary's medians, ratios, thresholds and non-time differences recompute exactly. Both reset states retain all 38 cases; no phase was substituted for the complete result.

All 12 exact commands use the same frozen adapter/workload/fixture, Java 17, `-Xmx8g`, `-XX:ActiveProcessorCount=4`, and no added JFR, profiler, LogCompilation or query observer. Classpaths select official 04ae90ec versus helper candidate 177d9c15 only. The order is C/B, B/C, C/B independently in each reset state. The original adapter's metrics/resource sampler remains part of the protocol. Each separate process receipt exactly equals its entry in the final run; all unique PID=PGID groups exited normally with code 0, no cleanup signals and no remaining members.

Recorded source/JAR/fixture identities remain unchanged. This audit rehashes small pinned inputs and exact raw outputs; it binds large JAR/fixture identities to completed before/after/build/scope receipts, without independently reading those large files. The 15 additional after-pins match the independently prepared oracle's outputs, compiled classes and verification receipts exactly. Expected results came from frozen main a5c2, not this candidate. Original workload SHA remains `f3e240e79739fd28ae8c869758dcd4b2bc2559c4c90254136ab74f0235ae3923`.

The catalog retains 19 logical cases × rows/DISTINCT: 9 single-graph, 5 multi-graph subsets, 3 all-64-graph and 2 zero-hit cases. This includes AND, OR and mixed four-term expressions. Full results are checked rather than merely the advertised graph coverage.

## Repeated diagnostic flags

| Reset | Query | B→C ms, pair 1 | Pair 2 | Pair 3 | Flagged pairs |
|---|---|---:|---:|---:|---|
| per-query-cold | mixed-four-few-rows | 308.109→631.249 | 763.965→798.271 | 555.947→764.819 | 1, 3 |
| replay-cold | mixed-four-few-rows | 678.454→570.642 | 157.705→724.221 | 301.103→512.932 | 2, 3 |

These are the only repeated >15% and >1 ms flags in each state. The companion audit preserves all single flags and every unflagged pair; three samples do not support per-query P95, which remains null. These diagnostics do not establish CI acceptance or excuse the original34 starting-main regression.

All **8 non-latency differences** are graphWorkUnits:

| Reset / query / pair | Base→candidate work |
|---|---:|
| cold / mixed-four-few-rows / 1 | 100228→252216 |
| cold / mixed-four-few-rows / 3 | 221526→294853 |
| cold / or-four-single-middle-rows / 1 | 31119327→30988255 |
| cold / or-four-single-middle-rows / 2 | 30988255→31119327 |
| cold / or-four-single-middle-rows / 3 | 30988255→31119327 |
| replay / mixed-four-few-rows / 1 | 304905→249974 |
| replay / mixed-four-few-rows / 2 | 45297→304887 |
| replay / mixed-four-few-rows / 3 | 103242→226933 |

The repeated slow mixed-row pairs coincide with more accounted graph work in this experiment. That narrows a subsequent investigation toward which work was completed/speculated before LIMIT or cancellation; it does not demonstrate the source of that work, a scheduling cause, or constant per-unit cost. All timing failures remain visible.

Reset meanings remain distinct: per-query-cold calls original setupInvocation each query; replay-cold calls it only before the first. Setup clears indexes and metrics, requests GC/finalization with three 100 ms sleeps, and resets/enables the original sampler. Cross-state comparisons therefore do not isolate cache effects. CPU/RSS were not measured by this protocol; no utilization or resource improvement is inferred. External JVM inventory is retained, and owned-group drain is not a claim that the entire host was isolated.

Only Python/offline small-file reads were used. `full38-audit.py` is a scoped adaptation of the previously validated sparse audit (candidate identity and output locations changed); `full38-close.py` adds independent per-process receipt equality, catalog coverage and hashes. No production/protocol inputs were edited.
