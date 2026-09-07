# Lazy filtered node consumer integration

Base `9ada2bf1`; consumer patch SHA256 `b4f97957cc8ab4462ba62048d922792e9762214b3bc311eec823d557c1dbfe5a`. This functional change adds necessary-condition candidate filtering, unbounded streaming projection and bounded generic filtering. It preserves main's selective reads, filter/projection ordering, qualified DISTINCT provenance draining, scoped early stop and the bounded scoped generic route exception. Pagination, ordering, relationship and optional/path dispatch remain separate.

## Source and correctness

The provider259 and independent54 evidence entries were rehashed before clean application. The102-file delta adds three production files and an engine hook; all four production hashes exactly match the independently reviewed provider. All2299 unaffected base source/fixture files remain unchanged, including synchronous DISTINCT, context cancellation, mapped scalar decoding and exact tuples. The final2401 module inputs match root, the full-module tested candidate and the shipping worktree.

`go test -race ./... -count=1` and `go vet ./...` pass. The independent reviewer tests were separately copied into the combined worktree, run under race10 times, then removed after verifying their exact bytes. They assert cancellation actually fires, verify a fresh request and owned values after Close, and audit all80 historical corrupt cases. Seventy-two gain lazy planner admission, but exactly8 complete expected results change after the existing typed-SID mapping; each maps to one unchanged main query. Original1048 improves994→1016; remaining24 main-success/native-error,4 different errors and4 different successful responses stay visible. All580 previous DISTINCT and122 ordinary cases remain equal. The C8/D8/E6 cases all match. New main corpus:480 broad+96 conjunction+36 source-wave+8 scoped-route responses=620, all full comparisons pass.

All67 exported artifact responses match their relevant provider or root baseline. Fifty-three speculative mappedView state leaves differ; complete rows/errors/other state remain unchanged and raw differences are retained. Source identities, commands and audit results are under `integration/`.

Twelve leaves in the original480 semantic JSON were corrected at the wire-oracle boundary: Java UTF8 encodes lone D800 as ASCII question mark, independently demonstrated by two actual main HTTP bodies. Original semantic files, failed comparisons and exact byte/unit probes remain. No production string normalization, query/parameter masking or denominator reduction was made.

## Real64 evidence

The unchanged fixture comprises64 real persisted graphs,1152files/10,338,207,518bytes,19,431,891nodes and20,448,885edges. Fresh main/native/HTTP COW clones use `/bin/cp -cRp`. A fresh pinned-main13-request HTTP process retains the old five complete control bodies and captures eight new C/D/E requests. A temporary nonshipping planner probe proves all eight new queries enter the new consumer, with no prior compiler interception. Unbounded queries return both0 and54 rows. Both sequential Go revisions return all13 complete main bodies. After real Close, original/main/native graph hashes remain unchanged with no extra graph files.

Commands: `python3 capture-main.py`; `python3 run-profile.py --worktree /tmp/graphite-go-cde-base-9ada2bf1 --out base` and the analogous candidate command with `/tmp/graphite-go-cde-root-9ada2bf1`. Exact absolute commands and binary/source identities are recorded. Environment: Go1.22.0 darwin/arm64,Java17.0.18,macOS16 logical CPUs/64GB,default Go settings. Main uses the established8GB heap baseline, no CPU-count override. These are single instrumented Execute+marshal observations, forcedGC outside request counters, no CPU sampler, background correctness/build work; not P95/peakRSS or main-relative speedup acceptance.

| Query/history | Base seconds | Candidate seconds | Base CPU seconds | Candidate CPU seconds | Base allocated bytes | Candidate allocated bytes |
|---|---:|---:|---:|---:|---:|---:|
| wrapped-firstLastGraphBimodalClassPrefix | 13.980943 | 14.087499 | 69.052798 | 69.731214 | 9,281,813,016 | 9,281,811,568 |
| wrapped-firstLastGraphBimodalClassPrefix-repeat | 10.051794 | 10.424524 | 46.997608 | 48.558149 | 3,759,957,408 | 3,759,929,928 |
| global-wide-wrapped-case-insensitive-distinct-dense | 0.295421 | 0.324119 | 1.820875 | 2.181768 | 268,476,536 | 268,469,264 |
| global-wide-wrapped-case-insensitive-distinct-dense-repeat | 0.302985 | 0.316465 | 1.987502 | 2.123095 | 268,445,000 | 268,442,376 |
| global-wide-distribution-broad-all-64 | 0.001870 | 0.001808 | 0.001901 | 0.001825 | 1,635,776 | 1,635,280 |
| c-necessary-ordinary | 12.130730 | 0.002318 | 16.865690 | 0.002344 | 13,743,344,640 | 2,698,568 |
| c-necessary-ordinary-repeat | 11.859974 | 0.001675 | 16.693562 | 0.001704 | 13,743,357,456 | 2,272,440 |
| c-necessary-distinct | 12.324256 | 2.185858 | 17.302618 | 10.422545 | 14,170,226,096 | 1,192,757,232 |
| c-necessary-distinct-repeat | 12.281630 | 2.119354 | 17.323354 | 10.109284 | 14,170,183,024 | 1,172,740,640 |
| d-unbounded-android | 11.531376 | 0.091208 | 16.180567 | 0.091218 | 12,625,966,936 | 137,492,016 |
| d-unbounded-kotlin | 11.541083 | 0.054738 | 16.201767 | 0.054769 | 12,626,186,872 | 82,167,664 |
| e-generic-ordinary | 12.952423 | 0.000533 | 13.109238 | 0.000570 | 3,890,732,256 | 780,536 |
| e-generic-distinct | 1.928698 | 6.492682 | 1.941437 | 6.470970 | 2,263,578,128 | 8,392,767,504 |

Keep required functional behavior with an explicit generic DISTINCT regression:1.929→6.493s,CPU1.941→6.471s,allocation2.264→8.393GB. The ordinary and necessary-condition paths avoid previous materialization; the qualified generic DISTINCT path must still fully consume and evaluate matching projections for provenance. Correctness does not authorize reverting those reads to avoid this cost. A separate optimization must preserve read/error/evaluation order. The prefix controls retain their previously documented high costs; this commit contains no Attempt15 map optimization.

## Shipping command

Two fresh uninstrumented processes pass13+42=55 HTTP200 responses, full typed bodies/headers and64-graph catalogs. Binary SHA256 `678d394d25750db2738f53196c6e7fe92901c8b1c73e3d8629d2acc2db237724`;150 active compiler/embed/module inputs and all2401 module inputs match the tested source and root. Default60s/capacity4, no profiling helpers. Both processes exited143 on SIGTERM, no forced kill. The unchanged complete verifier passes and original/HTTP-clone1152 graph hashes remain unchanged with no extra files.

The first final verifier reached the plain socket-bind check and observed address-in-use after process exit. Its failure receipt is retained. Subsequent probes found no listeners, connections refused and both plain binds successful; rerunning the unchanged verifier passed. No process was restarted, no responses changed and no socket-reuse option masked the check.

## Remaining scope

The32 original suite differences are not a complete product gap inventory. B pagination/ORDER, newly audited relationship/lazy-edge consumption, process-dependent MAPPED Class order, global JVM budgets/configurable GraphWork and other retained lifecycle boundaries remain. Source review also identified an EAGER parallel-strategy discrepancy shared by generic and direct CDE consumers; no public-output counterexample is yet established for those entries. That is separate from this bounded successful corpus. Full100% parity, repeated concurrent42-query P95 acceptance and the final benchmark-regression-gate remain unproven.
