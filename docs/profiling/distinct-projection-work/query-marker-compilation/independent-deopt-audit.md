# Independent query-marker / deoptimization audit

Python-only recomputation from original `*.events.json`, original catalog, TSV/oracle and same-fork `tty` XML. Root analysis summaries were not inputs. No Java, build or measurement was executed.

All four recordings independently bind exactly 34 successful, continuous, non-overlapping markers to exact catalog query text/parameters, TSV order and all 14 oracle fields (136 signatures). No recorded positive DataLoss event was found. Query assignment below uses JFR timestamps only, with half-open windows.

## Root callback traps

Root means the same-fork XML `nmethod` selected by compileId/compiler, confirmed against JFR Compilation method/descriptor and OSR flag; it does not mean any event with raw projection somewhere in its stack.

| Fork | Query | Compile ID | Thread | Trap BCI | Offset from start ms | Until query end ms | Exact pipeline phase frame | XML runtime matches |
|---|---|---:|---|---:|---:|---:|---|---:|
| profile-1 | global-wide-wrapped-case-insensitive-distinct-dense | 4215 | graphite-cypher-scan-4 | 58 | 27.612791 | 23.991792 | provenance | 1 |
| profile-1 | global-wide-wrapped-case-insensitive-distinct-dense | 4215 | graphite-callsite-segment-2 | 58 | 27.614125 | 23.990458 | unknown | 1 |
| profile-1 | global-wide-wrapped-case-insensitive-distinct-dense | 4215 | graphite-callsite-segment-1 | 58 | 27.641875 | 23.962708 | unknown | 1 |
| profile-2 | global-wide-wrapped-case-insensitive-distinct-targeted | 4218 | graphite-callsite-segment-2 | 37 | 37.151125 | 0.597584 | unknown | 1 |
| profile-3 | global-wide-wrapped-case-insensitive-distinct-targeted | 4248 | graphite-callsite-segment-2 | 37 | 36.789042 | 0.383458 | unknown | 1 |

Every row above is a separate original JFR event. Multiple worker events on one compile ID are retained, not collapsed into one event or misreported as multiple compiled methods. Trap method is `IntOpenHashSet.contains (I)Z`; the compiled root is the 790-byte mapped raw node callback.

## All deoptimizations: conservation and root distinction

| Fork | All | Within query | Outside query | Raw stack | Root callback | Raw stack, other root |
|---|---:|---:|---:|---:|---:|---:|
| profile-1 | 94 | 32 | 62 | 10 | 3 | 7 |
| profile-2 | 80 | 32 | 48 | 12 | 1 | 11 |
| profile-3 | 99 | 39 | 60 | 14 | 1 | 13 |

Concrete distinction: profile-3 compile 4246, global-wide-wrapped-case-insensitive-distinct-dense +3.946125 ms, contains BCI 58, has a raw callback frame but its root nmethod is `it.unimi.dsi.fastutil.ints.IntOpenHashSet contains (I)Z`. It is not an additional root-callback deoptimization.

All raw-stack root-method distributions and all individual deoptimizations (including events outside queries) are in the JSON. `phaseFromExactPipelineFrame` only uses the frozen-JAR initial/projectSource and provenance/lambda155-lambda154 mappings. A segment worker without either parent frame stays unknown, even when another thread has a provenance frame at nearly the same timestamp.

## Compilation identity and clock boundary

| Fork | Root compile ID | Compiler | XML nmethod stamp s | JFR compile duration ms | JFR end minus approximate XML publication ms | JFR compile end query | XML runtime traps |
|---|---:|---|---:|---:|---:|---|---:|
| profile-1 | 4210 | c1 | 7.902 | 2.053417 | -0.140166 | global-wide-wrapped-case-insensitive-distinct-targeted | 0 |
| profile-1 | 4215 | c2 | 7.945 | 12.439209 | 0.538334 | global-wide-wrapped-case-insensitive-distinct-dense | 3 |
| profile-1 | 4301 | c1 | 7.968 | 2.236209 | -0.090416 | global-wide-wrapped-case-insensitive-distinct-dense | 0 |
| profile-1 | 4304 | c1 | 7.970 | 1.676375 | -0.260041 | global-wide-wrapped-case-insensitive-distinct-dense | 0 |
| profile-1 | 4308 | c2 | 8.002 | 14.586916 | 0.282375 | global-wide-distribution-localized-late | 0 |
| profile-2 | 4205 | c1 | 7.978 | 1.682125 | 0.031875 | global-wide-wrapped-case-insensitive-distinct-targeted | 0 |
| profile-2 | 4210 | c1 | 7.981 | 2.0905 | 0.16025 | global-wide-wrapped-case-insensitive-distinct-targeted | 0 |
| profile-2 | 4218 | c2 | 8.002 | 14.256291 | 0.316875 | global-wide-wrapped-case-insensitive-distinct-targeted | 1 |
| profile-2 | 4227 | c1 | 8.009 | 2.445583 | 0.326167 | global-wide-wrapped-case-insensitive-distinct-dense | 0 |
| profile-2 | 4242 | c1 | 8.015 | 2.019375 | -0.1725 | global-wide-wrapped-case-insensitive-distinct-dense | 0 |
| profile-3 | 4243 | c1 | 7.878 | 2.057084 | 0.166209 | global-wide-wrapped-case-insensitive-distinct-targeted | 0 |
| profile-3 | 4248 | c2 | 7.907 | 12.177042 | -0.615416 | global-wide-wrapped-case-insensitive-distinct-targeted | 1 |
| profile-3 | 4264 | c1 | 7.909 | 2.233958 | 0.045125 | global-wide-wrapped-case-insensitive-distinct-dense | 0 |

This capture must be read on its own: profile-1 also has a later successful callback C2 publication (4308); the earlier no-tracing captures lacked that later publication, so their terminal compiler state must not be copied into this report.

Same-fork PID is checked. XML runtime traps are selected only from `tty/uncommon_trap`, not compile-task parse traps. Runtime identity matching uses compileId/compiler, trap method/BCI, reason/action and **OS thread ID**. JFR Compilation root-method matching is separate; an inlined trap method is expected to differ from the root method. All matching rows and make_not_entrant records are preserved in JSON.

The table compares different semantic moments: XML nmethod publication versus JFR Compilation end. Approximate XML epoch is `time_ms + stamp`; stamp is rounded to 3 decimal seconds. These differences are diagnostic residuals, not a proven shared nanosecond clock. XML timelines remain separate; no XML query ownership is inferred from this approximate conversion. Multiple runtime-trap matches, if present, remain a list rather than choosing the nearest event.

## Interpretation limits

- These are three diagnostic captures, not stable performance estimates or optimization acceptance.
- ExecutionSample counts are not CPU time; no count-to-ms conversion was made.
- Deoptimization has no measured duration here; neither its cost nor its contribution to query latency is established.
- A trap occurring approximately 37 ms into a query cannot explain the preceding 37 ms merely because it occurred in that query.
- Full per-event fields and input hashes are preserved in `independent-deopt-audit.json`; `independent-deopt-audit.py` is the standalone reproducer.
