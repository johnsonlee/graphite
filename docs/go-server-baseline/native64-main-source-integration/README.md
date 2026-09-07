# Main lazy candidate source integration

Base `10236487`; provider incremental patch `aee8349b9c860736ab6a7dd51efb3ac0841603f9e291a2942a9e8dd4631a1cc7`.

This functional correction gives generic DISTINCT main-compatible selective, lazy candidate reads. It separates retained/mapped index validation from the stricter existing certificate path, preserving concrete-type head ordering, owning projected values, current request cancellation and Store-owned loading/Close. It also extracts the existing ordinary candidate implementation into shared helpers. It does not yet implement the separate B/C/D/E consumers or relationship streaming.

All 1205 provider evidence files and 53 independent evidence files were rehashed before application. The 41-file incremental patch applied cleanly to the existing synchronous executor, context checkpoint, mapped decoder and exact tuple implementation. Fourteen production files match the provider byte for byte. The only two production deltas are existing tuple access/cleanup and mapped scalar decoding in `distinct_projection.go` and `store.go`; their exact diffs are in `integration/`. All 2260 untouched base module files were independently compared with HEAD.

Whole-module `go test -race ./...` and `go vet ./...` pass; all 2301 source/testdata inputs were unchanged during verification and match root. All 66 exported artifacts preserve the provider full query responses. Forty-five raw state differences concern speculative cancelled-task mappedView publication only; they remain recorded. Original1048 improves968→994, exactly26 newly main-equal responses, with34 main-success/native-error,16 differing errors and4 differing successes retained. All580 former DISTINCT and122 ordinary cases remain equal. The new source suite contains58 scenarios and122 complete actual-main target/warm responses; independently replayed main evidence is retained.

## Real64 paired diagnostics

The fixture is all64 persisted production graphs,1152 files totaling10,338,207,518bytes,19,431,891nodes and20,448,885edges. Each graph was cloned with `/bin/cp -cRp` to a fresh COW directory. Four sequential native processes run the unchanged eight-query global history and six-query explicit32+32 route history, with pinned-main full-body oracles from the previous exact-tuple capture. Every output matches main. Source/binary hashes and original/clone graph hashes were checked after actual process Close, with no extra graph files.

Environment: Go1.22.0 darwin/arm64,macOS16 logical CPUs/64GB. Default Go runtime settings, no CPU sampler, forcedGC outside request counters, background correctness/build/file-copy work on the same host. These are single instrumented Execute+marshal observations, not HTTP/P95/peakRSS or main-relative performance acceptance.

| Query/history | Base seconds | Candidate seconds | Base CPU seconds | Candidate CPU seconds | Base allocated bytes | Candidate allocated bytes |
|---|---:|---:|---:|---:|---:|---:|
| wrapped-firstLastGraphBimodalClassPrefix | 13.132074 | 16.762499 | 72.691150 | 81.477525 | 1,837,234,128 | 9,281,741,192 |
| wrapped-firstLastGraphBimodalClassPrefix-repeat | 2.485201 | 11.548890 | 16.217181 | 54.964670 | 1,054,277,592 | 3,759,881,000 |
| global-wide-wrapped-case-insensitive-distinct-dense | 4.597434 | 0.314373 | 22.374278 | 2.066475 | 5,804,046,248 | 268,459,048 |
| global-wide-wrapped-case-insensitive-distinct-dense-repeat | 4.505829 | 0.295037 | 21.108569 | 1.795060 | 367,932,272 | 268,426,608 |
| global-wide-distribution-broad-all-64 | 0.001822 | 0.001886 | 0.001843 | 0.001922 | 1,631,792 | 1,635,024 |
| tuple-threshold255 | 4.454308 | 0.300362 | 20.631607 | 1.961762 | 384,879,800 | 285,363,864 |
| tuple-threshold256 | 4.095799 | 0.314105 | 19.068118 | 2.074978 | 385,153,960 | 285,640,288 |
| tuple-threshold256-repeat | 4.257778 | 0.306682 | 19.792588 | 2.032907 | 385,155,048 | 285,649,136 |
| first32-tuple-255 | 2.684840 | 2.721952 | 12.621267 | 12.827751 | 2,254,873,784 | 2,254,879,168 |
| first32-tuple-256 | 0.657520 | 0.649121 | 3.099855 | 3.059646 | 263,265,024 | 263,278,016 |
| first32-tuple-256-repeat | 0.108875 | 0.105850 | 0.509089 | 0.527702 | 48,146,264 | 48,153,048 |
| last32-tuple-255 | 1.698063 | 1.719804 | 8.126436 | 8.285312 | 3,449,737,664 | 3,449,736,272 |
| last32-tuple-256 | 0.407418 | 0.404615 | 1.900048 | 1.905209 | 185,390,720 | 185,387,280 |
| last32-tuple-256-repeat | 0.075705 | 0.075402 | 0.347766 | 0.363985 | 47,797,152 | 47,804,384 |

Keep this functional correction with an explicit prefix regression: repeat wall time2.485→11.549s,CPU16.217→54.965s,allocation1.054→3.760GB. First prefix also regresses. Dense requests in this fixed history improve markedly; the new prefix path already initializes64 mapped views before dense begins, so dense timing cannot be generalized to an independent cold request. The old generic prefix leaves0 mapped views; all raw before/after states are retained. A separate optimization must address prefix costs without restoring incorrect candidate consumption.

Both revisions preserve exactly the same routed tuple states:0→31→62 actual tables, across the two disjoint32-source groups covering all64 graphs. Global64 table counts remain0. This validates integration with actual tuple construction, rather than treating global non-trigger queries as construction tests.

## Shipping HTTP verification

Three fresh shipping command processes pass8+6+42=56 HTTP200 responses, including complete typed JSON, array order, headers and64-graph catalogs. Default60s timeout/capacity4, no profiling helpers or runtime tuning. All three exited143 on SIGTERM without forced kill; ports18868/18869/18870 were independently rebound. Binary SHA256 `3902a6eeed0d79b39ee1977b290f8f9964533a4e82bd813febe34cb948f9ecc9`;147 active compiler/embed/module inputs and all2301 module source/fixture files match the full-module tested candidate and root. `http-shipping/verify.py` rechecks all complete typed bodies and source identities without dynamic masks. Actual commands, build inputs, outputs and exit receipts are retained.

The first build succeeded, but its source collector failed on macOS `/tmp` versus `/private/tmp` path spelling. The original script and failure receipt remain; resolving the worktree path corrected only the collector before any HTTP request. Provider compilation, test and oracle setup failures are likewise retained in their frozen evidence.

## Remaining work

Global JVM reservation budgets/configurable GraphWork, broader scheduling and relationship consumers, B/C/D/E integration and final42-query repeated concurrent P95 acceptance remain unproven. This commit is a functional follow-up, not optimization attempt15 or a claim of100% compatibility. The required final benchmark-regression-gate remains outstanding.
