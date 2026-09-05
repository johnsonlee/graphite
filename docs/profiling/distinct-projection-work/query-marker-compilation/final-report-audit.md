# Final report audit

**PASS** — README and main analysis agree with independent recomputation from original marker/JFR JSON and same-fork XML. No Java, build or measurement executed.

| Profile | Targeted callback / app snapshots | Dense callback / app snapshots | First callback C2 duration ms | End query / offset ms |
|---|---:|---:|---:|---|
| profile-1 | 29 / 31 | 12 / 29 | 12.439209 | global-wide-wrapped-case-insensitive-distinct-dense / 7.771250 |
| profile-2 | 27 / 31 | 10 / 29 | 14.256291 | global-wide-wrapped-case-insensitive-distinct-targeted / 32.672000 |
| profile-3 | 22 / 25 | 7 / 22 | 12.177042 | global-wide-wrapped-case-insensitive-distinct-targeted / 36.507542 |

All 34 per-query Java snapshot counters in each profile match. Targeted callback frame types also match: Interpreted/JIT compiled = 24/5, 5/22, 16/6. These are Java snapshot counts; the JIT compiled label cannot distinguish C1/C2 or rule out execution through other inlined compilations.

Root callback traps retain exact remaining times: profile-1 dense 23.962708–23.991792 ms (three thread events, one compileId); profile-2 targeted 0.597584 ms; profile-3 targeted 0.383458 ms. Root compilation is selected by compileId and XML nmethod, not by raw stack presence. The late targeted trap is not evidence for the preceding approximately 37 ms.

Profile-1 first independent callback C2 starts during targeted and ends during dense; its 12.439209 ms compile duration crosses the gap as well as the two query windows. Profile-2/3 finish inside targeted. This is compiler elapsed activity, not request blocking or CPU cost.

No recorded GCPhasePause overlaps the targeted or dense DISTINCT windows. Other queries do have pause overlaps (first zero query: 3/3/1 events), so the absence must stay scoped. Each profile has exactly three CompilationFailure events, all strictly before the first query, with message `Jvmti state change invalidated dependencies`.

The main JSON field `observedJfrEndMinusXmlStampNanos` subtracts relative XML stamp from JFR epoch end; it is an anchor-sized diagnostic difference, not the small epoch-adjusted publication residual. README does not claim clock calibration. XML and JFR query ownership remain separate.

No factual correction required. JSON binds this review to exact README and analysis hashes and preserves all recomputed values.
